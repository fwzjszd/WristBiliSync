package moe.mcns.BiliMax.sync.tunnel

import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * mux-v1 二进制多路复用协议的纯编解码层（不依赖网络与 Android，便于对照规范核查）。
 *
 * 帧布局（大端）：`type(1B) | streamId(4B) | payload(可变)`。
 * 规范见根目录 bilibili-client.md §4 与 https://docs.b4qaq.cn/docs/mingcheng-api/tunnel/。
 *
 * 方向约定：REQUEST/DATA/END/CANCEL/PING 由服务端→设备；设备侧回 HEADERS→DATA*→END，
 * 异常回 ERROR；应用层 PING 回同 sid 的 PONG。
 */
object MuxFrame {

    const val HEADER_SIZE = 5

    const val REQUEST: Int = 0x01
    const val DATA: Int = 0x02
    const val HEADERS: Int = 0x03
    const val ERROR: Int = 0x04
    const val END: Int = 0x05
    const val CANCEL: Int = 0x06
    const val PING: Int = 0x07
    const val PONG: Int = 0x08

    /** 单个 u16 长度前缀字符串允许的最大 UTF-8 字节数。 */
    private const val MAX_STRING_BYTES = 65535

    /** REQUEST 载荷解析结果：方法 / 已剥设备码前缀的路径 / 请求头（保持顺序，可重复）。 */
    class RequestStart(
        val method: String,
        val path: String,
        val headers: List<Pair<String, String>>
    )

    /**
     * 组一个完整 mux 帧。DATA 热路径专用：一次分配 [head + payload]，避免逐帧二次拷贝。
     * payload 为 null 时仅返回 5 字节帧头（END/CANCEL/PING/PONG）。
     */
    fun encode(type: Int, sid: Int, payload: ByteArray? = null): ByteArray {
        val head = ByteArray(if (payload != null) HEADER_SIZE + payload.size else HEADER_SIZE)
        head[0] = type.toByte()
        head[1] = ((sid ushr 24) and 0xFF).toByte()
        head[2] = ((sid ushr 16) and 0xFF).toByte()
        head[3] = ((sid ushr 8) and 0xFF).toByte()
        head[4] = (sid and 0xFF).toByte()
        if (payload != null) System.arraycopy(payload, 0, head, HEADER_SIZE, payload.size)
        return head
    }

    /** 解析一个完整 mux 帧（payload 采用拷贝视图，调用方持有不影响底层缓冲）。 */
    fun decode(pkt: ByteArray): Triple<Int, Int, ByteArray> {
        if (pkt.size < HEADER_SIZE) throw EOFException("mux 帧短于 $HEADER_SIZE 字节：${pkt.size}")
        val type = pkt[0].toInt() and 0xFF
        var sid = 0
        for (i in 1..4) sid = (sid shl 8) or (pkt[i].toInt() and 0xFF)
        val payload = if (pkt.size > HEADER_SIZE) pkt.copyOfRange(HEADER_SIZE, pkt.size) else EMPTY
        return Triple(type, sid, payload)
    }

    /**
     * 解析 REQUEST 载荷：
     * `u16 methodLen|method | u16 pathLen|path | u16 headerCount | (u16 kLen|k|u16 vLen|v)*`。
     * 请求体不在其中，由随后的 DATA 帧承载，读到 END 结束（不依赖 content-length）。
     */
    fun decodeRequest(payload: ByteArray): RequestStart {
        val r = Reader(payload)
        val method = r.takeString(r.takeU16())
        val path = r.takeString(r.takeU16())
        val count = r.takeU16()
        if (count > 256) throw EOFException("请求头数量异常：$count")
        val headers = ArrayList<Pair<String, String>>(count.coerceAtMost(256))
        repeat(count) {
            val k = r.takeString(r.takeU16())
            val v = r.takeString(r.takeU16())
            headers.add(k to v)
        }
        return RequestStart(method, path, headers)
    }

    /**
     * 组 HEADERS 载荷：`u16 status | u16 headerCount | (u16 kLen|k|u16 vLen|v)*`。
     * 超过 u16 长度上限的非法头被丢弃，计数按最终写出的头填写。
     */
    fun encodeHeaders(status: Int, headers: List<Pair<String, String>>): ByteArray {
        val valid = headers.filter { (k, v) ->
            k.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES &&
                v.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES
        }
        val bos = ByteArrayOutputStream(64)
        writeU16(bos, status)
        writeU16(bos, valid.size)
        for ((k, v) in valid) {
            writeLengthString(bos, k)
            writeLengthString(bos, v)
        }
        return bos.toByteArray()
    }

    /** 组 ERROR 载荷：`u16 len | message(utf8)`，超长原因截断到 u16 上限。 */
    fun encodeError(message: String): ByteArray {
        val mb = message.toByteArray(Charsets.UTF_8)
            .let { if (it.size > MAX_STRING_BYTES) it.copyOfRange(0, MAX_STRING_BYTES) else it }
        val bos = ByteArrayOutputStream(mb.size + 2)
        writeU16(bos, mb.size)
        bos.write(mb)
        return bos.toByteArray()
    }

    private fun writeU16(bos: ByteArrayOutputStream, v: Int) {
        bos.write((v ushr 8) and 0xFF)
        bos.write(v and 0xFF)
    }

    private fun writeLengthString(bos: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeU16(bos, b.size)
        bos.write(b)
    }

    /** 大端 u16 前缀读取器，越界统一抛 [EOFException]。 */
    class Reader(private val b: ByteArray) {
        private var pos = 0

        fun takeU16(): Int {
            if (pos + 2 > b.size) throw EOFException("u16 越界 pos=$pos size=${b.size}")
            val v = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun takeString(n: Int): String {
            if (n < 0 || pos + n > b.size) throw EOFException("字符串越界 pos=$pos len=$n size=${b.size}")
            val s = String(b, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }
    }

    /**
     * 逐跳头（hop-by-hop）判定：转发前后都必须剥离。
     * 含 RFC 7230 清单 + content-length（改由本地连接自行定长/分块）+ host（按目标重建）
     * + x-tunnel-target（路由指令，仅本端消费）。
     */
    fun isHopByHop(name: String): Boolean {
        val n = name.lowercase()
        return n == "connection" ||
            n == "keep-alive" ||
            n == "proxy-connection" ||
            n.startsWith("proxy-") ||
            n == "te" ||
            n == "trailers" ||
            n == "transfer-encoding" ||
            n == "upgrade" ||
            n == "content-length" ||
            n == "host" ||
            n == "x-tunnel-target"
    }

    private val EMPTY = ByteArray(0)
}

/**
 * mux 帧写出端：实现方需保证多线程并发调用时帧不交错（多 sid 并发在途）。
 * [LoopbackMuxStream] 经此接口回帧，便于与具体 WS 连接解耦。
 */
interface MuxSender {
    fun send(type: Int, sid: Int, payload: ByteArray? = null)
}

fun MuxSender.sendData(sid: Int, chunk: ByteArray) = send(MuxFrame.DATA, sid, chunk)
fun MuxSender.sendEnd(sid: Int) = send(MuxFrame.END, sid, null)
fun MuxSender.sendPong(sid: Int) = send(MuxFrame.PONG, sid, null)
fun MuxSender.sendHeaders(sid: Int, status: Int, headers: List<Pair<String, String>>) =
    send(MuxFrame.HEADERS, sid, MuxFrame.encodeHeaders(status, headers))
fun MuxSender.sendError(sid: Int, message: String) =
    send(MuxFrame.ERROR, sid, MuxFrame.encodeError(message))
