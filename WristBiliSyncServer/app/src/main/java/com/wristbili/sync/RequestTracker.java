package com.wristbili.sync;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 请求跟踪器：记录每次手环 fetch 请求的完整生命周期（调试面板"请求详情"页数据源）。
 */
public final class RequestTracker {
    public static final class Record {
        public final long time;
        public String id = "";
        public String method = "GET";
        public String url = "";
        public String state = "pending";     // pending / ok / err / direct
        public int httpStatus = 0;
        public int bizCode = 0;              // B 站业务码（如 0 / -400 / -101），0=未知
        public long bytes = 0;
        public long costMs = 0;
        public String detail = "";           // 响应预览（JSON 前若干字符）
        public String transport = "interconnect";  // interconnect=走网桥转发 / fetch=手环端直连

        public Record() {
            time = System.currentTimeMillis();
        }

        public String label() {
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            String tag = "fetch".equals(transport) ? "[fetch] " : "[互联] ";
            return tag + "[" + tf.format(new Date(time)) + "] " + method + " " + state
                    + (httpStatus > 0 ? " HTTP" + httpStatus : "")
                    + (bizCode != 0 ? " code=" + bizCode : "")
                    + " " + bytes + "B " + costMs + "ms\n" + truncate(url, 70);
        }
    }

    private static final int MAX = 200;
    private static final List<Record> records = new CopyOnWriteArrayList<>();
    private static volatile Record current = null;

    private RequestTracker() {
    }

    public static synchronized Record begin(String id, String method, String url) {
        Record r = new Record();
        r.id = id;
        r.method = method == null ? "GET" : method.toUpperCase();
        r.url = url == null ? "" : url;
        records.add(r);
        while (records.size() > MAX) records.remove(0);
        current = r;
        return r;
    }

    /** 手环端直连 fetch 上报（未走网桥转发）：只记录发生了直连，无响应数据 */
    public static synchronized Record beginDirect(String method, String url) {
        Record r = new Record();
        r.id = "direct-" + System.currentTimeMillis();
        r.method = method == null ? "GET" : method.toUpperCase();
        r.url = url == null ? "" : url;
        r.state = "direct";
        r.transport = "fetch";
        r.detail = "手环端直连 fetch（未走网桥转发）";
        records.add(r);
        while (records.size() > MAX) records.remove(0);
        return r;
    }

    public static synchronized void finish(Record r, int httpStatus, long bytes, String detail) {
        if (r == null) return;
        r.state = httpStatus >= 200 && httpStatus < 600 ? "ok" : "err";
        r.httpStatus = httpStatus;
        r.bytes = bytes;
        r.detail = truncate(detail == null ? "" : detail, 4000);
        r.costMs = System.currentTimeMillis() - r.time;
    }

    public static synchronized void fail(Record r, String detail) {
        if (r == null) return;
        r.state = "err";
        r.detail = truncate(detail == null ? "" : detail, 4000);
        r.costMs = System.currentTimeMillis() - r.time;
    }

    public static List<Record> all() {
        return new CopyOnWriteArrayList<>(records);
    }

    public static void clear() {
        records.clear();
        current = null;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
}
