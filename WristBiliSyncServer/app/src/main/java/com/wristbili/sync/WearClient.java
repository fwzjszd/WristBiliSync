package com.wristbili.sync;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.exception.AppNotInstalledException;
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException;
import com.xiaomi.xms.wearable.exception.PermissionDeniedException;
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import com.xiaomi.xms.wearable.service.ServiceApi;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * xms-wearable-lib（小米穿戴互联 SDK）封装：
 *  SDK 初始化 → 服务监听 → 扫描节点 → 检测手表端应用 → 权限 → 消息监听。
 *  消息收转发给 FetchBridgeServer 处理。
 */
public final class WearClient {

    public static Context appContext = null;
    public static NodeApi nodeApi = null;
    public static AuthApi authApi = null;
    public static MessageApi messageApi = null;
    public static ServiceApi serviceApi = null;

    public static volatile String connectedNodeId = "";
    public static volatile String connectedNodeName = "";

    // 手环端可互联的 B 站快应用包名（只保留本快应用：WristBili 米环版）
    public static final String[] WEAR_APPS = {
            "com.example.band.bilibili.lite"   // WristBili（米环版，目标应用）
    };
    public static volatile String wearAppFound = "";

    public static volatile boolean hostAppInstalled = false;
    public static volatile String hostAppDetail = "未检测";
    public static volatile boolean serviceConnected = false;
    public static volatile int serviceApiLevel = -1;
    public static volatile Boolean permDeviceManager = null;
    public static volatile Boolean permNotify = null;
    public static volatile boolean messageListenerActive = false;
    public static volatile String lastError = "";

    private static boolean serviceListenerRegistered = false;

    private static final OnServiceConnectionListener serviceListener = new OnServiceConnectionListener() {
        @Override
        public void onServiceConnected() {
            serviceConnected = true;
            SyncLog.i("[SDK服务] 服务已连接");
        }

        @Override
        public void onServiceDisconnected() {
            serviceConnected = false;
            SyncLog.i("[SDK服务] 服务已断开");
        }
    };

    private static final OnMessageReceivedListener messageListener = new OnMessageReceivedListener() {
        @Override
        public void onMessageReceived(@NonNull @NotNull String nodeId, @NonNull @NotNull byte[] bytes) {
            SyncLog.i("[SDK] 收到 " + bytes.length + " 字节消息，开始处理");
            try {
                FetchBridgeServer.onMessage(nodeId, new String(bytes, "UTF-8"));
            } catch (Exception e) {
                SyncLog.i("[SDK] 消息处理异常: " + e);
            }
        }
    };

    private WearClient() {
    }

    public static String nodeId() {
        return connectedNodeId;
    }

    // ===================== 连接流程 =====================

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    /** 完整连接流程：初始化 SDK → 服务监听 → 扫描节点 → 检测应用 → 权限 → 消息监听 */
    public static void connectAndSetup() {
        SyncLog.i("===== 连接流程开始 =====");
        if (appContext == null) {
            SyncLog.i("应用上下文未初始化");
            return;
        }
        try {
            nodeApi = Wearable.getNodeApi(appContext);
            authApi = Wearable.getAuthApi(appContext);
            messageApi = Wearable.getMessageApi(appContext);
            serviceApi = Wearable.getServiceApi(appContext);
            SyncLog.i("SDK API 初始化完成");
        } catch (Exception e) {
            lastError = describeException(e);
            SyncLog.i("SDK API 初始化异常: " + describeException(e));
            return;
        }

        registerServiceListener();

        try {
            serviceApi.getServiceApiLevel()
                    .addOnSuccessListener(level -> {
                        serviceConnected = true;
                        serviceApiLevel = level;
                        SyncLog.i("[SDK服务] API Level = " + level);
                    })
                    .addOnFailureListener(e -> {
                        serviceConnected = false;
                        lastError = describeException(e);
                        SyncLog.i("[SDK服务] 获取 API Level 失败: " + describeException(e));
                    });
        } catch (Exception e) {
            SyncLog.i("[SDK服务] getServiceApiLevel 异常: " + describeException(e));
        }

        scanNodes();
    }

