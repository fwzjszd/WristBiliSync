package moe.mcns.BiliMax.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 胶卷包（.bmvd）手动推送到手表的互联协议管理器，跑在 XMS 消息通道（[WearTransport]）之上。
 *
 * 与表端约定的消息（能力来自统一握手 SF_HANDSHAKE 登记，不再单独探测）：
 *   1. 握手 data.fetch == true（表端 @system.fetch 可用）：
 *      手机把 .bmvd 解压到 HTTP 服务器静态目录，发 SERVER_UPLOAD_FILE（含服务器候选基址、
 *      胶卷名、文件清单与字节数），表端自行用 fetch 逐文件下载，全部完成后回
 *      SERVER_UPLOAD_FILE_DONE；直连失败时自动降级到消息通道；
 *   2. 握手 data.fetch == false：
 *      手机走逐文件 UPLOAD_FILE（base64 分片，append/position 续传），
 *      每个文件收 UPLOAD_FILE_DONE 后再传下一个，协议与 EternalAstroboxPlugins 互联一致。
 *
 * 约束：XMS 单条消息 < 4096 字节（UTF-8），base64 分片每片有效载荷约 2.4KB；
 * 全部流程在单线程 io 上串行执行，回调切主线程。
 */
object FilmPush {

    private const val TAG = "BiliMax-Push"

    const val T_SERVER_UPLOAD_FILE = "SERVER_UPLOAD_FILE"
    const val T_SERVER_UPLOAD_PROGRESS = "SERVER_UPLOAD_PROGRESS"
    const val T_SERVER_UPLOAD_FILE_DONE = "SERVER_UPLOAD_FILE_DONE"
    const val T_UPLOAD_FILE = "UPLOAD_FILE"
    const val T_UPLOAD_FILE_DONE = "UPLOAD_FILE_DONE"

    // 手表端胶卷落盘根目录（internal://files）
    private const val DEST_BASE_URI = "internal://files/film"
    // SERVER_UPLOAD_FILE 静态服务的 URL 前缀（TranscodeServer 提供 /push/<dir>/... 路由）
    private const val PUSH_URL_PREFIX = "/push"

