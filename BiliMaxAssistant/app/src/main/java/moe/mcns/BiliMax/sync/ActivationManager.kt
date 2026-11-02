package moe.mcns.BiliMax.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import moe.mcns.BiliMax.AppPrefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 铭诚云端授权激活管理器（tunnel 配套）。
 *
 * 在 XMS 传输层（[WearTransport]）之上只承担「Key 的获取与下发」，不参与 SF 桥接代发：
 *  1. 应答表端可能主动发来的 GET_APIKEY/GET_FETCHAVA（标准协议下表端一般不主动发）；
 *  2. [startActivation]：实时 GET_DEVICEINFO 取硬件三元组（product/deviceId/serial），
 *     拼 verify_data 后按授权类型向铭诚网关取 Key：
 *       - 免费版（type=free，默认）：单次 GET verifyCheck 即时取 Key，失败透传真实错误，不轮询；
 *       - 付费版（type=normal）：浏览器打开 verify 页付款，随后轮询 verifyCheck（间隔 3s，上限 5min）；
 *  3. 取到 Key 后 PUT_SETTINGS 下发，等表端 PUT_SETTINGS_DONE，再保存到本机 [AppPrefs.cloudApiKey]，
 *     并唤醒 [CloudRelayClient] 拨出云端 WS。
 *
 * 防滥用/RSA 验签已全部关闭（与 EternalAstroboxPlugins 一致），signature 仅透传。
 * 拓扑与协议依据：根目录 bilibili-client.md、AGENTS.md 第 6/8 项。
 */
object ActivationManager {

    private const val TAG = "BiliMax-Activation"

    private const val T_GET_APIKEY = "GET_APIKEY"
    private const val T_APIKEY = "APIKEY"
    private const val T_GET_DEVICEINFO = "GET_DEVICEINFO"
    private const val T_DEVICEINFO = "DEVICEINFO"
    private const val T_GET_FETCHAVA = "GET_FETCHAVA"
    private const val T_FETCHAVA = "FETCHAVA"
    private const val T_PUT_SETTINGS = "PUT_SETTINGS"
    private const val T_PUT_SETTINGS_DONE = "PUT_SETTINGS_DONE"

    /** 控制消息请求-应答超时 */
    private const val CTRL_TIMEOUT_MS = 15_000L

    /** 付费版轮询间隔与总时长 */
    private const val POLL_INTERVAL_MS = 3_000L
    private const val POLL_MAX_MS = 5L * 60 * 1000

    /** 免费版单次取 Key 的总时限 */
    private const val FREE_DEADLINE_MS = 15_000L

    /** 等待传输会话进入 CONNECTED 的时限 */
    private const val CONNECT_WAIT_MS = 30_000L

