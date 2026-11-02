package moe.mcns.BiliMax.sync

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 同步器全局日志环形缓冲：服务器启停、手表连接、桥接请求、胶卷推送等事件
 * 都追加到这里，供「查看日志」页实时展示（带时间戳）、导出与系统分享。
 *
 * 可穿戴配套场景下日志量需要克制：上限 500 条，超出丢弃最旧记录；
 * 监听器集合使用 CopyOnWriteArrayList（极少增删，UI 页面进出自由订阅）；
 * 记录本体用 ArrayDeque + 锁：顺序追加、环形裁剪都是 O(1)。
 */
object SyncLog {

    data class Entry(val time: Long, val tag: String, val message: String) {
        fun formatted(): String {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
            return "[$ts] $tag: $message"
        }
    }

    interface Listener {
        fun onLogEntry(entry: Entry)
    }

    private const val TAG = "BiliMax-SyncLog"
    private const val MAX_ENTRIES = 500

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES + 1)
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun i(tag: String, message: String) {
        append(tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        append(tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        append(tag, if (t != null) "$message（${t.message}）" else message)
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
    }

    private fun append(tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, message)
        // 顺序追加 + 环形裁剪。原实现用 CopyOnWriteArrayList.addIfAbsent(entry)：Entry 是 data class
        // 具有值相等语义，同一毫秒内 tag 与文案都相同的两条日志会被判为重复而**静默丢弃**；
        // 且超出上限后的 removeAt(0) 每次都要整体复制数组（O(n)）。
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        listeners.forEach { l ->
            try {
                l.onLogEntry(entry)
            } catch (t: Throwable) {
                Log.w(TAG, "log listener error: ${t.message}")
            }
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    fun asText(): String = synchronized(lock) { entries.joinToString("\n") { it.formatted() } }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }
}
