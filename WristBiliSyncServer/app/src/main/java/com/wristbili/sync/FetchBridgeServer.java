package com.wristbili.sync;

import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 互联消息引擎（同步器核心）：
 *  1. WristBili FetchBridge v3 协议（__hs__ 握手 / fetch 请求 / fetch-chunk 分片 / fetch-ack 滑动窗口 ACK）
 *  2. 官方 HyperBili 信封协议（{id, message:{msgtype,message}}：FETCH / SHOWQR / HELLO）
 * 协议细节与手环端 fetchbridge.js 一一对应（见 WristBiliSyncServer 工程说明）。
 */
public final class FetchBridgeServer {

    private static final Gson gson = new Gson();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ===== FetchBridge v3 常量（与手环端 fetchbridge.js 一致）=====
    private static final int BRIDGE_VERSION = 3;
    private static final boolean BRIDGE_CHUNK_SUPPORTED = true;
    private static final int BRIDGE_MAX_CHUNK_SIZE = 4096;
    private static final String[] BRIDGE_ENCODINGS = {"text", "base64", "hex"};
    private static final String[] BRIDGE_COMPRESSIONS = {"none"};
    private static final boolean BRIDGE_ACK_SUPPORTED = true;
    private static final int BRIDGE_ACK_WINDOW = 4;
    /** 编码后单块直发上限（与手环端约定一致） */
    private static final int MAX_UNCHUNKED_WIRE_LEN = 16384;
    /** 分块发送的 ACK 等待超时兜底 */
    private static final long CHUNK_ACK_TIMEOUT_MS = 3000;

    private static volatile boolean bridgeHandshakeDone = false;
    private static volatile JsonObject bridgeNegotiated = null;

    // 互联诊断结果（手环端 connect.diagnosis() 上报，log38）：
    // status: 0=OK 204=连接超时 1001=对端应用未安装 1000=其他 -1=失败
    private static volatile int diagStatus = Integer.MIN_VALUE;
    private static volatile int diagCode = 0;
    private static volatile String diagMsg = "";
    private static volatile long diagAt = 0;

    // log49：心跳验活（10s ping，30s 无 pong 判定手环重启/断连 → 主动握手拉起）
    private static volatile long lastPongAt = 0;
    private static volatile ScheduledFuture<?> heartbeatTask = null;
    private static volatile int pongCount = 0;

    // ===== log49：图片 base64 分片下发（思路 @B4QAQ/@雪松）=====
    // 手环端 fetchbridge 收到 file://img_N 占位 → 等手机端分片下发 → @system.file 解码写 internal://files/img_N.png
    // → 全局就绪表 → 页面把占位替换为本地路径。单消息 ≤30KB，分片 20KB/片。
    /** B 站图片 URL 正则（与 HttpRelay 一致；保留 @ 缩略/雪碧图裁切参数，下载时一并请求省流量） */
    private static final Pattern BILI_IMG_URL = Pattern.compile(
            "(?:https?:)?//(?:[a-z0-9-]+\\.)*(?:hdslb\\.com|biliimg\\.com)[^\"'\\s,}]*");
    /** 图片占位 id 全局自增（纯数字，与手环端 img_ 前缀 + file://img_N 正则匹配） */
    private static final AtomicInteger imgIdCounter = new AtomicInteger(1);
    /** URL → imgId 去重缓存（翻页/重复加载同一响应时复用已保存的本地文件，减少带宽与手环负载） */
    private static final Map<String, Integer> imgUrlCache = new ConcurrentHashMap<>();
    /** 待处理图片队列（下载→分片下发串行，单线程图片分发器处理，天然限速防互联拥塞） */
    private static final ConcurrentLinkedQueue<ImgJob> imgQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean imgPumpRunning = new AtomicBoolean(false);
    /** 单线程图片分发器：下载+下发串行，避免并发打爆互联通道/手环 */
    private static final ScheduledExecutorService imgSender = Executors.newSingleThreadScheduledExecutor();
    /** 每张图片下发后休息间隔（让低配手环缓一缓，防 3 分钟内连续解码写文件重启） */
    private static final long IMG_INTERVAL_MS = 300;
    /** 不做图片占位替换的 action（含流媒体/雪碧图 URL，替换会破坏播放/帧图） */
    private static boolean isImageSkipAction(String action) {
        return "video_videoshot".equals(action)
                || "video_playurl".equals(action)
                || "video_page".equals(action);
    }

    /** 分块发送状态（滑动窗口 ACK 流控） */
    private static final Map<String, ChunkSendState> chunkSenders = new ConcurrentHashMap<>();

    private FetchBridgeServer() {
    }

    // ===================== 入口：收到手环消息 =====================

    public static void onMessage(String nodeId, String data) {
        SyncLog.p("[手表→同步器]", truncate(data, 2000));

        // 1) 先判 FetchBridge 协议（带 tag 字段）
        try {
            JsonObject json = JsonParser.parseString(data).getAsJsonObject();
            if (json.has("tag")) {
                handleBridgeMessage(nodeId, json);
                return;
            }
        } catch (Exception ignored) {
        }

        // 2) 官方 HyperBili 信封协议（{id, message:{msgtype, message}}）
        handleLegacyPacket(nodeId, data);
    }

    // ===================== FetchBridge v3 =====================

    private static void handleBridgeMessage(String nodeId, JsonObject msg) {
        String tag = optStr(msg, "tag", "");
        switch (tag) {
            case "__hs__":
                handleHandshake(nodeId, msg);
                break;
            case "fetch":
                handleBridgeFetch(nodeId, msg);
                break;
            case "fetch-chunk":
                // 同步器是响应端，不接收请求分块
                SyncLog.i("[网桥] 收到 fetch-chunk #" + optStr(msg, "id", "") + " seq=" + optInt(msg, "seq", -1) + "（同步器不做请求分块）");
                break;
            case "fetch-ack": {
                String ackId = optStr(msg, "id", "");
                int ack = optInt(msg, "ack", -1);
                SyncLog.i("[网桥] 收到 ACK #" + ackId + " ack=" + ack);
                ChunkSendState st = chunkSenders.get(ackId);
                if (st != null && !st.done) {
                    if (ack > st.ackBase) st.ackBase = ack;
                    sendNextChunks(st);
                }
                break;
            }
            case "logout":
                // 手环 Settings 退出登录通知：真实登录 Cookie 只存手机端，同步清除，避免继续注入旧 Cookie
                // （否则手环已退出登录，手机端仍以旧账号请求 → nav 仍返回已登录，状态错乱/换账号失效）
                LoginStore.clear();
                SyncLog.i("[登录] 收到手环退出登录通知，已清除手机端登录信息（LoginStore）");
                break;
            case "__pong__":
                // log49：心跳回应（手环端 fetchbridge 收到 __ping__ 自动回 __pong__）。
                // 10s 心跳验活：lastPongAt 超过 30s 未更新 → 判定手环重启/断连 → 主动握手拉起。
                lastPongAt = System.currentTimeMillis();
                if ((++pongCount % 30) == 1) {
                    SyncLog.i("[网桥] 心跳 pong 正常（每 5 分钟记录一次）");
                }
                break;
            case "__diag__":
                // 手环端 connect.diagnosis() 上报（log38）：0=OK 204=连接超时 1001=对端未安装 1000=其他
                diagStatus = msg.has("status") ? msg.get("status").getAsInt() : -1;
                diagCode = msg.has("code") ? msg.get("code").getAsInt() : 0;
                diagMsg = optStr(msg, "msg", "");
                diagAt = System.currentTimeMillis();
                SyncLog.i("[互联诊断] 手环上报 status=" + diagStatus
                        + (diagCode != 0 ? " code=" + diagCode : "")
                        + (diagMsg.isEmpty() ? "" : " msg=" + diagMsg));
                break;
            case "__transport__":
                // 手环端直连 fetch 上报（未走网桥）：记录到请求详情，供调试区分 interconnect/fetch
                SyncLog.i("[手表→同步器] 手环直连 fetch（未走网桥）: " + optStr(msg, "method", "GET") + " " + optStr(msg, "url", ""));
                RequestTracker.beginDirect(optStr(msg, "method", "GET"), optStr(msg, "url", ""));
                break;
            case "showqr_req":
                // 手环进入登录界面 → 手环发二维码请求 → 手机向 passport 申请 Web 二维码并回传，随后手机端轮询登录状态
                SyncLog.i("[网桥] 收到手环二维码请求 (showqr_req)，手机开始请求 Web 登录二维码");
                TvLogin.requestQr(new TvLogin.QrCallback() {
                    @Override
                    public void onSuccess(String qrUrl, String authCode) {
                        JsonObject out = new JsonObject();
                        out.addProperty("tag", "showqr_resp");
                        out.addProperty("code", 0);
                        out.addProperty("qrUrl", qrUrl);
                        out.addProperty("auth_code", authCode);
                        SyncLog.p("[同步器→手表]", "[登录] 返回二维码给手环 (qrcode_key=" + authCode + ")");
                        sendRaw(nodeId, gson.toJson(out));
                        // 手机端轮询登录状态：等待扫码/已扫码/已确认（确认后自动下发 Cookie 给手环）
                        TvLogin.startPolling(nodeId, authCode);
                    }

                    @Override
                    public void onFailure(int bizCode, String message) {
                        JsonObject out = new JsonObject();
                        out.addProperty("tag", "showqr_resp");
                        out.addProperty("code", bizCode);
                        out.addProperty("message", message == null ? "" : message);
                        SyncLog.p("[同步器→手表]", "[登录] 二维码获取失败 code=" + bizCode + " " + message);
                        sendRaw(nodeId, gson.toJson(out));
                    }
                });
                break;
            case "__status__":
                // 手环端网桥状态上报（诊断）
                SyncLog.i("[手表状态] " + msg.toString());
                break;
            default:
                SyncLog.i("[网桥] 未知 tag: " + tag + " -> " + truncate(msg.toString(), 500));
        }
    }