    // 单条消息 base64 有效载荷：2400 字符 ≈ 1800 字节/片，加信封后 < 4096 字节
    private const val CHUNK_BYTES = 1800
    private const val RESPONSE_TIMEOUT_MS = 30_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "film-push-io").apply { isDaemon = true }
    }

    interface Callback {
        /** phase：当前阶段说明；progress：0~1；doneFile/totalFile：逐文件进度 */
        fun onPhase(phase: String, progress: Float, doneFile: Int, totalFile: Int)
        fun onLog(line: String)
        fun onDone(viaFetch: Boolean)
        fun onError(message: String)
    }

    private class PendingRequest(val type: String) {
        val latch: CountDownLatch = CountDownLatch(1)
        val status: AtomicReference<String?> = AtomicReference(null)
        val data: AtomicReference<JSONObject?> = AtomicReference(null)
        /** 最近一次收到任何响应（含 SERVER_UPLOAD_PROGRESS）的时间，用于空闲超时判定 */
        val lastActivity = AtomicLong(System.currentTimeMillis())
        /** 是否收到过表端下载进度；收到过即证明表端已开始拉取并写盘 */
        val sawProgress = AtomicBoolean(false)
        @Volatile var onProgress: ((JSONObject) -> Unit)? = null
    }

    @Volatile
    private var pending: PendingRequest? = null

    /** 是否有胶卷包正在推送（单线程串行，UI 据此防止重复发起） */
    @Volatile
    private var busy = false

    fun isBusy(): Boolean = busy

    /**
     * App 启动时调用一次：把非 SF_ 的互联业务消息接入本管理器。
     * 必须在 SfBridge.attach 设置 messageHandler 之后再包一层。
     */
    fun attach(context: Context) {
        try {
            val transport = WearTransport.get(context)
            val inner = transport.messageHandler
            transport.messageHandler = { raw ->
                try {
                    if (!handleMessage(raw)) inner?.invoke(raw)
                } catch (t: Throwable) {
                    Log.w(TAG, "handle message failed: ${t.message}")
                    inner?.invoke(raw)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attach failed: ${t.message}")
        }
    }

    /**
     * 处理手表回包；返回 true 表示已被本管理器消费（不再下发 SF 桥接）。
     */
    private fun handleMessage(raw: String): Boolean {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return false
        }
        val type = msg.optString("type")
        val p = pending
        // 进度回包：只刷新「最近活动时间」并回调，不结算（等 SERVER_UPLOAD_FILE_DONE 才结束）
        if (type == T_SERVER_UPLOAD_PROGRESS) {
            if (p != null && p.type == T_SERVER_UPLOAD_FILE_DONE) {
                p.lastActivity.set(System.currentTimeMillis())
                p.sawProgress.set(true)
                try {
                    p.onProgress?.invoke(msg.optJSONObject("data") ?: JSONObject())
                } catch (_: Throwable) {
                }
            }
            return true
        }
        if (p == null) return type in RESPONSE_TYPES
        if (type != p.type) return type in RESPONSE_TYPES
        p.lastActivity.set(System.currentTimeMillis())
        p.status.set(msg.optString("status", "OK"))
        p.data.set(msg.optJSONObject("data"))
        p.latch.countDown()
        return true
    }

    private val RESPONSE_TYPES = setOf(
        T_SERVER_UPLOAD_FILE_DONE, T_UPLOAD_FILE_DONE
    )

    /**
     * 直连通道在表端**已开始拉取**后才失败（超时/断连）。
     * 此时绝不能再降级到消息通道：表端 HTTP 下载可能仍在写同一批文件，
     * 两条通道并发 resetDir/覆盖写会直接触发 r107 真机闪退。
     */
    private class PushStartedException(message: String) : IllegalStateException(message)

    /**
     * 按最大单文件体积估算表端单文件下载超时（保守 64KB/s + 15s 建连/转存余量），
     * 钳制在 [60s, 10min]。表端用它算每个文件的看门狗，手机用它算空闲超时。
     */
    private fun estimateFileTimeoutMs(maxBytes: Long): Long {
        val base = if (maxBytes > 0) {
            (maxBytes + 64 * 1024 - 1) / (64 * 1024) * 1000 + 15_000
        } else 0L
        return base.coerceIn(60_000L, 10 * 60 * 1000L)
    }

    private fun send(transport: WearTransport, type: String, data: JSONObject): Boolean {
        val text = JSONObject()
            .put("type", type)
            .put("status", "OK")
            .put("data", data)
            .toString()
        var ok = false
        val latch = CountDownLatch(1)
        transport.sendText(text) { success ->
            ok = success
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return ok
    }

    /**
     * 发送请求并同步等待对应响应；超时或 status 非 OK 抛异常。
     *
     * 超时语义是「空闲超时」而非「总超时」：每收到一条同请求的 SERVER_UPLOAD_PROGRESS
     * （或同类型响应）就刷新计时，因此整卷 30~40MB 在慢链路上不会被一刀切判死；
     * 只有连续 idleTimeoutMs 无任何回包才判失败。
     */
    private fun request(
        transport: WearTransport,
        reqType: String,
        respType: String,
        data: JSONObject,
        idleTimeoutMs: Long = RESPONSE_TIMEOUT_MS,
        onProgress: ((JSONObject) -> Unit)? = null
    ): JSONObject {
        val req = PendingRequest(respType)
        req.onProgress = onProgress
        pending = req
        try {
            if (!send(transport, reqType, data)) {
                throw IllegalStateException("消息发送失败（手表未连接？）")
            }
            while (true) {
                val idleFor = System.currentTimeMillis() - req.lastActivity.get()
                val remain = idleTimeoutMs - idleFor
                if (remain <= 0) {
                    val msg = if (req.sawProgress.get()) {
                        "等待手表响应超时（${respType}，已 ${idleFor / 1000}s 无进度）"
                    } else {
                        "等待手表响应超时（$respType）"
                    }
                    throw IllegalStateException(msg)
                }
                if (req.latch.await(remain, TimeUnit.MILLISECONDS)) break
            }
            val status = req.status.get() ?: "OK"
            if (status != "OK") {
                throw IllegalStateException("手表返回错误：$status")
            }
            return req.data.get() ?: JSONObject()
        } finally {
            pending = null
        }
    }

    /**
     * 推送 .bmvd 胶卷包到手表。
     * @param bvidFile  .bmvd 文件（ZIP 容器：info.json + frame/frame_N.jpg + audio.mp3?）
     * @param filmName  胶卷名（手表端目录名）
     */
    fun push(context: Context, bvidFile: File, filmName: String, callback: Callback) {
        val appContext = context.applicationContext
        io.execute {
            busy = true
            try {
                val transport = WearTransport.get(appContext)
                if (transport.currentState() != WearState.CONNECTED) {
                    throw IllegalStateException("手表未连接，请先在转码服务页确认连接状态")
                }

                // 1) 解压 .bmvd 到临时目录
                post(callback) { onPhase("解压胶卷包", 0.02f, 0, 0) }
                val workDir = File(appContext.cacheDir, "push_work/${System.currentTimeMillis()}")
                workDir.deleteRecursively()
                workDir.mkdirs()
                val files = unzip(bvidFile, workDir)
                if (files.isEmpty()) throw IllegalStateException("胶卷包为空或格式不正确")
                log(callback, "解压完成：${files.size} 个文件")

                // 2) 读取统一握手已登记的表端 fetch 能力（不再单独发探测消息）
                //    ⚠️ 注册表的键是表端自报的 device.deviceId，而这里只有 XMS nodeId（peerId），
                //    两者不同源：只按键精确查会永远落空 ⇒ fetchAvailable 恒 false ⇒ 直连通道永不启用
                //    （r41 优化的"帧按序号 + 音频收尾"直连快路径因此从未生效，r92 修复）
                val fetchAvailable = DeviceRegistry.findByPeer(transport.peerId)?.fetchSupported == true
                log(callback, if (fetchAvailable) "手表支持直连下载（fetch）" else "手表不支持直连下载，改用消息通道逐文件传输")

                var viaFetch = false
                // 计入设备负载（预留：未来多设备同时在线时，服务器据此分配推送/转码任务）
                val deviceId = transport.peerId.ifBlank { "wear" }
                DeviceRegistry.beginJob(deviceId)
                try {
                    if (fetchAvailable) {
                        // 直连通道：表端尚未开始拉取时（地址不可达/服务未开）才降级消息通道
                        try {
                            pushViaServer(transport, appContext, workDir, files, filmName, callback)
                            viaFetch = true
                        } catch (e: PushStartedException) {
                            // 表端已在写盘：并发降级会双通道写同一批文件（r107 闪退根因），直接报错
                            log(callback, e.message ?: "直连下载中断")
                            throw e
                        } catch (e: Throwable) {
                            log(callback, "直连下载失败（${e.message}），改用消息通道逐文件传输")
                            pushViaMessages(transport, workDir, files, filmName, callback)
                        }
                    } else {
                        pushViaMessages(transport, workDir, files, filmName, callback)
                    }
                } finally {
                    DeviceRegistry.endJob(deviceId)
                }

                workDir.deleteRecursively()
                post(callback) { onDone(viaFetch) }
            } catch (e: Throwable) {
                Log.w(TAG, "push failed: ${e.message}")
                post(callback) { onError(e.message ?: "传输失败") }
            } finally {
                busy = false
            }
        }
    }

    /**
     * 路径一（fetch 可用）：解压目录挂到 HTTP 服务器静态路由，通知表端自行下载。
     */
    private fun pushViaServer(
        transport: WearTransport,
        context: Context,
        workDir: File,
        files: List<File>,
        filmName: String,
        callback: Callback
    ) {
        if (!TranscodeServer.isRunning()) {
            throw IllegalStateException("转码服务未运行，手表无法直连下载，请先开启转码服务")
        }
        val bases = TranscodeServer.candidateBases()
        if (bases.isEmpty()) {
            throw IllegalStateException("无法获取手机局域网地址，请确认手机与手表在同一网络")
        }
        // 把解压产物交给服务器静态托管（cacheDir/srv_push/<dir>），返回 URL 路径前缀
        val serveDir = TranscodeServer.mountPushDir(workDir)
        log(callback, "已托管 ${files.size} 个文件供手表直连下载（/push/$serveDir）")

        val fileInfos = JSONArray()
        files.forEach { f ->
            fileInfos.put(JSONObject()
                .put("path", f.relativeTo(workDir).invariantSeparatorsPath)
                .put("size", f.length()))
        }
        // 表端单文件看门狗与手机空闲超时都按最大文件体积估算：
        // 10MB 音频 ≈ 175s，不会像固定 30s 那样把正常慢链路误判成失败并触发双通道并发。
        val maxSize = files.maxOfOrNull { it.length() } ?: 0L
        val fileTimeoutMs = estimateFileTimeoutMs(maxSize)
        val idleTimeoutMs = fileTimeoutMs + 60_000L
        val data = JSONObject()
            // 首选基址保留旧字段；新增候选基址列表，表端探活择优，任一可达即可下载
            .put("baseUrl", "${bases.first()}$PUSH_URL_PREFIX/$serveDir")
            .put("bases", JSONArray(bases.map { "$it$PUSH_URL_PREFIX/$serveDir" }))
            .put("name", filmName)
            .put("destDir", "$DEST_BASE_URI/$filmName")
            .put("files", fileInfos)
            .put("fileTimeout", fileTimeoutMs)

        post(callback) { onPhase("已通知手表下载，等待手表拉取", 0.2f, 0, files.size) }
        log(callback, "服务器候选地址：${bases.joinToString()}")
        val started = AtomicBoolean(false)
        val resp = try {
            request(transport, T_SERVER_UPLOAD_FILE, T_SERVER_UPLOAD_FILE_DONE, data, idleTimeoutMs) { d ->
                // 表端每落盘一个文件回一条 SERVER_UPLOAD_PROGRESS：既刷新空闲超时，
                // 也把「已开始拉取」这件事记下来（决定失败时是否允许降级）
                started.set(true)
                val done = d.optInt("done", -1)
                if (done < 0) return@request
                val total = d.optInt("total", files.size).coerceAtLeast(1)
                post(callback) {
                    onPhase("手表下载 $done/$total", 0.2f + 0.8f * (done.toFloat() / total), done, total)
                }
            }
        } catch (e: Throwable) {
            // 表端已在写盘：禁止降级（否则消息通道与 HTTP 下载并发写同一批文件，真机闪退）
            if (started.get()) {
                throw PushStartedException("直连下载中断（${e.message ?: "未知错误"}），表端已开始写盘，为避免双通道并发写盘未自动降级，请重试")
            }
            throw e
        }
        val ok = resp.optBoolean("success", true)
        if (!ok) throw IllegalStateException("手表下载失败：${resp.optString("message", "未知错误")}")
        post(callback) { onPhase("传输完成", 1f, files.size, files.size) }
    }

    /**
     * 路径二（fetch 不可用/直连失败）：逐文件 UPLOAD_FILE，base64 分片推送。
     * Vela 文件接口无 append/position，表端按 uri 收齐全部分片后一次性写盘，
     * 本端在每个文件末片带 last=true 作为结束标记。
     */
    private fun pushViaMessages(
        transport: WearTransport,
        workDir: File,
        files: List<File>,
        filmName: String,
        callback: Callback
    ) {
        val total = files.size
        files.forEachIndexed { index, f ->
            val rel = f.relativeTo(workDir).invariantSeparatorsPath
            val uri = "$DEST_BASE_URI/$filmName/$rel"
            post(callback) {
                onPhase("传输文件 ${index + 1}/$total：$rel", 0.1f + 0.9f * index / total, index, total)
            }
            uploadOneFile(transport, f, uri) { sent, len ->
                // 复用上面已做零值保护的 fileFrac：原实现在此处又做了一次无保护除法（len=0 时抛异常）
                val fileFrac = if (len > 0) sent.toFloat() / len else 1f
                post(callback) {
                    onPhase(
                        "传输文件 ${index + 1}/$total：$rel（${(fileFrac * 100).toInt()}%）",
                        0.1f + 0.9f * (index + fileFrac) / total,
                        index, total
                    )
                }
            }
            // 日志要能看出「传到第几个、还剩几个」，而不只是文件名
            val remaining = total - index - 1
            log(callback, "已传输 $rel（${index + 1}/$total，剩 $remaining 个文件，${f.length() / 1024} KB）")
        }
        post(callback) { onPhase("全部文件传输完成", 1f, total, total) }
    }

    /** 单个文件分片上传：逐片 base64 推送，末片带 last=true，表端收齐后一次性写盘 */
    private fun uploadOneFile(
        transport: WearTransport,
        file: File,
        uri: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ) {
        val totalLen = file.length()
        var sent = 0L
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = readFully(input, buf)
                if (n <= 0) break
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val last = sent + n >= totalLen
                val data = JSONObject()
                    .put("uri", uri)
                    .put("data", b64)
                    .put("append", sent > 0)
                    .put("last", last)
                if (sent == 0L) data.put("position", 0)
                request(transport, T_UPLOAD_FILE, T_UPLOAD_FILE_DONE, data)
                sent += n
                onProgress(sent, totalLen)
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun unzip(zipFile: File, destDir: File): List<File> {
        val out = mutableListOf<File>()
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                // 防目录穿越：等于目录本身（zip 里的 "./" 根条目）或在其内才放行；补分隔符避免
                // srv_x 与 srv_xyz 这类同前缀目录被误判为在目录内
                val dest = destDir.canonicalPath
                val path = outFile.canonicalPath
                if (path != dest && !path.startsWith(dest + File.separator)) {
                    throw IllegalStateException("非法的胶卷包路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { zis.copyTo(it) }
                    out.add(outFile)
                }
                zis.closeEntry()
            }
        }
        // 推送顺序：info.json → frame/frame_N.jpg（按数字序号）→ 音频及其它文件收尾。
        // 画面优先落盘，音频单文件最后；不能用字典序（audio.mp3 会排到 frame/ 前，
        // 且 frame_10 会排到 frame_2 前）。直连通道下发给表端的清单与消息通道发送顺序共用此排序。
        return out.sortedWith { a, b ->
            val ra = a.relativeTo(destDir).invariantSeparatorsPath
            val rb = b.relativeTo(destDir).invariantSeparatorsPath
            val ba = filmBucket(ra)
            val bb = filmBucket(rb)
            if (ba != bb) return@sortedWith ba - bb
            if (ba == 1) {
                val na = frameIndex(ra)
                val nb = frameIndex(rb)
                if (na != nb) return@sortedWith na - nb
            }
            ra.compareTo(rb)
        }
    }

    private val FRAME_PATH = Regex("""frame/frame_(\d+)\.jpg${'$'}""")

    private fun filmBucket(rel: String): Int = when {
        rel == "info.json" -> 0
        FRAME_PATH.matches(rel) -> 1
        else -> 2
    }

    private fun frameIndex(rel: String): Int =
        FRAME_PATH.find(rel)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun log(callback: Callback, line: String) {
        Log.i(TAG, line)
        SyncLog.i(TAG, line)
        post(callback) { onLog(line) }
    }

    private fun post(callback: Callback, action: Callback.() -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                callback.action()
            } catch (_: Throwable) {
            }
        }
    }
}
