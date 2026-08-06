package com.wristbili.sync;

/**
 * 同步器全局状态：供主界面状态卡 + WebView 前端（官方 index.html）轮询。
 * 字段名与官方前端 index-da61150d.js 中 ui_params 完全一致（Gson 序列化）。
 */
public class QrState {
    public static volatile boolean connected = false;
    public static volatile String connected_device_name = "未知设备";
    public static volatile boolean mifitness_connected = false;
    public static volatile boolean device_permission = false;
    public static volatile String interconnect_tool_version = "1.0.0";
    /** 手环 SHOWQR 发来的二维码内容（qrUrl），前端轮询到非空即生成二维码 */
    public static volatile String qrcode_key = "";

    /** 网桥握手状态（主界面显示用） */
    public static volatile boolean bridgeHandshakeDone = false;
    /** 网桥协议协商摘要 */
    public static volatile String bridgeNegotiatedText = "";
}
