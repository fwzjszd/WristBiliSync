package com.wristbili.sync;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 登录信息存储（手机端持久化，工具页"登录信息"栏展示）：
 *  - 扫码登录确认后由 TvLogin 写入：Cookie / UID / access_token / refresh_token / 登录时间
 *  - 随后用 Cookie 请求 nav 接口获取用户信息（昵称/UID/等级/硬币/头像），按手环 My 页字段展示
 */
public final class LoginStore {

    private static final String PREFS = "bili_login";
    private static final String K_COOKIE = "cookie";
    private static final String K_MID = "mid";
    private static final String K_ACCESS_TOKEN = "access_token";
    private static final String K_REFRESH_TOKEN = "refresh_token";
    private static final String K_LOGIN_TS = "login_ts";
    private static final String K_UNAME = "uname";
    private static final String K_LEVEL = "level";
    private static final String K_COINS = "coins";
    private static final String K_FACE = "face";
    private static final String K_USER_TS = "user_ts";

    private LoginStore() {
    }

    private static SharedPreferences sp() {
        return WearClient.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn() {
        String c = sp().getString(K_COOKIE, "");
        return c != null && !c.isEmpty();
    }

    /** 扫码确认后保存登录凭证（web 轮询响应里无 mid/access_token 时为 ""，由 nav 用户信息补齐 UID） */
    public static void saveLogin(String cookie, String mid, String accessToken, String refreshToken) {
        sp().edit()
                .putString(K_COOKIE, cookie == null ? "" : cookie)
                .putString(K_MID, mid == null ? "" : mid)
                .putString(K_ACCESS_TOKEN, accessToken == null ? "" : accessToken)
                .putString(K_REFRESH_TOKEN, refreshToken == null ? "" : refreshToken)
                .putLong(K_LOGIN_TS, System.currentTimeMillis())
                .apply();
    }

    /** nav 接口返回的用户信息（按手环 My 页字段：昵称/UID/等级/硬币/头像） */
    public static void saveUserInfo(String uname, String mid, String level, String coins, String face) {
        sp().edit()
                .putString(K_UNAME, uname == null ? "" : uname)
                .putString(K_MID, mid == null ? "" : mid)
                .putString(K_LEVEL, level == null ? "" : level)
                .putString(K_COINS, coins == null ? "" : coins)
                .putString(K_FACE, face == null ? "" : face)
                .putLong(K_USER_TS, System.currentTimeMillis())
                .apply();
    }

    public static void clear() {
        sp().edit().clear().apply();
    }

    public static String cookie() {
        return sp().getString(K_COOKIE, "");
    }

    /** 从登录 Cookie 提取 bili_jct（写操作 CSRF，手环端已不再持有，由手机端补齐） */
    public static String biliJct() {
        String c = cookie();
        if (c == null || c.isEmpty()) return "";
        for (String part : c.split(";")) {
            String p = part.trim();
            if (p.startsWith("bili_jct=")) return p.substring("bili_jct=".length()).trim();
        }
        return "";
    }

    /** 工具页展示文本：登录凭证在上、用户信息（手环格式）在下 */
    public static String summary() {
        SharedPreferences p = sp();
        StringBuilder sb = new StringBuilder();
        String cookie = p.getString(K_COOKIE, "");
        if (cookie == null || cookie.isEmpty()) {
            sb.append("登录状态: 未登录\n");
            sb.append("提示: 手环 My 页扫码登录成功后，凭证会自动存储并显示在这里。");
            return sb.toString();
        }
        long loginTs = p.getLong(K_LOGIN_TS, 0);
        sb.append("登录状态: 已登录");
        if (loginTs > 0) sb.append("  （").append(fmt(loginTs)).append("）");
        sb.append('\n');
        String mid = p.getString(K_MID, "");
        if (!mid.isEmpty()) sb.append("UID: ").append(mid).append('\n');
        sb.append("Cookie: ").append(cookie).append('\n');
        String at = p.getString(K_ACCESS_TOKEN, "");
        if (!at.isEmpty()) sb.append("access_token: ").append(at).append('\n');
        String rt = p.getString(K_REFRESH_TOKEN, "");
        if (!rt.isEmpty()) sb.append("refresh_token: ").append(rt).append('\n');

        String uname = p.getString(K_UNAME, "");
        if (!uname.isEmpty() || !mid.isEmpty()) {
            sb.append('\n').append("—— 用户信息（手环格式） ——\n");
            if (!uname.isEmpty()) sb.append("昵称: ").append(uname).append('\n');
            if (!mid.isEmpty()) sb.append("UID: ").append(mid).append('\n');
            String level = p.getString(K_LEVEL, "");
            if (!level.isEmpty()) sb.append("等级: ").append(level).append('\n');
            String coins = p.getString(K_COINS, "");
            if (!coins.isEmpty()) sb.append("硬币: ").append(coins).append('\n');
            String face = p.getString(K_FACE, "");
            if (!face.isEmpty()) sb.append("头像: ").append(face).append('\n');
            long userTs = p.getLong(K_USER_TS, 0);
            if (userTs > 0) sb.append("用户信息更新: ").append(fmt(userTs)).append('\n');
        } else {
            sb.append('\n').append("用户信息: 待获取（点右上\"刷新\"用 Cookie 请求 nav）\n");
        }
        return sb.toString();
    }

    private static String fmt(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ts));
    }
}
