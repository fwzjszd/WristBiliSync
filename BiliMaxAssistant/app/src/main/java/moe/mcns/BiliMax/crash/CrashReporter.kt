package moe.mcns.BiliMax.crash

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集。
 *
 * 两种崩溃都要覆盖：
 * 1) Java/Kotlin 未捕获异常：UncaughtExceptionHandler 在进程终止前写报告。
 * 2) native 崩溃（SIGSEGV/SIGABRT、linker 加载失败等）：信号直接杀进程，
 *    Java 处理器没机会运行。因此进程启动即在后台持续把本进程 logcat
 *    落盘（DEBUG/libc/linker/AndroidRuntime 等标签），下次启动时若发现
 *    上次不是正常退出，就把这份 logcat 归档为崩溃日志。
 *
 * 所有日志都写到文件管理器可见的位置，无需进应用、无需存储权限：
 * - 下载/BiliMax/（API29+ MediaStore 公共目录）
 * - Android/data/<包名>/files/crash_logs/（全版本免权限）
 */
object CrashReporter {
    private const val DIR = "crash_reports"
    private const val EXTERNAL_DIR = "crash_logs"
    private const val DOWNLOAD_SUBDIR = "BiliMax"
    private const val MAX_REPORTS = 5
    private const val FILE_PREFIX = "crash_"
    private const val NATIVE_PREFIX = "crash_native_"
    private const val PATHS_FILE = "latest_paths.txt"
    private const val LIVE_LOG = "live_logcat.txt"
    private const val ALIVE_FLAG = "alive.flag"

    // logcat 中出现这些内容即认定上次是崩溃而非正常退出
    private val CRASH_MARKERS = arrayOf(
        "FATAL EXCEPTION", "AndroidRuntime",
        "SIGSEGV", "SIGABRT", "signal ", "Fatal signal",
        "FORTIFY", "UnsatisfiedLinkError", "JNI DETECTED ERROR",
        "dlopen failed", "cannot locate symbol", "java.lang.UnsatisfiedLinkError",
        "E DEBUG", ">>> BiliMax <<<", "tombstone", "libc"
    )

    @Volatile
    private var installed = false

    @Volatile
    private var captureProcess: java.lang.Process? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        // attachBaseContext 阶段 getApplicationContext() 可能尚未挂载返回 null，兜底用传入的 context
        val appContext: Context = context.applicationContext ?: context

