package com.wristbili.sync;

import android.net.Uri;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Web OAuth 二维码登录（标准网页版扫码登录流程，手机端驱动）：
 *  1. 手环发 showqr_req → 手机向 passport.bilibili.com/x/passport-login/web/qrcode/generate 申请二维码
 *     （必须带合法 UA + Referer 头）→ 拿到 qrcode_key + 二维码内容
 *  2. 手机把二维码内容通过 showqr_resp 回传手环，手环用官方 qrcode 组件显示
 *  3. 手机端拿 qrcode_key 每 2.5 秒轮询 x/passport-login/web/qrcode/poll（等待扫码/已扫码/已过期）
 *  4. 轮询返回"已确认"（data.url 非空）→ 从 SSO URL 提取 SESSDATA/bili_jct/DedeUserID 等 Cookie
 *     → 通过 showqr_done 下发手环入库；手环后续所有请求自动带这些 Cookie（个性化推荐/登录态）
 */
public final class TvLogin {

    public interface QrCallback {
        /** 成功：qrUrl + authCode（authCode=web 流程的 qrcode_key） */
        void onSuccess(String qrUrl, String authCode);

        /** 失败：bizCode 为 B 站业务码（如 -400），message 为描述 */
        void onFailure(int bizCode, String message);
    }

    private static final String GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final long POLL_INTERVAL_MS = 2500;
    private static final int POLL_MAX_TRIES = 120; // 约 5 分钟

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private static volatile ScheduledExecutorService pollScheduler = null;
    private static volatile ScheduledFuture<?> pollTask = null;
    /** 最近一次下发的轮询状态文案（变化才下发，避免刷屏） */
    private static volatile String lastStatus = "";

    private TvLogin() {
    }

