package com.wristbili.sync;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 线程安全日志存储。
 * 两个通道：
 *  - logs  ：普通运行日志（主界面 + 调试面板共用）
 *  - proto ：协议原始消息（仅调试面板"协议日志"页，带方向标记）
 */
public final class SyncLog {
    private static final int MAX = 4000;
    private static final List<String> logs = new CopyOnWriteArrayList<>();
    private static final List<String> proto = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TF = new SimpleDateFormat("HH:mm:ss", Locale.US);
    /** 日志变更版本号：任何写入/清空都会自增，UI 据此判断是否需要刷新（避免相等跳过导致清空后不更新） */
    public static volatile long version = 0;

    private SyncLog() {
    }

    public static String now() {
        return TF.format(new Date());
    }

    /** 普通日志 */
    public static void i(String msg) {
        String line = "[" + now() + "] " + msg;
        logs.add(line);
        trim(logs);
        version++;
    }

    /** 协议原始消息（双向记录：日志 + 协议通道） */
    public static void p(String direction, String msg) {
        String line = "[" + now() + "] " + direction + " " + msg;
        logs.add(line);
        proto.add(line);
        trim(logs);
        trim(proto);
        version++;
    }

    private static void trim(List<String> list) {
        while (list.size() > MAX) list.remove(0);
    }

    /** 最近 N 条普通日志（用于 UI 展示） */
    public static List<String> recentLogs(int n) {
        int from = Math.max(0, logs.size() - n);
        return new CopyOnWriteArrayList<>(logs.subList(from, logs.size()));
    }

    /** 最近 N 条协议日志 */
    public static List<String> recentProto(int n) {
        int from = Math.max(0, proto.size() - n);
        return new CopyOnWriteArrayList<>(proto.subList(from, proto.size()));
    }

    public static void clearAll() {
        logs.clear();
        proto.clear();
        version++;
    }

    public static void clearProto() {
        proto.clear();
        version++;
    }
}