        // 崩溃处理器必须在当前线程同步注册，保证尽早生效。
        // 注意：attachBaseContext 是应用实例化的最早阶段，部分 ROM（如 Android 15+ 的 MIUI/HyperOS）
        // 此刻 RuntimeInit 尚未设置默认处理器，getDefaultUncaughtExceptionHandler() 会返回 null。
        // 整段注册必须独立 try/catch：处理器自身一旦抛异常，应用会在 Application 实例化阶段
        // 直接闪退（连第一帧都画不出来），表现为“点击图标即重启”。
        try {
            val previous: Thread.UncaughtExceptionHandler? =
                Thread.getDefaultUncaughtExceptionHandler()
            val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    saveReport(appContext, thread, throwable)
                } catch (_: Throwable) {
                }
                try { markAlive(appContext, false) } catch (_: Throwable) {}
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                }
            }
            Thread.setDefaultUncaughtExceptionHandler(handler)
        } catch (_: Throwable) {
            // 注册失败也绝不能阻断应用启动
        }

        // 归档与存活标记涉及磁盘 IO，放后台线程，绝不能阻塞/影响应用启动
        Thread({
            try {
                // 上次启动留下了存活标记却走到这里，说明上次进程是被信号杀死的
                // （native 崩溃/系统强杀），Java 异常处理器没机会执行，用落盘的 logcat 归档
                archivePreviousCrash(appContext)
                markAlive(appContext, true)
            } catch (_: Throwable) {
            }
        }, "crash-boot").apply { isDaemon = true }.start()
    }

    /**
     * 启动后台 logcat 持续落盘：崩溃（含 native）前的最后日志在进程死亡后
     * 仍保留在文件里，下次启动由 [archivePreviousCrash] 归档。
     * 只过滤关键标签，控制体积；live 文件超过 1MB 从头清空一次（阈值见下方 1_000_000 字节）。
     */
    fun startLogcatCapture(context: Context) {
        if (captureProcess != null) return
        val appContext: Context = context.applicationContext ?: context
        Thread({
            var reader: BufferedReader? = null
            try {
                val liveFile = File(reportsDir(appContext), LIVE_LOG)
                val tags = arrayOf(
                    "AndroidRuntime:E", "System.err:W", "art:W", "DEBUG:E",
                    "libc:E", "linker:E", "BiliMax:V", "BiliMax-Sync:V",
                    "BiliMax-Service:V", "Transcoder:V", "FFmpegKit:V",
                    "ffmpegkit:V", "ActivityManager:W", "*:S"
                )
                val cmd = ArrayList<String>().apply {
                    add("logcat"); add("-v"); add("threadtime")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        add("--pid"); add(Process.myPid().toString())
                    }
                    addAll(tags)
                }
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                captureProcess = process
                reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                    // 攒约 10 行就刷一次盘：崩溃前最后几行务必落盘
                    if (sb.length > 1000) {
                        flushLive(liveFile, sb.toString())
                        sb.setLength(0)
                        if (liveFile.length() > 1_000_000) liveFile.writeText("")
                    }
                }
                // 进程死亡前把缓冲里残余的行也落盘
                if (sb.isNotEmpty()) flushLive(liveFile, sb.toString())
            } catch (_: Throwable) {
                // logcat 不可用时静默放弃（不影响主流程）
            } finally {
                try { reader?.close() } catch (_: Throwable) {}
            }
        }, "crash-logcat").apply { isDaemon = true }.start()
    }

    private fun flushLive(liveFile: File, chunk: String) {
        try {
            liveFile.appendText(chunk)
        } catch (_: Throwable) {
        }
    }

    private fun markAlive(context: Context, alive: Boolean) {
        try {
            val f = File(reportsDir(context), ALIVE_FLAG)
            if (alive) f.writeText(System.currentTimeMillis().toString()) else f.delete()
        } catch (_: Throwable) {
        }
    }

    private fun archivePreviousCrash(context: Context) {
        try {
            val flag = File(reportsDir(context), ALIVE_FLAG)
            if (!flag.exists()) return  // 上次正常退出或首次安装
            val startedAt = flag.readText().trim().toLongOrNull()
            flag.delete()
            val live = File(reportsDir(context), LIVE_LOG)
            val tail = if (live.exists()) {
                val text = live.readText()
                // 只保留最后 400 行，避免文件过大
                text.lines().takeLast(400).joinToString("\n")
            } else ""
            live.writeText("")
            // 只有日志里出现崩溃特征（Java 异常/native 信号/链接器失败）才归档，
            // 避免用户正常划掉任务、系统回收等情况误报崩溃
            val crashHit = CRASH_MARKERS.any { tail.contains(it) }
            if (!crashHit) return
            val report = buildString {
                append("===== BiliMax 崩溃报告（上次进程异常死亡，疑似 native 崩溃）=====\n")
                append("说明: 进程被系统信号直接终止（如 SIGSEGV/SIGABRT/链接器失败），\n")
                append("Java 异常处理器未能执行，以下为崩溃前落盘的 logcat 记录。\n")
                append("上次启动: ")
                append(
                    startedAt?.let {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(it))
                    } ?: "(未知)"
                ).append('\n')
                append("归档时间: ")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date()))
                append('\n')
                append("应用版本: ").append(appVersion(context)).append('\n')
                append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("Android: ").append(Build.VERSION.RELEASE)
                append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
                }
                append("\n--- 崩溃前 logcat（含 native DEBUG/libc/linker 输出）---\n")
                append(if (tail.isBlank()) "(logcat 落盘为空，请用 adb logcat 复现抓取)" else tail)
            }
            writeReportToLocations(context, NATIVE_PREFIX, report)
        } catch (_: Throwable) {
        }
    }

    fun reportsDir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    fun reportFiles(context: Context): List<File> =
        reportsDir(context).listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun latestReport(context: Context): String? =
        reportFiles(context).firstOrNull()?.takeIf { it.length() > 0 }?.readText()

    fun latestSavedPaths(context: Context): List<String> {
        val f = File(reportsDir(context), PATHS_FILE)
        if (!f.exists()) return emptyList()
        return f.readText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    // 只列文件管理器里能找到的位置（应用私有目录 /data/ 下用户不可见）
    fun savedLocations(context: Context): List<String> =
        latestSavedPaths(context).filterNot { it.startsWith("/data/") }.ifEmpty {
            listOf(
                "文件管理器 → 下载目录 → $DOWNLOAD_SUBDIR 文件夹（crash_*.txt）",
                "Android/data/${context.packageName}/files/$EXTERNAL_DIR/ 文件夹（crash_*.txt）"
            )
        }

    fun clear(context: Context) {
        reportFiles(context).forEach { it.delete() }
        File(reportsDir(context), PATHS_FILE).delete()
        try {
            context.getExternalFilesDir(EXTERNAL_DIR)
                ?.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    fun buildReport(
        context: Context,
        thread: Thread?,
        throwable: Throwable,
        extra: Map<String, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        sb.append("===== BiliMax 崩溃报告 =====\n")
        sb.append("时间: ").append(now).append('\n')
        sb.append("应用版本: ").append(appVersion(context)).append('\n')
        sb.append("线程: ").append(thread?.name ?: "unknown").append('\n')
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        }
        try {
            sb.append("FFmpegKit 版本: ").append(FFmpegKitConfig.getFFmpegVersion() ?: "unknown").append('\n')
        } catch (t: Throwable) {
            sb.append("FFmpegKit 版本: 获取失败(").append(t.javaClass.simpleName).append(")\n")
        }
        if (extra.isNotEmpty()) {
            sb.append("\n--- 现场信息 ---\n")
            extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
        }
        sb.append("\n--- 异常链 ---\n")
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < 12) {
            sb.append("[").append(depth).append("] ")
                .append(cause.javaClass.name).append(": ")
                .append(cause.message ?: "(无消息)").append('\n')
            sb.append(stackTraceOf(cause)).append('\n')
            cause = cause.cause
            depth++
        }
        sb.append("\n--- FFmpeg 最近会话日志 ---\n")
        sb.append(fetchFfmpegLogs())
        sb.append("\n--- 落盘 logcat 尾部 ---\n")
        sb.append(liveLogcatTail(context))
        return sb.toString()
    }

    fun saveReport(
        context: Context,
        thread: Thread?,
        throwable: Throwable,
        extra: Map<String, String> = emptyMap()
    ): List<String> {
        val report = buildReport(context, thread, throwable, extra)
        return writeReportToLocations(context, FILE_PREFIX, report)
    }

    /** 把报告写到内部目录、Android/data 外部目录、公共下载目录三个位置 */
    private fun writeReportToLocations(
        context: Context,
        prefix: String,
        report: String
    ): List<String> {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "$prefix$ts.txt"
        val savedPaths = mutableListOf<String>()

        try {
            val internal = File(reportsDir(context), fileName)
            internal.writeText(report)
            prune(reportsDir(context), prefix)
            savedPaths.add(internal.absolutePath)
        } catch (_: Throwable) {
        }

        try {
            val extDir = context.getExternalFilesDir(EXTERNAL_DIR)
            if (extDir != null) {
                extDir.mkdirs()
                File(extDir, fileName).writeText(report)
                prune(extDir, prefix)
                savedPaths.add(File(extDir, fileName).absolutePath)
            }
        } catch (_: Throwable) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_SUBDIR
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val uri = resolver.insert(collection, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(report.toByteArray(Charsets.UTF_8))
                    }
                    savedPaths.add("下载/$DOWNLOAD_SUBDIR/$fileName（公共目录，文件管理器可直接查看）")
                }
            }
        } catch (_: Throwable) {
        }

        try {
            File(reportsDir(context), PATHS_FILE).writeText(savedPaths.joinToString("\n"))
        } catch (_: Throwable) {
        }
        return savedPaths
    }

    private fun prune(dir: File, prefix: String) {
        try {
            // 按本次写入的前缀清理。FILE_PREFIX("crash_") 恰是 NATIVE_PREFIX("crash_native_") 的前缀，
            // 故普通上报会连带清理 native 上报（与旧行为一致）；native 上报只清理自己那批。
            // 原实现忽略入参 prefix，且多写了个永远匹配不到的 "native_" 分支。
            dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_REPORTS)
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    private fun liveLogcatTail(context: Context): String {
        val live = File(reportsDir(context), LIVE_LOG)
        if (!live.exists()) return "(无落盘 logcat)"
        return try {
            val text = live.readText()
            if (text.isBlank()) "(logcat 为空)"
            else text.lines().takeLast(200).joinToString("\n")
        } catch (t: Throwable) {
            "(读取落盘 logcat 失败: ${t.javaClass.simpleName})"
        }
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trim()
    }

    private fun fetchFfmpegLogs(): String {
        return try {
            val session = FFmpegKitConfig.getLastSession()
                ?: return "(无 FFmpeg 会话记录)"
            val logs = session.allLogsAsString ?: ""
            if (logs.isBlank()) return "(会话日志为空)"
            buildString {
                append("会话ID: ").append(session.sessionId).append('\n')
                append("返回码: ").append(session.returnCode?.toString() ?: "null").append('\n')
                append("命令: ").append(session.command).append("\n\n")
                append(logs.lines().takeLast(120).joinToString("\n"))
            }
        } catch (t: Throwable) {
            "(读取 FFmpeg 日志失败: ${t.javaClass.simpleName}: ${t.message})"
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
                else @Suppress("DEPRECATION") pkg.versionCode.toLong()
            "${pkg.versionName} ($name)"
        } catch (t: Throwable) {
            "unknown"
        }
    }
}
