package moe.mcns.BiliMax.sync

import android.content.Context
import android.os.Build
import moe.mcns.BiliMax.AppPrefs
import moe.mcns.BiliMax.sync.tunnel.LoopbackMuxStream
import moe.mcns.BiliMax.sync.tunnel.MuxFrame
import moe.mcns.BiliMax.sync.tunnel.MuxSender
import moe.mcns.BiliMax.sync.tunnel.WebSocketConnection
import moe.mcns.BiliMax.sync.tunnel.sendEnd
import moe.mcns.BiliMax.sync.tunnel.sendError
import moe.mcns.BiliMax.sync.tunnel.sendPong
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock

/**
 * 云端隧道 WS 客户端（tunnel 模块，mux-v1，详见根目录 bilibili-client.md）。
 *
 * 本类只承担三件事：
 *   1. 常驻守护线程：按「转码服务器运行 && Key 非空」门控拨出 WS，断线指数退避重连；
 *   2. 把收齐 FIN 的 WS 消息按 mux-v1 帧分发给每条 [LoopbackMuxStream]（多 sid 并发）；
 *   3. 提供 [attach]/[wakeup]/[restart] 公开入口。
 *
 * WS 帧层（RFC6455，含 Sec-WebSocket-Accept 校验）见 [WebSocketConnection]，
 * mux 编解码见 [MuxFrame]，回环转发见 [LoopbackMuxStream]。
 *
 * 拓扑：手表 HTTPS → 云网关 → WSS(mux-v1) → 本手机 → 127.0.0.1:<port>（或局域网主机）。
 */
object CloudRelayClient {

    const val TAG = "BiliMax-Relay"

    // 重连退避：初始 1s，失败翻倍至上限 30s，±20% 抖动；收到 ready 后重置为 1s。
    private const val BACKOFF_MIN_MS = 1_000L
    private const val BACKOFF_MAX_MS = 30_000L
    private const val IDLE_POLL_MS = 3_000L

    /** 应用层/WS 心跳保活：70s 内无任何帧到达视为断线。 */
    private const val READ_TIMEOUT_MS = 70_000

    /** TCP 建连 / TLS 握手超时：网关不可达时快速进入退避，不允许无限挂起。 */
    private const val CONNECT_TIMEOUT_MS = 15_000

    private lateinit var appContext: Context
    @Volatile private var attached = false

    private val wakeLock = ReentrantLock()
    private val wakeCond = wakeLock.newCondition()

    /** 本轮连接是否收到过 ready（注册成功），跨守护线程与连接线程读写。 */
    @Volatile private var readyReceived = false

    @Volatile private var currentSocket: Socket? = null

