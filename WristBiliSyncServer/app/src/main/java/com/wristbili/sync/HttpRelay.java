package com.wristbili.sync;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

/**
 * 通用 HTTP 转发（"手环要什么就给什么"）：
 *  - 代理任意 method / headers / body（原样透传，覆盖全部 B 站接口）
 *  - 响应原样返回（图片 URL 保持原样，不做 base64 内联）
 *  - 登录态统一注入（真实 Cookie 只存手机端）+ 写操作 csrf 自动补齐
 *  - 结果通过 RelayCallback 回传，由协议引擎按 FetchBridge v3 / 官方信封格式发送。
 */
public final class HttpRelay {

    /** 转发结果回调（回调线程为 OkHttp 线程） */
    public interface RelayCallback {
        /** 转发成功：status=HTTP 状态码，body 为原样字节，encoding 为 'text'/'base64' */
        void onResponse(int status, Map<String, List<String>> headers, byte[] body, String encoding);

        /** 转发失败：errBody 为 JSON 错误体 */
        void onFailure(String errBody);
    }

    private static final Gson gson = new Gson();

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11; 2109119BC) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.72 Safari/537.36";

    // log48：阿里云 ESA 边缘安全加速图像转换域名（需在 ESA 控制台：添加站点 + 开启"图像转换" + 回源到 B 站图床 i0.hdslb.com）。
    // 开启后该域名下图片 URL 加 ?image_process=format,png 即返回 PNG（手环 image 组件处理 jpg 有 BUG，PNG 正常）。
    // 填入 ESA 域名（如 "img.example.com"）即启用；留空 "" = 不启用（图片 URL 保持原样）。
    private static final String ESA_IMG_DOMAIN = "";

    // log48b：免费边缘图片转码服务（Vercel Serverless，无需自备域名/备案）——项目 edge-image-service/，
    // 部署后得到 https://<project>.vercel.app，本常量为 `https://<project>.vercel.app/api/img?url=`。
    // 手机端把 B 站图片 URL 编码后作为 url 参数发给它，它拉取 jpg → 转 PNG 返回 → 手环显示。
    // 留空 "" = 不启用。IMG_PROXY_BASE 优先于 ESA_IMG_DOMAIN（两者都填时用代理）。
    // log48c：曾填免费公共图片代理 images.weserv.nl（零部署先验证）。
    // log49：改为 base64 分片方案（FetchBridgeServer 把图片下载转 PNG ≤20KB → base64 分片 →
    // 手环用 @system.file 保存为 internal://files/img_N.png → image 加载本地路径），
    // 本常量必须留空，否则 URL 被重写成代理域名，fetchbridge 的占位提取匹配不到 hdslb/biliimg。
    // 如需回退 wsrv：`https://images.weserv.nl/?output=png&url=`（⚠️ 必须带 output=png 且放在 url 参数之前）。
    private static final String IMG_PROXY_BASE = "";

    // B 站图片 URL 正则：https:// 或 // 开头 + 任意子域 .hdslb.com / .biliimg.com + 路径（到引号/空白/逗号/} 为止）
    private static final java.util.regex.Pattern BILI_IMG_URL = java.util.regex.Pattern.compile(
            "(?:https?:)?//(?:[a-z0-9-]+\\.)*(?:hdslb\\.com|biliimg\\.com)[^\"'\\s,}]*");

    private HttpRelay() {
    }

    /**
     * 执行转发。
     *
     * @param url     目标 URL
     * @param method  HTTP 方法
     * @param headers 请求头（原样透传，无 UA 时补默认）
     * @param body    请求体
     * @param raw     true=不解析 JSON（图片内联仅对 JSON 生效）
     */
    public static void execute(String url, String method, Map<String, String> headers,
                               String body, boolean raw, RelayCallback cb) {
        try {
            // 登录态统一注入（架构：真实登录凭证只存手机端，手环仅持有占位登录标记）：
            // 目标是 B 站域名且手机端有登录 Cookie 时，无条件用真实 Cookie 覆盖请求头 Cookie
            //（手环页面会按 storage 的占位标记判断"已登录"走个性化流程，真实 Cookie 由手机端注入，
            //  手环传的占位/空 Cookie 一律替换，防止旧值或占位符污染登录态）
            String storedCookie = LoginStore.cookie();
            if (!storedCookie.isEmpty() && url != null
                    && (url.contains("bilibili.com") || url.contains("biliapi.net") || url.contains("biligame.com"))) {
                Map<String, String> nh = headers != null ? new HashMap<>(headers) : new HashMap<>();
                nh.remove("Cookie");
                nh.remove("cookie");
                nh.put("Cookie", storedCookie);
                headers = nh;
                SyncLog.i("[登录态] " + (method == null ? "GET" : method.toUpperCase()) + " " + url
                        + " 统一注入登录 Cookie（" + storedCookie.length() + " 字符）");
            }

            Request.Builder rb = new Request.Builder().url(url);

            boolean hasUA = false;
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    try {
                        rb.header(e.getKey(), e.getValue());
                        if ("user-agent".equalsIgnoreCase(e.getKey())) hasUA = true;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!hasUA) rb.header("User-Agent", DEFAULT_UA);

            // B 站接口统一补 Referer（语义化请求手环不再传 header，多数 API 要求同源 Referer）
            if (url != null && url.contains("bilibili.com") && !hasHeader(headers, "referer")) {
                rb.header("Referer", "https://www.bilibili.com/");
            }

            String m = method == null ? "GET" : method.toUpperCase();

            // 写操作 csrf 补齐：手环端已不持有真实 Cookie（取不到 bili_jct），
            // POST 表单体缺 csrf 或 csrf 为空时由手机端从登录 Cookie 自动补（未登录则不补）
            if (("POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m))
                    && body != null && !body.isEmpty()) {
                String jct = LoginStore.biliJct();
                if (!jct.isEmpty()) {
                    if (body.contains("csrf=") || body.contains("csrf_token=")) {
                        // body 已带空 csrf/csrf_token（手环从占位 cookie 提取为空）→ 替换为真实值
                        body = body.replaceAll("csrf=[^&;]*", "csrf=" + android.net.Uri.encode(jct))
                                .replaceAll("csrf_token=[^&;]*", "csrf_token=" + android.net.Uri.encode(jct));
                        SyncLog.i("[登录态] 写操作补齐 csrf（替换空值）");
                    } else {
                        body = body + (body.endsWith("&") ? "" : "&") + "csrf=" + android.net.Uri.encode(jct);
                        SyncLog.i("[登录态] 写操作自动补齐 csrf（bili_jct）");
                    }
                }
            }

            switch (m) {
                case "POST":
                case "PUT":
                case "PATCH":
                case "DELETE": {
                    RequestBody rbody;
                    if (body != null && !body.isEmpty()) {
                        MediaType mt = null;
                        if (headers != null) {
                            for (String k : headers.keySet()) {
                                if ("content-type".equalsIgnoreCase(k)) {
                                    try {
                                        mt = MediaType.parse(headers.get(k));
                                    } catch (Exception ignored) {
                                    }
                                    break;
                                }
                            }
                        }
                        if (mt == null) mt = MediaType.parse("application/octet-stream");
                        rbody = RequestBody.create(body.getBytes(StandardCharsets.UTF_8), mt);
                    } else {
                        rbody = RequestBody.create(new byte[0], null);
                    }
                    rb.method(m, rbody);
                    break;
                }
                case "HEAD":
                    rb.method("HEAD", null);
                    break;
                default:
                    rb.method("GET", null);
            }

            Request request = rb.build();

            // 链路2日志：同步器 → 服务器
            SyncLog.i("[同步器→服务器] ── 转发 " + m + " " + url);
            if (headers != null && !headers.isEmpty()) {
                SyncLog.i("[同步器→服务器] 转发头: " + truncate(gson.toJson(headers), 800));
            }
            if (body != null && !body.isEmpty()) {
                SyncLog.i("[同步器→服务器] 转发体: " + truncate(body, 400));
            }

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    SyncLog.i("[同步器→服务器] 请求失败: " + e);
                    String msg = e.getMessage() == null ? "network error" : e.getMessage();
                    cb.onFailure("{\"code\":-1,\"message\":\"" + escapeJson(msg) + "\"}");
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    int status = response.code();
                    Map<String, List<String>> respHeaders = new HashMap<>();
                    for (String name : response.headers().names()) {
                        respHeaders.put(name, response.headers().values(name));
                    }
                    byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                    SyncLog.i("[同步器→服务器] 服务器返回: HTTP " + status + ", " + bytes.length + " 字节");

                    String encoding = isValidUtf8(bytes) ? "text" : "base64";

                    // log48/log48b：图片转码服务——响应为文本且配置了代理/ESA 时，把 B 站图片 URL 重写，
                    // 让手环 image 组件加载到 PNG（绕开 jpg 解码 BUG）。IMG_PROXY_BASE（免费边缘服务）优先。
                    if ("text".equals(encoding) && bytes.length > 0) {
                        if (!IMG_PROXY_BASE.isEmpty()) {
                            byte[] nb = rewriteBiliImageUrls(new String(bytes, StandardCharsets.UTF_8), IMG_PROXY_BASE, true)
                                    .getBytes(StandardCharsets.UTF_8);
                            if (nb.length != bytes.length) {
                                bytes = nb;
                                SyncLog.i("[图片] 边缘转码: B 站图片 URL 已重定向到 " + IMG_PROXY_BASE
                                        + "url=...（" + bytes.length + " 字节）");
                            }
                        } else if (!ESA_IMG_DOMAIN.isEmpty()) {
                            byte[] nb = rewriteBiliImageUrls(new String(bytes, StandardCharsets.UTF_8), ESA_IMG_DOMAIN, false)
                                    .getBytes(StandardCharsets.UTF_8);
                            if (nb.length != bytes.length) {
                                bytes = nb;
                                SyncLog.i("[图片] ESA 图像转换: B 站图片 URL 已重定向到 " + ESA_IMG_DOMAIN
                                        + "?image_process=format,png（" + bytes.length + " 字节）");
                            }
                        }
                    }

                    cb.onResponse(status, respHeaders, bytes, encoding);
                }
            });
        } catch (Exception e) {
            SyncLog.i("[同步器→服务器] 转发异常: " + e);
            cb.onFailure("{\"code\":-1,\"message\":\"" + escapeJson(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把响应文本中所有 B 站图片 URL 重写为转码服务 URL。
     *  - proxy=true（IMG_PROXY_BASE，免费边缘服务）：完整 URL 编码（**保留字面 @**）后拼在 base 后
     *  - proxy=false（ESA_IMG_DOMAIN）：域名替换为 base + 追加 ?image_process=format,png
     * 返回替换后的文本；无匹配时返回原文本。
     *
     * 关键（保留 @）：B 站视频雪碧图播放由手环端在 URL 后拼 B 站 CDN 区域裁切参数 `@x-y-w-ha.jpg`
     * （按帧坐标裁切单帧）。若 @ 被编码成 %40，该参数会落在代理 URL 的 query 末尾而非 url 参数内部，
     * 代理会拉取并返回**整张雪碧图**。保留字面 @ 后，`?url=xxx.jpg@0-0-160-90a.jpg` 的 @ 位于
     * url 参数值内，代理原样转发给 B 站 CDN → 返回裁切后的单帧 jpg → 转 PNG → 手环显示单帧。
     */
    private static String rewriteBiliImageUrls(String text, String base, boolean proxy) {
        if (text == null || text.isEmpty()) return text;
        java.util.regex.Matcher m = BILI_IMG_URL.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean hit = false;
        while (m.find()) {
            String u = m.group();
            // wbi 密钥图（/bfs/wbi/）不重写：手机端 WbiSigner 从原始 URL 提取 img_key/sub_key（独立通道），
            // 且手环端不消费该字段；重写反而会丢失原始路径格式。
            if (u.contains("/bfs/wbi/")) continue;
            hit = true;
            String replaced;
            if (proxy) {
                // 编码其余字符但恢复 @（手环端后续拼接的 @x-y-w-ha.jpg 区域裁切参数必须保留在 url 参数内）
                replaced = base + android.net.Uri.encode(u).replace("%40", "@");
            } else {
                int slash = u.indexOf('/', 7);
                String path = slash >= 0 ? u.substring(slash) : "";
                String b = "https://" + base + path;
                String sep = b.contains("?") ? "&" : "?";
                replaced = b + sep + "image_process=format,png";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replaced));
        }
        if (!hit) return text;
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        if (headers == null) return false;
        for (String k : headers.keySet()) {
            if (k != null && k.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(共" + s.length() + "字符)";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
