package moe.mcns.BiliMax.sync.tunnel

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 握手未返回 101，或响应头/状态行不符合预期。 */
class WebSocketHandshakeException(message: String) : IOException(message)

/** 对端（或本端按 RFC6455）检测到协议违例。 */
class WebSocketProtocolException(message: String) : IOException(message)

/**
 * RFC 6455 最小 WebSocket 客户端（仅客户端角色，发送帧一律掩码）。
 *
 * 不引入第三方依赖：minSdk 26 没有可用的 java.net.http.WebSocket（需 API 34），
 * 故与 TranscodeServer 的原生 Socket 风格一致手写实现。本类只负责 WS 帧层，
 * 不关心 mux-v1 语义——mux 包通过 [Handler.onBinary] 原样上抛。
 *
 * 相对旧实现补强：
 *   1. 握手严格校验 `Sec-WebSocket-Accept = base64(sha1(key + GUID))`，防止被非 WS 服务劫持；
 *   2. 完整处理分片：续帧 opcode=0 归属由起始帧决定，控制帧（ping/pong/close）允许夹插；
 *   3. 校验 RSV 位、保留 opcode、ping 载荷上限、未分片时收到续帧 / 分片进行中收到新起始帧；
 *   4. 协议违例主动回 1002 close 帧再断开；
 *   5. 所有写出在 OutputStream 上互斥，多 sid 并发应答与 pong 不会交错。
 *
 * 使用方式：
 *   val ws = WebSocketConnection.connect(socket, host, path, readTimeoutMs)
 *   ws.runLoop(handler) // 阻塞到连接关闭；ws 可被其它线程并发 sendBinary/sendClose
 */