    private val streamExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "cloud-relay-stream").apply { isDaemon = true }
    }

    /** App.onCreate 调用：启动常驻守护线程，按门控自动连接/重连。 */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        Thread {
            var backoff = BACKOFF_MIN_MS
            while (attached) {
                val shouldRun = TranscodeServer.isRunning() && currentKey().isNotBlank()
                if (!shouldRun) {
                    sleepWait(IDLE_POLL_MS)
                    backoff = BACKOFF_MIN_MS
                    continue
                }
                readyReceived = false
                try {
                    connectAndServe()
                } catch (e: Throwable) {
                    SyncLog.w(TAG, "云端隧道连接异常：${e.message}")
                }
                backoff = if (readyReceived) BACKOFF_MIN_MS
                else (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                sleepWait(jitter(backoff))
            }
        }.apply { isDaemon = true; name = "cloud-relay" }.start()
    }

    /** 激活补全 Key/外部条件变化时唤醒守护线程，立即重新评估门控。 */
    fun wakeup() {
        wakeLock.withLock { wakeCond.signalAll() }
    }

    /** 新 Key/网关设置保存后调用：关闭当前连接，让守护线程按新配置立即重连。 */
    fun restart() {
        currentSocket?.let { runCatching { it.close() } }
    }

    private fun sleepWait(ms: Long) {
        wakeLock.withLock {
            try {
                wakeCond.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun jitter(base: Long): Long {
        val factor = 0.8 + Math.random() * 0.4
        return (base * factor).toLong().coerceAtLeast(1L)
    }

    private fun currentKey(): String =
        runCatching { AppPrefs.get(appContext).cloudApiKey }.getOrNull().orEmpty()

    // ---------------- 连接与帧读循环 ----------------

    private fun connectAndServe() {
        val prefs = AppPrefs.get(appContext)
        val base = prefs.relayGatewayBase.ifBlank { AppPrefs.DEFAULT_RELAY_GATEWAY }
        val key = prefs.cloudApiKey.trim()
        if (key.isBlank()) return

        val uri = parseBase(base)
        val name = URLEncoder.encode(deviceName(), "UTF-8")
        val ip = URLEncoder.encode(NetInfo.reportedIpv4().orEmpty(), "UTF-8")
        val lanBase = URLEncoder.encode(TranscodeServer.lanBaseUrl().orEmpty(), "UTF-8")
        val path = "${AppPrefs.RELAY_API_PREFIX}/connectDevice/${AppPrefs.RELAY_APPNAME}" +
            "?key=${URLEncoder.encode(key, "UTF-8")}&name=$name&ip=$ip" +
            if (lanBase.isNotEmpty()) "&lanBaseUrl=$lanBase" else ""

        // 显式连接超时：裸 Socket(host,port) 与 SSL 握手在网关不可达时会无限挂起，
        // 且守护线程的 wakeup 无法打断——先 TCP 限时连上，再包装 TLS 并限时握手。
        val raw = Socket()
        raw.connect(InetSocketAddress(uri.host, uri.port), CONNECT_TIMEOUT_MS)
        val socket = if (uri.tls) {
            try {
                raw.soTimeout = READ_TIMEOUT_MS
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, uri.host, uri.port, true) as SSLSocket
                ssl.soTimeout = READ_TIMEOUT_MS
                ssl.startHandshake()
                ssl
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        } else {
            raw
        }
        currentSocket = socket
        // 本轮在途流：连接结束统一取消，避免线程/连接泄漏
        val streams = ConcurrentHashMap<Int, LoopbackMuxStream>()
        try {
            val ws = WebSocketConnection.connect(
                socket = socket,
                hostHeader = uri.host,
                port = uri.port,
                path = path,
                tls = uri.tls,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            SyncLog.i(
                TAG,
                "云端隧道已连接：${uri.host}" +
                    uri.port.let { if (it == 443 || it == 80) "" else ":$it" }
            )

            val sender = object : MuxSender {
                override fun send(type: Int, sid: Int, payload: ByteArray?) {
                    try {
                        ws.sendBinary(MuxFrame.encode(type, sid, payload))
                    } catch (e: Exception) {
                        SyncLog.w(TAG, "mux 帧发送失败 sid=$sid type=$type：${e.message}")
                    }
                }
            }

            var readyDone = false
            ws.runLoop(object : WebSocketConnection.Handler {
                override fun onText(text: String) {
                    // 升级后首条文本帧即 ready，之后业务帧全为二进制
                    if (readyDone) {
                        SyncLog.w(TAG, "ready 后又收到文本帧，忽略：$text")
                        return
                    }
                    handleReady(text)
                    readyDone = true
                }

                override fun onBinary(data: ByteArray) {
                    if (!readyDone) {
                        SyncLog.w(TAG, "ready 前到达 mux 帧，丢弃 ${data.size}B")
                        return
                    }
                    handleMuxPacket(data, sender, streams)
                }

                override fun onClose(code: Int, reason: String) {
                    SyncLog.i(TAG, "云端隧道连接关闭（code=$code reason=$reason），将重连")
                }
            })
        } finally {
            currentSocket = null
            runCatching {
                streams.values.forEach { runCatching { it.cancel() } }
                streams.clear()
            }
            runCatching { socket.close() }
        }
    }

    /** ready 首帧：{"ready":true,"deviceID":"384921","protocol":"mux-v1"}，设备侧不回 hello。 */
    private fun handleReady(msg: String) {
        val json = try {
            JSONObject(msg)
        } catch (e: Exception) {
            SyncLog.w(TAG, "无法解析服务端 ready：$msg")
            return
        }
        readyReceived = true
        SyncLog.i(
            TAG,
            "云端隧道注册成功（deviceID=${json.optString("deviceID")} " +
                "protocol=${json.optString("protocol")}）"
        )
    }

    private fun deviceName(): String =
        runCatching { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "BiliMax-Android"

    // ---------------- mux-v1 帧分发 ----------------

    private fun handleMuxPacket(
        pkt: ByteArray,
        sender: MuxSender,
        streams: ConcurrentHashMap<Int, LoopbackMuxStream>
    ) {
        val (type, sid, payload) = try {
            MuxFrame.decode(pkt)
        } catch (e: Exception) {
            SyncLog.w(TAG, "mux 帧非法：${e.message}")
            return
        }
        when (type) {
            MuxFrame.REQUEST -> {
                if (streams.containsKey(sid)) {
                    SyncLog.w(TAG, "重复 REQUEST sid=$sid，忽略")
                    return
                }
                val req = try {
                    MuxFrame.decodeRequest(payload)
                } catch (e: Exception) {
                    SyncLog.w(TAG, "REQUEST 解析失败 sid=$sid：${e.message}")
                    sender.sendError(sid, "bad request frame: ${e.message}")
                    sender.sendEnd(sid)
                    return
                }
                val stream = LoopbackMuxStream(sid, sender)
                streams[sid] = stream
                streamExecutor.execute {
                    try {
                        stream.handle(req)
                    } catch (t: Throwable) {
                        SyncLog.w(TAG, "流处理失败 sid=$sid：${t.message}")
                        sender.sendError(sid, "relay handler error: ${t.message}")
                        sender.sendEnd(sid)
                    } finally {
                        streams.remove(sid)
                    }
                }
            }
            MuxFrame.DATA -> streams[sid]?.feedData(payload)
                ?: SyncLog.w(TAG, "未知 sid=$sid 的 DATA，忽略")
            MuxFrame.END -> streams[sid]?.endData()
                ?: SyncLog.w(TAG, "未知 sid=$sid 的 END，忽略")
            MuxFrame.CANCEL -> streams.remove(sid)?.let {
                SyncLog.i(TAG, "调用方取消 sid=$sid")
                it.cancel()
            }
            MuxFrame.PING -> sender.sendPong(sid)
            MuxFrame.PONG -> Unit
            MuxFrame.HEADERS, MuxFrame.ERROR ->
                SyncLog.w(TAG, "设备侧不应收到 type=0x${type.toString(16)} sid=$sid")
            else -> SyncLog.w(TAG, "未知 mux 帧类型 type=0x${type.toString(16)} sid=$sid")
        }
    }

    // ---------------- 工具 ----------------

    private class UriParts(val tls: Boolean, val host: String, val port: Int)

    /** 解析网关基址，支持 https/wss/http/ws（默认端口 443/80）。 */
    private fun parseBase(base: String): UriParts {
        var s = base.trim().trimEnd('/')
        val tls = when {
            s.startsWith("https://") -> { s = s.removePrefix("https://"); true }
            s.startsWith("wss://") -> { s = s.removePrefix("wss://"); true }
            s.startsWith("http://") -> { s = s.removePrefix("http://"); false }
            s.startsWith("ws://") -> { s = s.removePrefix("ws://"); false }
            else -> { s = s.removePrefix("https://"); true }
        }
        val host: String
        val port: Int
        if (s.contains(':')) {
            host = s.substringBefore(':')
            port = s.substringAfter(':').substringBefore('/').toIntOrNull()
                ?: if (tls) 443 else 80
        } else {
            host = s.substringBefore('/')
            port = if (tls) 443 else 80
        }
        return UriParts(tls, host, port)
    }
}
