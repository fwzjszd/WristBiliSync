package moe.mcns.BiliMax.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * B 站视频下载器：按 BV 号拉取视频信息并下载 360P MP4 到手机。
 * 仅使用公开视频接口（无需登录），UA/Referer 按浏览器约定携带。
 * 播放接口用 html5=1 平台，返回 durl 单文件直链，无需 ffmpeg 合并 DASH。
 */
object BiliDownloader {

    data class VideoInfo(
        val bvid: String,
        val aid: Long,
        val cid: Long,
        val title: String,
        val owner: String,
        val durationSec: Int,
        val pic: String
    )

    interface Callback {
        fun onInfo(info: VideoInfo) {}
        /** stage：下载阶段说明；progress：0~1 */
        fun onProgress(stage: String, progress: Float) {}
        fun onDone(file: File, info: VideoInfo) {}
        fun onError(message: String) {}
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"
    private const val URL_VIEW = "https://api.bilibili.com/x/web-interface/view?bvid=%s"
    private const val URL_PLAY =
        "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%d&qn=16&fnval=0&fourk=0&platform=html5&high_quality=1"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 从 BV 号或包含 BV 号的链接中提取 BV 号；无法提取返回 null */
    fun extractBvid(input: String): String? {
        val trimmed = input.trim()
        Regex("(?i)(BV[0-9A-Za-z]{10})").find(trimmed)?.let { return it.groupValues[1] }
        return if (Regex("(?i)^BV[0-9A-Za-z]{10}$").matches(trimmed)) trimmed else null
    }

    fun fetchVideoInfo(context: Context, bvid: String): VideoInfo {
        val body = httpGet(String.format(URL_VIEW, bvid))
        val json = JSONObject(body)
        val code = json.optInt("code")
        if (code != 0) {
            throw IllegalStateException(json.optString("message").ifEmpty { "视频信息获取失败（code=$code）" })
        }
        val d = json.optJSONObject("data") ?: throw IllegalStateException("视频信息为空")
        return VideoInfo(
            bvid = d.optString("bvid").ifEmpty { bvid },
            aid = d.optLong("aid"),
            cid = d.optLong("cid"),
            title = d.optString("title").ifEmpty { bvid },
            owner = d.optJSONObject("owner")?.optString("name").orEmpty(),
            durationSec = d.optInt("duration"),
            pic = d.optString("pic")
        )
    }

    /**
     * 下载视频：拉取信息 → 取 360P MP4 直链 → 落盘到 cacheDir/bili_video/。
     * 全部回调切回主线程。
     */
    fun download(context: Context, bvid: String, callback: Callback) {
        executor.execute {
            try {
                val appCtx = context.applicationContext
                post(callback) { onProgress("正在获取视频信息…", 0f) }
                val info = fetchVideoInfo(appCtx, bvid)
                post(callback) { onInfo(info) }

                val playBody = httpGet(String.format(URL_PLAY, info.bvid, info.cid))
                val playJson = JSONObject(playBody)
                if (playJson.optInt("code") != 0) {
                    throw IllegalStateException(
                        playJson.optString("message").ifEmpty { "播放地址获取失败" }
                    )
                }
                val data = playJson.optJSONObject("data")
                val durl = data?.optJSONArray("durl")
                val videoUrl = durl?.optJSONObject(0)?.optString("url").orEmpty()
                if (videoUrl.isEmpty()) {
                    throw IllegalStateException("未取得可下载的 MP4 直链（该视频可能仅支持 DASH 或为会员/充电专属）")
                }

                val dir = File(appCtx.cacheDir, "bili_video").apply { mkdirs() }
                val safeTitle = info.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
                val out = File(dir, "${info.bvid}_$safeTitle.mp4")

                post(callback) { onProgress("正在下载视频…", 0.02f) }
                downloadFile(videoUrl, out) { frac ->
                    post(callback) { onProgress("正在下载视频…", 0.05f + frac * 0.55f) }
                }
                if (!out.exists() || out.length() < 1024) {
                    out.delete()
                    throw IllegalStateException("视频下载失败或文件不完整")
                }
                // 校验 MP4 文件头（ftyp box）：直链可能被 CDN 拦截而落盘为错误页 HTML，
                // 这种文件能下载成功但转码时才报“无法读取视频时长”，提前在此处给出明确原因。
                if (!isMp4(out)) {
                    out.delete()
                    throw IllegalStateException("下载内容不是有效的 MP4（可能被服务器拦截），请稍后重试或更换视频")
                }
                post(callback) { onDone(out, info) }
            } catch (t: Throwable) {
                android.util.Log.e("BiliDownloader", "下载失败 bvid=$bvid", t)
                val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                post(callback) { onError(msg) }
            }
        }
    }

    /** 校验文件是否为 ISO BMFF（MP4）容器：字节偏移 4..8 必须为 "ftyp" */
    private fun isMp4(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(12)
                val read = input.read(head)
                read >= 8 &&
                    head[4] == 0x66.toByte() && // f
                    head[5] == 0x74.toByte() && // t
                    head[6] == 0x79.toByte() && // y
                    head[7] == 0x70.toByte()    // p
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadFile(urlStr: String, dest: File, onFraction: (Float) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var copied = 0L
                    var lastTick = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        copied += read
                        if (total > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 300) {
                                lastTick = now
                                onFraction((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGet(urlStr: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        } finally {
            conn?.disconnect()
        }
    }

    private fun post(cb: Callback, action: Callback.() -> Unit) {
        main.post { cb.action() }
    }

    /** 下载器产物目录（供缓存清理统计使用） */
    fun videoDir(context: Context): File = File(context.cacheDir, "bili_video")
}
