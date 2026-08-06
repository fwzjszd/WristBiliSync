package com.wristbili.sync;

import android.net.Uri;

import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * B 站 web 接口 wbi 签名（首页个性化推荐 rcmd 等接口强制要求，否则被风控返回 -352/-403）。
 *
 * 签名流程（官方 web 端算法）：
 *  1. 从 nav 接口获取 wbi_img（img_url/sub_url）→ 提取 img_key / sub_key
 *  2. mixin_key = (img_key + sub_key) 按 MIXIN_KEY_ENC_TAB 置换取前 32 字符
 *  3. 请求全部参数（过滤 !'()* 字符）按 key 排序 + 追加 wts 时间戳
 *  4. w_rid = md5(排序后 query + mixin_key)
 */
public final class WbiSigner {

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /** B 站公开的 mixin key 混淆表 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static volatile String imgKey = null;
    private static volatile String subKey = null;
    private static volatile long keysFetchedAt = 0;
    private static final long KEY_TTL_MS = 6 * 3600 * 1000L; // 6 小时缓存

    private WbiSigner() {
    }

    /** 获取 wbi 密钥（nav 接口，带登录 Cookie；缓存 6 小时） */
    private static synchronized boolean ensureKeys() {
        long now = System.currentTimeMillis();
        if (imgKey != null && subKey != null && now - keysFetchedAt < KEY_TTL_MS) return true;
        try {
            Request req = new Request.Builder()
                    .url(NAV_URL)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", LoginStore.cookie())
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String text = resp.body() != null ? resp.body().string() : "";
                String img = extractKey(text, "img_url");
                String sub = extractKey(text, "sub_url");
                if (img != null && sub != null) {
                    imgKey = img;
                    subKey = sub;
                    keysFetchedAt = now;
                    SyncLog.i("[wbi] 密钥获取成功 img_key=" + img + " sub_key=" + sub);
                    return true;
                }
            }
        } catch (Exception e) {
            SyncLog.i("[wbi] 密钥获取失败: " + e);
        }
        return imgKey != null && subKey != null;
    }

    /** 从 nav 响应文本提取 wbi 图 URL 的文件名（img_key） */
    private static String extractKey(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) return null;
        i += marker.length();
        int end = json.indexOf('"', i);
        if (end < 0) return null;
        String url = json.substring(i, end);
        // https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 对已组装的 URL 追加 wts + w_rid 签名参数；密钥不可用时原样返回（走候选兜底） */
    public static String sign(String url) {
        if (url == null || url.isEmpty()) return url;
        if (!ensureKeys()) return url;

        Map<String, String> params = new TreeMap<>();
        int q = url.indexOf('?');
        String base = url;
        if (q >= 0) {
            base = url.substring(0, q);
            for (String pair : url.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = Uri.decode(pair.substring(0, eq));
                    String v = Uri.decode(pair.substring(eq + 1));
                    if (!k.isEmpty()) params.put(k, v);
                }
            }
        }

        // B 站规则：过滤 !'()* 字符，空值不参与签名
        Map<String, String> filtered = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue().replaceAll("[!'()*]", "");
            if (!v.isEmpty()) filtered.put(e.getKey(), v);
        }

        long wts = System.currentTimeMillis() / 1000;
        filtered.put("wts", String.valueOf(wts));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : filtered.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        String mixin = mixinKey(imgKey, subKey);
        String wRid = md5(sb.toString() + mixin);
        return base + "?" + sb + "&w_rid=" + wRid;
    }

    private static String mixinKey(String img, String sub) {
        String s = img + sub;
        StringBuilder sb = new StringBuilder();
        for (int i : MIXIN_KEY_ENC_TAB) {
            if (i < s.length()) {
                sb.append(s.charAt(i));
                if (sb.length() >= 32) break;
            }
        }
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
