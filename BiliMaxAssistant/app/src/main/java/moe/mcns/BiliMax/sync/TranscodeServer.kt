package moe.mcns.BiliMax.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import moe.mcns.BiliMax.AppPrefs
import moe.mcns.BiliMax.MainActivity
import moe.mcns.BiliMax.transcode.FilmQuality
import moe.mcns.BiliMax.transcode.Transcoder
import moe.mcns.BiliMax.transcode.TranscodeOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 手机端内嵌 HTTP 转码服务器（监听 0.0.0.0:[PORT]）。
 *
 * 「转码服务」就是这台 HTTP 服务器本身：不关心请求方是谁、从哪条通道到达，
 * 只要收到标准 HTTP 请求就处理。请求到达有两种方式（对服务器完全透明）：
 *   1. 表端自带 @system.fetch（可联网）：直接访问手机局域网地址
 *      http://<手机局域网 IP>:[PORT]/...；
 *   2. 表端无 fetch：经 XMS 消息通道上的 SimpleFetch 桥接（SfBridge）
 *      由手机代发 HTTP 请求回环到本机（该桥接 App 启动时自动运行，
 *      与本服务器启停互不影响）。
 *
 * 路由（与手环端约定）：
 *   GET  /api/status                 健康检查
 *   POST /api/createTask             创建转码任务，JSON 入参：
 *                                      url 或 BVID（二选一，BVID 也兼容 bvid/bv 字段）
 *                                      fps          抽帧帧率，默认 15（1~30）
 *                                      processAudio 是否提取音轨，默认 true
 *                                      quality      high | standard（默认）| low
 *                                      name?        任务名
 *   GET  /api/task/{id}              查询进度/结果（JSON）
 *   GET  /api/task/{id}/info.json    胶卷元数据（含 frameW/frameH/fps/totalFrame/tile）
 *   GET  /api/task/{id}/frame/frame_N.jpg 逐帧下载（自然数命名，从 1 开始）
 *   GET  /api/task/{id}/audio.mp3    音轨（若有）
 *
 *   GET  /push/{dir}/{relPath}       手动传输托管的胶卷文件（SERVER_UPLOAD_FILE 直连下载）
 *
 * 任务产物保留在 cacheDir/srv_jobs/{id}/，服务关闭或下次启动时清理，控制手表端拉取期间不被删。
 * 手动传输的解压产物经 mountPushDir 复制到 cacheDir/srv_push/{dir}/ 托管，同样随服务重启清理。
 */
object TranscodeServer {

    // 实际监听端口：start() 时从 AppPrefs.serverPort 读取（Debug 页可改，重启服务生效）。
    // 用只读属性 PORT 暴露，既有引用点（FilmPush/通知文案/状态接口）无需改动。
    @Volatile
    private var boundPort: Int = AppPrefs.DEFAULT_SERVER_PORT

