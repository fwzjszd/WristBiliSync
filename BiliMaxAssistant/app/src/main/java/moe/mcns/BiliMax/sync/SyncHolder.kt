package moe.mcns.BiliMax.sync

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import moe.mcns.BiliMax.AppPrefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 转码服务器生命周期管理（进程内单例）。
 *
 * 转码服务本身就是手机端的一台纯 HTTP 服务器（[TranscodeServer]，
 * 监听 0.0.0.0:[TranscodeServer.PORT]）：谁发来的 HTTP 请求都一律受理，
 * 负责创建转码任务、回报进度、供表端拉取帧图/音轨，不关心请求方在
 * 局域网里直连还是经由别的通道到达。
 *
 * 互联（让没有联网能力的表端也能发 HTTP 请求）是另一层独立能力，
 * 由 [WearTransport]（Xiaomi Wearable SDK 消息通道）+ [SfBridge]
 * （标准 SimpleFetch 协议手机端）在 App 启动时自动挂载、自动连接，
 * 与本类的启停互不影响；表端自带 fetch 时该桥接完全用不到。
 *
 * 职责：
 * - [init]：App 启动时调用，按「自动运行转码服务」偏好决定是否启动；
 * - [setServerEnabled]：服务卡片点击/设置开关，同步启停 HTTP 服务器与前台保活服务；
 * - 「后台保活」开启时以前台服务（specialUse）常驻，降低进程被系统查杀概率。
 */
object SyncHolder {
    private const val TAG = "BiliMax-SyncHolder"
    private const val START_MAX_RETRY = 5
    private const val START_RETRY_INTERVAL_MS = 200L
    private const val SERVER_START_WAIT_MS = 5_000L

    private lateinit var appContext: android.app.Application

    @Volatile private var inited = false
    @Volatile private var serviceRunning = false
    @Volatile private var keepAliveBeforeActivation = false

    /** 转码服务器运行状态变化回调（启动/停止完成后在主线程触发） */
    fun interface OnServerStateChanged {
        fun onServerStateChanged(running: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverListeners = CopyOnWriteArrayList<OnServerStateChanged>()

    /** App.onCreate 中调用：注入 Context，并按偏好自动启动转码服务器。 */
    fun init(app: android.app.Application) {
        appContext = app
        inited = true
        val prefs = AppPrefs.get(appContext)
        if (prefs.autoRunService) {
            setServerEnabled(true)
        }
    }

    /** 兼容旧调用点（MainActivity.onCreate）：等价于确保单例已按偏好初始化。 */
    fun ensureStarted() {
        if (!inited) return
        if (AppPrefs.get(appContext).autoRunService && !TranscodeServer.isRunning()) {
            setServerEnabled(true)
        }
    }

    /** 转码服务器是否运行（HTTP 服务器实际状态为准）。 */
    fun isServerRunning(): Boolean = TranscodeServer.isRunning()

    fun addOnServerStateChanged(l: OnServerStateChanged) {
        serverListeners.addIfAbsent(l)
        l.onServerStateChanged(TranscodeServer.isRunning())
    }

    fun removeOnServerStateChanged(l: OnServerStateChanged) {
        serverListeners.remove(l)
    }

    private fun notifyServerState() {
        val running = TranscodeServer.isRunning()
        val snapshot = serverListeners.toList()
        mainHandler.post {
            for (l in snapshot) {
                try {
                    l.onServerStateChanged(running)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * 启停转码服务器。
     * 开启：后台线程绑定 ServerSocket（避免主线程网络风险），端口刚释放时可能处于
     * TIME_WAIT，采用短间隔重试，确保「关闭后立即再开启」也能成功；绑定成功后
     * 经 [OnServerStateChanged] 在主线程通知 UI，避免界面状态与实际脱节。
     * 「后台保活」开启时同步拉起前台服务；
     * 关闭：停止服务器；未开启保活时前台服务一并退出（保活开启时前台服务仅作进程守护）。
     */
    fun setServerEnabled(enabled: Boolean) {
        if (!inited) return
        if (enabled) {
            Thread {
                var ok = false
                repeat(START_MAX_RETRY) { attempt ->
                    TranscodeServer.start(appContext)
                    if (TranscodeServer.isRunning()) {
                        ok = true
                        return@repeat
                    }
                    TranscodeServer.stop()
                    try {
                        Thread.sleep(START_RETRY_INTERVAL_MS * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (ok) {
                    if (AppPrefs.get(appContext).keepAlive) startSyncService()
                } else {
                    SyncLog.w(TAG, "转码服务器多次启动失败，端口可能被占用")
                }
                notifyServerState()
            }.apply { isDaemon = true }.start()
        } else {
            TranscodeServer.stop()
            if (!AppPrefs.get(appContext).keepAlive) stopSyncService()
            notifyServerState()
        }
    }

    /** 设置页切换「后台保活」时即时生效；开启即拉起前台服务（其 onCreate 会兜底启动转码服务器） */
    fun applyKeepAlive(enabled: Boolean) {
        if (enabled) {
            startSyncService()
        } else {
            stopSyncService()
        }
    }

    fun beginActivationKeepAlive() {
        if (!inited) return
        keepAliveBeforeActivation = serviceRunning
        startSyncService()
    }

    fun endActivationKeepAlive() {
        if (!inited) return
        if (!keepAliveBeforeActivation) stopSyncService()
        keepAliveBeforeActivation = false
    }

    /** SyncService.onDestroy 回调：服务被系统销毁或 startForeground 失败自停时同步状态位 */
    fun notifyServiceDestroyed() {
        serviceRunning = false
    }

    /** SyncService 兜底启动转码服务器后回调：在主线程刷新一次服务状态 UI */
    fun refreshServerState() {
        notifyServerState()
    }

    fun onCloudKeyReady() {
        ensureServerRunningForActivation()
        runCatching { CloudRelayClient.wakeup() }
    }

    private fun ensureServerRunningForActivation() {
        if (TranscodeServer.isRunning()) return
        setServerEnabled(true)
        val deadline = System.currentTimeMillis() + SERVER_START_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (TranscodeServer.isRunning()) return
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        SyncLog.w(TAG, "激活后等待转码服务器启动超时，tunnel 可能暂不拨出")
    }

    private fun startSyncService() {
        if (serviceRunning) return
        try {
            val intent = Intent(appContext, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            serviceRunning = true
        } catch (e: Throwable) {
            SyncLog.w(TAG, "启动前台保活服务失败：${e.message}")
        }
    }

    private fun stopSyncService() {
        if (!serviceRunning) return
        serviceRunning = false
        try {
            appContext.stopService(Intent(appContext, SyncService::class.java))
        } catch (e: Throwable) {
            SyncLog.w(TAG, "停止前台保活服务失败：${e.message}")
        }
    }
}
