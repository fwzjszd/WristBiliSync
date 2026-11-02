package moe.mcns.BiliMax.sync.tunnel

import moe.mcns.BiliMax.sync.SyncLog
import moe.mcns.BiliMax.sync.SyncStatus
import moe.mcns.BiliMax.sync.TranscodeServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 单条 mux 流的完整生命周期：把网关转发来的一次标准 HTTP 请求回环到本机转码服务
 * （`http://127.0.0.1:<port>`）或 `X-Tunnel-Target` 指定的局域网主机，并把应答编码为
 * HEADERS → DATA(32KB)* → END 沿原 sid 回发。
 *
 * 线程模型：WS 读线程经 [feedData]/[endData]/[cancel] 投递，回环动作在独立线程池执行
 * [handle]，二者通过有界请求体队列解耦。
 *
 * 相对旧 MuxStream 的修复：
 *   1. 请求体队列**有界**（32 块）：慢速/异常上游无法无限堆积内存，溢出即判错中断，
 *      不再用无界 LinkedBlockingQueue（手表可穿戴场景的内存纪律同样适用于手机端）；
 *   2. 已发 HEADERS 之后的传输错误改发 **ERROR**，不再用 END 把半截响应伪装成完整成功
 *      （旧实现会让网关上的手表端拿到截断的 jpg/mp3 却以为下载完成）；
 *   3. 响应头收集跳过状态行（旧实现从 index 0 起遇 null key 即 break，除补的 Content-Type
 *      外所有响应头都没转发）；
 *   4. DELETE 等也可能带 body：对 POST/PUT/PATCH/DELETE 用“首块限时到达”判断有无请求体，
 *      网关对无体请求也会立即发 END，毫秒即到，不会误等；
 *   5. 目标地址做 CRLF/斜杠注入校验；
 *   6. 请求体**收齐后定长发送**（不再用 chunked）：本地服务（TranscodeServer）只解析
 *      Content-Length，chunked 会被判成空体，云端 /api/createTask 必然 400。
 */
