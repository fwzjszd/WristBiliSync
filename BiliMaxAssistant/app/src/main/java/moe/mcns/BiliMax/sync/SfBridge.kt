package moe.mcns.BiliMax.sync

import android.content.Context
import android.util.Base64
import moe.mcns.BiliMax.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SimpleFetch（SF）协议消息类型常量。
 * 与手表端 src/common/js/interconnect.js、simpleFetch.js 中类型表逐字对齐。
 */
object SfProtocol {
    const val T_HANDSHAKE = "SF_HANDSHAKE"
    const val T_HANDSHAKE_ACK = "SF_HANDSHAKE_ACK"
    const val T_PING = "SF_PING"
    const val T_PONG = "SF_PONG"
    const val T_REQUEST = "SF_REQUEST"
    const val T_RESPONSE = "SF_RESPONSE"
    const val T_CLOSE_BRIDGE = "SF_CLOSE_BRIDGE"
    const val T_CLOSE_BRIDGE_ACK = "SF_CLOSE_BRIDGE_ACK"

    const val T_BILI_GET_SERVER_INFO = "BILI_GET_SERVER_INFO"
    const val T_BILI_SERVER_INFO = "BILI_SERVER_INFO"

    private lateinit var appContext: android.content.Context

    fun initContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /**
     * 构造统一握手 data（HANDSHAKE 与 HANDSHAKE_ACK 共用同一结构）。
     *
     * 一次握手完成三件事：
     *  1. 节点确认：bridge=true 声明本端支持 SF_REQUEST 代发；
     *  2. 能力协商：app/v 标识端与协议版本；
     *  3. 地址下发：server 子对象给出转码服务运行状态、端口、首选基址与全部候选基址。
     *
     * 旧字段 serverBase 保留：手表端即使是旧版取数逻辑也能拿到地址；
     * 候选基址由 [TranscodeServer.candidateBases] 枚举多网卡生成，服务未运行时为空。
     */
    fun handshakeData(): JSONObject {
        val data = JSONObject()
            .put("app", "BiliMax-apk")
            .put("v", 1)
            .put("bridge", true)
        val server = serverObject()
        data.put("server", server)
        val base = server.optString("base")
        if (base.isNotBlank()) data.put("serverBase", base)
        return data
    }

    /** BILI_SERVER_INFO 业务载荷：与握手 data.server 完全同构，便于手表端复用同一套收口逻辑 */
    fun serverInfoData(): JSONObject = serverObject()

    private fun serverObject(): JSONObject {
        val server = JSONObject()
            .put("running", TranscodeServer.isRunning())
            .put("port", TranscodeServer.PORT)
        val bases = TranscodeServer.candidateBases()
        server.put("bases", JSONArray(bases))
        server.put("base", bases.firstOrNull().orEmpty())
        val key = if (::appContext.isInitialized) runCatching {
            AppPrefs.get(appContext).cloudApiKey
        }.getOrNull().orEmpty() else ""
        if (key.isNotBlank()) server.put("cloudKey", key)
        return server
    }
}

/**
 * SimpleFetch 手机端桥接：在 XMS 传输层（[WearTransport]）之上运行标准 SF 协议。
 *
 * 职责：
 * - 收到 SF_HANDSHAKE 回 SF_HANDSHAKE_ACK（双向均带统一握手 data）；
 * - 收到 SF_PING 回 SF_PONG（ts 原样带回）；
 * - 收到 SF_CLOSE_BRIDGE 回 SF_CLOSE_BRIDGE_ACK；
 * - 收到 BILI_GET_SERVER_INFO 回 BILI_SERVER_INFO（地址下发的握手后兜底通道）；
 * - 收到 SF_REQUEST 时在手机侧发起真实 HTTP 请求，回 SF_RESPONSE：
 *   * 文本/JSON 直接回原始字符串；
 *   * 二进制响应（图片等）回 Base64；
 *   * 响应体过大时按 totalChunks 协议分片（chunk 从 1 开始，首片带 statusCode/headers）；
 *   * 网络层错误回 statusCode=0 + 中文 status；
 *   * 多条 Set-Cookie 用换行符合并为一个 "set-cookie" 值。
 *
 * 心跳由手表端 SF_PING 驱动，本类不发心跳。
 */
object SfBridge {

    private const val TAG = "BiliMax-Sf"
    private const val MAX_BODY_BYTES = 20L * 1024 * 1024
    private const val DEFAULT_TIMEOUT_MS = 20000
    private const val BRIDGE_STAT_REPORT_MS = 1_000L
    private val bridgeStatLock = Any()
    private var bridgeStatCount = 0
    private var bridgeStatBytes = 0L
    private var bridgeStatWindowStart = 0L