    /** 握手：count<2 回 count+1；count>=2 握手完成（不回包）。与手环端 handleHandshake 一致。 */
    private static void handleHandshake(String nodeId, JsonObject msg) {
        int count = optInt(msg, "count", 0);
        JsonElement peerCaps = msg.has("caps") ? msg.get("caps") : null;
        SyncLog.i("[网桥] 收到握手 count=" + count + (peerCaps != null ? ", caps=" + peerCaps.toString() : ", 无caps"));
        negotiateCaps(peerCaps);
        // 收到任意握手回复（count>=1）即视为完成，对齐手环端 count>=1 完成逻辑：
        // 手机主动握手（发 count=0）后只会收到手环的 count=1 回复，原 count>=2 判断在主动握手路径永不置位
        // → 手机端一直显示"未就绪"（手环端却已就绪）。count>=1 兼容两条握手路径（手环先发/手机先发）。
        if (count >= 1) {
            bridgeHandshakeDone = true;
            QrState.bridgeHandshakeDone = true;
            QrState.bridgeNegotiatedText = negotiatedText();
            SyncLog.i("[网桥] 握手完成! " + QrState.bridgeNegotiatedText);
            // log49：握手完成即启动 10s 心跳验活（手环重启后 pong 停 → 30s 主动握手拉起）
            lastPongAt = System.currentTimeMillis();
            startHeartbeat();
        }
        if (count < 2) {
            JsonObject reply = new JsonObject();
            reply.addProperty("tag", "__hs__");
            reply.addProperty("count", count + 1);
            reply.add("caps", buildCaps());
            SyncLog.p("[同步器→手表]", "[网桥] 回应握手 count=" + (count + 1));
            sendRaw(nodeId, gson.toJson(reply));
        }
    }