    /** 扫描已连接的手表节点 */
    public static void scanNodes() {
        SyncLog.i("开始扫描已连接节点...");
        try {
            nodeApi.getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        SyncLog.i("已连接节点数: " + nodes.size());
                        if (nodes.isEmpty()) {
                            QrState.connected = false;
                            connectedNodeId = "";
                            lastError = "未发现已连接的手表节点";
                            SyncLog.i("未发现已连接的手表节点，请确认手表已配对并保持连接");
                            return;
                        }
                        Node n = nodes.get(0);
                        connectedNodeId = n.id;
                        connectedNodeName = n.name;
                        QrState.connected = true;
                        QrState.connected_device_name = n.name;
                        SyncLog.i("连接节点: " + n.id + " (" + n.name + ")");
                        // 监听先行：立即启动消息监听（成功后 2s 自动主动握手），不依赖权限链——
                        // 否则权限链任何一环卡住/失败/用户忽略授权弹窗，监听不启动 → 手环握手消息/请求无人接收
                        // （表现为"连握手都做不到"、"请求数据不可用"）
                        startListening(n.id);
                        // 权限链独立运行：申请互联权限 → 检测手环端应用（isWearAppInstalled 需要 DEVICE_MANAGER，
                        // 应用检测结果不阻断互联，宽容模式由 finishWearAppCheck 处理）
                        checkAndRequestPermission(n.id);
                    })
                    .addOnFailureListener(e -> {
                        lastError = describeException(e);
                        SyncLog.i("获取节点列表失败: " + describeException(e));
                    });
        } catch (Exception e) {
            lastError = describeException(e);
            SyncLog.i("扫描节点异常: " + describeException(e));
        }
    }

    /** 检查手环端应用安装情况，任一安装则继续监听流程 */
    private static void checkWearAppAndConnect(String nodeId) {
        SyncLog.i("检查手表端应用安装情况...");
        wearAppFound = "";
        AtomicInteger pending = new AtomicInteger(WEAR_APPS.length);
        for (String pkg : WEAR_APPS) {
            try {
                nodeApi.isWearAppInstalled(pkg)
                        .addOnSuccessListener(installed -> {
                            SyncLog.i("  手表端 " + pkg + " 安装状态: " + installed);
                            if (installed && wearAppFound.isEmpty()) wearAppFound = pkg;
                            finishWearAppCheck(pending, nodeId);
                        })
                        .addOnFailureListener(e -> {
                            SyncLog.i("  检查 " + pkg + " 失败: " + describeException(e));
                            // 权限被拒绝（新设备/宿主未授权）：明确提示引导授权后重连，不静默中断
                            if (e instanceof PermissionDeniedException) {
                                lastError = "互联权限被拒绝：请在手机端宿主 App（小米运动健康/小米穿戴）中允许互联权限后点重连";
                            }
                            finishWearAppCheck(pending, nodeId);
                        });
            } catch (Exception e) {
                SyncLog.i("  检查 " + pkg + " 异常: " + describeException(e));
                finishWearAppCheck(pending, nodeId);
            }
        }
    }

    private static void finishWearAppCheck(AtomicInteger pending, String nodeId) {
        if (pending.decrementAndGet() != 0) return;
        if (!wearAppFound.isEmpty()) {
            SyncLog.i("检测到手表端应用: " + wearAppFound + "，启动消息监听");
        } else {
            // 宽容模式：应用检测失败/误报（权限时序、宿主限制、isWearAppInstalled 不稳定）不阻断监听——
            // 手环端只要装了本快应用并开启网桥，互联即可工作，消息监听本身不依赖应用检测结果。
            // 否则"请求数据不可用"（手机端没监听 → 手环请求无人接收）
            SyncLog.i("手表端未检测到可互联应用（宽容模式），仍尝试启动消息监听");
        }
        startListening(nodeId);
    }

    public static void checkAndRequestPermission(String nodeId) {
        SyncLog.i("开始检查/申请权限, node=" + nodeId);
        try {
            authApi.checkPermission(nodeId, Permission.DEVICE_MANAGER)
                    .addOnSuccessListener(b -> {
                        QrState.mifitness_connected = true;
                        permDeviceManager = b;
                        SyncLog.i("DEVICE_MANAGER 当前授权状态: " + b);
                        if (b != null && b) {
                            // 已授权（老用户）：直接检测手环端应用 → 启动监听
                            checkWearAppAndConnect(nodeId);
                        } else {
                            // 未授权（新用户首次使用）：先申请权限，成功后再检测应用（isWearAppInstalled 依赖 DEVICE_MANAGER）
                            requestPermissionsNow(nodeId);
                        }
                    })
                    .addOnFailureListener(e -> {
                        lastError = describeException(e);
                        SyncLog.i("检查 DEVICE_MANAGER 失败: " + describeException(e));
                        // 检查权限被拒（新设备/宿主未授权）：尝试直接申请，成功后再继续
                        requestPermissionsNow(nodeId);
                    });
        } catch (Exception e) {
            SyncLog.i("checkPermission 异常: " + describeException(e));
        }
    }

    public static void requestPermissionsNow(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            SyncLog.i("无节点，无法申请权限");
            return;
        }
        try {
            authApi.requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                    .addOnSuccessListener(perms -> {
                        QrState.device_permission = true;
                        StringBuilder sb = new StringBuilder("权限申请成功: ");
                        if (perms != null) {
                            for (Permission p : perms) sb.append(p.getName()).append(' ');
                        }
                        SyncLog.i(sb.toString());
                        // 权限就绪后再检测手环端应用（isWearAppInstalled 不再被拒）→ 启动监听
                        checkWearAppAndConnect(nodeId);
                    })
                    .addOnFailureListener(e -> {
                        QrState.device_permission = false;
                        lastError = describeException(e);
                        SyncLog.i("权限申请失败: " + describeException(e));
                    });
        } catch (Exception e) {
            SyncLog.i("requestPermission 异常: " + describeException(e));
        }
    }

    /** 启动消息监听（防止重复注册），成功后延迟 2s 主动握手 */
    public static void startListening(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            SyncLog.i("无节点，无法开始监听");
            return;
        }
        if (messageListenerActive) {
            SyncLog.i("消息监听已在运行中");
            return;
        }
        try {
            messageApi.addListener(nodeId, messageListener)
                    .addOnSuccessListener(v -> {
                        messageListenerActive = true;
                        SyncLog.i("消息监听已启动, node=" + nodeId);
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ignored) {
                            }
                            FetchBridgeServer.sendBridgeHandshake();
                        }).start();
                    })
                    .addOnFailureListener(e -> SyncLog.i("启动消息监听失败: " + describeException(e)));
        } catch (Exception e) {
            SyncLog.i("addListener 异常: " + describeException(e));
        }
    }

    /** 向手环发送文本消息 */
    public static void sendToWatch(String nodeId, String json) {
        try {
            if (messageApi == null || nodeId == null || nodeId.isEmpty()) {
                SyncLog.i("[发送失败] messageApi=" + (messageApi != null) + " node='" + nodeId + "'");
                return;
            }
            messageApi.sendMessage(nodeId, json.getBytes("UTF-8"))
                    .addOnSuccessListener(v -> {
                    })
                    .addOnFailureListener(e -> SyncLog.i("[网桥] 发送失败: " + describeException(e)));
        } catch (Exception e) {
            SyncLog.i("[网桥] 发送异常: " + e);
        }
    }

    // ===================== 环境自检 =====================

    /** 环境自检：宿主、SDK、节点、权限 全链路 */
    public static void environmentCheck() {
        SyncLog.i("===== 环境自检 =====");
        checkHostApp();
        try {
            nodeApi = Wearable.getNodeApi(appContext);
            authApi = Wearable.getAuthApi(appContext);
            messageApi = Wearable.getMessageApi(appContext);
            serviceApi = Wearable.getServiceApi(appContext);
            SyncLog.i("SDK API 初始化完成");
        } catch (Exception e) {
            SyncLog.i("SDK API 初始化异常: " + describeException(e));
            return;
        }
        registerServiceListener();
        try {
            serviceApi.getServiceApiLevel()
                    .addOnSuccessListener(level -> {
                        serviceConnected = true;
                        serviceApiLevel = level;
                        SyncLog.i("[SDK服务] 已连接, API Level=" + level);
                    })
                    .addOnFailureListener(e -> {
                        serviceConnected = false;
                        SyncLog.i("[SDK服务] 连接失败: " + describeException(e));
                    });
        } catch (Exception e) {
            SyncLog.i("[SDK服务] getServiceApiLevel 异常: " + describeException(e));
        }
        try {
            nodeApi.getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        SyncLog.i("已连接节点数: " + nodes.size());
                        for (Node n : nodes) SyncLog.i("  - " + n.id + " (" + n.name + ")");
                        if (!nodes.isEmpty()) {
                            Node n = nodes.get(0);
                            connectedNodeId = n.id;
                            connectedNodeName = n.name;
                            QrState.connected = true;
                            QrState.connected_device_name = n.name;
                            checkPermissionsNow();
                        } else {
                            QrState.connected = false;
                            SyncLog.i("提示: 未发现已连接节点");
                        }
                    })
                    .addOnFailureListener(e -> {
                        lastError = describeException(e);
                        SyncLog.i("获取节点失败: " + describeException(e));
                    });
        } catch (Exception e) {
            lastError = describeException(e);
            SyncLog.i("扫描节点异常: " + describeException(e));
        }
    }

    public static void checkPermissionsNow() {
        if (connectedNodeId.isEmpty()) {
            SyncLog.i("无节点，无法检查权限");
            return;
        }
        checkPermissionOne(connectedNodeId, Permission.DEVICE_MANAGER);
        checkPermissionOne(connectedNodeId, Permission.NOTIFY);
    }

    private static void checkPermissionOne(String nodeId, Permission p) {
        try {
            authApi.checkPermission(nodeId, p)
                    .addOnSuccessListener(b -> {
                        if (p.getName().equals(Permission.DEVICE_MANAGER.getName())) permDeviceManager = b;
                        else permNotify = b;
                        SyncLog.i("权限[" + p.getName() + "] = " + b);
                    })
                    .addOnFailureListener(e -> SyncLog.i("检查权限[" + p.getName() + "]失败: " + describeException(e)));
        } catch (Exception e) {
            SyncLog.i("checkPermission[" + p.getName() + "] 异常: " + describeException(e));
        }
    }

    /** 检测宿主应用（小米运动健康 / 小米穿戴）是否安装 */
    public static void checkHostApp() {
        StringBuilder detail = new StringBuilder();
        boolean any = false;
        String[] hosts = {"com.mi.health", "com.xiaomi.wearable"};
        for (String pkg : hosts) {
            try {
                PackageInfo pi = appContext.getPackageManager().getPackageInfo(pkg, 0);
                any = true;
                detail.append(pkg).append(" v").append(pi.versionName).append(" ✓; ");
                SyncLog.i("宿主应用已安装: " + pkg + " v" + pi.versionName);
            } catch (PackageManager.NameNotFoundException e) {
                detail.append(pkg).append(" 未安装; ");
                SyncLog.i("宿主应用未安装: " + pkg);
            }
        }
        hostAppInstalled = any;
        hostAppDetail = detail.toString();
    }

    private static void registerServiceListener() {
        if (serviceListenerRegistered) return;
        try {
            serviceApi.registerServiceConnectionListener(serviceListener);
            serviceListenerRegistered = true;
            SyncLog.i("[SDK服务] 已注册服务连接监听");
        } catch (Exception e) {
            SyncLog.i("[SDK服务] 注册监听失败: " + describeException(e));
        }
    }

    /** 将 SDK 异常转换为可读中文描述 */
    public static String describeException(Throwable e) {
        if (e == null) return "未知错误";
        String msg = e.getMessage();
        if (e instanceof AppNotInstalledException) return "宿主应用未安装/不可用: " + msg;
        if (e instanceof SignatureVerifyFailedException) return "签名校验失败（应用签名与 SDK 不匹配）: " + msg;
        if (e instanceof DeviceDisconnectedException) return "设备未连接: " + msg;
        if (e instanceof PermissionDeniedException) return "权限被拒绝: " + msg;
        return e.getClass().getSimpleName() + ": " + (msg != null ? msg : "");
    }
}