    private fun recordBridgeTraffic(bytes: Long) {
        val now = System.currentTimeMillis()
        synchronized(bridgeStatLock) {
            if (bridgeStatWindowStart == 0L) bridgeStatWindowStart = now
            bridgeStatCount++
            bridgeStatBytes += bytes
            if (now - bridgeStatWindowStart >= BRIDGE_STAT_REPORT_MS) {
                val count = bridgeStatCount
                val total = bridgeStatBytes
                bridgeStatCount = 0
                bridgeStatBytes = 0L
                bridgeStatWindowStart = now
                SyncLog.i(TAG, "SF 桥代发 $count 个请求 / ${formatKb(total)}")
            }
        }
    }

    private fun formatKb(bytes: Long): String = if (bytes < 1024) {
        // 小响应（状态 JSON / 404 错误体）显示真实字节数，别让「0.0 KB」被误读成空响应
        "$bytes B"
    } else {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }

    // 单条 XMS 消息 < 4096 字节（UTF-8）。中文最坏 3 字节/字符：
    // 非分片消息体 <= 3600 字节、SF 分片每片 <= 3000 字节，均留有信封余量。
    private const val SINGLE_MSG_BODY_LIMIT = 3600
    private const val CHUNK_PART_BYTES = 3000
    // 单次响应的分片上限：超出直接快速失败，避免把十几 MB 的整包塞进消息通道
    // （手表端按 Range 分段取，单片约 128 KB / 60 片，远低于此上限）
    private const val MAX_CHUNKS = 400
    // 单片发送 watchdog：XMS sendMessage 无回调/回调丢失时也要把链条推进或中止，
    // 否则一次丢包就会让整条响应永远凑不齐
    private const val CHUNK_SEND_TIMEOUT_MS = 10_000L
    private const val CHUNK_SEND_RETRY = 1

