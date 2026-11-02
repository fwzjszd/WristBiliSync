package moe.mcns.BiliMax.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import moe.mcns.BiliMax.MainActivity

/**
 * 转码服务器前台保活服务。
 *
 * 仅守护本机 HTTP 转码服务器（TranscodeServer，端口 6732）。
 * 手机即服务器：受理监听 0.0.0.0:6732 上收到的一切 HTTP 请求并转码。
 * 开启「后台保活」或服务器运行期间，以前台服务常驻，降低进程被系统
 * 查杀导致转码中断的概率。
 *
 * 前台服务类型为 specialUse：服务本身仅守护本机 HTTP 服务器，
 * 不直接连接配套设备，connectedDevice 类型的设备前置校验不适用。
 */
class SyncService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed, service runs without foreground state", e)
            stopSelf()
            return
        }
        acquireWakeLock()
        warnIfBatteryOptimized()
        // 服务被系统重启（START_STICKY）时也要确保服务器在运行
        if (!TranscodeServer.isRunning()) {
            Thread {
                TranscodeServer.start(applicationContext)
                SyncHolder.refreshServerState()
            }.apply { isDaemon = true }.start()
        }
        Log.i(TAG, "SyncService started")
    }

    /**
     * 持有 PARTIAL_WAKE_LOCK 直到服务销毁。
     *
     * 进程持有唤醒锁时，Android 的 CachedAppOptimizer 与厂商（MIUI/HyperOS 的 mimd）
     * 冻结器都会跳过该进程；否则手机熄屏或切到其它应用后整个 UID 会被 cgroup freezer
     * 冻结，6732 端口只剩 LISTEN/排队、读不到也答不了，表现为手表拿到 0 KB 响应
     * 并提示「云端下载失败，请检查网络」。
     */
    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliMax:server").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Throwable) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Throwable) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        }
        wakeLock = null
    }

    /** 未加入电池优化白名单时留一条可见日志，便于用户按提示去设置「无限制」 */
    private fun warnIfBatteryOptimized() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val msg = "未加入电池优化白名单，锁屏后可能被系统冻结导致手表下载失败；请在设置-后台保活中允许后台运行"
                Log.w(TAG, msg)
                SyncLog.w(TAG, msg)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被系统重启时 WakeLock 会随进程一起丢失，这里补一次（幂等）
        acquireWakeLock()
        // 每次被 startService 都重新声明前台通知：用户在权限弹窗授权后再次启动，
        // 可把此前因未授权被隐藏的常驻通知重新显示出来。
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground on onStartCommand failed", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        SyncHolder.notifyServiceDestroyed()
        Log.i(TAG, "SyncService destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "BiliMax 转码服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持手机端转码服务器运行" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BiliMax 转码服务运行中")
            .setContentText("正在等待手表发起转码任务（端口 ${TranscodeServer.PORT}）")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val TAG = "BiliMax-Service"
        private const val CHANNEL_ID = "bilimax_server"
        private const val NOTIF_ID = 1001
    }
}