    /** 手机端向 passport 申请 Web 登录二维码（qrcode_key 流程，与网页版扫码一致） */
    public static void requestQr(final QrCallback cb) {
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url(GENERATE_URL)
                        .header("User-Agent", UA)
                        .header("Referer", REFERER)
                        .build();
                SyncLog.i("[登录] 手机请求 Web 二维码: " + GENERATE_URL);
                try (Response resp = client.newCall(request).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        SyncLog.i("[登录] generate 接口 HTTP " + resp.code() + ": " + truncate(text, 200));
                        cb.onFailure(resp.code(), "HTTP " + resp.code());
                        return;
                    }
                    SyncLog.i("[登录] generate 响应: " + truncate(text, 300));
                    JsonObject json;
                    try {
                        json = JsonParser.parseString(text).getAsJsonObject();
                    } catch (Exception e) {
                        cb.onFailure(-1, "响应非 JSON");
                        return;
                    }
                    int code = json.has("code") ? json.get("code").getAsInt() : -1;
                    if (code != 0 || !json.has("data") || !json.get("data").isJsonObject()) {
                        String msg = json.has("message") ? json.get("message").getAsString() : "code=" + code;
                        cb.onFailure(code, msg);
                        return;
                    }
                    JsonObject data = json.getAsJsonObject("data");
                    String url = data.has("url") ? data.get("url").getAsString() : "";
                    String qrcodeKey = data.has("qrcode_key") ? data.get("qrcode_key").getAsString() : "";
                    if (url.isEmpty() || qrcodeKey.isEmpty()) {
                        cb.onFailure(code, "响应缺 url/qrcode_key");
                        return;
                    }
                    SyncLog.i("[登录] Web 二维码获取成功 qrcode_key=" + qrcodeKey + " url长度=" + url.length());
                    cb.onSuccess(url, qrcodeKey);
                }
            } catch (Exception e) {
                SyncLog.i("[登录] 请求二维码异常: " + e);
                cb.onFailure(-1, e.getMessage() == null ? "网络异常" : e.getMessage());
            }
        }).start();
    }

    /** 手机端开始轮询登录状态（成功/过期/超时自动停止；新请求会先取消旧轮询） */
    public static void startPolling(final String nodeId, final String qrcodeKey) {
        cancelPolling();
        lastStatus = "";
        if (pollScheduler == null) {
            pollScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        final int[] tries = {0};
        pollTask = pollScheduler.scheduleWithFixedDelay(
                () -> pollOnce(nodeId, qrcodeKey, tries),
                POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        SyncLog.i("[登录] 开始轮询 qrcode_key=" + qrcodeKey + " 每 " + POLL_INTERVAL_MS + "ms");
    }

    public static void cancelPolling() {
        if (pollTask != null) {
            pollTask.cancel(true);
            pollTask = null;
        }
    }

    private static void pollOnce(String nodeId, String qrcodeKey, int[] tries) {
        tries[0]++;
        if (tries[0] > POLL_MAX_TRIES) {
            SyncLog.i("[登录] 轮询超时（" + POLL_MAX_TRIES + " 次），停止");
            sendStatus(nodeId, "二维码已过期，请重新登录");
            cancelPolling();
            return;
        }
        try {
            String url = POLL_URL + "?qrcode_key=" + Uri.encode(qrcodeKey);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                String text = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    SyncLog.i("[登录] poll HTTP " + resp.code() + ": " + truncate(text, 200));
                    return;
                }
                JsonObject json;
                try {
                    json = JsonParser.parseString(text).getAsJsonObject();
                } catch (Exception e) {
                    return;
                }
                int code = json.has("code") ? json.get("code").getAsInt() : -1;
                if (code == 0 && json.has("data") && json.get("data").isJsonObject()) {
                    JsonObject data = json.getAsJsonObject("data");
                    String ssoUrl = data.has("url") ? data.get("url").getAsString() : "";
                    if (!ssoUrl.isEmpty()) {
                        // 已确认：提取 Cookie 并下发手环，手环入库后所有请求自动带登录态
                        SyncLog.i("[登录] poll 确认 data.url=" + truncate(ssoUrl, 200));
                        String cookie = ensureBuvid(sanitizeCookie(extractCookies(ssoUrl)));
                        SyncLog.i("[登录] 提取 Cookie(裁剪后 " + cookie.length() + " 字符): " + truncate(cookie, 200));
                        String mid = data.has("mid") && !data.get("mid").isJsonNull() ? String.valueOf(data.get("mid").getAsLong()) : "";
                        String refreshToken = data.has("refresh_token") && !data.get("refresh_token").isJsonNull() ? data.get("refresh_token").getAsString() : "";
                        String accessToken = data.has("access_token") && !data.get("access_token").isJsonNull() ? data.get("access_token").getAsString() : "";
                        // 手机端持久化登录凭证（工具页展示）+ 用 Cookie 请求 nav 获取用户信息
                        LoginStore.saveLogin(cookie, mid, accessToken, refreshToken);
                        fetchUserInfo(cookie);
                        // 登录凭证只留在手机端：showqr_done 仅下发确认与 UID，手环不再持有 Cookie
                        // （手环后续请求由手机端 HttpRelay 转发时统一附加登录态）
                        JsonObject out = new JsonObject();
                        out.addProperty("tag", "showqr_done");
                        out.addProperty("code", 0);
                        if (!mid.isEmpty()) {
                            out.addProperty("mid", Long.parseLong(mid));
                        }
                        SyncLog.p("[同步器→手表]", "[登录] 扫码确认成功，下发登录确认（Cookie 仅存手机端）");
                        sendRaw(nodeId, out);
                        cancelPolling();
                        return;
                    }
                }
                // 状态文案：仅变化时下发
                if (code == 86101) {
                    sendStatus(nodeId, "等待扫码...");
                } else if (code == 86090) {
                    sendStatus(nodeId, "已扫码，请在手机确认");
                } else if (code == 86038) {
                    sendStatus(nodeId, "二维码已过期，请重新登录");
                    cancelPolling();
                } else if (code != 0) {
                    SyncLog.i("[登录] poll code=" + code + " 继续轮询");
                }
            }
        } catch (Exception e) {
            SyncLog.i("[登录] 轮询异常: " + e);
        }
    }

    private static void sendStatus(String nodeId, String text) {
        if (text != null && text.equals(lastStatus)) return;
        lastStatus = text == null ? "" : text;
        JsonObject out = new JsonObject();
        out.addProperty("tag", "showqr_status");
        out.addProperty("text", text);
        sendRaw(nodeId, out);
    }

    private static void sendRaw(String nodeId, JsonObject obj) {
        if (nodeId == null || nodeId.isEmpty()) return;
        FetchBridgeServer.sendRaw(nodeId, obj.toString());
    }

    /**
     * 提取登录 Cookie（SESSDATA/bili_jct/DedeUserID 等）：
     *  - 已登录账号扫码时 B 站返回的是 ticket 式 SSO URL，真实会话 Cookie 在**跟随该 URL 的 Set-Cookie 头**里
     *  - 因此先收集所有重定向的 Set-Cookie（优先），再以 query 参数兜底（正常场景 SESSDATA 也在 query 里）
     */
    private static String extractCookies(String ssoUrl) {
        Map<String, String> cookies = new LinkedHashMap<>();
        // 1) query 参数（正常场景：DedeUserID/SESSDATA/bili_jct 直接在此）
        int q = ssoUrl.indexOf('?');
        if (q >= 0) {
            for (String pair : ssoUrl.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    if (!k.isEmpty() && !v.isEmpty()) cookies.put(k, Uri.decode(v));
                }
            }
        }
        // 2) 跟随 SSO URL，收集全部重定向（含最终响应）的 Set-Cookie（ticket 场景：真实会话 cookie 在这里）
        try {
            Request request = new Request.Builder()
                    .url(ssoUrl)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                // 旧版 OkHttp 无 Response.history()，改走 priorResponse 链（最早的重定向在前）
                List<Response> chain = new ArrayList<>();
                for (Response cur = resp; cur != null; cur = cur.priorResponse()) {
                    chain.add(cur);
                }
                Collections.reverse(chain);
                for (Response r : chain) {
                    for (String sc : r.headers("Set-Cookie")) {
                        int semi = sc.indexOf(';');
                        String pair = (semi > 0 ? sc.substring(0, semi) : sc).trim();
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            SyncLog.i("[登录] 跟随 SSO URL 获取 Cookie 失败: " + e);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    /** 手环请求 B 站 API 真正需要的核心 Cookie 字段（其余如 gourl/first_domain/ticket/PVID/b_lsid/fingerprint 等对 API 请求无用，且会让 showqr_done 消息过大） */
    private static final String[] COOKIE_KEEP = {
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
            "bili_ticket", "bili_ticket_expires",
            "buvid3", "buvid4", "_uuid", "b_nut", "sid"
    };
    /** 下发手环的 Cookie 总长度上限（远超此值会撑大 showqr_done 单条消息，超出互联通道承受范围导致手环闪退） */
    private static final int COOKIE_MAX_LEN = 1200;

    /** 只保留核心字段，控制下发给手环的 Cookie 大小（完整会话 Cookie 可达 2-4KB，互联通道单条消息承受不住） */
    private static String sanitizeCookie(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        Map<String, String> keep = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            if (v.isEmpty()) continue;
            for (String good : COOKIE_KEEP) {
                if (k.equals(good)) {
                    keep.put(k, v);
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : keep.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        String out = sb.toString();
        if (out.length() > COOKIE_MAX_LEN) out = out.substring(0, COOKIE_MAX_LEN);
        return out;
    }

    /** 补齐 B 站 API 风控要求的设备指纹：cookie 缺 buvid3/buvid4 时生成并追加（否则部分接口返回 HTTP 412/非 JSON，手环判为请求失败） */
    private static String ensureBuvid(String cookie) {
        if (cookie == null || cookie.isEmpty()) return cookie;
        if (!cookie.contains("buvid3=")) {
            cookie += "; buvid3=" + UUID.randomUUID().toString().replace("-", "");
        }
        if (!cookie.contains("buvid4=")) {
            StringBuilder sb = new StringBuilder();
            java.util.Random r = new java.util.Random();
            for (int i = 0; i < 8; i++) {
                if (i > 0) sb.append('-');
                sb.append(String.format("%04X", r.nextInt(0x10000)));
            }
            cookie += "; buvid4=" + sb.toString();
        }
        return cookie;
    }

    /** 工具页"刷新用户信息"：用已存储的 Cookie 重新请求 nav */
    public static void refreshUserInfo() {
        String cookie = LoginStore.cookie();
        if (cookie == null || cookie.isEmpty()) return;
        SyncLog.i("[登录] 手动刷新用户信息");
        fetchUserInfo(cookie);
    }

    /** 用登录 Cookie 请求 nav 获取用户信息（按手环格式存储到 LoginStore），后台线程不阻塞主流程 */
    private static void fetchUserInfo(final String cookie) {
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url("https://api.bilibili.com/x/web-interface/nav")
                        .header("User-Agent", UA)
                        .header("Referer", REFERER)
                        .header("Cookie", cookie)
                        .build();
                try (Response resp = client.newCall(request).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    if (json.has("code") && json.get("code").getAsInt() == 0
                            && json.has("data") && json.get("data").isJsonObject()) {
                        JsonObject d = json.getAsJsonObject("data");
                        if (d.has("isLogin") && !d.get("isLogin").isJsonNull() && d.get("isLogin").getAsBoolean()) {
                            String uname = d.has("uname") && !d.get("uname").isJsonNull() ? d.get("uname").getAsString() : "";
                            String mid = d.has("mid") && !d.get("mid").isJsonNull() ? String.valueOf(d.get("mid").getAsLong()) : "";
                            int lv = 0;
                            if (d.has("level_info") && d.get("level_info").isJsonObject()
                                    && d.getAsJsonObject("level_info").has("current_level")
                                    && !d.getAsJsonObject("level_info").get("current_level").isJsonNull()) {
                                lv = d.getAsJsonObject("level_info").get("current_level").getAsInt();
                            }
                            String coins = d.has("coins") && !d.get("coins").isJsonNull() ? String.valueOf(d.get("coins").getAsDouble()) : "";
                            String face = d.has("face") && !d.get("face").isJsonNull() ? d.get("face").getAsString() : "";
                            LoginStore.saveUserInfo(uname, mid, "LV" + lv, coins, face);
                            SyncLog.i("[登录] nav 用户信息: " + uname + " mid=" + mid + " LV" + lv + " 硬币=" + coins);
                        } else {
                            SyncLog.i("[登录] nav 未登录: " + truncate(text, 150));
                        }
                    } else {
                        SyncLog.i("[登录] nav 用户信息获取失败: " + truncate(text, 150));
                    }
                }
            } catch (Exception e) {
                SyncLog.i("[登录] nav 用户信息异常: " + e);
            }
        }).start();
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
}