    /** 手机端主动发起握手（修复手环端卡在握手协商中的场景） */
    public static void sendBridgeHandshake() {
        if (WearClient.nodeId().isEmpty()) {
            SyncLog.i("[网桥] 无法主动握手：尚未连接节点");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("tag", "__hs__");
        msg.addProperty("count", 0);
        msg.add("caps", buildCaps());
        SyncLog.p("[同步器→手表]", "[网桥] 手机端主动发起握手 count=0");
        sendRaw(WearClient.nodeId(), gson.toJson(msg));
    }

    // ===================== log49：心跳验活 =====================

    /** 10s 心跳：ping 手环验活；30s 无 pong → 判定手环重启/断连 → 主动握手拉起（幂等，只启动一次） */
    private static synchronized void startHeartbeat() {
        if (heartbeatTask != null) return;
        lastPongAt = System.currentTimeMillis();
        heartbeatTask = scheduler.scheduleWithFixedDelay(() -> {
            if (WearClient.nodeId().isEmpty()) return;
            try {
                JsonObject ping = new JsonObject();
                ping.addProperty("tag", "__ping__");
                ping.addProperty("ts", System.currentTimeMillis());
                sendRaw(WearClient.nodeId(), gson.toJson(ping));
            } catch (Exception ignored) {
            }
            long now = System.currentTimeMillis();
            if (now - lastPongAt > 30000) {
                SyncLog.i("[网桥] 心跳超时（>30s 无 pong，手环可能重启/断连），主动握手拉起");
                sendBridgeHandshake();
                lastPongAt = now; // 防止拉起后未收到 pong 前连续触发
            }
        }, 10, 10, TimeUnit.SECONDS);
        SyncLog.i("[网桥] 心跳已启动（10s ping / 30s 超时拉起）");
    }

    // ===================== log49：图片 base64 分片下发 =====================

    /**
     * 把响应 JSON 中的**内容图字段**（封面/头像/动态图等）URL 替换为 file://img_N 占位
     * （手环端 fetchbridge 收到占位后等待手机端分片下发保存为本地文件再替换为 internal://files/img_N.png）。
     * 图标类字段（icon/badge/logo/vip_label 等非白名单字段）**不替换**，保持原 URL 直接显示——
     * base64 分片只用于必要的内容图，图标/小图不走该通道。
     * 图片下载与分片下发异步进行，不阻塞主响应。返回替换后的文本；无匹配返回原文本。
     */
    private static String replaceImageUrls(String text, String nodeId) {
        if (text == null || text.isEmpty()) return text;
        try {
            JsonElement el = JsonParser.parseString(text);
            JsonElement out = replaceImgInTree(el, nodeId);
            String outStr = out.toString();
            if (!outStr.equals(text)) {
                pumpImageSender();
                return outStr;
            }
            return text;
        } catch (Exception e) {
            // 非 JSON 文本：保守不替换，避免图片通道被滥用
            return text;
        }
    }

    /** 内容图字段白名单：只有这些字段的 URL 走 base64 分片（图标类字段名不在此列 → 保持原样直接显示） */
    private static final Set<String> IMG_CONTENT_FIELDS = new HashSet<>(Arrays.asList(
            "pic", "cover", "face", "avatar", "src", "url",
            "avatar_url", "user_face", "talker_face", "sub_pic",
            "keyframe", "cover_url", "image", "user_cover",
            "thumbnail", "bphoto", "preview", "poster"));

    private static JsonElement replaceImgInTree(JsonElement el, String nodeId) {
        if (el == null || el.isJsonNull()) return el;
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString();
                String r = replaceUrlInField(s, nodeId);
                if (!r.equals(s)) return new JsonPrimitive(r);
            }
            return el;
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            JsonArray out = new JsonArray();
            boolean changed = false;
            for (JsonElement e : arr) {
                JsonElement ne = replaceImgInTree(e, nodeId);
                if (ne != e) changed = true;
                out.add(ne);
            }
            return changed ? out : el;
        }
        JsonObject obj = el.getAsJsonObject();
        JsonObject out = new JsonObject();
        boolean changed = false;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && IMG_CONTENT_FIELDS.contains(k)) {
                String s = v.getAsString();
                String r = replaceUrlInField(s, nodeId);
                if (!r.equals(s)) {
                    v = new JsonPrimitive(r);
                    changed = true;
                }
            } else if (v != null && (v.isJsonObject() || v.isJsonArray())) {
                JsonElement nv = replaceImgInTree(v, nodeId);
                if (nv != v) {
                    v = nv;
                    changed = true;
                }
            }
            out.add(k, v);
        }
        return changed ? out : el;
    }

    /** 把字符串值内的 B 站图片 URL（hdslb/biliimg，排除 /bfs/wbi/）替换为 file://img_N 占位并入队下载 */
    private static String replaceUrlInField(String s, String nodeId) {
        if (s == null || s.isEmpty() || s.indexOf("hdslb.com") < 0 && s.indexOf("biliimg.com") < 0) return s;
        Matcher m = BILI_IMG_URL.matcher(s);
        StringBuilder sb = new StringBuilder();
        boolean hit = false;
        while (m.find()) {
            String u = m.group();
            // wbi 密钥图（/bfs/wbi/）不替换：手机端 WbiSigner 从原始 URL 提取 img_key/sub_key，需保留原始格式
            if (u.contains("/bfs/wbi/")) continue;
            Integer cached = imgUrlCache.get(u);
            int id;
            if (cached != null) {
                id = cached;
            } else {
                id = imgIdCounter.getAndIncrement();
                Integer prev = imgUrlCache.putIfAbsent(u, id);
                if (prev != null) {
                    id = prev;
                } else {
                    imgQueue.add(new ImgJob(nodeId, u, id));
                }
            }
            hit = true;
            m.appendReplacement(sb, Matcher.quoteReplacement("file://img_" + id));
        }
        if (!hit) return s;
        m.appendTail(sb);
        // 防内存膨胀：URL 缓存超过 500 条清空（仅影响去重，重新加载会再次下载）
        if (imgUrlCache.size() > 500) imgUrlCache.clear();
        return sb.toString();
    }

    /** 图片分发器：串行处理队列（下载→分片下发→休息），单线程天然限速，防互联拥塞/手环过载 */
    private static void pumpImageSender() {
        if (!imgPumpRunning.compareAndSet(false, true)) return;
        imgSender.submit(() -> {
            try {
                while (true) {
                    ImgJob job = imgQueue.poll();
                    if (job == null) break;
                    handleImgJob(job);
                    try {
                        Thread.sleep(IMG_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
            } catch (Exception e) {
                SyncLog.i("[图片] 分发异常: " + e);
            } finally {
                imgPumpRunning.set(false);
                if (!imgQueue.isEmpty()) pumpImageSender(); // 竞态兜底：处理期间又有新任务
            }
        });
    }

    /** 单张图片：下载 → 转 PNG ≤20KB → base64 → 分片下发；失败兜底 1x1 透明 PNG（保证占位必被替换） */
    private static void handleImgJob(ImgJob job) {
        String b64 = ImageTransfer.downloadAsPngBase64(job.url);
        if (b64 == null) {
            SyncLog.i("[图片] 下载/转码失败 #" + job.id + " " + truncate(job.url, 120) + "（兜底 1x1）");
            b64 = ImageTransfer.FALLBACK_PNG_B64;
        }
        sendImageChunks(job.nodeId, job.id, b64);
        SyncLog.i("[图片] #" + job.id + " 已下发 base64 " + b64.length() + " 字符, 共 "
                + (b64.length() / ImageTransfer.CHUNK_LEN + 1) + " 片");
    }

    /** 分片下发：img_begin（告知片数）+ 逐片 img_chunk（≤20KB/片，单消息 <30KB 互联上限） */
    private static void sendImageChunks(String nodeId, int id, String b64) {
        try {
            List<String> parts = ImageTransfer.splitBase64(b64);
            JsonObject begin = new JsonObject();
            begin.addProperty("tag", "img_begin");
            begin.addProperty("id", id);
            begin.addProperty("total", parts.size());
            sendRaw(nodeId, gson.toJson(begin));
            for (int i = 0; i < parts.size(); i++) {
                JsonObject chunk = new JsonObject();
                chunk.addProperty("tag", "img_chunk");
                chunk.addProperty("id", id);
                chunk.addProperty("seq", i);
                chunk.addProperty("total", parts.size());
                chunk.addProperty("data", parts.get(i));
                sendRaw(nodeId, gson.toJson(chunk));
            }
        } catch (Exception e) {
            SyncLog.i("[图片] 分片下发异常 #" + id + ": " + e);
        }
    }

    /** 图片任务（nodeId=目标手环节点，url=源图，id=全局唯一占位 id） */
    private static final class ImgJob {
        final String nodeId;
        final String url;
        final int id;

        ImgJob(String nodeId, String url, int id) {
            this.nodeId = nodeId;
            this.url = url;
            this.id = id;
        }
    }

    // ===================== log49b：传输进度信号 =====================

    /** 请求处理中信号：手环端收到后确认连接正常并重置该请求超时（慢响应不误判网络错误） */
    private static void sendProgress(String nodeId, String id) {
        if (nodeId == null || nodeId.isEmpty() || id == null || id.isEmpty()) return;
        try {
            JsonObject p = new JsonObject();
            p.addProperty("tag", "fetch-progress");
            p.addProperty("id", id);
            p.addProperty("ts", System.currentTimeMillis());
            sendRaw(nodeId, gson.toJson(p));
        } catch (Exception ignored) {
        }
    }

    /** 请求手环执行官方 connect.diagnosis() 并上报结果（log38，环境自检触发） */
    public static void requestInterconnectDiag() {
        if (WearClient.nodeId().isEmpty()) {
            SyncLog.i("[互联诊断] 无法请求：尚未连接节点");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("tag", "__diag_req__");
        msg.addProperty("id", "diagreq-" + System.currentTimeMillis());
        SyncLog.p("[同步器→手表]", "[互联诊断] 请求手环执行 connect.diagnosis()");
        sendRaw(WearClient.nodeId(), gson.toJson(msg));
    }

    /** 互联诊断结果展示文本（调试面板"环境自检"区） */
    public static String diagSummary() {
        if (diagAt == 0) return "互联诊断: 未执行（点\"环境自检\"触发）";
        String statusText;
        switch (diagStatus) {
            case 0:
                statusText = "连接OK";
                break;
            case 204:
                statusText = "连接超时(CONNECT_TIMEOUT)";
                break;
            case 1001:
                statusText = "对端应用未安装(APP_UNINSTALLED)";
                break;
            case 1000:
                statusText = "其他连接错误(OTHERS)";
                break;
            default:
                statusText = "诊断失败(-1)";
                break;
        }
        StringBuilder sb = new StringBuilder("互联诊断: ").append(statusText);
        if (diagCode != 0) sb.append(" code=").append(diagCode);
        if (!diagMsg.isEmpty()) sb.append(" msg=").append(diagMsg);
        sb.append("（").append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(diagAt))).append("）");
        return sb.toString();
    }

    private static JsonObject buildCaps() {
        JsonObject caps = new JsonObject();
        caps.addProperty("version", BRIDGE_VERSION);
        caps.addProperty("chunk", BRIDGE_CHUNK_SUPPORTED);
        caps.addProperty("maxChunkSize", BRIDGE_MAX_CHUNK_SIZE);
        JsonArray encs = new JsonArray();
        for (String e : BRIDGE_ENCODINGS) encs.add(e);
        caps.add("encodings", encs);
        JsonArray comps = new JsonArray();
        for (String c : BRIDGE_COMPRESSIONS) comps.add(c);
        caps.add("compressions", comps);
        caps.addProperty("ack", BRIDGE_ACK_SUPPORTED);
        caps.addProperty("ackWindow", BRIDGE_ACK_WINDOW);
        return caps;
    }

    /** 能力协商：取双方交集（与手环端 negotiateCaps 一致） */
    private static void negotiateCaps(JsonElement peer) {
        JsonObject p = peer != null && peer.isJsonObject() ? peer.getAsJsonObject() : null;
        int peerVersion = p != null && p.has("version") ? p.get("version").getAsInt() : 1;
        boolean peerChunk = p == null || !p.has("chunk") || p.get("chunk").getAsBoolean();
        boolean peerAck = p != null && p.has("ack") && p.get("ack").getAsBoolean();
        int version = Math.min(peerVersion, BRIDGE_VERSION);
        boolean chunked = BRIDGE_CHUNK_SUPPORTED && peerChunk && version >= 2;
        int chunkSize = BRIDGE_MAX_CHUNK_SIZE;
        if (p != null && p.has("maxChunkSize")) {
            try {
                chunkSize = Math.min(p.get("maxChunkSize").getAsInt(), BRIDGE_MAX_CHUNK_SIZE);
            } catch (Exception ignored) {
            }
        }
        int ackWindow = chunked && BRIDGE_ACK_SUPPORTED && peerAck
                ? Math.min(p != null && p.has("ackWindow") ? p.get("ackWindow").getAsInt() : BRIDGE_ACK_WINDOW, BRIDGE_ACK_WINDOW)
                : 0;

        JsonArray encs = new JsonArray();
        if (p != null && p.has("encodings") && p.get("encodings").isJsonArray()) {
            for (JsonElement e : p.getAsJsonArray("encodings")) {
                String s = e.isJsonPrimitive() ? e.getAsString() : "";
                for (String local : BRIDGE_ENCODINGS) {
                    if (local.equals(s) && !contains(encs, s)) encs.add(s);
                }
            }
        }
        if (encs.size() == 0) encs.add("base64");

        JsonArray comps = new JsonArray();
        if (p != null && p.has("compressions") && p.get("compressions").isJsonArray()) {
            for (JsonElement c : p.getAsJsonArray("compressions")) {
                String s = c.isJsonPrimitive() ? c.getAsString() : "";
                for (String local : BRIDGE_COMPRESSIONS) {
                    if (local.equals(s) && !contains(comps, s)) comps.add(s);
                }
            }
        }
        if (comps.size() == 0) comps.add("none");

        bridgeNegotiated = new JsonObject();
        bridgeNegotiated.addProperty("version", version);
        bridgeNegotiated.addProperty("chunked", chunked);
        bridgeNegotiated.addProperty("chunkSize", chunkSize);
        bridgeNegotiated.add("encodings", encs);
        bridgeNegotiated.add("compressions", comps);
        bridgeNegotiated.addProperty("ackWindow", ackWindow);
        SyncLog.i("[网桥] 能力协商完成: " + bridgeNegotiated.toString());
    }

    private static String negotiatedText() {
        if (bridgeNegotiated == null) return "尚未协商";
        JsonObject n = bridgeNegotiated;
        String text = "协议版本: v" + n.get("version").getAsInt();
        text += ", 分片: " + n.get("chunked").getAsBoolean();
        text += ", 分片大小: " + n.get("chunkSize").getAsInt();
        text += ", 编码: " + n.get("encodings").getAsJsonArray().toString();
        text += ", ACK窗口: " + n.get("ackWindow").getAsInt();
        return text;
    }

    private static int chunkSize() {
        if (bridgeNegotiated != null && bridgeNegotiated.has("chunkSize")) {
            return bridgeNegotiated.get("chunkSize").getAsInt();
        }
        return BRIDGE_MAX_CHUNK_SIZE;
    }

    private static int ackWindow() {
        if (bridgeNegotiated != null && bridgeNegotiated.has("ackWindow")) {
            return bridgeNegotiated.get("ackWindow").getAsInt();
        }
        return 0;
    }

    /** 处理手环 fetch 请求：解析 → 记录 → 三段转发 */
    private static void handleBridgeFetch(String nodeId, JsonObject msg) {
        String id = optStr(msg, "id", "");
        // log49b：收到请求立即回"正在传输"信号——手环端确认连接正常并重置该请求超时，
        // 慢响应（wbi 签名/多候选兜底/大响应分块）期间不误判网络错误/超时
        sendProgress(nodeId, id);
        String url = optStr(msg, "url", "");
        JsonObject options = msg.has("options") && msg.get("options").isJsonObject()
                ? msg.getAsJsonObject("options") : new JsonObject();
        String method = optStr(options, "method", "GET");
        boolean raw = options.has("raw") && options.get("raw").getAsBoolean();
        String transport = optStr(msg, "transport", "interconnect");

        // 语义化请求（手环只发 action + params，URL 由手机端映射表整合）
        String action = optStr(msg, "action", "");
        Map<String, String> params = new HashMap<>();
        if (msg.has("params") && msg.get("params").isJsonObject()) {
            JsonObject ps = msg.getAsJsonObject("params");
            for (Map.Entry<String, JsonElement> e : ps.entrySet()) {
                JsonElement v = e.getValue();
                params.put(e.getKey(), v.isJsonPrimitive() ? v.getAsString() : v.toString());
            }
        }
        boolean multiUrl = false;
        List<String> apiUrls = null;
        if (!action.isEmpty() && BiliApiMap.has(action)) {
            apiUrls = BiliApiMap.resolve(action, params);
            String apiMethod = BiliApiMap.method(action);
            if (apiUrls != null && !apiUrls.isEmpty()) {
                method = apiMethod;
                // 语义化请求：header 由手机端统一构造（UA/Referer/Cookie），不信任手环传的占位 Cookie
                options = new JsonObject();
                options.addProperty("method", method);
                SyncLog.i("[API映射] action=" + action + " -> " + apiMethod + " " + apiUrls.get(0)
                        + (apiUrls.size() > 1 ? " （备选 " + (apiUrls.size() - 1) + " 个）" : ""));
                if (apiUrls.size() == 1) {
                    url = apiUrls.get(0);
                } else {
                    multiUrl = true;
                }
                // wbi 签名：首页个性化推荐 rcmd 强制要求（否则被风控返回 -352/-403）
                if ("home_rcmd".equals(action) && !apiUrls.isEmpty()) {
                    String signed = WbiSigner.sign(apiUrls.get(0));
                    if (signed != null && !signed.isEmpty()) {
                        apiUrls.set(0, signed);
                        if (!multiUrl) url = signed;
                        SyncLog.i("[wbi] home_rcmd 已附加 wbi 签名参数");
                    }
                }
            }
        } else if (!action.isEmpty()) {
            SyncLog.i("[API映射] action=" + action + " 未收录，回退 url 直传");
        }

        Map<String, String> headers = new HashMap<>();
        if (options.has("headers") && options.get("headers").isJsonObject()) {
            JsonObject hs = options.getAsJsonObject("headers");
            for (Map.Entry<String, JsonElement> e : hs.entrySet()) {
                headers.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
            }
        }
        String body = optStr(options, "body", "");

        SyncLog.i("[手表→同步器] ── Fetch 请求 #" + id + " " + method + " " + url);
        if (!headers.isEmpty()) SyncLog.i("[手表→同步器] 请求头: " + gson.toJson(headers));
        if (!body.isEmpty()) SyncLog.i("[手表→同步器] 请求体: " + truncate(body, 400));

        RequestTracker.Record rec = RequestTracker.begin(id, method, url);
        rec.transport = "fetch".equals(transport) ? "fetch" : "interconnect";

        // 多候选 URL（首页直播等原手环端多接口重试）：依次尝试，任一 HTTP 2xx 且 body 非空即采用
        if (multiUrl && apiUrls != null) {
            relayWithFallback(nodeId, id, method, apiUrls, headers, body, raw, rec, 0, action);
            return;
        }

        HttpRelay.execute(url, method, headers, body, raw, new HttpRelay.RelayCallback() {
            @Override
            public void onResponse(int status, Map<String, List<String>> respHeaders, byte[] bodyBytes, String encoding) {
                finishAndSend(nodeId, id, status, respHeaders, bodyBytes, encoding, raw, rec, action);
            }

            @Override
            public void onFailure(String errBody) {
                rec.bizCode = -1;
                RequestTracker.fail(rec, errBody);
                byte[] b = errBody.getBytes(StandardCharsets.UTF_8);
                sendFetchResponse(nodeId, id, 502, new HashMap<>(), b, "text", raw);
            }
        });
    }

    /** 依次尝试候选 URL：任一 HTTP 2xx 且 body 非空即采用并回传；全部失败回传最后一次/502 */
    private static void relayWithFallback(String nodeId, String id, String method, List<String> urls,
                                          Map<String, String> headers, String body, boolean raw,
                                          RequestTracker.Record rec, int idx, String action) {
        if (idx >= urls.size()) {
            rec.bizCode = -1;
            RequestTracker.fail(rec, "all fallback urls failed");
            byte[] b = "{\"code\":-1,\"message\":\"all fallback urls failed\"}".getBytes(StandardCharsets.UTF_8);
            sendFetchResponse(nodeId, id, 502, new HashMap<>(), b, "text", raw);
            return;
        }
        SyncLog.i("[API映射] 候选 " + (idx + 1) + "/" + urls.size() + " " + method + " " + urls.get(idx));
        HttpRelay.execute(urls.get(idx), method, headers, body, raw, new HttpRelay.RelayCallback() {
            @Override
            public void onResponse(int status, Map<String, List<String>> respHeaders, byte[] bodyBytes, String encoding) {
                boolean ok = status >= 200 && status < 300 && bodyBytes.length > 0;
                // 业务码兜底：首页推荐被风控时 B 站返回 HTTP 200 但业务 code!=0（如 -352/-403），此时也切换下一候选（热门）
                if (ok && "home_rcmd".equals(action) && idx + 1 < urls.size()) {
                    int biz = extractBizCode(new String(bodyBytes, StandardCharsets.UTF_8));
                    if (biz != 0) {
                        SyncLog.i("[API映射] home_rcmd 业务码 code=" + biz + "，尝试下一个候选");
                        ok = false;
                    }
                }
                if (!ok && idx + 1 < urls.size()) {
                    SyncLog.i("[API映射] 候选未采用，尝试下一个");
                    relayWithFallback(nodeId, id, method, urls, headers, body, raw, rec, idx + 1, action);
                    return;
                }
                finishAndSend(nodeId, id, status, respHeaders, bodyBytes, encoding, raw, rec, action);
            }

            @Override
            public void onFailure(String errBody) {
                if (idx + 1 < urls.size()) {
                    SyncLog.i("[API映射] 候选请求失败，尝试下一个");
                    relayWithFallback(nodeId, id, method, urls, headers, body, raw, rec, idx + 1, action);
                    return;
                }
                rec.bizCode = -1;
                RequestTracker.fail(rec, errBody);
                byte[] b = errBody.getBytes(StandardCharsets.UTF_8);
                sendFetchResponse(nodeId, id, 502, new HashMap<>(), b, "text", raw);
            }
        });
    }

    /** 记录请求完成并回传响应（单 URL 与多候选共用）；风控码（-412/-352/-403）进入封锁窗口（log36） */
    private static void finishAndSend(String nodeId, String id, int status,
                                      Map<String, List<String>> respHeaders, byte[] bodyBytes,
                                      String encoding, boolean raw, RequestTracker.Record rec, String action) {
        String bodyText = "text".equals(encoding) ? new String(bodyBytes, StandardCharsets.UTF_8) : "";
        int biz = extractBizCode(bodyText);
        rec.bizCode = biz;
        if (biz != 0) {
            SyncLog.i("[监听] 请求 #" + id + " 业务码 code=" + biz + " (HTTP " + status + ")");
        }
        // 风控判断窗口（log36）：命中风控业务码 → 记录封锁窗口（剩余时间/触发接口/次数）
        RiskControl.onBizCode(biz, action, rec.url);
        RequestTracker.finish(rec, status, bodyBytes.length, bodyText);
        // log42：大响应裁剪（首页推荐/动态/私信/历史 只保留手环端渲染白名单字段，显著减小互联传输）
        if ("text".equals(encoding) && bodyBytes.length > 2048) {
            String slim = slimResponse(action, bodyText);
            if (slim != null && !slim.isEmpty() && slim.length() < bodyText.length()) {
                bodyBytes = slim.getBytes(StandardCharsets.UTF_8);
                SyncLog.i("[网桥] 响应裁剪 #" + id + " " + bodyText.length() + "→" + slim.length() + "字符");
            }
        }
        // log49：图片 base64 分片方案——把响应 JSON 中的 B 站图片 URL 替换为 file://img_N 占位，
        // 手机端异步下载→PNG→分片下发，手环保存为本地文件后占位被替换为 internal://files/img_N.png
        // （排除播放/雪碧图接口，避免破坏视频流与帧图 URL）
        if ("text".equals(encoding) && bodyBytes.length > 0 && !isImageSkipAction(action)) {
            String cur = new String(bodyBytes, StandardCharsets.UTF_8);
            String replaced = replaceImageUrls(cur, nodeId);
            if (!replaced.equals(cur)) {
                bodyBytes = replaced.getBytes(StandardCharsets.UTF_8);
                SyncLog.i("[图片] 响应 #" + id + " 图片 URL → file://img_N 占位（" + cur.length() + "→" + replaced.length() + "字符）");
            }
        }
        sendFetchResponse(nodeId, id, status, respHeaders, bodyBytes, encoding, raw);
    }

    /**
     * log42：按 action 裁剪 B 站响应为"手环端渲染白名单"（只保留列表页显示与点击跳转所需字段）。
     * 动态/私信/历史/推荐 原始响应可达几十~几百 KB，互联分块传输慢导致手环端超时/失败；
     * 裁剪后单页数据量降到 KB 级，第一轮列表秒开，详情仍由点击后第二轮请求（video_view 等）提供。
     */
    private static String slimResponse(String action, String bodyText) {
        if (bodyText == null || bodyText.isEmpty()) return bodyText;
        boolean match = "home_rcmd".equals(action) || "home_popular".equals(action)
                || "dynamic_feed".equals(action)
                || "msg_sessions_v2".equals(action) || "msg_sessions_v1".equals(action)
                || "history_cursor".equals(action);
        if (!match) return bodyText;
        try {
            JsonElement el = JsonParser.parseString(bodyText);
            if (!el.isJsonObject()) return bodyText;
            JsonObject root = el.getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) return bodyText;
            JsonObject data = root.getAsJsonObject("data");
            switch (action) {
                case "home_rcmd":
                case "home_popular":
                    slimHomeFeed(data);
                    break;
                case "dynamic_feed":
                    slimDynamicFeed(data);
                    break;
                case "msg_sessions_v2":
                case "msg_sessions_v1":
                    slimSessions(data);
                    break;
                case "history_cursor":
                    slimHistory(data);
                    break;
                default:
                    return bodyText;
            }
            return root.toString();
        } catch (Exception e) {
            return bodyText;
        }
    }

    /** 首页推荐/热门：item/list 只保留 title/pic/bvid/cid/aid/stat.view/owner.name（+ 分页游标字段） */
    private static void slimHomeFeed(JsonObject data) {
        String key = data.has("item") && data.get("item").isJsonArray() ? "item"
                : (data.has("list") && data.get("list").isJsonArray() ? "list" : null);
        if (key == null) return;
        JsonArray arr = data.getAsJsonArray(key);
        JsonArray out = new JsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject it = e.getAsJsonObject();
            JsonObject keep = new JsonObject();
            addStr(keep, it, "aid");
            addStr(keep, it, "bvid");
            addStr(keep, it, "cid");
            addStr(keep, it, "title");
            addStr(keep, it, "pic");
            if (it.has("stat") && it.get("stat").isJsonObject()) {
                JsonObject st = new JsonObject();
                if (it.getAsJsonObject("stat").has("view")) {
                    st.add("view", it.getAsJsonObject("stat").get("view"));
                }
                keep.add("stat", st);
            }
            if (it.has("owner") && it.get("owner").isJsonObject()) {
                JsonObject ow = new JsonObject();
                if (it.getAsJsonObject("owner").has("name")) {
                    ow.add("name", it.getAsJsonObject("owner").get("name"));
                }
                keep.add("owner", ow);
            }
            out.add(keep);
        }
        data.add(key, out);
    }

    /** 动态：items 只保留渲染白名单（author.name + major 各类型封面/title/bvid + desc.text）+ id_str（分页游标） */
    private static void slimDynamicFeed(JsonObject data) {
        if (!data.has("items") || !data.get("items").isJsonArray()) return;
        JsonArray arr = data.getAsJsonArray("items");
        JsonArray out = new JsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject it = e.getAsJsonObject();
            JsonObject keep = new JsonObject();
            if (it.has("id_str")) keep.add("id_str", it.get("id_str"));
            if (it.has("modules") && it.get("modules").isJsonObject()) {
                JsonObject modules = it.getAsJsonObject("modules");
                JsonObject keepMods = new JsonObject();
                if (modules.has("module_author") && modules.get("module_author").isJsonObject()) {
                    JsonObject au = modules.getAsJsonObject("module_author");
                    JsonObject keepAu = new JsonObject();
                    if (au.has("name")) keepAu.add("name", au.get("name"));
                    keepMods.add("module_author", keepAu);
                }
                if (modules.has("module_dynamic") && modules.get("module_dynamic").isJsonObject()) {
                    JsonObject dyn = modules.getAsJsonObject("module_dynamic");
                    JsonObject keepDyn = new JsonObject();
                    if (dyn.has("major") && dyn.get("major").isJsonObject()) {
                        JsonObject major = dyn.getAsJsonObject("major");
                        JsonObject keepMaj = new JsonObject();
                        if (major.has("type")) keepMaj.add("type", major.get("type"));
                        // 视频：保留 title/bvid/cover
                        if (major.has("archive") && major.get("archive").isJsonObject()) {
                            JsonObject arc = major.getAsJsonObject("archive");
                            JsonObject keepArc = new JsonObject();
                            addStr(keepArc, arc, "title");
                            addStr(keepArc, arc, "bvid");
                            addStr(keepArc, arc, "cover");
                            keepMaj.add("archive", keepArc);
                        }
                        // 图片动态：只留第一张图
                        if (major.has("draw") && major.get("draw").isJsonObject()) {
                            JsonObject draw = major.getAsJsonObject("draw");
                            JsonObject keepDraw = new JsonObject();
                            if (draw.has("items") && draw.get("items").isJsonArray()) {
                                JsonArray items = draw.getAsJsonArray("items");
                                if (items.size() > 0 && items.get(0).isJsonObject()) {
                                    JsonArray one = new JsonArray();
                                    JsonObject first = items.get(0).getAsJsonObject();
                                    JsonObject keepFirst = new JsonObject();
                                    addStr(keepFirst, first, "src");
                                    addStr(keepFirst, first, "url");
                                    one.add(keepFirst);
                                    keepDraw.add("items", one);
                                }
                            }
                            keepMaj.add("draw", keepDraw);
                        }
                        if (major.has("pgc") && major.get("pgc").isJsonObject()) {
                            JsonObject pgc = major.getAsJsonObject("pgc");
                            JsonObject keepPgc = new JsonObject();
                            addStr(keepPgc, pgc, "cover");
                            addStr(keepPgc, pgc, "title");
                            keepMaj.add("pgc", keepPgc);
                        }
                        if (major.has("article") && major.get("article").isJsonObject()) {
                            JsonObject art = major.getAsJsonObject("article");
                            JsonObject keepArt = new JsonObject();
                            if (art.has("covers") && art.get("covers").isJsonArray()) {
                                JsonArray cv = art.getAsJsonArray("covers");
                                JsonArray one = new JsonArray();
                                if (cv.size() > 0) one.add(cv.get(0));
                                keepArt.add("covers", one);
                            }
                            keepMaj.add("article", keepArt);
                        }
                        if (major.has("common") && major.get("common").isJsonObject()) {
                            JsonObject com = major.getAsJsonObject("common");
                            JsonObject keepCom = new JsonObject();
                            addStr(keepCom, com, "cover");
                            keepMaj.add("common", keepCom);
                        }
                        if (major.has("live_rcmd") && major.get("live_rcmd").isJsonObject()) {
                            JsonObject lv = major.getAsJsonObject("live_rcmd");
                            JsonObject keepLv = new JsonObject();
                            if (lv.has("content")) keepLv.add("content", lv.get("content"));
                            keepMaj.add("live_rcmd", keepLv);
                        }
                        keepDyn.add("major", keepMaj);
                    }
                    if (dyn.has("desc") && dyn.get("desc").isJsonObject()) {
                        JsonObject desc = dyn.getAsJsonObject("desc");
                        JsonObject keepDesc = new JsonObject();
                        if (desc.has("text")) keepDesc.add("text", desc.get("text"));
                        keepDyn.add("desc", keepDesc);
                    }
                    keepMods.add("module_dynamic", keepDyn);
                }
                keep.add("modules", keepMods);
            }
            out.add(keep);
        }
        data.add("items", out);
    }

    /** 私信会话：session_list 只保留 渲染字段 + last_msg 截断为 content/text */
    private static void slimSessions(JsonObject data) {
        String listKey = data.has("session_list") ? "session_list"
                : data.has("sessions") ? "sessions" : null;
        if (listKey == null || !data.get(listKey).isJsonArray()) return;
        JsonArray arr = data.getAsJsonArray(listKey);
        JsonArray out = new JsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject it = e.getAsJsonObject();
            JsonObject keep = new JsonObject();
            if (it.has("account_info") && it.get("account_info").isJsonObject()) {
                JsonObject acc = it.getAsJsonObject("account_info");
                JsonObject keepAcc = new JsonObject();
                addStr(keepAcc, acc, "name");
                addStr(keepAcc, acc, "face");
                keep.add("account_info", keepAcc);
            }
            addStr(keep, it, "talker_uname");
            addStr(keep, it, "uname");
            addStr(keep, it, "name");
            addStr(keep, it, "nickname");
            addStr(keep, it, "talker_face");
            addStr(keep, it, "face");
            addStr(keep, it, "avatar");
            addStr(keep, it, "avatar_url");
            addStr(keep, it, "user_face");
            addStr(keep, it, "talker_id");
            addStr(keep, it, "receiver_id");
            addStr(keep, it, "uid");
            addStr(keep, it, "mid");
            addStr(keep, it, "session_id");
            if (it.has("last_msg")) {
                JsonElement lm = it.get("last_msg");
                if (lm.isJsonObject()) {
                    JsonObject keepLm = new JsonObject();
                    if (lm.getAsJsonObject().has("content")) {
                        keepLm.add("content", clipStr(lm.getAsJsonObject().get("content"), 120));
                    } else if (lm.getAsJsonObject().has("text")) {
                        keepLm.add("text", clipStr(lm.getAsJsonObject().get("text"), 120));
                    }
                    keep.add("last_msg", keepLm);
                } else if (lm.isJsonPrimitive()) {
                    keep.add("last_msg", clipStr(lm, 120));
                }
            }
            out.add(keep);
        }
        data.add(listKey, out);
    }

    /** 观看历史：list 只保留 title/author_name/cover/bvid/view_at（+history.bvid 兜底） */
    private static void slimHistory(JsonObject data) {
        if (!data.has("list") || !data.get("list").isJsonArray()) return;
        JsonArray arr = data.getAsJsonArray("list");
        JsonArray out = new JsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject it = e.getAsJsonObject();
            JsonObject keep = new JsonObject();
            addStr(keep, it, "title");
            addStr(keep, it, "author_name");
            addStr(keep, it, "cover");
            addStr(keep, it, "bvid");
            addStr(keep, it, "view_at");
            if (it.has("history") && it.get("history").isJsonObject()) {
                JsonObject h = it.getAsJsonObject("history");
                JsonObject keepH = new JsonObject();
                if (h.has("bvid")) keepH.add("bvid", h.get("bvid"));
                keep.add("history", keepH);
            }
            out.add(keep);
        }
        data.add("list", out);
    }

    private static void addStr(JsonObject dst, JsonObject src, String key) {
        if (src.has(key) && src.get(key).isJsonPrimitive()) {
            dst.add(key, src.get(key));
        }
    }

    /** 字符串值截断（防 last_msg 过长） */
    private static JsonElement clipStr(JsonElement el, int max) {
        try {
            String s = el.getAsString();
            if (s != null && s.length() > max) s = s.substring(0, max) + "...";
            JsonObject o = new JsonObject();
            o.addProperty("v", s);
            return o.get("v");
        } catch (Exception e) {
            return el;
        }
    }

    /** 从响应 JSON 中提取 B 站业务码（code 字段） */
    private static int extractBizCode(String bodyText) {
        if (bodyText == null || bodyText.isEmpty()) return 0;
        try {
            JsonElement el = JsonParser.parseString(bodyText);
            if (el.isJsonObject() && el.getAsJsonObject().has("code")) {
                return el.getAsJsonObject().get("code").getAsInt();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 组装并发送响应：小响应单块直发，大响应分块发送（FetchBridge v3） */
    private static void sendFetchResponse(String nodeId, String id, int status,
                                          Map<String, List<String>> respHeaders,
                                          byte[] bodyBytes, String encoding, boolean raw) {
        JsonObject headersJson = new JsonObject();
        if (respHeaders != null) {
            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                List<String> v = e.getValue();
                if (v == null || v.isEmpty()) continue;
                if (v.size() == 1) headersJson.addProperty(e.getKey(), v.get(0));
                else {
                    JsonArray arr = new JsonArray();
                    for (String s : v) arr.add(s);
                    headersJson.add(e.getKey(), arr);
                }
            }
        }

        String bodyStr = "base64".equals(encoding)
                ? Base64.encodeToString(bodyBytes, Base64.NO_WRAP)
                : new String(bodyBytes, StandardCharsets.UTF_8);

        // 链路3日志：同步器 → 手表
        SyncLog.i("[同步器→手表] ── 响应 #" + id + " HTTP " + status + ", " + bodyBytes.length + " 字节, 编码=" + encoding);

        if (bodyStr.length() <= MAX_UNCHUNKED_WIRE_LEN) {
            // 单块直发
            JsonObject resp = new JsonObject();
            resp.addProperty("status", status);
            resp.add("headers", headersJson);
            resp.addProperty("body", bodyStr);
            resp.addProperty("bodyEncoding", encoding);
            resp.addProperty("raw", raw);

            JsonObject out = new JsonObject();
            out.addProperty("tag", "fetch");
            out.addProperty("id", id);
            out.add("resp", resp);

            String json = gson.toJson(out);
            SyncLog.p("[同步器→手表]", "[网桥] 响应 #" + id + " (" + json.length() + "字符)");
            sendRaw(nodeId, json);
        } else {
            // 分块发送（滑动窗口 ACK 流控）
            int size = chunkSize();
            int chunkCount = (bodyStr.length() + size - 1) / size;

            JsonObject resp = new JsonObject();
            resp.addProperty("chunked", true);
            resp.addProperty("chunkCount", chunkCount);
            resp.addProperty("totalBytes", bodyStr.length());
            resp.addProperty("chunkSize", size);
            resp.addProperty("status", status);
            resp.add("headers", headersJson);
            resp.addProperty("bodyEncoding", encoding);
            resp.addProperty("compression", "none");
            resp.addProperty("raw", raw);
            resp.addProperty("ack", true);

            JsonObject head = new JsonObject();
            head.addProperty("tag", "fetch");
            head.addProperty("id", id);
            head.add("resp", resp);

            String headJson = gson.toJson(head);
            SyncLog.p("[同步器→手表]", "[网桥] 分块头 #" + id + " 共 " + chunkCount + " 块, totalBytes=" + bodyStr.length());
            sendRaw(nodeId, headJson);

            List<String> chunks = new ArrayList<>(chunkCount);
            for (int seq = 0; seq < chunkCount; seq++) {
                int from = seq * size;
                int to = Math.min(from + size, bodyStr.length());
                chunks.add(bodyStr.substring(from, to));
            }

            int win = ackWindow();
            int window = win > 0 ? win : chunkCount;
            ChunkSendState st = new ChunkSendState(nodeId, id, chunks, window);
            chunkSenders.put(id, st);
            sendNextChunks(st);
        }
    }

    /** 滑动窗口发送：窗口内发送分片，等待 fetch-ack 推进后继续；附超时兜底防死锁 */
    private static synchronized void sendNextChunks(ChunkSendState st) {
        if (st.done) return;
        int total = st.chunks.size();
        while (st.sent < total && (st.sent - st.ackBase) < st.window) {
            JsonObject chunk = new JsonObject();
            chunk.addProperty("tag", "fetch-chunk");
            chunk.addProperty("id", st.id);
            chunk.addProperty("seq", st.sent);
            chunk.addProperty("total", total);
            chunk.addProperty("data", st.chunks.get(st.sent));
            SyncLog.p("[同步器→手表]", "[网桥] 分块 #" + st.id + " [" + (st.sent + 1) + "/" + total + "]");
            sendRaw(st.nodeId, gson.toJson(chunk));
            st.sent++;
        }
        if (st.sent >= total) {
            if (st.window >= total || st.ackBase >= total) {
                st.done = true;
                chunkSenders.remove(st.id);
                SyncLog.i("[网桥] 分块发送完成 #" + st.id);
            } else {
                scheduleChunkTimeout(st);
            }
            return;
        }
        scheduleChunkTimeout(st);
    }

    /** ACK 超时兜底：避免 ACK 丢失导致分块永久停滞 */
    private static void scheduleChunkTimeout(ChunkSendState st) {
        if (st.timeout != null) st.timeout.cancel(false);
        st.timeout = scheduler.schedule(() -> {
            if (st.done) return;
            if (st.ackBase < st.sent) {
                SyncLog.i("[网桥] ACK 超时，强制推进分块 #" + st.id + " (ackBase=" + st.ackBase + ", sent=" + st.sent + ")");
                st.ackBase = st.sent;
            }
            sendNextChunks(st);
        }, CHUNK_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ===================== 官方 HyperBili 信封协议 =====================

    private static void handleLegacyPacket(String nodeId, String data) {
        JsonObject packet;
        try {
            packet = JsonParser.parseString(data).getAsJsonObject();
        } catch (Exception e) {
            SyncLog.i("无法解析的协议消息: " + truncate(data, 300));
            return;
        }
        String id = optStr(packet, "id", "");
        String messageStr = optStr(packet, "message", "");
        if (messageStr.isEmpty()) {
            SyncLog.i("协议消息缺少 message: " + truncate(data, 300));
            return;
        }
        JsonObject message;
        try {
            message = JsonParser.parseString(messageStr).getAsJsonObject();
        } catch (Exception e) {
            SyncLog.i("message 非 JSON: " + truncate(messageStr, 300));
            return;
        }
        String msgtype = optStr(message, "msgtype", "");
        switch (msgtype) {
            case "FETCH": {
                JsonObject req = message.has("message") && message.get("message").isJsonObject()
                        ? message.getAsJsonObject("message") : new JsonObject();
                String url = optStr(req, "url", "");
                String method = optStr(req, "method", "GET");
                String body = optStr(req, "data", "");
                Map<String, String> headers = new HashMap<>();
                if (req.has("header") && req.get("header").isJsonObject()) {
                    JsonObject hs = req.getAsJsonObject("header");
                    for (Map.Entry<String, JsonElement> e : hs.entrySet()) {
                        headers.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
                    }
                }
                SyncLog.i("[官方FETCH] " + method + " " + url);
                RequestTracker.Record rec = RequestTracker.begin(id, method, url);
                rec.transport = "interconnect"; // 官方 FETCH 信封同样走互联通道
                HttpRelay.execute(url, method, headers, body, false, new HttpRelay.RelayCallback() {
                    @Override
                    public void onResponse(int status, Map<String, List<String>> respHeaders, byte[] bodyBytes, String encoding) {
                        String dataStr = "base64".equals(encoding)
                                ? Base64.encodeToString(bodyBytes, Base64.NO_WRAP)
                                : new String(bodyBytes, StandardCharsets.UTF_8);
                        RequestTracker.finish(rec, status, bodyBytes.length, dataStr);
                        JsonObject respObj = new JsonObject();
                        respObj.addProperty("code", status);
                        respObj.addProperty("data", dataStr);
                        JsonObject hdrs = new JsonObject();
                        if (respHeaders != null) {
                            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                                List<String> v = e.getValue();
                                if (v != null && !v.isEmpty()) {
                                    if (v.size() == 1) hdrs.addProperty(e.getKey(), v.get(0));
                                    else {
                                        JsonArray arr = new JsonArray();
                                        for (String s : v) arr.add(s);
                                        hdrs.add(e.getKey(), arr);
                                    }
                                }
                            }
                        }
                        respObj.add("headers", hdrs);
                        JsonObject out = new JsonObject();
                        out.addProperty("id", id);
                        out.addProperty("response", gson.toJson(respObj));
                        SyncLog.p("[同步器→手表]", "[官方FETCH] 响应 #" + id + " (" + out.toString().length() + "字符)");
                        sendRaw(nodeId, gson.toJson(out));
                    }

                    @Override
                    public void onFailure(String errBody) {
                        RequestTracker.fail(rec, errBody);
                        JsonObject respObj = new JsonObject();
                        respObj.addProperty("code", -1);
                        respObj.addProperty("data", errBody);
                        respObj.add("headers", new JsonObject());
                        JsonObject out = new JsonObject();
                        out.addProperty("id", id);
                        out.addProperty("response", gson.toJson(respObj));
                        sendRaw(nodeId, gson.toJson(out));
                    }
                });
                break;
            }
            case "SHOWQR":
                // 官方方向：手环发起 TV 登录拿到 qrUrl → SHOWQR 发手机 → 手机 WebView 显示二维码 → 手机扫码
                QrState.qrcode_key = optStr(message, "message", "");
                SyncLog.i("[官方SHOWQR] 收到二维码内容: " + truncate(QrState.qrcode_key, 200));
                JsonObject okQr = new JsonObject();
                okQr.addProperty("id", id);
                okQr.addProperty("response", "{\"content\": \"OK\"}");
                sendRaw(nodeId, gson.toJson(okQr));
                break;
            case "HELLO":
                SyncLog.i("[官方HELLO] 收到 Hello，Let's Go！");
                JsonObject okHello = new JsonObject();
                okHello.addProperty("id", id);
                okHello.addProperty("response", "{\"content\": \"OK\"}");
                sendRaw(nodeId, gson.toJson(okHello));
                break;
            default:
                SyncLog.i("未知 msgtype: " + msgtype);
        }
    }

    // ===================== 开发者模式推送 =====================

    /** 向手表发送 __dev_fetch__ 指令：手表端 fetchbridge 收到后发起真实请求（三段转发）并跳转页面 */
    public static void sendDevFetch(String name, String url, String uri) {
        if (WearClient.nodeId().isEmpty()) {
            SyncLog.i("[网桥] 无法推送[" + name + "]：尚未连接节点");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("tag", "__dev_fetch__");
        msg.addProperty("name", name);
        msg.addProperty("url", url);
        if (uri != null && !uri.isEmpty()) msg.addProperty("uri", uri);
        SyncLog.p("[同步器→手表]", "[开发者推送][" + name + "] " + url + (uri != null ? " → 跳转" + uri : ""));
        sendRaw(WearClient.nodeId(), gson.toJson(msg));
    }

    /** 语义化推送：手环收到后按 action+params 走 BiliApiMap（wbi 签名/多候选兜底/登录态注入由手机端统一处理） */
    public static void sendDevFetch(String name, String action, Map<String, String> params, String uri) {
        if (WearClient.nodeId().isEmpty()) {
            SyncLog.i("[网桥] 无法推送[" + name + "]：尚未连接节点");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("tag", "__dev_fetch__");
        msg.addProperty("name", name);
        msg.addProperty("action", action);
        if (params != null && !params.isEmpty()) {
            JsonObject ps = new JsonObject();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null) ps.addProperty(e.getKey(), e.getValue());
            }
            msg.add("params", ps);
        }
        if (uri != null && !uri.isEmpty()) msg.addProperty("uri", uri);
        SyncLog.p("[同步器→手表]", "[开发者推送][" + name + "] action=" + action + (uri != null ? " → 跳转" + uri : ""));
        sendRaw(WearClient.nodeId(), gson.toJson(msg));
    }

    // ===================== 发送 =====================

    public static void sendRaw(String nodeId, String json) {
        WearClient.sendToWatch(nodeId, json);
    }

    /** 分块发送状态 */
    private static final class ChunkSendState {
        final String nodeId;
        final String id;
        final List<String> chunks;
        int ackBase = 0;
        int sent = 0;
        int window;
        boolean done = false;
        ScheduledFuture<?> timeout;

        ChunkSendState(String nodeId, String id, List<String> chunks, int window) {
            this.nodeId = nodeId;
            this.id = id;
            this.chunks = chunks;
            this.window = window;
        }
    }

    // ===================== 工具 =====================

    private static boolean contains(JsonArray arr, String s) {
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive() && s.equals(e.getAsString())) return true;
        }
        return false;
    }

    private static String optStr(JsonObject o, String key, String def) {
        if (o != null && o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString();
        return def;
    }

    private static int optInt(JsonObject o, String key, int def) {
        if (o != null && o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(共" + s.length() + "字符)";
    }
}
