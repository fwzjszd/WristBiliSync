package moe.mcns.BiliMax.transcode

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// MJPEG 编码器 -q:v 取值 2~31，数值越小画质越高、体积越大；
// 标准档 10 对齐手表端参考转码命令（-q:v 10）
enum class FilmQuality(val label: String, val q: Int) {
    HIGH("高画质", 7),
    STANDARD("标准", 10),
    LOW("省空间", 15)
}

data class TranscodeOptions(
    val name: String,
    val exportAudio: Boolean,
    val quality: FilmQuality,
    val fps: Int = 15
)

data class TranscodeResult(
    val zipFile: File,
    val totalFrames: Int,
    val stripCount: Int,
    val durationSec: Double,
    val withAudio: Boolean
)

class Transcoder {

    interface Callback {
        fun onPhase(phase: String, progress: Float)
        fun onLog(line: String)
        fun onDone(result: TranscodeResult)
        fun onError(message: String)
    }

    // 转码工作线程用 companion 里的进程级共享池（原先每个实例各自 new 一个、从不 shutdown，
    // 而服务器路径每个任务都 new 一个 Transcoder ⇒ 每任务泄漏一条常驻线程）
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context, input: Uri, options: TranscodeOptions, callback: Callback) {
        sharedExecutor.execute {
            try {
                runTranscode(context, input, options, callback)
            } catch (t: Throwable) {
                // 转码失败属于业务失败（文件损坏、编码不支持、FFmpeg 返回错误等），
                // 应用本身并未崩溃：不能写崩溃记录、更不能在主页弹崩溃卡片。
                // 现场信息完整打到 logcat（被崩溃采集器持续落盘），界面只提示转码失败原因。
                android.util.Log.e(
                    "Transcoder",
                    "转码失败 video=${options.name} quality=${options.quality.label} " +
                        "audio=${options.exportAudio} uri=$input",
                    t
                )
                val cause = t.cause
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: cause?.message?.takeIf { it.isNotBlank() }
                    ?: t.javaClass.simpleName
                log(callback, "转码失败：$detail")
                postError(
                    callback,
                    "转码失败：$detail\n\n" +
                        "可尝试：更换其他 MP4 文件、降低画质后重试；" +
                        "若多次失败，请将手机「下载/BiliMax」文件夹中的日志反馈给开发者。"
                )
            }
        }
    }

    /**
     * 服务器版转码：产物直接输出到 [workDir]（frame/frame_N.jpg + info.json + 可选 audio.mp3），
     * 不打包 zip，供手表经 HTTP/互联按文件下载。输入为本地文件（视频已由服务器预先下载落盘）。
     */
    fun startServerJob(
        workDir: File,
        inputFile: File,
        options: TranscodeOptions,
        callback: Callback
    ) {
        sharedExecutor.execute {
            try {
                runServerTranscode(workDir, inputFile, options, callback)
            } catch (t: Throwable) {
                android.util.Log.e(
                    "Transcoder",
                    "服务器转码失败 video=${options.name} quality=${options.quality.label} " +
                        "audio=${options.exportAudio} file=$inputFile",
                    t
                )
                val cause = t.cause
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: cause?.message?.takeIf { it.isNotBlank() }
                    ?: t.javaClass.simpleName
                log(callback, "转码失败：$detail")
                postError(callback, detail)
            }
        }
    }

    private fun runTranscode(
        context: Context,
        input: Uri,
        options: TranscodeOptions,
        callback: Callback
    ) {
        val work = File(context.cacheDir, "transcode/${System.currentTimeMillis()}")
        val frameDir = File(work, "frame")
        frameDir.mkdirs()
        runFilmPipeline(context, input, null, work, frameDir, options, callback, zipResult = true)
    }

    private fun runServerTranscode(
        workDir: File,
        inputFile: File,
        options: TranscodeOptions,
        callback: Callback
    ) {
        val frameDir = File(workDir, "frame")
        frameDir.mkdirs()
        runFilmPipeline(null, Uri.EMPTY, inputFile, workDir, frameDir, options, callback, zipResult = false)
    }

    /**
     * 胶卷转码流水线。两种输入二选一：
     * - SAF/content Uri（手动转码，[context] 非空）：每次 ffmpeg 调用前重新取 saf 参数；
     * - 本地文件（服务器转码，[inputFile] 非空）：直接传文件绝对路径。
     * [zipResult] = true 时末尾打包 output.bmvd（BiliMax 胶卷包，ZIP 容器，手动转码导出）；
     * false 时产物留在 [workDir]（服务器任务，由 HTTP 逐文件提供）。
     */
    private fun runFilmPipeline(
        context: Context?,
        input: Uri,
        inputFile: File?,
        work: File,
        frameDir: File,
        options: TranscodeOptions,
        callback: Callback,
        zipResult: Boolean
    ) {

        phase(callback, "读取视频信息", 0.02f)

        // 文件选择器返回 content:// URI，ffmpeg/ffprobe 原生层无法直接打开，
        // 必须转换为 ffmpeg-kit 的 saf 参数（内部通过 ContentResolver 读取）。
        // 注意：saf 参数底层对应一个打开的 fd，session 结束后 fd 会被关闭，
        // 不能跨 session 复用——ffprobe / 帧生成 / 提音频每次执行前都要重新获取。
        // 服务器任务输入为已落盘的本地文件，直接用绝对路径。
        val durationSec = probeDuration(resolveInputRef(context, input, inputFile))
        if (durationSec <= 0.0) {
            throw IllegalStateException("无法读取视频时长，请确认文件为有效 MP4")
        }
        log(callback, "视频时长 %.1f 秒".format(Locale.ROOT, durationSec))

        val durationMs = (durationSec * 1000.0).toLong()

        // 输出帧尺寸：最长边按 MAX_SIDE 等比缩放，短边对齐偶数（JPEG 编码器要求）。
        // 同一视频所有帧尺寸一致，tile 排布步长固定，手表端按帧宽裁切拼接处无缝；
        // 帧实际宽高通过 info.json 的 frameW/frameH 下发，不拉伸变形、不留黑边。
        val srcSize = probeSize(resolveInputRef(context, input, inputFile))
        val (outW, outH) = fitSize(srcSize.first, srcSize.second)
        log(callback, "输出帧尺寸 ${outW}x${outH}，${options.fps}fps")

        // totalFrame 必须是 fps 过滤后实际抽取的帧数，而非“时长×帧率”的估算值
        // （估算可能偏差十几帧，手表端会据此读取，末尾读到填充黑帧）
        phase(callback, "统计帧数", 0.03f)
        val totalFrames = countFrames(resolveInputRef(context, input, inputFile), durationMs, options.fps, callback)
        log(callback, "实际抽取 $totalFrames 帧")

        phase(callback, "生成胶卷帧", 0.05f)
        // r6t 防御：帧生成前清空目录内的旧 jpg 残留，避免将来复用目录时混入上一任务的帧导致胶卷错位
        frameDir.listFiles { f -> f.extension.lowercase(Locale.ROOT) == "jpg" }?.forEach { it.delete() }
        val frameArgs = mutableListOf(
            "-y",
            "-i", resolveInputRef(context, input, inputFile),
            "-vf", "fps=${options.fps}," +
                // 最长边 MAX_SIDE 等比缩放，force_original_aspect_ratio=decrease 保证不变形；
                // 二次 scale 仅做偶数对齐（结果已是偶数，不再改变比例）
                "scale=${outW}:${outH}:flags=lanczos," +
                // padding 必须为 0：帧间任何黑边都会在胶卷拼接处留缝。
                // tile 必须跟随 fps：一张胶卷 = 1 秒是手表端推算帧号的前提，固定 15 会让
                // fps≠15 的转码出现「一张胶卷≠1 秒」（r6t 规格，r92 回归修复）
                "tile=${options.fps}x1:margin=0:padding=0",
            "-fps_mode", "passthrough",
            "-q:v", options.quality.q.toString(),
            File(frameDir, "frame_%d.jpg").absolutePath
        )
        runFfmpeg(frameArgs, durationMs, 0.05f, 0.82f, callback)

        val strips = frameDir.listFiles { f -> f.extension.lowercase(Locale.ROOT) == "jpg" }
            ?.sortedBy { f ->
                // 帧序号只认 "frame_<数字>"（与 FilmPush 的 FRAME_PATH 同形）：原实现用非锚定的
                // (\d+) 取首个数字串，遇到 "1_frame_2.jpg" 这类名字会取错序号
                Regex("frame_(\\d+)$").find(f.nameWithoutExtension)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } ?: emptyList()
        if (strips.isEmpty()) {
            throw IllegalStateException("未生成任何胶卷图片")
        }
        log(callback, "生成胶卷 ${strips.size} 张，共 $totalFrames 帧")

        var audioFile: File? = null
        if (options.exportAudio) {
            phase(callback, "提取音频", 0.84f)
            audioFile = File(work, "audio.mp3")
            val audioArgs = listOf(
                "-y",
                "-i", resolveInputRef(context, input, inputFile),
                "-vn",
                "-acodec", "libmp3lame",
                "-b:a", "128k",
                audioFile.absolutePath
            )
            try {
                runFfmpeg(audioArgs, durationMs, 0.84f, 0.92f, callback)
                if (!audioFile.exists() || audioFile.length() == 0L) {
                    audioFile = null
                    log(callback, "该视频没有可提取的音轨")
                }
            } catch (e: Exception) {
                audioFile = null
                log(callback, "音频提取失败，将仅导出画面")
            }
        }

        val info = JSONObject()
        info.put("name", options.name)
        info.put("fps", options.fps)
        info.put("totalFrame", totalFrames)
        info.put("strips", strips.size)
        info.put("tile", options.fps)
        info.put("frameW", outW)
        info.put("frameH", outH)
        if (audioFile != null) {
            info.put("audioFile", "audio.mp3")
        }

        if (zipResult) {
            phase(callback, "打包 .bmvd 胶卷包", 0.94f)
            val zipFile = File(work, "output.bmvd")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("info.json"))
                zip.write(info.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                for (f in strips) {
                    zip.putNextEntry(ZipEntry("frame/${f.name}"))
                    f.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                if (audioFile != null) {
                    zip.putNextEntry(ZipEntry("audio.mp3"))
                    audioFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            log(callback, "打包完成：${zipFile.length() / 1024} KB")

            phase(callback, "完成", 1f)
            val result = TranscodeResult(
                zipFile = zipFile,
                totalFrames = totalFrames,
                stripCount = strips.size,
                durationSec = durationSec,
                withAudio = audioFile != null
            )
            main.post { callback.onDone(result) }
        } else {
            // 服务器模式：info.json 直接写到任务目录，帧与音频已在 workDir 下
            phase(callback, "写入信息", 0.94f)
            File(work, "info.json").writeText(info.toString(2), Charsets.UTF_8)
            phase(callback, "完成", 1f)
            val result = TranscodeResult(
                zipFile = File(work, "info.json"),
                totalFrames = totalFrames,
                stripCount = strips.size,
                durationSec = durationSec,
                withAudio = audioFile != null
            )
            main.post { callback.onDone(result) }
        }
    }

    /** 两种输入源统一解析为 ffmpeg 可用的引用：本地文件用路径，content Uri 用 saf 参数 */
    private fun resolveInputRef(context: Context?, input: Uri, inputFile: File?): String {
        if (inputFile != null) return inputFile.absolutePath
        return openInputRef(context!!, input)
    }

    private fun openInputRef(context: Context, input: Uri): String {
        if (input.scheme == ContentResolver.SCHEME_CONTENT) {
            return try {
                FFmpegKitConfig.getSafParameterForRead(context, input)
            } catch (e: Exception) {
                throw IllegalStateException("无法读取所选文件（授权失效或文件不可访问），请重新选择 MP4")
            }
        }
        // file:// 必须转换为裸路径：直接把 file URI 字符串传给 ffmpeg/ffprobe 时，
        // 其中的中文（标题）未做百分号编码会导致打不开文件，表现为无法读取视频时长。
        if (input.scheme == ContentResolver.SCHEME_FILE) {
            return input.path ?: input.toString()
        }
        return input.toString()
    }

    private fun probeDuration(input: String): Double {
        val session = FFprobeKit.getMediaInformation(input)
        val info = session?.mediaInformation
        if (info == null) {
            val logs = session?.allLogsAsString ?: ""
            val tail = logs.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
            android.util.Log.e("Transcoder", "ffprobe 无法读取视频信息：\n$tail")
            return 0.0
        }
        return info.duration?.toDoubleOrNull() ?: 0.0
    }

    /** 探测源视频宽高；失败时回退 16:9（不阻断转码） */
    private fun probeSize(input: String): Pair<Int, Int> {
        val session = FFprobeKit.getMediaInformation(input)
        val stream = session?.mediaInformation?.streams
            ?.firstOrNull { it.type == "video" }
        val w = stream?.width?.takeIf { it > 0 }?.toInt()
        val h = stream?.height?.takeIf { it > 0 }?.toInt()
        if (w != null && h != null) return w to h
        android.util.Log.w("Transcoder", "ffprobe 未取到视频宽高，回退 16:9")
        return 16 to 9
    }

    /** 最长边缩放到 [MAX_SIDE] 等比缩放，两边对齐偶数（JPEG yuv420p 要求） */
    private fun fitSize(srcW: Int, srcH: Int): Pair<Int, Int> {
        val scale = MAX_SIDE.toFloat() / maxOf(srcW, srcH)
        fun even(v: Float) = maxOf(2, (v / 2f).toInt() * 2)
        return if (srcW >= srcH) {
            MAX_SIDE to even(srcH * scale)
        } else {
            even(srcW * scale) to MAX_SIDE
        }
    }

    private fun runFfmpeg(
        args: List<String>,
        durationMs: Long,
        from: Float,
        to: Float,
        callback: Callback
    ) {
        val latch = CountDownLatch(1)
        val failure = AtomicLong(-1L)
        val phaseName = if (to <= 0.82f) "生成胶卷帧" else "提取音频"

        FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { session ->
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    failure.set(1L)
                }
                latch.countDown()
            },
            { log ->
                val msg = log?.message?.trim() ?: ""
                if (msg.isNotEmpty()) android.util.Log.i("Transcoder", msg)
            },
            { statistics: Statistics ->
                val time = statistics.time
                if (time > 0.0 && durationMs > 0L) {
                    val frac = (time / durationMs).coerceIn(0.0, 1.0)
                    val p = (from + (to - from) * frac).toFloat()
                    main.post { callback.onPhase(phaseName, p) }
                }
            }
        )

        // 看门狗：ffmpeg-kit 的回调在极端情况下可能永不到达（native 异常/被系统杀掉），
        // 无超时会把这条转码线程永久挂死，任务永远停在"转码中"（r92）
        if (!latch.await(FFMPEG_TIMEOUT_MIN, TimeUnit.MINUTES)) {
            FFmpegKit.cancel()
            throw IllegalStateException("${phaseName}超时（>${FFMPEG_TIMEOUT_MIN} 分钟），已取消")
        }

        if (failure.get() == 1L) {
            val session = FFmpegKitConfig.getLastSession()
            val logs = session?.allLogsAsString ?: ""
            val tail = logs.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
            val failLog = "${phaseName}失败，FFmpeg 返回：\n$tail"
            android.util.Log.e("Transcoder", failLog)
            main.post { callback.onLog(failLog) }
            throw IllegalStateException("${phaseName}失败，请换用其他 MP4 或降低画质重试")
        }
    }

    // 用 -f null 空输出跑一遍与抽帧相同的 fps 过滤，取统计回调的最终帧计数，
    // 得到实际抽取帧数（不写文件，只多一次解码，开销很小）
    private fun countFrames(
        inputRef: String,
        durationMs: Long,
        fps: Int,
        callback: Callback
    ): Int {
        val latch = CountDownLatch(1)
        val failure = AtomicLong(-1L)
        val frames = java.util.concurrent.atomic.AtomicInteger(0)

        FFmpegKit.executeWithArgumentsAsync(
            arrayOf(
                "-i", inputRef,
                "-vf", "fps=$fps",
                "-an",
                "-f", "null",
                "-"
            ),
            { session ->
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    failure.set(1L)
                } else if (frames.get() == 0) {
                    // 兜底：个别版本 null 输出不回传统计帧号，从会话日志结尾的
                    // “frame=  NNN”进度行解析实际输出帧数
                    val logs = session.allLogsAsString ?: ""
                    Regex("frame=\\s*(\\d+)").findAll(logs).lastOrNull()
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { frames.set(it) }
                }
                latch.countDown()
            },
            { log ->
                val msg = log?.message?.trim() ?: ""
                if (msg.isNotEmpty()) android.util.Log.i("Transcoder", msg)
            },
            { statistics: Statistics ->
                if (statistics.videoFrameNumber > 0) frames.set(statistics.videoFrameNumber)
                val time = statistics.time
                if (time > 0.0 && durationMs > 0L) {
                    val frac = (time / durationMs).coerceIn(0.0, 1.0)
                    main.post { callback.onPhase("统计帧数", (0.03f + 0.02f * frac).toFloat()) }
                }
            }
        )

        // 同样的看门狗（见 runFfmpeg 注释）
        if (!latch.await(FFMPEG_TIMEOUT_MIN, TimeUnit.MINUTES)) {
            FFmpegKit.cancel()
            throw IllegalStateException("统计帧数超时（>${FFMPEG_TIMEOUT_MIN} 分钟），已取消")
        }
        if (failure.get() == 1L) {
            throw IllegalStateException("统计帧数失败，请换用其他 MP4 重试")
        }
        return frames.get().coerceAtLeast(1)
    }

    private fun phase(callback: Callback, phase: String, progress: Float) {
        main.post { callback.onPhase(phase, progress) }
    }

    private fun log(callback: Callback, line: String) {
        main.post { callback.onLog(line) }
    }

    private fun postError(callback: Callback, message: String) {
        main.post { callback.onError(message) }
    }

    companion object {
        const val FPS = 15
        // 输出帧最长边像素，按视频比例等比缩放
        const val MAX_SIDE = 500

        /** 单个 ffmpeg 会话的看门狗上限（分钟）：回调永不来时不能把转码线程永久挂死 */
        private const val FFMPEG_TIMEOUT_MIN = 60L

        /**
         * 进程级共享单线程池：转码是重活，串行执行更省内存，daemon 线程也不阻塞进程退出。
         * 原实现每个 Transcoder 实例各自 new 一个从不 shutdown 的 executor，而服务器路径
         * **每个任务都 new 一个 Transcoder** ⇒ 每处理一个任务就泄漏一条常驻线程（r92 修复）。
         */
        private val sharedExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bilimax-transcode").apply { isDaemon = true }
        }
    }
}
