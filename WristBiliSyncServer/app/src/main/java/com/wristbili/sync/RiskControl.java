package com.wristbili.sync;

/**
 * 风控封锁监控器（log36）：
 * 检测 B 站响应中的风控业务码，维护"封锁窗口"状态（首次命中记时，窗口内持续提示），
 * 供主界面/调试面板展示当前是否被风控、剩余窗口、触发接口与处置建议。
 * 风控码：
 *   -412  请求被拦截（IP/设备/频率风控，最常命中 rcmd 个性化推荐）
 *   -352  wbi 签名校验失败（签名 key 过期/缺失）
 *   -403  禁止访问（账号/接口权限风控）
 * 说明：-101 是"未登录"（会话失效）而非风控，由登录信息区提示，不进入封锁窗口。
 */
public final class RiskControl {

    /** 封锁窗口时长：命中风控码后视为"封锁期"（毫秒），B 站临时风控一般 30 分钟~数小时 */
    public static final long BLOCK_WINDOW_MS = 30 * 60 * 1000L; // 30 分钟

    private static final int[] RISK_CODES = {-412, -352, -403};

    private static volatile boolean blocked = false;
    private static volatile long blockedAt = 0;
    private static volatile int lastCode = 0;
    private static volatile String lastAction = "";
    private static volatile String lastUrl = "";
    private static volatile int hitCount = 0;

    private RiskControl() {
    }

    /** 业务码回调（请求完成时调用）：命中风控码则进入/延续封锁窗口 */
    public static synchronized void onBizCode(int code, String action, String url) {
        if (code == 0) return;
        boolean risk = false;
        for (int c : RISK_CODES) {
            if (c == code) {
                risk = true;
                break;
            }
        }
        if (!risk) return;
        if (!blocked) {
            blocked = true;
            blockedAt = System.currentTimeMillis();
            hitCount = 0;
            SyncLog.i("[风控] 检测到 code=" + code + (action == null || action.isEmpty() ? "" : " action=" + action)
                    + "，进入封锁窗口（" + (BLOCK_WINDOW_MS / 60000) + " 分钟）");
        }
        lastCode = code;
        if (action != null && !action.isEmpty()) lastAction = action;
        if (url != null && !url.isEmpty()) lastUrl = url;
        hitCount++;
    }

    /** 是否处于封锁窗口内 */
    public static boolean isBlocked() {
        if (!blocked) return false;
        if (System.currentTimeMillis() - blockedAt > BLOCK_WINDOW_MS) {
            blocked = false; // 窗口过期自动解除
            lastCode = 0;
            hitCount = 0;
            return false;
        }
        return true;
    }

    /** 剩余封锁窗口（毫秒），非封锁期为 0 */
    public static long remainingMs() {
        if (!isBlocked()) return 0;
        long r = BLOCK_WINDOW_MS - (System.currentTimeMillis() - blockedAt);
        return Math.max(0, r);
    }

    /** 手动清除封锁状态（用户确认已恢复/重新扫码后调用） */
    public static synchronized void reset() {
        blocked = false;
        blockedAt = 0;
        lastCode = 0;
        lastAction = "";
        lastUrl = "";
        hitCount = 0;
        SyncLog.i("[风控] 用户手动清除风控状态");
    }

    public static int lastCode() {
        return lastCode;
    }

    public static String lastAction() {
        return lastAction;
    }

    public static String lastUrl() {
        return lastUrl;
    }

    public static int hitCount() {
        return hitCount;
    }

    /** 展示文本（调试面板"风控状态"卡） */
    public static String summary() {
        if (!isBlocked()) return "风控：无";
        long rem = remainingMs() / 1000;
        long min = rem / 60, sec = rem % 60;
        StringBuilder sb = new StringBuilder();
        sb.append("风控拦截中  code=").append(lastCode);
        if (!lastAction.isEmpty()) sb.append("  接口:").append(lastAction);
        sb.append('\n');
        sb.append("剩余窗口: ").append(min).append("分").append(sec).append("秒（命中 ").append(hitCount).append(" 次）");
        if (!lastUrl.isEmpty()) sb.append('\n').append("URL: ").append(lastUrl);
        sb.append('\n').append("建议: 等待窗口结束 / 切换网络(WiFi↔流量) / 重新扫码登录");
        return sb.toString();
    }

    /** 单行摘要（主界面状态行） */
    public static String shortText() {
        if (!isBlocked()) return "无";
        return "code=" + lastCode + " 剩余" + (remainingMs() / 60000) + "分钟";
    }
}
