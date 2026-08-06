package com.wristbili.sync;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 主界面（Material 3）：连接状态卡 + 登录二维码区（WebView 自写登录页）+ 实时日志 + 操作按钮。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStatus, tvConnState, tvNode, tvHost, tvService, tvPerm, tvBridge, tvLogs, tvListen, tvRisk;
    private View statusDot;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastLogVersion = -1;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshLogs();
            refreshListen();
            ui.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WearClient.init(getApplicationContext());

        tvStatus = findViewById(R.id.tv_status);
        statusDot = findViewById(R.id.status_dot);
        tvConnState = findViewById(R.id.tv_conn_state);
        tvNode = findViewById(R.id.tv_node);
        tvHost = findViewById(R.id.tv_host);
        tvService = findViewById(R.id.tv_service);
        tvPerm = findViewById(R.id.tv_perm);
        tvBridge = findViewById(R.id.tv_bridge);
        tvRisk = findViewById(R.id.tv_risk);
        tvLogs = findViewById(R.id.tv_logs);
        tvListen = findViewById(R.id.tv_listen);
        // 日志 TextView 必须设置 movement method 才能滚动查看历史
        tvLogs.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvLogs.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        setupButtons();

        // 启动即连接
        WearClient.connectAndSetup();
        ui.post(refresh);
    }

    private void setupButtons() {
        ((Button) findViewById(R.id.btn_reconnect)).setOnClickListener(v -> {
            WearClient.connectAndSetup();
        });
        ((Button) findViewById(R.id.btn_handshake)).setOnClickListener(v -> {
            FetchBridgeServer.sendBridgeHandshake();
        });
        ((Button) findViewById(R.id.btn_debug)).setOnClickListener(v -> {
            startActivity(new Intent(this, DebugActivity.class));
        });
        ((Button) findViewById(R.id.btn_clear_log)).setOnClickListener(v -> {
            SyncLog.clearAll();
            lastLogVersion = -1; // 强制下次刷新清屏
            refreshLogs();
            android.widget.Toast.makeText(this, "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshStatus() {
        boolean connected = !WearClient.connectedNodeId.isEmpty();
        if (connected) {
            tvStatus.setText("已连接");
            tvConnState.setText("已连接设备");
            statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#4CD964")));
        } else {
            tvStatus.setText("未连接");
            tvConnState.setText("设备未连接");
            statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F2B8B5")));
        }
        tvNode.setText("设备："
                + (connected ? WearClient.connectedNodeName + " (" + WearClient.connectedNodeId + ")" : "未连接")
                + (WearClient.wearAppFound.isEmpty() ? "" : "  [手环应用:" + WearClient.wearAppFound + "]"));
        tvHost.setText("宿主：" + WearClient.hostAppDetail);
        tvService.setText("SDK 服务：" + (WearClient.serviceConnected ? "已连接" : "未连接")
                + (WearClient.serviceApiLevel >= 0 ? " (Level " + WearClient.serviceApiLevel + ")" : ""));
        tvPerm.setText("权限："
                + (WearClient.permDeviceManager != null ? "DEVICE_MANAGER=" + WearClient.permDeviceManager + " " : "未检查 ")
                + (WearClient.permNotify != null ? "NOTIFY=" + WearClient.permNotify : ""));
        tvBridge.setText("网桥：" + (QrState.bridgeHandshakeDone
                ? "已就绪 " + QrState.bridgeNegotiatedText
                : (WearClient.messageListenerActive ? "等待握手..." : "未启动")));
        // 风控封锁判断窗口（log36）：命中 -412/-352/-403 显示封锁中 + 剩余窗口，正常显示"无"
        tvRisk.setText(RiskControl.isBlocked()
                ? "风控：拦截中 code=" + RiskControl.lastCode() + " 剩余" + (RiskControl.remainingMs() / 60000) + "分钟（详见调试面板）"
                : "风控：无");
    }

    private void refreshLogs() {
        // 版本号驱动：清空/新日志都会自增，任何变化都刷新（不再依赖文本相等判断）
        if (SyncLog.version == lastLogVersion) return;
        lastLogVersion = SyncLog.version;
        List<String> lines = SyncLog.recentLogs(40);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        // 记录当前滚动位置比例：在底部则刷新后平滑跟随最新日志（像手表日志区一样自动下滑，不跳动）；
        // 用户上滑查看历史时按比例恢复位置，不打扰阅读
        final float scrollRatio;
        if (tvLogs.getLayout() != null && tvLogs.getHeight() > 0) {
            int maxY = Math.max(0, tvLogs.getLayout().getHeight() - tvLogs.getHeight());
            scrollRatio = maxY > 0 ? (float) tvLogs.getScrollY() / maxY : 1f;
        } else {
            scrollRatio = 1f;
        }
        tvLogs.setText(sb.toString());
        tvLogs.post(() -> {
            int maxY = Math.max(0, tvLogs.getLayout() == null ? 0 : tvLogs.getLayout().getHeight() - tvLogs.getHeight());
            if (maxY <= 0) return;
            int target = scrollRatio >= 0.98f ? maxY : Math.round(scrollRatio * maxY); // 底部则跟随最新
            int cur = tvLogs.getScrollY();
            if (target == cur) return;
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(cur, target);
            anim.setDuration(200);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(a -> tvLogs.scrollTo(0, (Integer) a.getAnimatedValue()));
            anim.start();
        });
    }

    /** 请求监听：显示手环最近 6 次联网请求（方法/URL/HTTP状态/业务码/字节/耗时） */
    private void refreshListen() {
        List<RequestTracker.Record> all = RequestTracker.all();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, all.size() - 6);
        for (int i = from; i < all.size(); i++) {
            RequestTracker.Record r = all.get(i);
            sb.append(r.method).append(' ').append(truncate(r.url, 60)).append('\n');
            sb.append("   ").append(r.state)
                    .append(r.httpStatus > 0 ? " HTTP " + r.httpStatus : "")
                    .append(r.bizCode != 0 ? " code=" + r.bizCode : "")
                    .append("  ").append(r.bytes).append("B  ").append(r.costMs).append("ms\n");
        }
        String text = sb.length() == 0 ? "暂无请求" : sb.toString();
        if (!text.equals(tvListen.getText().toString())) {
            tvListen.setText(text);
        }
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresh);
        super.onDestroy();
    }
}