    /** 当前服务器端口（未启动时为默认/上次启动端口） */
    val PORT: Int get() = boundPort
    private const val TAG = "BiliMax-Server"
    private const val API_PREFIX = "/api/task/"
    private const val PUSH_PREFIX = "/push/"
    // 创建任务前要求的最小可用存储（视频下载 + 帧产物），不足则拒绝并通知
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024
    // 监听队列长度：默认 50 在手表逐帧并发拉取时容易堆满，满了内核直接丢弃 SYN，
    // 表现为「端口 LISTEN 但 connect 超时」，手表侧只能等到 0 字节
    private const val LISTEN_BACKLOG = 128

    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "http-server").apply { isDaemon = true }
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pushDirs = ConcurrentHashMap<String, File>()
    private lateinit var appContext: Context
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    private class Job(
        val id: String,
        val dir: File,
        @Volatile var name: String,
        @Volatile var phase: String = "排队中",
        @Volatile var progress: Float = 0f,
        @Volatile var status: String = "running",
        @Volatile var error: String? = null,
        @Volatile var totalFrames: Int = 0,
        @Volatile var strips: Int = 0,
        @Volatile var frameW: Int = 0,
        @Volatile var frameH: Int = 0,
        @Volatile var fps: Int = 15,
        // 一张胶卷横向平铺的帧数（Transcoder 按 tile=${fps}x1 生成，正常与 fps 相等）；
        // 单独存一份而不是复用 fps：两者语义不同，info.json 才是权威来源
        @Volatile var tile: Int = 0,
        // 进度通知节流：下载/转码回调频率很高，限制更新通知的间隔，避免频繁刷新系统通知栏
        @Volatile var lastNotifAt: Long = 0L,
        // 日志节流：只在阶段变化或进度跨过 10% 档时写一条，避免高频回调刷爆 500 条环形缓冲
        @Volatile var logPhase: String = "",
        @Volatile var logPct: Int = -1
    )

    @Synchronized
    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        try {
            cleanupStaleJobs()
            boundPort = AppPrefs.get(appContext).serverPort
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", boundPort), LISTEN_BACKLOG)
            serverSocket = ss
            running = true
            // accept 循环独立线程：与请求处理线程池分开，避免请求处理排队把受理也堵住
            Thread({ acceptLoop(ss) }, "http-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "转码 HTTP 服务器已启动，端口 $PORT")
            SyncLog.i(TAG, "转码 HTTP 服务器已启动，端口 $PORT")
            // 服务就绪后向已连接手表主动推送一次地址（握手回包丢失/服务晚于互联启动时的兜底）
            io.execute {
                try {
                    Thread.sleep(800)
                    SfBridge.broadcastServerInfo()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "服务器启动失败: ${e.message}", e)
            SyncLog.e(TAG, "转码服务器启动失败", e)
            running = false
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        stopInternal()
        Log.i(TAG, "转码 HTTP 服务器已停止")
        SyncLog.i(TAG, "转码 HTTP 服务器已停止")
    }

    private fun stopInternal() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }

    fun isRunning() = running

    /**
     * 手机局域网服务器首选基址（如 http://192.168.1.10:6732）。
     * 服务未运行或无可用地址时返回 null。地址解析顺序：
     * Debug 页手动指定 IP（serverIpOverride）优先，其次 [NetInfo] 打分最高的网卡。
     */
    fun lanBaseUrl(): String? = candidateBases().firstOrNull()

    /**
     * 全部候选基址，按优先级排列：
     *  1. 手动指定 IP 生成的基址始终在最前（用户显式指定，优先级最高）；
     *  2. 其余为 [NetInfo] 枚举打分的全部物理网卡（已去重、排除 override 本身）。
     * 服务未运行时返回空列表。手表端对候选基址逐个做可达性探测择优。
     */
    fun candidateBases(): List<String> {
        if (!running) return emptyList()
        val override = runCatching { AppPrefs.get(appContext).serverIpOverride }
            .getOrNull().orEmpty().trim()
        val bases = mutableListOf<String>()
        if (override.isNotBlank()) bases.add("http://$override:$PORT")
        NetInfo.candidateIps()
            .filter { override.isBlank() || it != override }
            .forEach { bases.add("http://$it:$PORT") }
        return bases.distinct()
    }

    fun jobCount(): Int = jobs.size

    /** 在跑任务的只读快照（供「查看日志」页状态卡展示）；没有任务时返回 null。 */
    data class RunningJob(val name: String, val phase: String, val progress: Float)

    fun runningJob(): RunningJob? {
        val job = jobs.values.firstOrNull { it.status == "running" } ?: return null
        return RunningJob(job.name, job.phase, job.progress)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running && serverSocket === ss) {
            val client = try {
                ss.accept()
            } catch (e: Throwable) {
                if (!running || serverSocket !== ss) return
                // accept 的瞬时异常（EMFILE、连接被重置、socket 被底层打断）不能让受理循环永久退出：
                // 一旦退出，端口仍在 LISTEN 但再也不会 accept，手表侧只会等到 0 KB 空响应。
                Log.w(TAG, "accept 中断: ${e.message}，200ms 后重试")
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            try {
                io.execute {
                    try {
                        handle(client)
                    } catch (e: Throwable) {
                        Log.w(TAG, "请求处理失败: ${e.message}")
                    } finally {
                        try {
                            client.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (e: Throwable) {
                // 线程池拒绝（线程创建失败等）时立刻关闭连接，避免堆积成 CLOSE_WAIT
                Log.w(TAG, "连接派发失败: ${e.message}")
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---------------- HTTP 处理 ----------------

    private fun handle(client: java.net.Socket) {
        client.soTimeout = 20_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) return
        val method = parts[0]
        val rawPath = parts[1]
        val qIdx = rawPath.indexOf('?')
        val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath

        val headers = HashMap<String, String>()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim()
                val v = line.substring(colon + 1).trim()
                headers[k.lowercase()] = v
                if (k.lowercase() == "content-length") contentLength = v.toIntOrNull() ?: 0
            }
        }

        var body: String? = null
        if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            body = String(buf, 0, read)
        }

        route(client, method, path, body, headers)
    }

    private fun route(
        client: java.net.Socket,
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>
    ) {
        when {
            path == "/api/status" && method == "GET" -> {
                val devices = org.json.JSONArray()
                DeviceRegistry.all().forEach { devices.put(it.toJson()) }
                sendJson(client, 200, JSONObject()
                    .put("ok", true)
                    .put("service", "bilimax-transcode")
                    .put("port", PORT)
                    .put("devices", devices))
            }

            path == "/api/createTask" && method == "POST" ->
                createJob(client, body)

            path.startsWith(API_PREFIX) ->
                serveJobFile(client, path, headers)

            path.startsWith(PUSH_PREFIX) ->
                servePushFile(client, path, headers)

            else -> sendJson(client, 404, JSONObject().put("error", "not found: $path"))
        }
    }

    private fun createJob(client: java.net.Socket, body: String?) {
        if (body.isNullOrBlank()) {
            sendJson(client, 400, JSONObject().put("error", "empty body"))
            return
        }
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            sendJson(client, 400, JSONObject().put("error", "bad json: ${e.message}"))
            return
        }
        // 任务入参二选一：
        //   url/URL  —— 直链视频地址，服务器直接下载；
        //   BVID     —— B 站 BV 号（兼容 BVID/bvid/bv 字段，也可传完整链接由 extractBvid 解析），
        //               服务器经 BiliDownloader 解析直链再下载。
        val url = req.optString("url").ifBlank { req.optString("URL") }
        val bvid = req.optString("BVID")
            .ifBlank { req.optString("bvid") }
            .ifBlank { BiliDownloader.extractBvid(req.optString("bv")) ?: "" }
        if (url.isBlank() && bvid.isBlank()) {
            sendJson(client, 400, JSONObject().put("error", "missing url or BVID"))
            return
        }
        val fps = req.optInt("fps", Transcoder.FPS).coerceIn(1, 30)
        val processAudio = req.optBoolean("processAudio", true)
        val quality = when (req.optString("quality", "standard").lowercase()) {
            "high" -> FilmQuality.HIGH
            "low" -> FilmQuality.LOW
            else -> FilmQuality.STANDARD
        }

        // 存储预检：可用空间不足直接拒绝并发系统通知，避免下载到一半写满磁盘
        val freeBytes = appContext.cacheDir.usableSpace
        if (freeBytes in 1 until MIN_FREE_BYTES) {
            val text = "手机剩余存储 ${freeBytes / 1024 / 1024} MB，不足 200 MB，已拒绝转码任务，请清理存储后重试"
            Log.w(TAG, text)
            notifyStorageLow(freeBytes)
            sendJson(client, 507, JSONObject().put("error", "insufficient storage: ${freeBytes}B free"))
            return
        }

        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dir = File(appContext.cacheDir, "srv_jobs/$id")
        dir.mkdirs()
        val fallbackName = bvid.ifBlank { "video_$id" }
        var name = req.optString("name").ifBlank { fallbackName }
        val job = Job(id = id, dir = dir, name = name, fps = fps)
        jobs[id] = job

        Log.i(TAG, "新建转码任务 $id: $name <- ${if (bvid.isNotBlank()) "BVID=$bvid" else url} " +
            "(${quality.label}, ${fps}fps, audio=$processAudio)")
        // 日志页需要能看出「这条是谁要的、要做什么」，故与 logcat 分开写一条面向用户的
        SyncLog.i(TAG, "【手表请求】任务 $id · $name · 来源=${if (bvid.isNotBlank()) "BV号 $bvid" else "直链"} · " +
            "画质=${quality.label} · ${fps}fps · 音频=${if (processAudio) "导出" else "不导出"}")
        notifyRequest(job)
        io.execute {
            try {
                val inputFile = File(dir, "input.mp4")
                if (bvid.isNotBlank()) {
                    // B 站任务：复用 App 内下载链路（view 接口取标题/playurl 取直链/校验 MP4 文件头）
                    job.phase = "解析视频信息"
                    val downloadError = java.util.concurrent.atomic.AtomicReference<String?>()
                    val done = java.util.concurrent.CountDownLatch(1)
                    BiliDownloader.download(appContext, bvid, object : BiliDownloader.Callback {
                        override fun onInfo(info: BiliDownloader.VideoInfo) {
                            if (name.isBlank() || name == bvid) {
                                name = info.title.ifBlank { bvid }
                                job.name = name
                            }
                            job.phase = "下载视频"
                        }

                        override fun onProgress(stage: String, p: Float) {
                            job.phase = stage
                            job.progress = p * 0.03f
                            notifyProgress(job)
                        }

                        override fun onDone(file: File, info: BiliDownloader.VideoInfo) {
                            try {
                                file.copyTo(inputFile, overwrite = true)
                            } catch (e: Exception) {
                                downloadError.set(e.message)
                            }
                            done.countDown()
                        }

                        override fun onError(message: String) {
                            downloadError.set(message)
                            done.countDown()
                        }
                    })
                    // 无超时等待会让 http-server 线程在下载器漏回调时永久阻塞、任务永远停在 running
                    if (!done.await(30, java.util.concurrent.TimeUnit.MINUTES)) {
                        throw IllegalStateException("下载超时（>30 分钟），已放弃该任务")
                    }
                    downloadError.get()?.let { throw IllegalStateException("下载失败：$it") }
                } else {
                    downloadVideo(job, url, inputFile)
                }
                if (job.status != "running") return@execute
                job.phase = "转码中"
                val transcoder = Transcoder()
                transcoder.startServerJob(
                    dir,
                    File(dir, "input.mp4"),
                    TranscodeOptions(name = name, exportAudio = processAudio, quality = quality, fps = fps),
                    object : Transcoder.Callback {
                        override fun onPhase(phase: String, progress: Float) {
                            job.phase = phase
                            job.progress = progress
                            notifyProgress(job)
                        }

                        override fun onLog(line: String) {
                            Log.i(TAG, "[$id] $line")
                            // 让「查看日志」页也能看到转码细节（时长/尺寸/帧数/胶卷张数/音频结果）
                            SyncLog.i(TAG, "任务 $id · $line")
                        }

                        override fun onDone(result: moe.mcns.BiliMax.transcode.TranscodeResult) {
                            job.status = "done"
                            job.progress = 1f
                            job.phase = "完成"
                            job.totalFrames = result.totalFrames
                            job.strips = result.stripCount
                            // 帧实际宽高从 info.json 读回（转码流水线探测源尺寸后算出）
                            // tile 必须回读：一张胶卷的帧数 = fps（Transcoder 的 tile=${fps}x1），
                            // 手表端按 tile 计算横条宽度与可播帧位，缺省会回落到 5 导致三倍错位
                            runCatching {
                                val meta = JSONObject(File(dir, "info.json").readText(Charsets.UTF_8))
                                job.frameW = meta.optInt("frameW")
                                job.frameH = meta.optInt("frameH")
                                job.tile = meta.optInt("tile")
                            }
                            Log.i(TAG, "任务 $id 完成：${result.stripCount} 张胶卷 / ${result.totalFrames} 帧")
                            SyncLog.i(TAG, "任务 $id 完成 · ${result.stripCount} 张胶卷 / ${result.totalFrames} 帧 · " +
                                "${job.frameW}x${job.frameH} · ${job.fps}fps · 音频=${if (result.withAudio) "有" else "无"}")
                            notifyResult(job, "${result.stripCount} 张胶卷 · 共 ${result.totalFrames} 帧", true)
                        }

                        override fun onError(message: String) {
                            job.status = "error"
                            job.error = message
                            Log.w(TAG, "任务 $id 失败：$message")
                            SyncLog.w(TAG, "任务 $id 失败 · $message（停在「${job.phase}」${(job.progress * 100).toInt()}%）")
                            notifyResult(job, message, false)
                        }
                    }
                )
            } catch (e: Throwable) {
                job.status = "error"
                job.error = e.message ?: "download/transcode fail"
                Log.e(TAG, "任务 $id 异常: ${e.message}", e)
                SyncLog.e(TAG, "任务 $id 异常 · ${e.message}（停在「${job.phase}」${(job.progress * 100).toInt()}%）", e)
                notifyResult(job, e.message ?: "转码失败", false)
            }
        }

        sendJson(
            client, 202,
            JSONObject()
                .put("id", id)
                .put("status", "running")
                .put("query", "/api/task/$id")
        )
    }

    private fun downloadVideo(job: Job, url: String, dest: File) {
        job.phase = "下载视频"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载视频失败：HTTP $code")
            }
            val total = conn.contentLengthLong
            var got = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            job.progress = (got.toFloat() / total * 0.03f).coerceAtMost(0.03f)
                            notifyProgress(job)
                        }
                    }
                }
            }
            if (dest.length() == 0L) throw IllegalStateException("下载到空文件")
            Log.i(TAG, "视频下载完成：${dest.length() / 1024} KB")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * URL 路径解码：URLDecoder 是 form-urlencoded 语义，会把 '+' 解成空格（任务号或文件名
     * 含 '+' 时该请求会 404）；先把 '+' 转义为 %2B 再解码，其余行为与原来完全一致。
     */
    private fun decodePath(path: String): String =
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")

    /** 请求路径 → 段列表（先 URL 解码再按 / 切分，空段丢弃） */
    private fun pathSegments(path: String): List<String> =
        decodePath(path).trimStart('/').split('/').filter { it.isNotEmpty() }

    /**
     * 目录内安全解析：**等于目录本身或在其内**才放行，越界返回 null。
     * 补分隔符是必须的——否则 `srv_jobs/abc` 与 `srv_jobs/abcdef` 会被误判为同一目录；
     * "等于自身"用于兼容 zip 条目里的 `./` 根路径。
     */
    private fun resolveInside(base: File, rel: String): File? {
        val file = File(base, rel)
        val basePath = base.canonicalPath
        val path = file.canonicalPath
        return if (path == basePath || path.startsWith(basePath + File.separator)) file else null
    }

    /** 扩展名 → MIME（转码产物与手动传输托管共用同一套，避免两处各写一遍） */
    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp3" -> "audio/mpeg"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    /** 统一产物下发：不存在/越界 → 404，目录 → 403，其余按 MIME 发送（支持单段 Range） */
    private fun serveInsideDir(
        client: java.net.Socket,
        base: File,
        rel: String,
        headers: Map<String, String>
    ) {
        val file = resolveInside(base, rel)
        if (file == null || !file.exists()) {
            sendJson(client, 404, JSONObject().put("error", "file not found: $rel"))
            return
        }
        if (file.isDirectory) {
            sendJson(client, 403, JSONObject().put("error", "is directory"))
            return
        }
        sendFile(client, file, mimeOf(file), headers)
    }

    private fun serveJobFile(client: java.net.Socket, path: String, headers: Map<String, String>) {
        val segments = pathSegments(path)
        // /api/task/{id}/... -> ["api","task",id,...]，id 段下标 2，文件路径从下标 3 开始
        if (segments.size <= 2) {
            sendJson(client, 404, JSONObject().put("error", "bad path"))
            return
        }
        val id = segments[2]
        // 进程重启后 jobs 是空的内存表，但磁盘产物仍在：按需从 info.json 恢复任务，
        // 否则手表拿着旧 task id 请求会得到 404（表现为「云端下载失败」）。
        val job = jobs[id] ?: adoptJob(id)
        if (job == null) {
            Log.w(TAG, "404 未知任务 $id（磁盘目录${if (File(appContext.cacheDir, "srv_jobs/$id").exists()) "存在但缺 info.json" else "不存在"}）")
            sendJson(client, 404, JSONObject().put("error", "unknown job"))
            return
        }

        if (segments.size == 3) {
            val info = JSONObject()
                .put("id", id)
                .put("status", job.status)
                .put("phase", job.phase)
                .put("progress", job.progress)
                .put("totalFrame", job.totalFrames)
                .put("strips", job.strips)
                .put("frameW", job.frameW)
                .put("frameH", job.frameH)
                .put("fps", job.fps)
                // tile 与 info.json 同源；任务未完成时 info.json 尚未写出，先按 fps 给出
                // （Transcoder 恒以 tile=fps 生成），避免手表端拿到缺省 5 导致帧位错位
                .put("tile", if (job.tile > 0) job.tile else job.fps)
            if (job.error != null) info.put("error", job.error)
            if (job.status == "done") {
                info.put("info", "$API_PREFIX$id/info.json")
                info.put("framePattern", "$API_PREFIX$id/frame/frame_%d.jpg")
                info.put("audio", "$API_PREFIX$id/audio.mp3")
            }
            sendJson(client, 200, info)
            return
        }

        serveInsideDir(client, job.dir, segments.drop(3).joinToString(File.separator), headers)
    }

    // ---------------- 手动传输静态托管（/push/{dir}/...） ----------------

    /**
     * 把手动传输的解压产物托管为 HTTP 静态目录。
     * 复制 workDir 全部内容到 cacheDir/srv_push/{随机dir}/，登记后返回 URL 路径前缀段
     * （如 "ab12cd34ef56"），完整 URL 为 {lanBaseUrl}/push/{段}/<相对路径>。
     * 复制而非直接引用：调用方推送结束后会删除临时解压目录，托管副本须保留到服务重启清理。
     */
    fun mountPushDir(workDir: File): String {
        val dirId = UUID.randomUUID().toString().replace("-", "").take(12)
        val target = File(appContext.cacheDir, "srv_push/$dirId")
        target.deleteRecursively()
        target.mkdirs()
        workDir.listFiles()?.forEach { child ->
            if (child.isDirectory) child.copyRecursively(File(target, child.name), overwrite = true)
            else child.copyTo(File(target, child.name), overwrite = true)
        }
        pushDirs[dirId] = target
        Log.i(TAG, "托管推送目录：/push/$dirId（${target.walkTopDown().filter { it.isFile }.count()} 个文件）")
        return dirId
    }

    private fun servePushFile(client: java.net.Socket, path: String, headers: Map<String, String>) {
        val segments = pathSegments(path)
        // /push/{dir}/... -> ["push", dir, ...]，dir 段下标 1，文件路径从下标 2 开始
        if (segments.size <= 2) {
            sendJson(client, 404, JSONObject().put("error", "bad path"))
            return
        }
        val base = pushDirs[segments[1]]
        if (base == null || !base.exists()) {
            sendJson(client, 404, JSONObject().put("error", "unknown push dir"))
            return
        }
        serveInsideDir(client, base, segments.drop(2).joinToString(File.separator), headers)
    }

    // ---------------- 响应工具 ----------------

    private fun sendJson(client: java.net.Socket, code: Int, json: JSONObject) {
        val data = json.toString().toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    /**
     * 下发文件，支持单段 Range（手表端按 128 KB 分片续传，失败只需重下单片）。
     *   - 无 Range            → 200 全量 + `Accept-Ranges: bytes`
     *   - 合法 Range          → 206 + `Content-Range`
     *   - Range 起始越界      → 416 + `Content-Range`（只标总长）
     */
    private fun sendFile(
        client: java.net.Socket,
        file: File,
        mime: String,
        headers: Map<String, String>
    ) {
        val out = client.getOutputStream()
        val total = file.length()
        val range = parseRange(headers["range"], total)
        if (range != null && range.first < 0) {
            val head = "HTTP/1.1 416 ${reason(416)}\r\n" +
                "Content-Range: bytes */$total\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.flush()
            return
        }
        val start = range?.first ?: 0L
        val end = range?.last ?: (total - 1)
        val len = if (total == 0L) 0L else end - start + 1
        val code = if (range != null) 206 else 200
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $len\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    /**
     * 解析单段 Range（仅 bytes）：
     *   null             → 无 Range / 非 bytes / 多段区间，按整包 200 下发
     *   LongRange(-1,-1) → 无法满足（起始越界），回 416
     * 其余为闭区间 [start, end]。
     */
    private fun parseRange(header: String?, total: Long): LongRange? {
        val raw = header?.trim().orEmpty()
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substring(6).trim()
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        if (total <= 0L) return LongRange(-1, -1)
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        if (startText.isEmpty()) {
            // 后缀区间 bytes=-N：最后 N 字节
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0L) return LongRange(-1, -1)
            val len = suffix.coerceAtMost(total)
            return LongRange(total - len, total - 1)
        }
        val start = startText.toLongOrNull() ?: return null
        if (start < 0L || start >= total) return LongRange(-1, -1)
        val end = if (endText.isEmpty()) total - 1
        else (endText.toLongOrNull() ?: return null).coerceAtMost(total - 1)
        if (end < start) return LongRange(-1, -1)
        return LongRange(start, end)
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        507 -> "Insufficient Storage"
        else -> "OK"
    }

    /**
     * 服务启动时恢复/清理上次残留。
     *
     * 已完成的任务目录必须保留并从 info.json 重建内存表：应用重装或进程重启后
     * [jobs] 会被清空，而手表往往还在用旧 task id 拉帧，直接删盘会导致 404
     * （手表端显示「云端下载失败」）。只有缺 info.json 的残缺目录（未完成/损坏）
     * 才删除。手动传输托管副本（srv_push）仍在启动时清理。
     */
    private fun cleanupStaleJobs() {
        try {
            jobs.clear()
            val root = File(appContext.cacheDir, "srv_jobs")
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory && File(dir, "info.json").isFile) {
                    adoptJob(dir.name)
                } else {
                    dir.deleteRecursively()
                }
            }
            File(appContext.cacheDir, "srv_push").listFiles()?.forEach { it.deleteRecursively() }
            pushDirs.clear()
        } catch (_: Throwable) {
        }
    }

    /**
     * 从磁盘任务目录恢复一个已完成任务（懒加载）。
     *
     * @return 恢复成功返回任务；id 非法、目录或 info.json 缺失、元数据解析失败时返回 null。
     */
    private fun adoptJob(id: String): Job? {
        if (!JOB_ID_PATTERN.matches(id)) return null
        val dir = File(appContext.cacheDir, "srv_jobs/$id")
        val infoFile = File(dir, "info.json")
        if (!dir.isDirectory || !infoFile.isFile) return null
        val meta = try {
            JSONObject(infoFile.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            return null
        }
        val job = Job(
            id = id,
            dir = dir,
            name = meta.optString("name").ifBlank { "video_$id" },
            phase = "完成",
            progress = 1f,
            status = "done",
            totalFrames = meta.optInt("totalFrame"),
            strips = meta.optInt("strips"),
            frameW = meta.optInt("frameW"),
            frameH = meta.optInt("frameH"),
            fps = meta.optInt("fps", 15),
            tile = meta.optInt("tile")
        )
        val existing = jobs.putIfAbsent(id, job)
        if (existing != null) return existing
        Log.i(TAG, "从磁盘恢复任务 $id: ${job.name}（${job.strips} 张胶卷 / ${job.totalFrames} 帧）")
        return job
    }

    // ---------------- 转码通知（受 AppPrefs.notifyTranscode 开关控制） ----------------
    // 五类：收到任务（常驻）/ 转码进度（常驻 + 进度条）/ 完成 / 失败 / 存储过少警示

    /** 收到手表转码请求：常驻静默通知，展示任务名，后续进度更新同一条通知 */
    private fun notifyRequest(job: Job) {
        if (!AppPrefs.get(appContext).notifyTranscode) return
        try {
            ensureChannel(NOTIF_CHANNEL_REQUEST, "转码任务", NotificationManager.IMPORTANCE_LOW)
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val percent = (job.progress * 100).toInt().coerceIn(0, 100)
            nm.notify(NOTIF_ID_REQUEST, baseNotification(NOTIF_CHANNEL_REQUEST)
                .setContentTitle("收到转码请求：${job.name}")
                .setContentText("${job.phase} $percent%")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, false)
                .build())
            job.lastNotifAt = System.currentTimeMillis()
        } catch (e: Throwable) {
            Log.w(TAG, "notify request failed: ${e.message}")
        }
    }

    /**
     * 转码进度：写一条进度日志（节流）+ 更新常驻通知的进度条（500ms 节流）。
     * 日志与通知解耦：即使用户关掉了转码通知，日志页也应看得到"转到哪一步"。
     */
    private fun notifyProgress(job: Job) {
        logProgress(job)
        if (!AppPrefs.get(appContext).notifyTranscode) return
        val now = System.currentTimeMillis()
        if (now - job.lastNotifAt < NOTIF_THROTTLE_MS) return
        job.lastNotifAt = now
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val percent = (job.progress * 100).toInt().coerceIn(0, 100)
            nm.notify(NOTIF_ID_REQUEST, baseNotification(NOTIF_CHANNEL_REQUEST)
                .setContentTitle("转码中：${job.name}")
                .setContentText("${job.phase} $percent%")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, false)
                .build())
        } catch (e: Throwable) {
            Log.w(TAG, "notify progress failed: ${e.message}")
        }
    }

    /** 进度日志：阶段变化或进度跨过 10% 档时才写，避免 ffmpeg 高频回调把 500 条缓冲刷满 */
    private fun logProgress(job: Job) {
        val pct = (job.progress * 100).toInt().coerceIn(0, 100)
        val phaseChanged = job.phase != job.logPhase
        val bucketChanged = (pct / 10) != (job.logPct / 10)
        if (!phaseChanged && !bucketChanged) return
        job.logPhase = job.phase
        job.logPct = pct
        SyncLog.i(TAG, "任务 ${job.id} · ${job.phase} · $pct%")
    }

    /** 任务结束：撤掉常驻进度通知，发结果通知（成功 LOW / 失败 DEFAULT），点击进入应用 */
    private fun notifyResult(job: Job, text: String, success: Boolean) {
        HistoryStore.add(
            appContext,
            "server",
            job.name.ifBlank { job.id },
            text,
            success
        )
        if (!AppPrefs.get(appContext).notifyTranscode) return
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 任务有了结果，撤掉“进行中”的常驻通知
            nm.cancel(NOTIF_ID_REQUEST)
            ensureChannel(NOTIF_CHANNEL_RESULT, "转码结果",
                if (success) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT)
            val notifId = NOTIF_ID_RESULT_BASE + (job.id.hashCode() and 0xFFFF)
            nm.notify(notifId, baseNotification(NOTIF_CHANNEL_RESULT)
                .setContentTitle(if (success) "转码完成：${job.name}" else "转码失败：${job.name}")
                .setContentText(text)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(openAppIntent())
                .build())
        } catch (e: Throwable) {
            Log.w(TAG, "notify result failed: ${e.message}")
        }
    }

    /** 手机剩余存储过少：任务被拒绝时的警示通知，高优先级提醒用户清理空间 */
    private fun notifyStorageLow(freeBytes: Long) {
        if (!AppPrefs.get(appContext).notifyTranscode) return
        try {
            ensureChannel(NOTIF_CHANNEL_STORAGE, "存储告警", NotificationManager.IMPORTANCE_HIGH)
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_STORAGE, baseNotification(NOTIF_CHANNEL_STORAGE)
                .setContentTitle("手机剩余存储过少")
                .setContentText("剩余 ${freeBytes / 1024 / 1024} MB，转码任务已被拒绝，请清理存储后重试")
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(openAppIntent())
                .build())
        } catch (e: Throwable) {
            Log.w(TAG, "notify storage low failed: ${e.message}")
        }
    }

    private fun baseNotification(channel: String): Notification.Builder =
        Notification.Builder(appContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun ensureChannel(id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    private const val NOTIF_CHANNEL_REQUEST = "bilimax_transcode_req"
    private const val NOTIF_CHANNEL_RESULT = "bilimax_transcode_result"
    private const val NOTIF_CHANNEL_STORAGE = "bilimax_transcode_storage"
    private const val NOTIF_ID_REQUEST = 2001
    private const val NOTIF_ID_RESULT_BASE = 2100
    private const val NOTIF_ID_STORAGE = 2002
    // 进度通知最小刷新间隔（毫秒），下载与转码回调远高于此频率
    private const val NOTIF_THROTTLE_MS = 500L
    // 任务 id 只允许本进程生成的字符集；从 URL 恢复磁盘目录前先校验，杜绝路径穿越
    private val JOB_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
}
