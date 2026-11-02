package moe.mcns.BiliMax

import android.app.Application
import moe.mcns.BiliMax.crash.CrashReporter

/**
 * 进程入口：
 * - attachBaseContext 是能拿到 Context 的最早时机，此时注册崩溃处理器；
 *   注意必须传 this（super 之后 Application 已是完整 Context），
 *   此阶段 getApplicationContext() 尚未挂载、会返回 null。
 * - 日志机制任何异常都不允许影响 App 启动，全部 try/catch 兜底。
 */
class App : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        try {
            CrashReporter.install(this)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            CrashReporter.startLogcatCapture(this)
        } catch (_: Throwable) {
        }
        // 转码服务：内嵌 HTTP 服务器（端口 6732）。手机即服务器，只管监听
        // 0.0.0.0:6732 收到的 HTTP 请求并转码；是否随 App 自启由设置偏好决定。
        try {
            moe.mcns.BiliMax.sync.SyncHolder.init(this)
        } catch (t: Throwable) {
            android.util.Log.w("BiliMax-App", "SyncHolder init failed: ${t.message}")
        }
        // 互联服务（SimpleFetch 手机端）：App 启动即在后台自动建立 XMS 消息通道
        // 并挂载标准 SF 协议——发现手表节点 -> 授权 -> 注册监听 -> 主动 SF_HANDSHAKE。
        // 它等价于为表端提供 fetch 能力：表端自带 @system.fetch 时直连手机局域网
        // 地址即可，本桥接完全用不到、也不需要任何手动开关。
        try {
            // 必须在首次 Wearable.get*Api() 前按设置页偏好配置 SDK 后端
            // （AUTO 优先 OronBox 回退小米 / 强制小米运动健康 / 强制 Oronbox）
            moe.mcns.BiliMax.sync.WearTransport.applyBackendConfig(this)
            moe.mcns.BiliMax.sync.ActivationManager.attach(this)
            moe.mcns.BiliMax.sync.CloudRelayClient.attach(this)
            moe.mcns.BiliMax.sync.SfBridge.attach(this)
            // 胶卷包（.bmvd）手动推送：包装一层消息处理器，消费
            // GET_FETCHAVA_AVA / SERVER_UPLOAD_FILE_DONE / UPLOAD_FILE_DONE 等回包；
            // 必须在 SfBridge.attach 之后挂接。
            moe.mcns.BiliMax.sync.FilmPush.attach(this)
        } catch (t: Throwable) {
            android.util.Log.w("BiliMax-App", "SfBridge attach failed: ${t.message}")
        }
    }
}
