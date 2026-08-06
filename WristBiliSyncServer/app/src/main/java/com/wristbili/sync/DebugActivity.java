package com.wristbili.sync;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 调试面板（Material 3，三页签）：
 *  1. 协议日志：手表↔同步器 原始协议消息 + 三段链路日志
 *  2. 请求详情：请求列表（点击弹窗查看响应详情）
 *  3. 工具：推送测试 / 环境自检 / 重连 / 握手 / 手动发消息
 */
public class DebugActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvProto, tvEnv, tvLogin, tvRisk;
    private ListView lvRequests;
    private ArrayAdapter<String> reqAdapter;
    private final List<String> reqLabels = new ArrayList<>();
    private long lastProtoVersion = -1;
    private String lastLoginText = null;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshProto();
            refreshRequests();
            refreshLogin();
            refreshRisk();
            ui.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        TextView tvNode = findViewById(R.id.tv_node);
        tvNode.setText("节点: " + (WearClient.connectedNodeId.isEmpty() ? "未连接" : WearClient.connectedNodeName));

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvProto = findViewById(R.id.tv_proto);
        // 日志 TextView 必须设置 movement method 才能滚动查看历史
        tvProto.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvProto.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        tvEnv = findViewById(R.id.tv_env);
        tvLogin = findViewById(R.id.tv_login);
        tvRisk = findViewById(R.id.tv_risk);
        lvRequests = findViewById(R.id.lv_requests);
        reqAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, reqLabels);
        lvRequests.setAdapter(reqAdapter);
        lvRequests.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            List<RequestTracker.Record> all = RequestTracker.all();
            if (pos >= 0 && pos < all.size()) {
                showRecordDialog(all.get(pos));
            }
        });

        setupTabs();
        setupTools();
        ui.post(refresh);
    }

    /** 点击请求 → Material 对话框显示完整详情，每个子目（请求/URL/状态/响应内容）带"复制到剪贴板"按钮 */
    private void showRecordDialog(RequestTracker.Record r) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(4), dp(16), 0);

        String status = r.state
                + (r.httpStatus > 0 ? "  HTTP " + r.httpStatus : "")
                + (r.bizCode != 0 ? "  code=" + r.bizCode : "")
                + "  " + r.bytes + " 字节  " + r.costMs + "ms";
        String transport = "fetch".equals(r.transport) ? "fetch" : "interconnect";
        addCopyRow(root, "请求", "#" + r.id + "  " + r.method, transport);
        addCopyRow(root, "URL", r.url, transport);
        addCopyRow(root, "状态", status, transport);
        addCopyRow(root, "响应内容（预览）", r.detail.isEmpty() ? "（无）" : r.detail, transport);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(root);
        new MaterialAlertDialogBuilder(this)
                .setTitle("请求详情")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 详情弹窗中的一行：标签（含使用方式小字标注）+ 值（可长按选择）+ 复制按钮 */
    private void addCopyRow(LinearLayout root, String label, String value, String transport) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        textCol.setPadding(0, 0, dp(8), 0);

        // 标签行：子目标签 + 使用方式小字（interconnect 绿 / fetch 红）
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(12);
        tvLabel.setTextColor(0xFF9E9E9E);
        tvLabel.setTypeface(tvLabel.getTypeface(), android.graphics.Typeface.BOLD);
        labelRow.addView(tvLabel);

        boolean isInter = !"fetch".equals(transport);
        TextView tvTag = new TextView(this);
        tvTag.setText(isInter ? " interconnect" : " fetch");
        tvTag.setTextSize(9);
        tvTag.setTextColor(isInter ? 0xFF4CAF50 : 0xFFF44336);
        tvTag.setPadding(dp(4), 0, 0, 0);
        labelRow.addView(tvTag);

        textCol.addView(labelRow);

        TextView tvValue = new TextView(this);
        tvValue.setText(value == null ? "" : value);
        tvValue.setTextSize(13);
        tvValue.setTextIsSelectable(true);
        textCol.addView(tvValue);

        row.addView(textCol);

        MaterialButton btnCopy = new MaterialButton(this);
        btnCopy.setText("复制");
        btnCopy.setMinWidth(0);
        btnCopy.setOnClickListener(v -> copyToClipboard(label, value));
        row.addView(btnCopy);

        root.addView(row);
    }

    /** 复制指定内容到系统剪贴板 */
    private void copyToClipboard(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value == null ? "" : value));
        toast("已复制 " + label);
    }

    /** dp 转 px（构建动态布局用） */
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tab_layout);
        View tabProto = findViewById(R.id.tab_protocol);
        View tabReq = findViewById(R.id.tab_requests);
        View tabTools = findViewById(R.id.tab_tools);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                tabProto.setVisibility(tab.getPosition() == 0 ? View.VISIBLE : View.GONE);
                tabReq.setVisibility(tab.getPosition() == 1 ? View.VISIBLE : View.GONE);
                tabTools.setVisibility(tab.getPosition() == 2 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void setupTools() {
        findViewById(R.id.btn_clear_proto).setOnClickListener(v -> {
            SyncLog.clearProto();
            lastProtoVersion = -1;
            refreshProto();
            toast("协议日志已清空");
        });
        findViewById(R.id.btn_login_refresh).setOnClickListener(v -> {
            if (LoginStore.isLoggedIn()) {
                TvLogin.refreshUserInfo();
                toast("正在刷新用户信息...");
            } else {
                toast("未登录，无 Cookie 可刷新");
            }
        });
        findViewById(R.id.btn_risk_reset).setOnClickListener(v -> {
            RiskControl.reset();
            refreshRisk();
            toast("风控状态已清除");
        });
        findViewById(R.id.btn_push_home).setOnClickListener(v ->
                FetchBridgeServer.sendDevFetch("首页", "home_rcmd", Collections.singletonMap("page", String.valueOf(System.currentTimeMillis() % 1000)), "/home"));
        findViewById(R.id.btn_push_popular).setOnClickListener(v ->
                FetchBridgeServer.sendDevFetch("热门", "home_popular", Collections.singletonMap("page", "1"), "/home"));
        findViewById(R.id.btn_push_live).setOnClickListener(v ->
                FetchBridgeServer.sendDevFetch("直播", "home_live_list", null, "/home"));
        findViewById(R.id.btn_push_hot).setOnClickListener(v ->
                FetchBridgeServer.sendDevFetch("热榜", "hot_search", Collections.singletonMap("limit", "10"), "/hotsearch"));
        findViewById(R.id.btn_push_dynamic).setOnClickListener(v ->
                FetchBridgeServer.sendDevFetch("动态", "dynamic_feed", Collections.singletonMap("page", "1"), "/dynamic"));
        findViewById(R.id.btn_push_my).setOnClickListener(v ->
                FetchBridgeServer.sendDevFetch("我的", "nav", null, "/my"));
        findViewById(R.id.btn_env_check).setOnClickListener(v -> {
            WearClient.environmentCheck();
            // 互联诊断（log38）：同时请求手环执行 connect.diagnosis() 并上报
            FetchBridgeServer.requestInterconnectDiag();
            toast("环境自检中，互联诊断结果约 10 秒内显示");
        });
        findViewById(R.id.btn_reconnect2).setOnClickListener(v -> WearClient.connectAndSetup());
        findViewById(R.id.btn_handshake2).setOnClickListener(v -> FetchBridgeServer.sendBridgeHandshake());
        findViewById(R.id.btn_clear_all).setOnClickListener(v -> {
            SyncLog.clearAll();
            RequestTracker.clear();
            reqLabels.clear();
            reqAdapter.notifyDataSetChanged();
            tvProto.setText("");
            toast("全部日志已清空");
        });
        findViewById(R.id.btn_manual_send).setOnClickListener(v -> {
            JsonObject packet = new JsonObject();
            packet.addProperty("id", "hello-" + System.currentTimeMillis());
            JsonObject msg = new JsonObject();
            msg.addProperty("msgtype", "HELLO");
            msg.addProperty("message", "");
            packet.addProperty("message", msg.toString());
            SyncLog.p("[同步器→手表]", "[手动] 发送官方 HELLO 信封");
            FetchBridgeServer.sendRaw(WearClient.nodeId(), packet.toString());
        });
    }

    private void refreshProto() {
        // 版本号驱动：清空/新日志都会自增，任何变化都刷新
        if (SyncLog.version == lastProtoVersion) return;
        lastProtoVersion = SyncLog.version;
        List<String> lines = SyncLog.recentProto(120);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        tvProto.setText(sb.toString());
        refreshEnv();
    }

    private void refreshEnv() {
        StringBuilder sb = new StringBuilder();
        sb.append("宿主: ").append(WearClient.hostAppDetail).append('\n');
        sb.append("SDK服务: ").append(WearClient.serviceConnected ? "已连接" : "未连接");
        if (WearClient.serviceApiLevel >= 0) sb.append(" (Level ").append(WearClient.serviceApiLevel).append(")");
        sb.append('\n');
        sb.append("节点: ").append(WearClient.connectedNodeId.isEmpty() ? "无" : WearClient.connectedNodeId)
                .append(WearClient.connectedNodeName.isEmpty() ? "" : " (" + WearClient.connectedNodeName + ")").append('\n');
        sb.append("手环应用: ").append(WearClient.wearAppFound.isEmpty() ? "未检测到" : WearClient.wearAppFound).append('\n');
        sb.append("监听: ").append(WearClient.messageListenerActive ? "运行中" : "未启动")
                .append("  网桥: ").append(QrState.bridgeHandshakeDone ? "已就绪" : "未就绪").append('\n');
        sb.append("权限: ").append(WearClient.permDeviceManager != null ? "DM=" + WearClient.permDeviceManager + " " : "")
                .append(WearClient.permNotify != null ? "NOTIFY=" + WearClient.permNotify : "").append('\n');
        sb.append("最近错误: ").append(WearClient.lastError.isEmpty() ? "无" : WearClient.lastError);
        sb.append('\n');
        // 互联诊断结果（log38）：手环端 connect.diagnosis() 上报
        sb.append(FetchBridgeServer.diagSummary());
        tvEnv.setText(sb.toString());
    }

    private void refreshLogin() {
        String text = LoginStore.summary();
        if (text.equals(lastLoginText)) return;
        lastLoginText = text;
        tvLogin.setText(text);
    }

    /** 风控状态（log36）：命中 -412/-352/-403 后显示封锁窗口剩余时间，窗口过期自动恢复"无" */
    private void refreshRisk() {
        tvRisk.setText(RiskControl.summary());
    }

    private void refreshRequests() {
        List<RequestTracker.Record> all = RequestTracker.all();
        List<String> labels = new ArrayList<>();
        for (RequestTracker.Record r : all) labels.add(r.label());
        boolean changed = labels.size() != reqLabels.size();
        if (!changed) {
            for (int i = 0; i < labels.size(); i++) {
                if (!labels.get(i).equals(reqLabels.get(i))) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            reqLabels.clear();
            reqLabels.addAll(labels);
            reqAdapter.notifyDataSetChanged();
        }
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresh);
        super.onDestroy();
    }
}