class LoopbackMuxStream(
    private val sid: Int,
    private val sender: MuxSender,
    private val loopbackPort: Int = TranscodeServer.PORT,
    private val tag: String = TAG
) {

    companion object {
        private const val TAG = "BiliMax-Relay"
        private const val DATA_CHUNK = 32 * 1024
        private const val MAX_RESPONSE_BYTES = 64L * 1024 * 1024

        /** 请求体缓冲块数上限（每块来自网关一次 DATA，手表业务体均为小 JSON）。 */
        private const val BODY_QUEUE_CAPACITY = 32

        /** 收齐后的请求体字节上限：BiliMax 经隧道只发 createTask 小 JSON，1 MiB 足够。 */
        private const val MAX_REQUEST_BODY_BYTES = 1024 * 1024

        /** 后续 DATA 块/END 的等待上限。 */
        private const val BODY_BLOCK_WAIT_MS = 30_000L

        /**
         * 首块等待窗口：无请求体时网关会在 REQUEST 后立即发 END，正常毫秒到达；
         * 该窗口只用于区分“无体方法”与“体迟到”，不影响 GET 等不等待的方法。
         */
        private const val BODY_FIRST_WAIT_MS = 10_000L

        private val END_MARKER = ByteArray(0)

        // 云端转发请求量按秒聚合上屏：手表经隧道逐帧拉取时每帧一个流，逐条记录会瞬间刷爆
        // 500 条缓冲；createTask 等关键请求本身已由 TranscodeServer 逐条上屏，这里只汇总流量。
        private val relayStatLock = Any()
        private var relayStatCount = 0
        private var relayStatBytes = 0L
        private var relayStatWindowStart = 0L
        private const val RELAY_STAT_REPORT_MS = 1_000L

        fun recordRelayTransfer(bytes: Long) {
            val now = System.currentTimeMillis()
            synchronized(relayStatLock) {
                if (relayStatWindowStart == 0L) relayStatWindowStart = now
                relayStatCount++
                relayStatBytes += bytes
                if (now - relayStatWindowStart >= RELAY_STAT_REPORT_MS) {
                    val count = relayStatCount
                    val total = relayStatBytes
                    relayStatCount = 0
                    relayStatBytes = 0L
                    relayStatWindowStart = now
                    // 小响应（任务状态 JSON / 404 错误体）不足 1024 B，整数除法会显示成 0 KB，
                    // 看起来像「空响应」；不足 1 KB 时直接显示字节数，避免误判。
                    val size = if (total < 1024) "$total B" else "${total / 1024} KB"
                    SyncLog.i(TAG, "云端隧道转发 $count 个请求 / $size")
                }
            }
        }
    }

    private enum class State { ACTIVE, CANCELLED, FINISHED }

    private val state = AtomicReference(State.ACTIVE)

    // 有界请求体队列：数据块或 END_MARKER（ArrayBlockingQueue 不接受 null）
    private val reqBody = ArrayBlockingQueue<ByteArray>(BODY_QUEUE_CAPACITY)

    @Volatile private var bodyOverflow = false
    @Volatile private var conn: HttpURLConnection? = null

    /** HEADERS 帧是否已成功发出：决定异常收尾走 ERROR+END 还是仅 ERROR。 */
    @Volatile private var headersSent = false

    /** 网关 DATA：请求体分块。空块忽略；队列满说明本地服务消费不过来，判溢出并中断。 */
    fun feedData(chunk: ByteArray) {
        if (chunk.isEmpty() || state.get() != State.ACTIVE) return
        val copy = chunk.copyOf()
        if (!reqBody.offer(copy)) {
            bodyOverflow = true
            SyncLog.w(tag, "请求体缓冲溢出 sid=$sid，中断该流")
            runCatching { conn?.disconnect() }
        }
    }

    /** 网关 END：请求体结束。队列满（溢出已触发断开）时丢弃也不阻塞读线程。 */
    fun endData() {
        if (state.get() != State.ACTIVE) return
        if (!reqBody.offer(END_MARKER)) {
            bodyOverflow = true
            runCatching { conn?.disconnect() }
        }
    }

    /** 网关 CANCEL：调用方放弃，中断本地请求。 */
    fun cancel() {
        if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
            runCatching { conn?.disconnect() }
            reqBody.offer(END_MARKER) // 解除 pump 阻塞
        }
    }

    /** 在回环工作线程上执行一次完整转发。任何异常都收敛为 ERROR，不向读线程外抛。 */
    fun handle(req: MuxFrame.RequestStart) {
        val recvAt = System.currentTimeMillis()
        val method = req.method.uppercase()
        var c: HttpURLConnection? = null
        try {
            var target: String? = null
            val fwdHeaders = ArrayList<Pair<String, String>>(req.headers.size)
            for ((k, v) in req.headers) {
                if (k.equals("x-tunnel-target", ignoreCase = true)) {
                    target = v
                } else if (!MuxFrame.isHopByHop(k)) {
                    fwdHeaders.add(k to v)
                }
            }
            // 请求体**先收齐再发**：网关只按 DATA…END 供数、不告知长度，而本地服务
            // （TranscodeServer.handle）只解析 Content-Length——若照旧用 chunked 发送，
            // 服务端读到的是空体，/api/createTask 必然回 400 empty body（云端转码全废）。
            // 收齐后长度已知，改用定长发送，服务端按既有逻辑即可正确读体。
            val bodyBytes = if (methodHasBody(method)) collectRequestBody() else null

            val url = buildTargetUrl(target, req.path)

            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 120_000 // 产物读取给足时间，createTask 本身秒回 202
                instanceFollowRedirects = false
                try {
                    requestMethod = method
                } catch (e: java.net.ProtocolException) {
                    throw IOException("unsupported method: $method")
                }
            }
            for ((k, v) in fwdHeaders) c.setRequestProperty(k, v)
            conn = c

            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                c.doOutput = true
                // 定长发送：与 TranscodeServer 只解析 Content-Length 的实现对齐（见上方注释）
                c.setFixedLengthStreamingMode(bodyBytes.size)
                if (c.getRequestProperty("Content-Type") == null) {
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                c.outputStream.use { it.write(bodyBytes) }
            }

            if (state.get() == State.CANCELLED) return

            val status = try {
                c.responseCode
            } catch (e: Exception) {
                // 目标服务未运行/连不上：HEADERS 之前回 ERROR（网关映射 502/504）
                SyncLog.w(
                    tag,
                    "回环失败 sid=$sid $method ${req.path} " +
                        "耗时=${System.currentTimeMillis() - recvAt}ms err=${e.message}"
                )
                fail("local target unavailable: ${e.message}")
                return
            }

            sender.sendHeaders(sid, status, collectResponseHeaders(c))
            headersSent = true

            val input = if (status in 200..299) c.inputStream else c.errorStream
            var total = 0L
            var tooLarge = false
            if (input != null) {
                val buf = ByteArray(DATA_CHUNK)
                input.use { src ->
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (state.get() == State.CANCELLED) return
                        sender.sendData(sid, buf.copyOfRange(0, n))
                        total += n
                        if (total > MAX_RESPONSE_BYTES) {
                            tooLarge = true
                            break
                        }
                    }
                }
            }

            when {
                tooLarge -> {
                    // 超限中断：发 ERROR 让网关按异常中止，避免手表拿到截断产物当成功
                    SyncLog.w(tag, "响应过大 sid=$sid，中断（>$MAX_RESPONSE_BYTES B）")
                    sender.sendError(sid, "response too large")
                }
                state.get() == State.CANCELLED -> Unit
                else -> {
                    sender.sendEnd(sid)
                    recordRelayTransfer(total)
                    SyncStatus.noteDelivery("$method ${req.path} → $status")
                    // 非 2xx 逐条上屏：手表侧只会笼统报「下载失败」，这里留下它到底请求了
                    // 哪个产物、拿到什么状态码，是定位 404/502 的唯一现场。
                    if (status >= 400) {
                        SyncLog.w(tag, "回环应答 $status sid=$sid $method ${req.path}（$total B）")
                    }
                }
            }
            state.set(State.FINISHED)
        } catch (e: Exception) {
            if (state.get() == State.CANCELLED) {
                SyncLog.i(tag, "流已取消 sid=$sid $method ${req.path}")
            } else {
                SyncLog.w(tag, "流异常 sid=$sid $method ${req.path}：${e.message}")
                fail("relay error: ${e.message}")
            }
        } finally {
            conn = null
            c?.disconnect()
        }
    }

    private fun methodHasBody(method: String): Boolean =
        method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"

    /**
     * 收齐请求体：首块限时等待以兼容无体 DELETE；读到 END_MARKER 结束。
     * 网关按 DATA…END 供数、不告知长度，故必须**收齐后**才能改用定长发送
     * （TranscodeServer 只解析 Content-Length，chunked 会被判成空体）。
     * 超过 MAX_REQUEST_BODY_BYTES 即中断该流，避免异常上游吃满内存。
     */
    private fun collectRequestBody(): ByteArray {
        val out = ByteArrayOutputStream()
        var first = true
        while (true) {
            if (state.get() == State.CANCELLED) throw IOException("stream cancelled")
            if (bodyOverflow) throw IOException("request body buffer overflow")

            val chunk = reqBody.poll(
                if (first) BODY_FIRST_WAIT_MS else BODY_BLOCK_WAIT_MS,
                TimeUnit.MILLISECONDS
            )
            first = false

            when {
                chunk === END_MARKER -> return out.toByteArray()
                chunk == null -> {
                    // 首块窗口内无任何帧：按无请求体处理（正常网关对无体方法会立即发 END）
                    return out.toByteArray()
                }
                else -> {
                    if (out.size() + chunk.size > MAX_REQUEST_BODY_BYTES) {
                        throw IOException("request body exceeds MAX_REQUEST_BODY_BYTES")
                    }
                    out.write(chunk)
                }
            }
        }
    }

    /**
     * 异常收尾。HEADERS 之前：ERROR + END（网关回 502）；HEADERS 之后：仅 ERROR
     * （网关中止上游，绝不以 END 把半截响应标记为成功完成）。
     */
    private fun fail(message: String) {
        sender.sendError(sid, message)
        if (!headersSent) sender.sendEnd(sid)
    }

    /**
     * 收集响应头：跳过状态行（key 为 null），剥逐跳头；HttpURLConnection 同名头按序逐行返回。
     * 旧实现从 index 0 起在 key==null 时直接 break，在部分实现上会丢掉全部响应头。
     */
    private fun collectResponseHeaders(c: HttpURLConnection): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (index in 0..256) {
            val key = c.getHeaderFieldKey(index)
            val value = c.getHeaderField(index)
            if (key == null && value == null) break
            if (key == null || value == null) continue // 状态行
            if (MuxFrame.isHopByHop(key)) continue
            result.add(key to value)
            seen.add(key.lowercase())
        }
        if ("content-type" !in seen) {
            c.contentType?.takeIf { it.isNotBlank() }?.let { result.add("Content-Type" to it) }
        }
        return result
    }

    private fun buildTargetUrl(target: String?, rawPath: String): String {
        val p = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        if (p.contains('\r') || p.contains('\n')) {
            throw IOException("illegal characters in path")
        }
        return if (target.isNullOrBlank()) {
            "http://127.0.0.1:$loopbackPort$p"
        } else {
            val t = target.trim()
            if (t.contains('\r') || t.contains('\n') || t.contains('/')) {
                throw IOException("illegal X-Tunnel-Target: $t")
            }
            // 构造 URL 以提前拒绝空格等非法主机/端口
            URL("http://$t$p").toString()
        }
    }
}
