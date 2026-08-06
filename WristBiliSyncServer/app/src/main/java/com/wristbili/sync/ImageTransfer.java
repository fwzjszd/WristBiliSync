package com.wristbili.sync;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 图片传输（log49，思路 @B4QAQ/@雪松）：小米手环 10 Pro 等设备 image 组件处理网络 jpg 有 BUG（显示感叹号），
 * Vela img 不支持 base64 data URI → 可行路径：手机端把 B 站图片下载 → 转 PNG（≤20KB）→ base64 →
 * 经互联分片（≤20k/片，interconnect 单消息上限 30k）发给手环 → 手环用 @system.file 解码写为
 * internal://files/img_N.png → image 加载本地路径（PNG 正常显示）。
 *
 * 本类负责：下载图片 → Bitmap 缩放 → PNG 压缩（循环降尺寸保证 ≤maxBytes）→ base64 → 分片。
 */
public final class ImageTransfer {

    /** interconnect 单条消息上限约 30KB，推荐 20KB/片 */
    public static final int CHUNK_LEN = 20000;
    /** 单图 base64 上限（PNG ≤20KB 可单片直发；超过则分片） */
    public static final int MAX_B64_BYTES = 20000;
    /** 下载图片最大边（封面缩略 96~160px，够手环小屏显示且省流量） */
    private static final int MAX_EDGE = 160;

    /** 下载失败兜底：1x1 透明 PNG 的 base64（极短），保证手环端 file://img_N 占位必定被替换为本地文件，
     *  避免 resolveImgWait 空等 20s 超时后页面长时间挂起占位 */
    public static final String FALLBACK_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0 Safari/537.36";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private ImageTransfer() {
    }

    /**
     * 下载 B 站图片 → 缩放 → PNG 压缩（循环降尺寸保证 ≤maxBytes）→ base64。
     * 失败返回 null（调用方保留原始 URL 或占位）。
     */
    public static String downloadAsPngBase64(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .build();
            byte[] bytes;
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                bytes = resp.body().bytes();
            }
            if (bytes == null || bytes.length == 0) return null;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null || bmp.isRecycled()) return null;

            // 缩放：最大边 ≤ MAX_EDGE（原 URL 已带 @96w_60h 等缩略参数，这里兜底防大图）
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            float scale = 1f;
            if (w > MAX_EDGE || h > MAX_EDGE) {
                scale = Math.min((float) MAX_EDGE / w, (float) MAX_EDGE / h);
            }
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));

            // PNG 压缩：无损，控制大小只能靠缩放（循环降尺寸直到 ≤MAX_B64_BYTES）
            byte[] png = compressToPng(bmp, tw, th);
            if (png == null) return null;
            String b64 = Base64.encodeToString(png, Base64.NO_WRAP);
            if (b64.length() > MAX_B64_BYTES) {
                // 仍超限：继续缩小（如 96x60 PNG 压缩后仍可能 >20KB）
                float ratio = (float) MAX_B64_BYTES / b64.length();
                int nw = Math.max(16, (int) (tw * Math.sqrt(ratio)));
                int nh = Math.max(16, (int) (th * Math.sqrt(ratio)));
                png = compressToPng(bmp, nw, nh);
                if (png == null) return null;
                b64 = Base64.encodeToString(png, Base64.NO_WRAP);
            }
            bmp.recycle();
            return b64;
        } catch (Exception e) {
            SyncLog.i("[图片] 下载/转 PNG 失败: " + e);
            return null;
        }
    }

    /** 缩放并压缩为 PNG；返回字节数组（失败 null） */
    private static byte[] compressToPng(Bitmap src, int w, int h) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (scaled != src && !scaled.isRecycled()) scaled.recycle();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** base64 按 CHUNK_LEN 分片（interconnect 单消息 ≤30KB，推荐 20KB） */
    public static List<String> splitBase64(String b64) {
        List<String> parts = new ArrayList<>();
        if (b64 == null || b64.isEmpty()) return parts;
        for (int i = 0; i < b64.length(); i += CHUNK_LEN) {
            parts.add(b64.substring(i, Math.min(i + CHUNK_LEN, b64.length())));
        }
        return parts;
    }
}