    private val sendWatchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sf-bridge-send").apply { isDaemon = true }
        }

    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "sf-bridge-io").apply { isDaemon = true }
    }

    @Volatile
    private var attached = false

    /** App 启动时调用一次：把 SF 消息处理器挂到传输层并确保连接。 */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        SfProtocol.initContext(context)
        try {
            val transport = WearTransport.get(context)
            transport.messageHandler = { raw -> handleMessage(transport, raw) }
            transport.initialize()
        } catch (t: Throwable) {
            SyncLog.w(TAG, "SF 桥接入失败：${t.message}")
        }
    }

    private fun handleMessage(transport: WearTransport, raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        if (ActivationManager.onIncomingMessage(transport, msg)) return
        when (msg.optString("type")) {
            SfProtocol.T_HANDSHAKE -> {
                registerDevice(transport, msg.optJSONObject("data"))
                reply(transport, SfProtocol.T_HANDSHAKE_ACK, SfProtocol.handshakeData())
            }
            SfProtocol.T_HANDSHAKE_ACK ->
                // 本端主动握手的回包：同样登记表端能力/设备信息
                registerDevice(transport, msg.optJSONObject("data"))
            SfProtocol.T_PING -> {
                val data = msg.optJSONObject("data") ?: JSONObject()
                reply(transport, SfProtocol.T_PONG, JSONObject().put("ts", data.opt("ts")))
            }
            SfProtocol.T_CLOSE_BRIDGE ->
                reply(transport, SfProtocol.T_CLOSE_BRIDGE_ACK, JSONObject())
            SfProtocol.T_BILI_GET_SERVER_INFO ->
                // 手表握手后主动查询地址：回当前服务状态与全部候选基址
                reply(transport, SfProtocol.T_BILI_SERVER_INFO, SfProtocol.serverInfoData())
            SfProtocol.T_REQUEST -> {
                val data = msg.optJSONObject("data")
                if (data != null) ioPool.execute { handleRequest(transport, data) }
            }
            else -> Unit
        }
    }

    /**
     * 把表端握手 data（fetch/bridge 能力 + device 信息）登记到 [DeviceRegistry]。
     */
    private fun registerDevice(transport: WearTransport, data: JSONObject?) {
        try {
            DeviceRegistry.registerOrUpdate(
                channel = transport,
                handshakeData = data,
                fallbackName = transport.currentDeviceName()
            )
        } catch (t: Throwable) {
            SyncLog.w(TAG, "登记设备失败：${t.message}")
        }
    }

    private fun reply(transport: WearTransport, type: String, data: JSONObject) {
        val text = JSONObject()
            .put("type", type)
            .put("status", "OK")
            .put("data", data)
            .toString()
        transport.sendText(text)
    }

    /**
     * 向全部已登记在线设备广播一次 BILI_SERVER_INFO。
     * 转码服务启动/停止后调用：即便握手回包丢失，手表也能借此刷新地址。
     */
    fun broadcastServerInfo() {
        val text = JSONObject()
            .put("type", SfProtocol.T_BILI_SERVER_INFO)
            .put("status", "OK")
            .put("data", SfProtocol.serverInfoData())
            .toString()
        DeviceRegistry.allChannels().forEach { channel ->
            try {
                channel.sendText(text)
            } catch (t: Throwable) {
                SyncLog.w(TAG, "广播服务信息失败：${t.message}")
            }
        }
    }

    /**
     * 云端 tunnel 业务地址 → 手机本机回环地址。
     *
     * 手表把产物地址拼成 <网关>/api/v2/tunnel/<deviceID>/<原始路径>?key=…
     * 经 SF 桥接代发时，请求本来就由**手机**发出，再去绕公网网关毫无意义（且网关对
     * 大二进制/长连接会 504，表现为手表收到 0 字节 → 「云端下载失败」）。
     * 这里把网关前缀剥掉直接本机回环，旧版手表（未做隧道地址还原）也能正常取产物。
     */
    private val TUNNEL_PATH_RE =
        Regex("^https?://[^/?#]+/api/v2/tunnel/[^/?#]+(/[^?#]*)")

    private fun rewriteTunnelToLoopback(url: String): String {
        val path = TUNNEL_PATH_RE.find(url)?.groupValues?.get(1) ?: return url
        return "http://127.0.0.1:${TranscodeServer.PORT}$path"
    }

    private fun shortUrl(url: String): String = runCatching {
        val u = URL(url)
        u.path + (u.query?.let { "?$it" } ?: "")
    }.getOrDefault(url)

    private fun handleRequest(transport: WearTransport, data: JSONObject) {
        val id = data.optString("id")
        val rawUrl = data.optString("url")
        val method = data.optString("method", "GET").ifEmpty { "GET" }
        val timeout = data.optInt("timeout", DEFAULT_TIMEOUT_MS).let { if (it > 0) it else DEFAULT_TIMEOUT_MS }
        if (id.isEmpty() || rawUrl.isEmpty()) {
            sendError(transport, id, "请求参数无效")
            return
        }

        val url = rewriteTunnelToLoopback(rawUrl)
        if (url != rawUrl) {
            SyncLog.i(TAG, "云端 tunnel 地址改写为本机回环：${shortUrl(rawUrl)}")
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                useCaches = false
            }
            val headers = data.optJSONObject("headers")
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headers.opt(key)
                    if (value != null) conn.setRequestProperty(key, value.toString())
                }
            }
            val body: String? = if (data.isNull("body")) null else data.optString("body")
            if (!body.isNullOrEmpty()) {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val statusCode = conn.responseCode
            val respBytes = readStream(if (statusCode >= 400) conn.errorStream else conn.inputStream)
            val contentType = conn.contentType ?: ""
            val respHeaders = collectHeaders(conn)

            val binary = isBinary(contentType)
            val payloadBody: String
            if (binary) {
                payloadBody = Base64.encodeToString(respBytes, Base64.NO_WRAP)
            } else {
                payloadBody = String(respBytes, Charsets.UTF_8)
            }
            sendResponse(transport, id, statusCode, respHeaders, payloadBody)
            // 逐条留痕：手表报「下载失败」时可直接看出是哪个产物、什么状态码、多少字节
            SyncLog.i(TAG, "代发 $method ${shortUrl(url)} → $statusCode / ${respBytes.size} B")
            recordBridgeTraffic(respBytes.size.toLong())
            SyncStatus.noteDelivery("$method ${shortUrl(url)} → $statusCode")
        } catch (e: SocketTimeoutException) {
            SyncLog.w(TAG, "代发超时 [$id] $method ${shortUrl(url)}")
            sendError(transport, id, "请求超时")
        } catch (e: UnknownHostException) {
            SyncLog.w(TAG, "代发 DNS 失败 [$id] ${shortUrl(url)}")
            sendError(transport, id, "DNS解析失败")
        } catch (e: ConnectException) {
            SyncLog.w(TAG, "代发请求被拒绝 [$id] $url")
            sendError(transport, id, "连接被拒绝")
        } catch (e: ResponseTooLargeException) {
            SyncLog.w(TAG, "代发响应过大 [$id] ${shortUrl(url)}")
            sendError(transport, id, "响应内容过大，请改用 Range 分段下载")
        } catch (e: Exception) {
            SyncLog.w(TAG, "代发请求失败 [$id] ${shortUrl(url)}：${e.message}")
            sendError(transport, id, "网络不可达")
        } finally {
            conn?.disconnect()
        }
    }

    private fun collectHeaders(conn: HttpURLConnection): JSONObject {
        val out = JSONObject()
        var index = 0
        while (true) {
            val key = conn.getHeaderFieldKey(index) ?: break
            val value = conn.getHeaderField(index) ?: run { index++; continue }
            when {
                key.equals("Content-Type", ignoreCase = true) -> out.put("Content-Type", value)
                key.equals("Set-Cookie", ignoreCase = true) -> {
                    val merged = out.optString("set-cookie", "")
                    out.put("set-cookie", if (merged.isEmpty()) value else "$merged\n$value")
                }
                else -> out.put(key, value)
            }
            index++
        }
        return out
    }

    private fun readStream(stream: java.io.InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BODY_BYTES) throw ResponseTooLargeException()
            }
        }
        return out.toByteArray()
    }

    private fun isBinary(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.contains("image/") ||
            ct.contains("audio/") ||
            ct.contains("video/") ||
            ct.contains("font/") ||
            ct.contains("octet-stream") ||
            ct.contains("application/zip") ||
            ct.contains("application/pdf") ||
            ct.contains("application/wasm")
    }

    /**
     * 回 SF_RESPONSE。响应体可作为一条消息直发时不分片；
     * 否则按 SF totalChunks 协议切片（chunk 从 1 开始，首片带 statusCode+headers）。
     *
     * 分片必须**串行 + 等发送回执**：此前是一次性 forEach 连发上千条 XMS 消息，
     * 通道被灌满后大量消息丢失，手表永远凑不齐 totalChunks（表现为加载几十秒后超时），
     * 同时把 PING/PONG 挤掉触发心跳超时断桥。现在每片发出后等 onResult 才发下一片，
     * 并对单片做超时重试/中止，失败时给手表一条明确的错误响应。
     */
    private fun sendResponse(
        transport: WearTransport,
        id: String,
        statusCode: Int,
        headers: JSONObject,
        body: String
    ) {
        val single = JSONObject()
            .put("type", SfProtocol.T_RESPONSE)
            .put("status", "OK")
            .put("data", JSONObject()
                .put("id", id)
                .put("statusCode", statusCode)
                .put("headers", headers)
                .put("body", body))
            .toString()
        if (single.toByteArray(Charsets.UTF_8).size <= SINGLE_MSG_BODY_LIMIT) {
            transport.sendText(single)
            return
        }
        sendChunked(transport, id, statusCode, headers, body)
    }

    private fun sendChunked(
        transport: WearTransport,
        id: String,
        statusCode: Int,
        headers: JSONObject,
        body: String
    ) {
        val parts = splitUtf8(body, CHUNK_PART_BYTES)
        val total = parts.size
        if (total > MAX_CHUNKS) {
            SyncLog.w(TAG, "响应过大 [$id]：${body.length} 字符需 $total 片（上限 $MAX_CHUNKS），" +
                "请用手表端 Range 分段下载")
            sendError(transport, id, "响应过大，请改用 Range 分段下载")
            return
        }

        fun sendAt(index: Int, attempt: Int) {
            if (index >= total) return
            val data = JSONObject().put("id", id).put("body", parts[index])
            if (index == 0) {
                data.put("statusCode", statusCode)
                data.put("headers", headers)
            }
            data.put("chunk", index + 1)
            data.put("totalChunks", total)
            val text = JSONObject()
                .put("type", SfProtocol.T_RESPONSE)
                .put("status", "OK")
                .put("data", data)
                .toString()

            val settled = AtomicBoolean(false)
            fun advance(ok: Boolean) {
                if (!settled.compareAndSet(false, true)) return
                if (ok) {
                    sendAt(index + 1, 0)
                    return
                }
                if (attempt < CHUNK_SEND_RETRY) {
                    sendAt(index, attempt + 1)
                    return
                }
                SyncLog.w(TAG, "分片发送失败 [$id] 第 ${index + 1}/$total 片，中止本次响应")
                sendError(transport, id, "桥接分片发送失败")
            }
            try {
                transport.sendText(text) { ok -> advance(ok) }
            } catch (t: Throwable) {
                advance(false)
            }
            sendWatchdog.schedule({ advance(false) }, CHUNK_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        sendAt(0, 0)
    }

    /**
     * 按 UTF-8 字节数切分，且不切断代理对（emoji/生僻字）。
     * 手表端按字符拼接 chunk，因此不能按字节硬切——半个字符会变成乱码。
     */
    private fun splitUtf8(text: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>(text.length / maxBytes + 1)
        var start = 0
        var i = 0
        var bytes = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes && i > start) {
                out.add(text.substring(start, i))
                start = i
                bytes = 0
            }
            bytes += size
            i += Character.charCount(cp)
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    private fun sendError(transport: WearTransport, id: String, message: String) {
        SyncLog.w(TAG, "SF 代发失败 [$id]：$message")
        val data = JSONObject()
            .put("id", id)
            .put("statusCode", 0)
            .put("headers", JSONObject())
        val text = JSONObject()
            .put("type", SfProtocol.T_RESPONSE)
            .put("status", message)
            .put("data", data)
            .toString()
        transport.sendText(text)
    }

    private class ResponseTooLargeException : RuntimeException()
}
