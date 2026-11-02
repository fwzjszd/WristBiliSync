package moe.mcns.BiliMax.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 转码与传输历史记录：手动转码完成、服务器任务完成、胶卷推送完成时各追加一条，
 * 供「转码历史」页展示。持久化在 files/history.json，上限 100 条。
 */
object HistoryStore {

    data class Record(
        val time: Long,
        val kind: String,       // "manual" 手动转码 / "server" 手表请求转码 / "push" 手动传输
        val title: String,      // 视频名 / 胶卷名
        val detail: String,     // 结果摘要（帧数、大小、通道等）
        val ok: Boolean,
        val fileUri: String? = null  // 产物文件（content://），用于系统分享；缺失表示该记录无本地文件
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("time", time)
            .put("kind", kind)
            .put("title", title)
            .put("detail", detail)
            .put("ok", ok)
            .put("fileUri", fileUri ?: JSONObject.NULL)

        fun timeText(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))

        fun kindLabel(): String = when (kind) {
            "manual" -> "手动转码"
            "server" -> "手表请求转码"
            "push" -> "手动传输"
            else -> kind
        }
    }

    private const val MAX_RECORDS = 100
    private const val FILE_NAME = "history.json"

    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var loaded = false
    private val records = ArrayList<Record>()

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val f = file(context.applicationContext)
                if (f.exists()) {
                    val arr = JSONArray(f.readText(Charsets.UTF_8))
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        records.add(
                            Record(
                                time = o.optLong("time"),
                                kind = o.optString("kind", "manual"),
                                title = o.optString("title", ""),
                                detail = o.optString("detail", ""),
                                ok = o.optBoolean("ok", true),
                                fileUri = o.optString("fileUri", "").ifEmpty { null }
                            )
                        )
                    }
                }
            } catch (_: Throwable) {
            }
            loaded = true
        }
    }

    fun add(context: Context, kind: String, title: String, detail: String, ok: Boolean) {
        val app = context.applicationContext
        io.execute {
            ensureLoaded(app)
            synchronized(this) {
                records.add(0, Record(System.currentTimeMillis(), kind, title, detail, ok))
                while (records.size > MAX_RECORDS) records.removeAt(records.size - 1)
                persist(app)
            }
        }
    }

    fun all(context: Context): List<Record> {
        ensureLoaded(context.applicationContext)
        synchronized(this) { return ArrayList(records) }
    }

    /**
     * 回填最近一条手动转码成功记录的产物文件 URI（用户通过系统保存面板选定 .bmvd 位置后调用）。
     * 历史卡片分享按钮据此取文件；文件后续被删除/移动时分享会提示找不到文件。
     */
    fun attachFile(context: Context, title: String, fileUri: String) {
        val app = context.applicationContext
        io.execute {
            ensureLoaded(app)
            synchronized(this) {
                val idx = records.indexOfFirst { it.kind == "manual" && it.ok && it.title == title }
                if (idx >= 0) {
                    records[idx] = records[idx].copy(fileUri = fileUri)
                    persist(app)
                }
            }
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            records.clear()
            persist(app)
        }
    }

    private fun persist(context: Context) {
        try {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            file(context).writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}