    /** 授权网关固定走生产域名 */
    private const val AUTH_GATEWAY_BASE = "https://api.b4qaq.cn"

    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "activation-io").apply { isDaemon = true }
    }

    private lateinit var appContext: Context

    /** 响应类消息的等待门控：key=响应 type */
    private val pendingReplies = ConcurrentHashMap<String, PendingReply>()

    /** 激活流程互斥：避免双开浏览器/双轮询/重复 PUT_SETTINGS */
    private val activateLock = Any()
    @Volatile private var activateInProgress = false
    @Volatile private var cancelRequested = false

    private class PendingReply {
        val latch = CountDownLatch(1)
        val status = AtomicReference("")
        val data = AtomicReference<JSONObject?>(null)
    }

    /** 激活状态回调（主线程触发），供 UI 展示进度/结果 */
    interface ActivationListener {
        fun onActivationLog(line: String) {}
        fun onActivationStateChanged(running: Boolean, success: Boolean) {}
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ActivationListener>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun addListener(l: ActivationListener) { listeners.addIfAbsent(l) }
    fun removeListener(l: ActivationListener) { listeners.remove(l) }

    /** App 启动时调用：仅保存 Context，真正激活由用户手动触发。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 用户取消当前激活（付费版轮询/等待连接阶段可中断） */
    fun cancelActivation() {
        cancelRequested = true
    }

    /**
     * 由 [SfBridge] 把每条入站消息转交一次：
     * - 响应类消息（APIKEY/DEVICEINFO/FETCHAVA/PUT_SETTINGS_DONE 等）唤醒等待者并返回 true；
     * - 表端主动发来的 GET_APIKEY/GET_FETCHAVA 在此兼容应答；
     * - 其它消息返回 false，交回 SfBridge 继续处理。
     */
    fun onIncomingMessage(transport: WearTransport, msg: JSONObject): Boolean {
        val type = msg.optString("type")
        val status = msg.optString("status", "OK")

        val pending = pendingReplies[type]
        if (pending != null) {
            pending.status.set(status)
            pending.data.set(wrapData(msg))
            pending.latch.countDown()
            return true
        }
        return when (type) {
            T_GET_APIKEY -> {
                reply(transport, T_APIKEY, currentKey())
                true
            }
            T_GET_FETCHAVA -> {
                reply(transport, T_FETCHAVA, true)
                true
            }
            else -> false
        }
    }

    /** 响应 data 可能是布尔/字符串/对象，统一包装后交给等待者解析 */
    private fun wrapData(msg: JSONObject): JSONObject {
        val out = JSONObject()
        when (val raw = msg.opt("data")) {
            is Boolean -> out.put("__boolTrue", raw)
            is String -> out.put("__str", raw)
            else -> msg.optJSONObject("data")?.let { out.put("data", it) }
        }
        return out
    }

    /**
     * 用户手动触发一轮激活。
     * @param force true 时无视本机已有 Key 强制重新申请（设备凭证稳定，服务端幂等返回同一 Key）；
     *   false 时本机已有非空 Key 直接复用成功，不重复申请。
     * @param onDone 激活结束（成功/失败/取消/超时）时于主线程回调一次。
     */
    fun startActivation(
        context: Context,
        force: Boolean = false,
        onDone: ((success: Boolean) -> Unit)? = null
    ) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        ioPool.execute {
            var success = false
            SyncHolder.beginActivationKeepAlive()
            try {
                success = runActivation(force)
            } catch (t: Throwable) {
                SyncLog.w(TAG, "激活流程异常：${t.message}")
                logLine("激活异常：${t.message}")
            } finally {
                SyncHolder.endActivationKeepAlive()
                onDone?.let { cb -> mainHandler.post { runCatching { cb(success) } } }
            }
        }
    }

    private fun runActivation(force: Boolean): Boolean {
        synchronized(activateLock) {
            if (activateInProgress) {
                logLine("已有激活流程在进行，请稍候")
                return false
            }
            val existing = AppPrefs.get(appContext).cloudApiKey
            if (existing.isNotBlank() && !force) {
                SyncLog.i(TAG, "本机已有 APIKey，直接复用")
                logLine("本机已有有效 APIKey，无需重复获取")
                notifyState(false, true)
                return true
            }
            activateInProgress = true
            cancelRequested = false
        }
        try {
            return runActivationLocked()
        } finally {
            synchronized(activateLock) {
                activateInProgress = false
            }
        }
    }

    private fun runActivationLocked(): Boolean {
        val prefs = AppPrefs.get(appContext)
        val base = AUTH_GATEWAY_BASE
        val appname = AppPrefs.RELAY_APPNAME
        val authType = prefs.authType

        notifyState(true, false)

        val transport = waitForTransport()
        if (transport == null) {
            logLine("手表未连接，无法激活")
            notifyState(false, false)
            return false
        }

        val infoEnv = sendAndWait(transport, T_GET_DEVICEINFO, JSONObject(), T_DEVICEINFO)
        val info = infoEnv?.optJSONObject("data")
        try {
            if (info != null) {
                // DEVICEINFO 是三元组（product/deviceId/serial），**不是握手信封**：把三元组包成
                // device 子对象再登记，让注册表以真实 deviceId 建键，且不携带 fetch/bridge 键
                // （缺键时不会覆盖已有能力）。原写法直接拿 DEVICEINFO 当握手，会建出
                // fetch=false 的条目，进而让推送永远走慢速消息通道（r92 修复）
                DeviceRegistry.registerOrUpdate(
                    transport,
                    JSONObject().put("device", info),
                    transport.currentDeviceName()
                )
            }
        } catch (_: Throwable) {
        }

        val product = info?.optString("product").orEmpty().trim()
        val deviceId = info?.optString("deviceId").orEmpty().trim()
        val serial = info?.optString("serial").orEmpty().trim()
        if (product.isBlank() || deviceId.isBlank() || serial.isBlank()) {
            logLine("未能从手表获取设备标识（product/deviceId/serial），请确认手表已连接后重试")
            notifyState(false, false)
            return false
        }

        val unixSec = System.currentTimeMillis() / 1000
        val verifyData = "$product.$deviceId.$serial.$unixSec"
        val enc = URLEncoder.encode(verifyData, "UTF-8")
        val checkUrl = "$base/api/v2/verifyCheck/$appname?data=$enc&type=$authType"

        val isFree = authType != AppPrefs.AUTH_TYPE_NORMAL
        if (isFree) {
            logLine("正在获取免费授权…")
        } else {
            val verifyUrl = "$base/api/v2/verify/$appname?data=$enc&type=$authType"
            logLine("请在浏览器中完成付费授权…")
            openBrowser(verifyUrl)
        }

        val deadline = System.currentTimeMillis() + if (isFree) FREE_DEADLINE_MS else POLL_MAX_MS
        var key = ""
        var signature = ""
        while (System.currentTimeMillis() < deadline && !cancelRequested) {
            if (transport.currentState() != WearState.CONNECTED) {
                logLine("连接已断开，激活中止")
                notifyState(false, false)
                return false
            }
            val poll = httpGetJson(checkUrl)
            if (poll != null && poll.optInt("status") == 200) {
                val result = poll.optJSONObject("result")
                key = result?.optString("APIKey").orEmpty()
                signature = result?.optString("signature").orEmpty()
                if (key.isNotBlank()) break
            }
            if (isFree) {
                val reason = when {
                    poll == null -> "网络请求失败（无法连接授权服务器），请检查网络后重试"
                    poll.optInt("status") == 200 -> "服务端已响应但未返回 APIKey，请稍后重试"
                    else -> {
                        val svc = poll.optString("result").ifBlank { "未知错误" }
                        "授权服务器返回错误（${poll.optInt("status")}：$svc）"
                    }
                }
                SyncLog.w(TAG, "免费授权失败：${poll?.toString().orEmpty()}")
                logLine("获取免费授权失败：$reason")
                notifyState(false, false)
                return false
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (cancelRequested) {
            logLine("已取消激活")
            notifyState(false, false)
            return false
        }
        if (key.isBlank()) {
            logLine("激活超时，未取得 APIKey")
            notifyState(false, false)
            return false
        }

        SyncLog.i(TAG, "取得 APIKey（长度=${key.length}），跳过验签直接保存")
        val putData = JSONObject().put("APIKey", key).put("signature", signature)
        val done = sendAndWait(transport, T_PUT_SETTINGS, putData, T_PUT_SETTINGS_DONE)
        if (done == null) {
            logLine("Key 下发失败（手表无响应）")
            notifyState(false, false)
            return false
        }
        prefs.cloudApiKey = key
        SyncHolder.onCloudKeyReady()
        logLine("授权成功，APIKey 已保存")
        notifyState(false, true)
        return true
    }

    /** 等待传输会话进入 CONNECTED，超时/取消返回 null */
    private fun waitForTransport(): WearTransport? {
        val transport = runCatching { WearTransport.get(appContext) }.getOrNull()
        if (transport == null) return null
        runCatching { transport.initialize() }
        val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
        while (System.currentTimeMillis() < deadline && !cancelRequested) {
            if (transport.currentState() == WearState.CONNECTED) return transport
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return if (transport.currentState() == WearState.CONNECTED) transport else null
    }

    /** 发请求并同步等待对应响应；超时/状态非 OK 返回 null */
    private fun sendAndWait(
        transport: WearTransport,
        reqType: String,
        data: JSONObject,
        respType: String,
        timeoutMs: Long = CTRL_TIMEOUT_MS
    ): JSONObject? {
        val pending = PendingReply()
        pendingReplies[respType] = pending
        try {
            val text = JSONObject()
                .put("type", reqType)
                .put("status", "OK")
                .put("data", data)
                .toString()
            val sent = booleanArrayOf(false)
            val sendLatch = CountDownLatch(1)
            transport.sendText(text) { ok -> sent[0] = ok; sendLatch.countDown() }
            sendLatch.await(10, TimeUnit.SECONDS)
            if (!sent[0]) return null

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (pending.latch.await(500, TimeUnit.MILLISECONDS)) break
                if (transport.currentState() != WearState.CONNECTED) {
                    SyncLog.w(TAG, "等待应答期间连接已断开（$reqType→$respType）")
                    return null
                }
            }
            if (pending.status.get() != "OK") return null
            return pending.data.get()
        } catch (_: Throwable) {
            return null
        } finally {
            pendingReplies.remove(respType)
        }
    }

    private fun httpGetJson(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(text)
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun openBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            SyncLog.w(TAG, "打开浏览器失败：${t.message}")
        }
    }

    private fun reply(transport: WearTransport, type: String, data: Any?) {
        val text = JSONObject()
            .put("type", type)
            .put("status", "OK")
            .put("data", data)
            .toString()
        transport.sendText(text)
    }

    private fun currentKey(): String =
        runCatching { AppPrefs.get(appContext).cloudApiKey }.getOrNull().orEmpty()

    private fun logLine(line: String) {
        SyncLog.i(TAG, "[激活] $line")
        val snapshot = listeners.toList()
        mainHandler.post { snapshot.forEach { runCatching { it.onActivationLog(line) } } }
    }

    private fun notifyState(running: Boolean, success: Boolean) {
        val snapshot = listeners.toList()
        mainHandler.post {
            snapshot.forEach { runCatching { it.onActivationStateChanged(running, success) } }
        }
    }
}