class WebSocketConnection private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val out: OutputStream
) {

    /** 消息回调：onText/onBinary 收到的均为收齐 FIN 的完整消息。 */
    interface Handler {
        fun onText(text: String)
        fun onBinary(data: ByteArray)

        /**
         * 对端发起正常关闭（opcode 0x8）。code 为 close code（无载荷时为 0），
         * reason 为 UTF-8 原因（可能为空）。本类已回送 close，runLoop 随即返回。
         */
        fun onClose(code: Int, reason: String) {}
    }

    private val writeLock = Any()
    private val random = SecureRandom()

    @Volatile
    private var closed = false

    companion object {
        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        /** 单帧/单消息上限：mux 侧响应本身已限 64MB，这里留少量余量。 */
        private const val MAX_PAYLOAD = 64L * 1024 * 1024 + 1024

        /** RFC6455：控制帧载荷必须 <=125 且不得分片。 */
        private const val MAX_CONTROL_PAYLOAD = 125

        private const val CLOSE_PROTOCOL_ERROR = 1002

        /**
         * 在已建立的 TCP（含 TLS）[socket] 上完成 HTTP Upgrade 握手并返回 WS 连接。
         * 调用方负责在使用结束后关闭（runLoop 返回时本类也会关闭 socket）。
         *
         * @param hostHeader 仅用于 Host/默认端口判断；实际 TCP 由 [socket] 提供
         * @param path 以 '/' 开头的请求目标（含 query）
         * @param tls 仅影响默认 Host 端口显示，不改变 [socket]
         */
        fun connect(
            socket: Socket,
            hostHeader: String,
            port: Int,
            path: String,
            tls: Boolean,
            readTimeoutMs: Int = 70_000
        ): WebSocketConnection {
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = Base64.getEncoder().encodeToString(nonce)

            val hostLine = if ((tls && port == 443) || (!tls && port == 80)) hostHeader else "$hostHeader:$port"
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostLine).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            synchronized(out) {
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val statusLine = readLineRaw(input)
                ?: throw WebSocketHandshakeException("握手无响应（连接被对端关闭）")
            val parts = statusLine.split(' ')
            if (parts.size < 2 || parts[1] != "101") {
                throw WebSocketHandshakeException("握手被拒：$statusLine")
            }

            var accept: String? = null
            var upgradeOk = false
            while (true) {
                val line = readLineRaw(input)
                    ?: throw WebSocketHandshakeException("握手响应头不完整")
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (name.equals("Sec-WebSocket-Accept", ignoreCase = true)) accept = value
                    if (name.equals("Upgrade", ignoreCase = true) &&
                        value.equals("websocket", ignoreCase = true)
                    ) {
                        upgradeOk = true
                    }
                }
            }
            if (!upgradeOk) throw WebSocketHandshakeException("握手缺少 Upgrade: websocket")
            val expected = expectedAccept(wsKey)
            if (accept == null || !constantTimeEquals(accept, expected)) {
                throw WebSocketHandshakeException("Sec-WebSocket-Accept 校验失败")
            }
            return WebSocketConnection(socket, input, out)
        }

        private fun expectedAccept(wsKey: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_MAGIC).toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(digest)
        }

        /** 定长比较，避免 accept 校验被计时侧信道影响（长度不等直接 false）。 */
        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        /** 读握手期一行（ISO-8859-1，剥去结尾 CRLF）；流结束且无数据返回 null。 */
        private fun readLineRaw(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var prev = -1
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (prev == '\r'.code && c == '\n'.code) {
                    val bytes = buf.toByteArray()
                    return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), Charsets.ISO_8859_1)
                }
                buf.write(c)
                prev = c
                if (buf.size() > 64 * 1024) throw WebSocketHandshakeException("握手响应头过大")
            }
        }
    }

    /** 阻塞读循环：自动应答 WS ping，close 回送后返回；EOF（对端直接断连）正常返回。 */
    fun runLoop(handler: Handler) {
        // 分片消息累积：起始 opcode 决定整段是 text 还是 binary
        var fragOpcode = -1
        val fragBuf = ByteArrayOutputStream()
        try {
            while (true) {
                val frame = readFrame()
                    ?: return // TCP EOF：对端未发 close 直接断开
                val opcode = frame.opcode

                // RSV 位对所有帧（含控制帧）都必须为 0，未协商任何扩展
                if (frame.rsv) throw WebSocketProtocolException("RSV 位非法（未协商扩展）")

                // 控制帧允许夹插在分片中间，不改变 fragOpcode/fragBuf
                when (opcode) {
                    OP_PING -> {
                        sendControl(OP_PONG, frame.payload)
                        continue
                    }
                    OP_PONG -> continue
                    OP_CLOSE -> {
                        var code = 0
                        var reason = ""
                        if (frame.payload.size >= 2) {
                            code = ((frame.payload[0].toInt() and 0xFF) shl 8) or
                                (frame.payload[1].toInt() and 0xFF)
                            if (frame.payload.size > 2) {
                                reason = String(frame.payload, 2, frame.payload.size - 2, Charsets.UTF_8)
                            }
                        }
                        sendControl(OP_CLOSE, frame.payload) // 回送相同 close 载荷
                        handler.onClose(code, reason)
                        return
                    }
                }

                when {
                    opcode == OP_CONT && fragOpcode < 0 ->
                        throw WebSocketProtocolException("收到续帧但无进行中的分片")
                    opcode == OP_TEXT || opcode == OP_BINARY -> {
                        if (fragOpcode >= 0) {
                            throw WebSocketProtocolException("新起始帧到达时上一分片未结束")
                        }
                    }
                    opcode != OP_CONT ->
                        throw WebSocketProtocolException("保留 opcode：$opcode")
                }

                fragBuf.write(frame.payload)
                if (fragBuf.size().toLong() > MAX_PAYLOAD) {
                    throw WebSocketProtocolException("消息超过上限")
                }

                if (frame.fin) {
                    val message = fragBuf.toByteArray()
                    fragBuf.reset()
                    val messageOpcode = if (opcode == OP_CONT) fragOpcode else opcode
                    fragOpcode = -1
                    when (messageOpcode) {
                        OP_TEXT -> handler.onText(String(message, Charsets.UTF_8))
                        OP_BINARY -> handler.onBinary(message)
                    }
                } else if (fragOpcode < 0) {
                    fragOpcode = opcode
                }
            }
        } catch (e: IOException) {
            if (e is WebSocketProtocolException) {
                runCatching { sendClose(CLOSE_PROTOCOL_ERROR, "protocol error") }
            }
            throw e
        } finally {
            closeSocket()
        }
    }

    fun sendBinary(data: ByteArray) = sendFrame(OP_BINARY, data)

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendClose(code: Int, reason: String = "") {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        if (reasonBytes.size > 123) throw IllegalArgumentException("close 原因过长")
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code ushr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
        sendControl(OP_CLOSE, payload)
    }

    /** 关闭底层 socket（restart/外层取消用），runLoop 的读会因此抛异常或读到 EOF。 */
    fun shutdown() = closeSocket()

    private fun closeSocket() {
        closed = true
        runCatching { socket.close() }
    }

    private class RawFrame(
        val fin: Boolean,
        val rsv: Boolean,
        val opcode: Int,
        val payload: ByteArray
    )

    /**
     * 读一个 WS 帧。客户端接收的帧不应掩码；若对端掩码按 RFC 解出（兼容性），
     * 但服务端掩码属协议违例——这里仅解出不报错（实际网关不会这么做）。
     * TCP EOF 且尚未读到任何字节时返回 null。
     */
    private fun readFrame(): RawFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val fin = (b0 and 0x80) != 0
        val rsv = (b0 and 0x70) != 0
        val opcode = b0 and 0x0F

        val b1 = input.read()
        if (b1 < 0) throw EOFException("帧头不完整")
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((readByteSafe().toLong() and 0xFF) shl 8) or (readByteSafe().toLong() and 0xFF)
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or (readByteSafe().toLong() and 0xFF) }
            if (len < 0) throw WebSocketProtocolException("64 位帧长为负/超 Long 范围")
        }
        if ((opcode == OP_PING || opcode == OP_PONG || opcode == OP_CLOSE) &&
            (len > MAX_CONTROL_PAYLOAD || !fin)
        ) {
            throw WebSocketProtocolException("控制帧非法：len=$len fin=$fin")
        }
        if (len > MAX_PAYLOAD) throw WebSocketProtocolException("帧过大：$len")

        val mask = if (masked) ByteArray(4) { readByteSafe().toByte() } else null
        val payload = ByteArray(len.toInt())
        readFully(payload)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
        }
        return RawFrame(fin, rsv, opcode, payload)
    }

    private fun readByteSafe(): Int {
        val v = input.read()
        if (v < 0) throw EOFException("读帧时连接断开")
        return v
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("载荷不完整：期望 ${buf.size} 已得 $off")
            off += n
        }
    }

    /** 发送一帧（客户端角色必须掩码，FIN=1；本实现不产生 WS 层分片）。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed) throw IOException("WebSocket 已关闭")
        val header = ByteArrayOutputStream(10)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65536 -> {
                header.write(0x80 or 126)
                header.write((payload.size ushr 8) and 0xFF)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) {
                    header.write((payload.size.toLong() ushr (i * 8)).toInt() and 0xFF)
                }
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(payload.size)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        synchronized(writeLock) {
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    /** 控制帧载荷不允许超过 125 字节（调用处已保证）。 */
    private fun sendControl(opcode: Int, payload: ByteArray) {
        if (closed) return
        runCatching { sendFrame(opcode, payload) }
    }
}
