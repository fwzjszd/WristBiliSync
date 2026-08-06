# HyperBili Interconnect 完整开发文档（可复刻版 · 附完整代码）

> 本文档整合 2026-08-02 ~ 2026-08-06 全部开发经验与版本记录（log1 ~ log49d），以**功能块**为单位说明每个模块的作用、实现位置与关键逻辑，**并列出所叙述代码的完整内容**（手环端为 `patch-standard.js` 补丁代码，手机端为 `WristBiliSyncServer` Java 源码）。接手者可据此完整还原整套系统。
>
> 项目根目录：`c:\Users\Bob\StudioProjects\HyperbiliInterconnect-main`（Windows）。

---

## 1. 项目概述

| 端 | 载体 | 说明 |
|---|---|---|
| 手环端 | WristBili 快应用（RPK） | 小米手环/手表上的 B 站快应用（基线 `WristBili1.5.6.rpk`）。**不直连网络**，所有请求经互联通道转发给手机 |
| 手机端 | WristBiliSyncServer 同步器（APK） | `WristBiliSyncServer/` 子目录，Android 原生 Java。负责互联建链、代发请求、注入登录态、图片转码分片、心跳保活 |

**核心架构：三段转发（bridge-only）**

```
手环快应用                    手机同步器                        B站服务器
 页面 __wbFetch    互联通道      FetchBridgeServer   HTTP       HttpRelay
   → fetchbridge ──→ (interconnect) ──→ 协议引擎 ──→ HttpRelay ──→ B站 API
 页面渲染 ←──────── (interconnect) ←── 响应（裁剪/图片占位）←──── 响应
```

- 手环不直连网络（manifest 移除 `system.fetch`/`system.request`，`directFetch` 已删，log18）。
- 真实登录 Cookie 只存手机端，`HttpRelay` 转发时无条件注入（log21）。
- 列表响应手机端裁剪为白名单字段（log42）；图片 URL 按字段白名单替换为 `file://img_N` 占位，下载转 PNG 分片下发，手环本地保存显示（log49）。

---

## 2. 双端一致性（必须保持）

| 项 | 手环 RPK | 手机 APK |
|---|---|---|
| 包名 | `com.example.band.bilibili.lite` | `com.example.band.bilibili.lite` |
| 签名 | RPK 重签 = `7640E1AB...82EE7`（CN=Hyperbili, O=SearchStars, L=Beijing） | `app/android.jks`（SHA-256 相同） |

- 两端不一致 → `SignatureVerifyFailedException`，互联被拒。
- ⚠️ 签名必须用 `app/android.jks`（根目录 `android.jks` 是旧密钥，错误）。

---

## 3. 通讯层：连接方式与协议（附代码）

### 3.1 Vela interconnect 底层通道

手环 fetchbridge 使用（替换 AstroBox QAIC `createSession`，不兼容）。`conn.send` 的 **data 必须传 Object**，这是 log3 的决定性发现——原代码传 `JSON.stringify(obj)` 字符串导致全部 send 被宿主拒绝（`code=202 invalid data`）。补丁 `SM_DATA_OLD/NEW`：

```js
// 修复前（String）：
                                    data: str,
// 修复后（Object）：
                                    data: obj, // Vela: data 必须为 Object（String 报 202 invalid data）
```

| API | 说明 |
|---|---|
| `interconnect.instance()` | 全局单例连接（多实例 onmessage 互相覆盖，见 4.1） |
| `conn.onopen(d)` | 连接建立，`d.isReconnected` |
| `conn.onclose(d)` / `conn.onerror(d)` | `d.code`/`d.data` |
| `conn.onmessage(d)` | 解包 `d.data`（`'data' in d` 兼容字符串/对象） |
| `conn.send({data, success, fail})` | **data 必须 Object** |
| `conn.getReadyState({success, fail})` | status：1=连接成功 / 2=断开 |
| `conn.diagnosis({timeout})` | 0=OK / 204=超时 / 1001=对端未安装 / 1000=其他 |

**平台限制**：单条消息大小有上限（大消息 ≥2-4KB 单发导致手环闪退）；Vela `img` 不支持 base64 data URI（log43）。

### 3.2 FetchBridge v3 + 官方信封协议

`{tag, id, ...}` JSON 消息。能力协商 caps：`version/chunk/maxChunkSize/encodings/compressions/ack/ackWindow`，取双方交集（version=3、chunkSize=4096、ackWindow=4）。官方信封：`{id, message:{msgtype,message}}`（FETCH/SHOWQR/HELLO）。

### 3.3 完整消息类型总表

| tag | 方向 | 作用 |
|---|---|---|
| `__hs__` | 双向 | 握手：count<2 回 count+1，**count>=1 完成**；手机可主动发 count=0 |
| `fetch` | 双向 | 请求（`{action,params}`/`{url,options}`）与响应（`{resp:{status,headers,body,bodyEncoding,raw}}` 或分块头） |
| `fetch-chunk` | 手机→手环 | 大响应分片 `{id,seq,total,data}` 4096/片 |
| `fetch-ack` | 手环→手机 | 滑动窗口 ACK `{id,ack}`，窗口 4，3s 超时兜底 |
| `fetch-progress` | 手机→手环 | "正在传输"信号 `{id,ts}`，手环收到重置该请求超时 |
| `img_begin`/`img_chunk` | 手机→手环 | 图片 base64 分片 `{id,total}` / `{id,seq,total,data}` 20KB/片 |
| `__ping__`/`__pong__` | 手机↔手环 | 心跳验活（10s ping / 30s 拉起） |
| `showqr_req`/`showqr_resp` | 手环→手机→手环 | 登录二维码请求/返回（qrUrl+auth_code） |
| `showqr_status`/`showqr_done` | 手机→手环 | 轮询进度 / 扫码确认（写占位标记） |
| `logout` | 手环→手机 | 退出登录通知（`LoginStore.clear()`） |
| `__diag_req__`/`__diag__` | 手机↔手环 | 互联诊断 |
| `__status__` | 手环→手机 | 状态上报 |
| `__dev_fetch__` | 手机→手环 | 开发者推送 `{name,action,params,uri}` |

### 3.4 握手代码（手环端，`HH_OLD/NEW` 补丁）

```js
function handleHandshake(msg) {
    var count = msg.count;
    addLog('收到握手 count=' + count);
    // 会话缺失保障（log33）：session 可能因 onclose/页面销毁置空，此时不回包 → 手机端永远收不到
    // count>=1 → 握手永不完成。收到握手时若会话缺失且网桥开启，立即重建会话再回包。
    if (!state.session) {
        try {
            if (state.enabled) createSession();
        } catch (e) {}
        if (!state.session) {
            addLog('收到握手但互联会话未建立，暂无法回包');
        }
    }
    // 记录最后收到握手回复的时间（活性基准，全局共享：无论哪个 bundle 收到都刷新）
    state.lastHs = new Date().getTime();
    try {
        if (typeof globalThis !== 'undefined') globalThis.__wb_hs_ts = state.lastHs;
    } catch (e) {}
```

### 3.5 活性与心跳

- **手环端**：`_bfReady`/`isReady`/页面 `_rdy` 依赖 `globalThis.__wb_hs_ts` **90s 窗口**。log49b 补丁 `ACTIVE_TS_OLD/NEW` 把"收到任意消息"也刷新时间戳：

```js
                            if (!msg || !msg.tag) return;
                            // log49b：收到手机端任意消息即刷新握手活性时间戳（__wb_hs_ts 由"最近握手"升级为"最近收到消息"），
                            // 心跳 ping / fetch-progress / 图片分片 / 响应 都能保活，90s 窗口内不误判"网桥未就绪/网络错误"
                            try {
                                state.lastHs = new Date().getTime();
                                if (typeof globalThis !== 'undefined') globalThis.__wb_hs_ts = state.lastHs;
                            } catch (e) {}
                            switch(msg.tag){
```

- **手机端**：`startHeartbeat()` 握手完成后启动，10s `__ping__`，30s 无 `__pong__` 主动握手拉起（代码见 5.2）。
- **传输感知**：保活轮询检测到未组装完的 chunkBuffers 时跳过"握手过期重握"（log39d），完整代码见 4.2。

---

## 4. 手环端功能块（patch-standard.js 补丁体系）

fetchbridge 模块在 **5 个 bundle** 内联独立副本：`app.js`、`Bridge/bridge.js`、`Settings/settings.js`、`Video/video.js`、`Fullscreen/fullscreen.js`（log40 基线迁移新增第 5 个）。页面级补丁作用于各自 bundle。

### 4.1 多实例问题根治（全局回调表 + 全局 id）

**作用**：conn 是平台单例，5 个 bundle 各内联 fetchbridge 副本，onmessage 回调互相覆盖 → 响应丢失/错配。根治：pending 与分块缓冲写入全局表，任一实例收到响应都能路由回发起实例；`genId` 用全局计数器（跨实例 id 冲突会覆盖全局表，log29）。补丁 `BF_GENID_OLD/NEW`：

```js
// genId 改用全局共享计数器（log29）：
function genId() {
    if (typeof globalThis !== 'undefined') {
        globalThis.__wb_reqCounter = globalThis.__wb_reqCounter || 0;
        return 'r' + (++globalThis.__wb_reqCounter);
    }
    return 'r' + (++state.reqIdCounter);
}
```

`bridgeFetch` 注册 pending 时同时写全局表（`BF_OLD/NEW` 内）：

```js
                            state.pending[id] = {
                                success: options.success,
                                fail: options.fail,
                                responseType: options.responseType,
                                timer: setTimeout(function() { /* 超时清理本地+全局 */ }, timeout)
                            };
                            if (typeof globalThis !== 'undefined') {
                                globalThis.__wb_pending = globalThis.__wb_pending || {};
                                globalThis.__wb_pending[id] = state.pending[id];
                            }
```

`handleFetchResponse`/`handleFetchChunk`/`tryAssembleChunks` 先查本地再查全局：

```js
                            var pending = state.pending[id];
                            if (!pending && typeof globalThis !== 'undefined' && globalThis.__wb_pending) pending = globalThis.__wb_pending[id];
```

### 4.2 连接管理（createSession / 保活 / 重连）

**作用**：互联连接建立、3s 保活轮询自愈、静默断开重连、握手过期重握、分块传输感知。补丁 `CS_REPLACEMENT`（核心段）：

```js
                        function createSession() {
                            if (state.session) return;
                            try {
                                // 改用 Vela 标准 interconnect API（instance/onopen/onmessage/send）
                                var conn = _system3.default.instance();
                                if (!conn) throw new Error('no interconnect instance');
                                addLog('interconnect 实例已获取');
                                function report(extra) { /* 状态上报 __status__ */ }
                                conn.onopen = function(d) {
                                    addLog('interconnect 已连接(onopen) isReconnected=' + (d ? d.isReconnected : '?'));
                                    report({ status: 'connected', msg: 'onopen' });
                                    if (!state.connected) {
                                        state.connected = true;
                                        state.failCount = 0;
                                        notifyStatus('connected');
                                        startHandshake();
                                    }
                                };
                                conn.onclose = function(d) {
                                    addLog('连接关闭 code=' + (d ? d.code : '') + ' msg=' + (d ? d.data : ''));
                                    state.connected = false;
                                    state.session = null;
                                    notifyStatus('connect_failed');
                                    scheduleReconnect();
                                };
                                conn.onerror = function(d) { /* 同上：置空 + 重连 */ };
                                conn.onmessage = function(d) {
                                    var payload = d;
                                    if (d && 'object' == typeof d && 'data' in d) payload = d.data;
                                    addLog('收到消息: ' + String(payload).substring(0, 60));
                                    onMessage(payload);
                                };
                                state.session = conn;
                                // getReadyState：status=1 恢复 / status=2 静默断开重连
                                // 保活轮询（每3s）：多实例回调覆盖自愈 + 握手活性校验 + 分块传输感知
                                if (!state.keepAliveTimer) {
                                    state.keepAliveTimer = setInterval(function() {
                                        if (!state.enabled) return;
                                        if (!state.connected) {
                                            if (!state.reconnectTimer) scheduleReconnect();
                                            if (conn.getReadyState) {
                                                conn.getReadyState({
                                                    success: function(d) {
                                                        if (d && d.status === 1 && !state.connected) {
                                                            addLog('保活: 检测到连接已建立(status=1)，恢复状态');
                                                            state.connected = true;
                                                            state.failCount = 0;
                                                            notifyStatus('connected');
                                                            startHandshake();
                                                        }
                                                    },
                                                    fail: function() {}
                                                });
                                            }
                                            return;
                                        }
                                        // 握手活性校验：>90秒未收到 __hs__ 回复 → 重新握手验证
                                        var _hsNow2 = new Date().getTime();
                                        var _hsTs2 = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                                        var _hsFresh = _hsTs2 && (_hsNow2 - _hsTs2 <= 90000);
                                        if (!_hsFresh) {
                                            // log39d：分块传输进行中（存在未组装完的 chunkBuffers）跳过重新握手
                                            var _activeChunk = false;
                                            try {
                                                var _cb = (typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) ? globalThis.__wb_chunk_buffers : null;
                                                for (var _ck in _cb) {
                                                    if (_cb[_ck] && _cb[_ck].received < _cb[_ck].chunkCount) { _activeChunk = true; break; }
                                                }
                                            } catch (e) {}
                                            if (!_activeChunk) {
                                                if (state.handshakeDone) { state.handshakeDone = false; notifyStatus('handshake_timeout'); }
                                                // 每实例 8 秒节流，避免多 bundle 并发轰炸握手
                                                if (!state._hsProbeTs || (_hsNow2 - state._hsProbeTs > 8000)) {
                                                    state._hsProbeTs = _hsNow2;
                                                    addLog('保活: 握手过期，重新发起握手');
                                                    startHandshake();
                                                }
                                            }
                                        } else if (!state.handshakeDone && _hsFresh) {
                                            state.handshakeDone = true;
                                            notifyStatus('handshake_done');
                                        }
                                        if (conn.getReadyState) {
                                            conn.getReadyState({
                                                success: function(d) {
                                                    if (d && d.status === 2 && state.connected) {
                                                        addLog('保活检测到断开(status=2)，自动重连');
                                                        state.connected = false;
                                                        state.session = null;
                                                        state.handshakeDone = false;
                                                        notifyStatus('connect_failed');
                                                        scheduleReconnect();
                                                    }
                                                },
                                                fail: function() {}
                                            });
                                        }
                                    }, 3000);
                                }
                            } catch (e) {
                                addLog('interconnect 不可用: ' + e);
                                state.connected = false;
                                state.session = null;
                                notifyStatus('interconnect_unavailable');
                                scheduleReconnect();
                            }
                        }
```

**sendMessage 会话缺失自动重建（log37）**——原实现 `if (!state.session) return false` 静默丢弃导致握手回包/请求全部丢失：

```js
                        function sendMessage(obj) {
                            if (!state.session) {
                                try {
                                    if (state.enabled) createSession();
                                } catch (e) {}
                                if (!state.session) {
                                    addLog('发送消息失败：互联会话未建立');
                                    return false;
                                }
                            }
```

**enable/disable 全局同步（log37）**——页面层统一用 `globalThis.__wb_bridge.isReady()` 判断就绪，只改本实例 enabled 会导致状态不一致：

```js
                            enable: function() {
                                state.enabled = true;
                                // 全局同步：任何实例开启网桥时同步 globalThis.__wb_bridge 实例
                                try {
                                    var _gb = (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) ? globalThis.__wb_bridge : null;
                                    if (_gb && _gb !== this && _gb.enable) _gb.enable();
                                } catch (e) {}
                                _system2.default.set({ key: 'bili_bridge_enabled', value: 'on' });
                                if (!state.session) createSession();
                                notifyStatus('enabling');
                            },
```

**全局开关同步（ADDLOG_NEW 内）**——任何实例每 3s 跟随 storage 中的 `bili_bridge_enabled`，修复 Settings/Bridge 开关与各实例不一致：

```js
                        // 全局开关同步：任何实例每3秒跟随 storage 中的 bili_bridge_enabled
                        setInterval(function() {
                            try {
                                _system2.default.get({
                                    key: 'bili_bridge_enabled',
                                    success: function(d) {
                                        if ('on' === d && !state.enabled) {
                                            state.enabled = true;
                                            if (!state.session) createSession();
                                        } else if ('off' === d && state.enabled) {
                                            state.enabled = false;
                                            closeSession();
                                        }
                                    },
                                    fail: function() {}
                                });
                            } catch (e) {}
                        }, 3000);
```

### 4.3 状态真实性（getStatus / isReady 基于活性）

**作用**：`getReadyState` status=1 只反映宿主在线，不反映同步器存活；改为基于全局活性时间戳 `__wb_hs_ts`（90s 窗口）。补丁 `GETSTATUS_NEW` / `ISREADY_NEW`：

```js
                        getStatus: function() {
                            if (!state.enabled) return 'disabled';
                            var _hsTs = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                            if (_hsTs && (new Date().getTime() - _hsTs <= 90000)) return 'ready';
                            if (!state.connected) return 'connecting';
                            return 'handshaking';
                        }
```

```js
                        isReady: function() {
                            if (!state.enabled) return false;
                            if (!state.connected) return false;
                            var _hsTs = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                            return !!_hsTs && (new Date().getTime() - _hsTs <= 90000);
                        }
```

### 4.4 请求转发（bridgeFetch / 语义化 / bridge-only 等待就绪）

**作用**：手环发 `{tag:'fetch', action, params}`（语义化）或 `{url, options}`（直传）；网桥未就绪等待（最长 30s）后自动发送，绝不降级直连。补丁 `BF_OLD/NEW`：

```js
                        function bridgeFetch(options) {
                            addLog('网桥请求 ' + (options.method || 'GET') + ' ' + options.url);
                            // 网桥模式（bridge-only）：手环不直连网络，请求一律经互联由手机网络完成。
                            // 网桥未就绪时等待就绪（最长30秒）再发送，超时才失败——绝不降级直连
                            var _bfReady = function() {
                                var _ts = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                                return state.connected && state.handshakeDone && _ts && (new Date().getTime() - _ts <= 90000);
                            };
                            if (!_bfReady()) {
                                var _t = 0;
                                var _w = setInterval(function() {
                                    _t++;
                                    if (_bfReady()) {
                                        clearInterval(_w);
                                        bridgeFetch(options);
                                    } else if (_t >= 30) {
                                        clearInterval(_w);
                                        addLog('网桥未就绪，请求失败（已禁用直连）');
                                        if (options.fail) options.fail({ code: -2, message: '网桥未就绪，请稍后重试' });
                                    }
                                }, 1000);
                                return;
                            }
```

**语义化请求构造 + 登录态自动附加（`BF_HDRS_OLD/NEW`，log22/log10）**：

```js
                            var _hdrs = options.header || {};
                            if (state.cookie && !_hdrs.Cookie && !_hdrs.cookie) _hdrs.Cookie = state.cookie;
                            var reqMsg = {
                                tag: 'fetch',
                                id: id,
                                action: options.action || '',
                                params: options.params || {},
                                url: options.url || '',
                                transport: 'interconnect',
                                options: {
                                    method: options.method || 'GET',
                                    headers: _hdrs,
                                    body: options.data || '',
                                    raw: false
                                }
                            };
```

### 4.5 响应处理（单发 / 分块 / 超时）

**作用**：单发响应（body ≤16384 字符）走 `processSingleResponse`；分块响应（`{resp:{chunked,...}}` 头 + `fetch-chunk` 逐片 + ACK）走 `tryAssembleChunks`。分块头到达清除原 pending 超时（log39c 双计时器竞争），总超时由 `CHUNK_ASSEMBLY_TIMEOUT=180000` 兜底；收到 `fetch-progress` 重置超时（log49b，代码见 4.8）。超时常量：

```js
                        var FETCH_DEFAULT_TIMEOUT = 120000;    // log39：30s → 120s
                        var CHUNK_ASSEMBLY_TIMEOUT = 180000;   // log39：60s → 180s
```

分块头到达重置 pending 超时（log39c）：

```js
                            if (true === resp.chunked) {
                                // log39c：分块响应以"分块头到达"重新计时（清除原 pending 超时），
                                // 总超时改由分块组装超时 CHUNK_ASSEMBLY_TIMEOUT 兜底
                                if (pending.timer) clearTimeout(pending.timer);
                                state.chunkBuffers[id] = { chunkCount: resp.chunkCount || 0, received: 0, chunks: {},
                                    info: resp, timer: setTimeout(/* 组装超时清理 */, CHUNK_ASSEMBLY_TIMEOUT) };
```

**fetch-progress 分支（重置请求超时，log49b）**——追加在 `FILE_ONMSG_NEW` 的 `__ping__` case 之后：

```js
                                case 'fetch-progress':
                                    // log49b：手机端"正在传输"信号——确认连接正常并重置该请求超时（慢响应不误判 fetch timeout）
                                    try {
                                        if (msg && msg.id) {
                                            var _fp = state.pending[msg.id] || (typeof globalThis !== 'undefined' && globalThis.__wb_pending && globalThis.__wb_pending[msg.id]);
                                            if (_fp && _fp.timer) {
                                                clearTimeout(_fp.timer);
                                                var _fto = msg.timeout || 120000;
                                                _fp.timer = setTimeout(function() {
                                                    var _fp0 = state.pending[msg.id] || (typeof globalThis !== 'undefined' && globalThis.__wb_pending && globalThis.__wb_pending[msg.id]);
                                                    if (_fp0) {
                                                        delete state.pending[msg.id];
                                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_pending) delete globalThis.__wb_pending[msg.id];
                                                        if (state.chunkBuffers[msg.id]) delete state.chunkBuffers[msg.id];
                                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) delete globalThis.__wb_chunk_buffers[msg.id];
                                                        if (_fp0.fail) _fp0.fail({ code: -1, message: 'fetch timeout' });
                                                    }
                                                }, _fto);
                                            }
                                        }
                                    } catch (e) {}
                                    break;
```

### 4.6 图片分片接收与本地保存（log49）

**作用**：10 Pro 等低配设备 image 组件加载**网络 jpg 有 BUG**（感叹号），Vela img 不支持 base64 data URI → 手机端把图片压缩成 PNG(≤20KB) → base64 分片（20KB/片）→ 手环用 `@system.file` 写 `internal://files/img_N.png` → image 加载本地路径。手环性能差（连续写文件/解码 3 分钟内可能重启）→ **保存必须串行 + 800ms/张节流**。

补丁 `FILE_IMPORT_OLD/NEW`（import `@system.file`）：

```js
                        var _system2 = _interopRequireDefault($app_require$1("@app-module/system.storage"));
                        // log49：文件存储（把手机端下发的图片 base64 解码写为 internal://files/img_N.png，image 加载本地路径）
                        var _wfile = _interopRequireDefault($app_require$1("@app-module/system.file"));
```

补丁 `FILE_STATE_OLD/NEW`（state 增加图片缓冲与保存队列）：

```js
                        var state = {
                            session: null, connected: false, handshakeDone: false,
                            logs: [], cookie: "", lastHs: 0, _hsProbeTs: 0,
                            negotiated: null, enabled: false, reqIdCounter: 0,
                            pending: {}, chunkBuffers: {},
                            handshakeCallbacks: [], statusCallback: null,
                            reconnectTimer: null, failCount: 0,
                            // log49：图片 base64 分片缓冲（imgParts: id → {parts, total, got}）与保存队列（串行+节流防重启）
                            imgParts: {},
                            imgSaveQueue: [],
                            imgSaving: false
                        };
```

补丁 `FILE_ONMSG_OLD/NEW`（onMessage 增加 `img_begin`/`img_chunk`/`__ping__` 分支）：

```js
                                case 'img_begin':
                                    // log49：手机端开始下发图片 base64 分片（id 唯一，total=片数）
                                    try {
                                        if (msg && msg.id) {
                                            state.imgParts[msg.id] = { parts: {}, total: msg.total || 0, got: 0 };
                                        }
                                    } catch (e) {}
                                    break;
                                case 'img_chunk':
                                    // log49：分片到达 → 收齐后组装 base64 入保存队列（串行+节流写文件）
                                    try {
                                        if (msg && msg.id && state.imgParts[msg.id]) {
                                            var _ip = state.imgParts[msg.id];
                                            if (void 0 === _ip.parts[msg.seq]) {
                                                _ip.parts[msg.seq] = msg.data || '';
                                                _ip.got++;
                                                if (msg.total) _ip.total = msg.total;
                                            }
                                            if (_ip.total > 0 && _ip.got >= _ip.total) {
                                                var _ib = '';
                                                for(var _ii = 0; _ii < _ip.total; _ii++)_ib += _ip.parts[_ii];
                                                delete state.imgParts[msg.id];
                                                state.imgSaveQueue.push({ id: msg.id, b64: _ib });
                                                pumpImgSave();
                                            }
                                        }
                                    } catch (e) {}
                                    break;
                                case '__ping__':
                                    // log49：手机端 10s 心跳验活，手环回 pong（重启后手机端可据超时拉起重连）
                                    try {
                                        sendMessage({ tag: '__pong__', ts: msg.ts || 0 });
                                    } catch (e) {}
                                    break;
```

补丁 `FILE_PUMP_OLD/NEW`（`pumpImgSave` 串行保存 + 800ms 节流；⚠️ 就绪表键必须用 `'img_'+id` 与查询键一致）：

```js
                        // log49：图片保存队列（串行 + 每张保存后休息 800ms，防低配手环连续解码写文件 3 分钟内重启）
                        function pumpImgSave() {
                            if (state.imgSaving || 0 === state.imgSaveQueue.length) return;
                            var it = state.imgSaveQueue.shift();
                            state.imgSaving = true;
                            var _relax = function() {
                                setTimeout(function() {
                                    state.imgSaving = false;
                                    pumpImgSave();
                                }, 800);
                            };
                            try {
                                var bytes = base64Decode(it.b64);
                                var arr = new Uint8Array(bytes.length);
                                for(var _bi = 0; _bi < bytes.length; _bi++)arr[_bi] = bytes[_bi];
                                var uri = 'internal://files/img_' + it.id + '.png';
                                _wfile.default.writeArrayBuffer({
                                    uri: uri,
                                    buffer: arr,
                                    success: function() {
                                        if (typeof globalThis !== 'undefined') {
                                            globalThis.__wb_img_files = globalThis.__wb_img_files || {};
                                            // 键必须与 resolveImgWait 的查询键一致（'img_'+id，不能只存数字 id）
                                            globalThis.__wb_img_files['img_' + it.id] = uri;
                                        }
                                        _relax();
                                    },
                                    fail: function() { _relax(); }
                                });
                            } catch (e) { _relax(); }
                        }
```

### 4.7 文字先行 + 逐张出图（log49 / log49d）

**作用**：`resolveImgWait` 处理含 `file://img_N` 占位的 JSON 响应——①快路径（图已就绪）直接替换回调；②**文字先行**立即回调（图片位显 icon.png）；③**逐张出图**每新增一张就绪即回调一次（`_lastCnt` 防重复）；④20s 超时兜底。

```js
                        // log49d：响应含图片占位 file://img_N → **文字先行 + 逐张出图**：立即回调文字
                        // （图片位先显默认图 icon.png），手机端分片每保存完成一张即回调一次（已就绪替换为
                        // internal://files/img_N.png，未就绪保持 icon.png）——加载一张显示一张，不等全部。
                        // 全部就绪或超时 20s 兜底；翻页/刷新/缓存命中时图已就绪 → 快路径直接替换回调。
                        function resolveImgWait(text, pending, info, status) {
                            var _sub = function(_t) {
                                try {
                                    if (pending.success) pending.success({ data: safeJsonParse(_t), statusCode: status || 200, headers: info || {} });
                                } catch (e) {}
                            };
                            if ('string' != typeof text || !/file:\\/\\/img_\\d+/.test(text)) {
                                _sub(text);
                                return;
                            }
                            var _ids = text.match(/file:\\/\\/img_(\\d+)/g) || [];
                            var _allReady = function() {
                                for(var _wi = 0; _wi < _ids.length; _wi++){
                                    var _key = _ids[_wi].replace('file://img_', 'img_');
                                    if (!(typeof globalThis !== 'undefined' && globalThis.__wb_img_files && globalThis.__wb_img_files[_key])) return false;
                                }
                                return true;
                            };
                            var _build = function() {
                                // 已就绪的图替换为本地路径，未就绪的保持默认 icon（逐张出图，不等全部）
                                return text.replace(/file:\\/\\/img_(\\d+)/g, function(_m, _n) {
                                    var _f = (typeof globalThis !== 'undefined' && globalThis.__wb_img_files) ? globalThis.__wb_img_files['img_' + _n] : null;
                                    return _f || '/common/images/icon.png';
                                });
                            };
                            if (_allReady()) {
                                _sub(_build()); // 快路径：图片已就绪（翻页/刷新/去重缓存命中），直接出图
                                return;
                            }
                            _sub(_build()); // 文字先行：先回调文字（此时图片位全为默认 icon）
                            if (pending.__imgWaiting) return;
                            pending.__imgWaiting = true;
                            var _lastCnt = 0;
                            var _waited = 0;
                            (function wait() {
                                var _cnt = 0;
                                for(var _wi = 0; _wi < _ids.length; _wi++){
                                    var _key = _ids[_wi].replace('file://img_', 'img_');
                                    if (typeof globalThis !== 'undefined' && globalThis.__wb_img_files && globalThis.__wb_img_files[_key]) _cnt++;
                                }
                                if (_cnt > _lastCnt) {
                                    _lastCnt = _cnt;
                                    _sub(_build()); // 每新增一张就绪 → 回调一次，加载一张显示一张
                                }
                                if (_allReady() || _waited > 20000) return;
                                _waited += 300;
                                setTimeout(wait, 300);
                            })();
                        }
```

`FILE_PSR_NEW`（单发响应 JSON 统一走 resolveImgWait）：

```js
                            var finalData = decodedBody;
                            if ('json' === pending.responseType && 'string' == typeof finalData) {
                                // log49：JSON 响应统一走图片占位等待（含 file://img_N → internal://files/img_N.png 替换）
                                resolveImgWait(finalData, pending, resp.headers, resp.status);
                                return;
                            }
```

### 4.8 登录态（占位标记 / showqr 流程）

**作用**：真实 Cookie 只存手机端；手环只存占位标记 `bili_cookie='bili_logged=1;'`（>10 字符）+ `bili_login_ok='1'` + `bili_mid`。登录流程：My 页发 `showqr_req` → 手机申请 Web 二维码 + 2.5s 轮询 → 回 `showqr_resp`（qrUrl）→ 手环官方 `qrcode` 组件渲染 → 手机扫码 → `showqr_done` 写占位标记 → `fetchNav` 刷新。

`showqr_done` 分支（`DEV_FETCH_NEW` 内，写占位标记）：

```js
                                case 'showqr_done':
                                    // 手机端扫码确认：真实登录凭证只保存在手机端，手环端只保留登录状态；
                                    // bili_cookie 写占位标记（页面按"长度>10"判断已登录走个性化），真实 Cookie 由手机端转发时统一注入
                                    try {
                                        if (msg && 0 === msg.code) {
                                            _system2.default.set({ key: 'bili_cookie', value: 'bili_logged=1;' }); // 占位登录标记
                                            _system2.default.set({ key: 'bili_login_ok', value: '1' });
                                        }
                                    } catch (e) {}
                                    break;
```

My 页 `onInit`（`MY_ONINIT_NEW`，登录态以手机端 nav 为准，删除 800ms 占位自动跳码——log49b）：

```js
                        onInit () {
                            var self = this;
                            _system3.default.get({
                                key: 'bili_avatar_cached',
                                success: function(pic) {
                                    if (pic && pic.length > 10) self.avatar = pic;
                                },
                                fail: function() {}
                            });
                            this.loadProfile();
                            // log49b：登录态以手机端为准（loadProfile → fetchNav 请求 nav，手机端注入真实 Cookie）。
                            // 原 800ms 定时器用本地占位 bili_login_ok 判断，占位标记丢失（手机端已登录）时仍跳二维码 →
                            // 已删除；未登录由 fetchNavFallback 自动 createQr，网桥未就绪时保持"请求中"不误跳。
                        }
```

My 页 `loadProfile`（`MY_LOADPROFILE_NEW`，直接请求 nav）：

```js
                        loadProfile () {
                            var self = this;
                            self.cookie = ''; // 手环不持有 Cookie，登录态由手机端转发时统一附加
                            // 与登录成功后的自动加载完全同一套逻辑：直接请求 nav（登录状态以手机端为准，不再本地判断）
                            self.mode = '个性化';
                            self.showLogin = false;
                            self.showProfile = true;
                            self.mainBtnText = '刷新个人数据';
                            self.fetchNav();
                        }
```

Settings 页账号信息（`ST_ACCOUNT_NEW`，缓存优先 + 15s 节流 + 失败保留缓存）：

```js
                        loadAccountInfo () {
                            var self = this;
                            // 老设备性能优化（log42）：先读本地缓存立即显示（秒开，不依赖网络）
                            _system2.default.get({
                                key: 'bili_uname_cached',
                                success: function(uname) {
                                    if (uname && uname.length > 0) {
                                        self.accountName = uname;
                                        self.accountStatus = '查看个人主页';
                                    }
                                },
                                fail: function() {}
                            });
                            _system2.default.get({
                                key: 'bili_avatar_cached',
                                success: function(avatar) {
                                    if (avatar && avatar.length > 5) self.accountAvatar = avatar;
                                },
                                fail: function() {}
                            });
                            // 请求节流：15 秒内已请求过 nav 则跳过（onInit/onShow/onStatusChange 会重复触发）
                            var _now = new Date().getTime();
                            if (self._accTs && (_now - self._accTs < 15000)) return;
                            self._accTs = _now;
                            // 登录状态以手机端为准：直接请求 nav（不再依赖手环本地 bili_cookie 占位）
                            _fetchbridge.default.fetch({
                                action: 'nav',
                                responseType: 'json',
                                success: function(res) {
                                    var data = res.data;
                                    if ('string' == typeof data) data = JSON.parse(data);
                                    if (data && data.data && data.data.isLogin) {
                                        self.accountName = data.data.uname || '已登录';
                                        self.accountStatus = '查看个人主页';
                                        _system2.default.set({ key: 'bili_uname_cached', value: self.accountName });
                                        if (data.data.face) {
                                            var _f = data.data.face;
                                            if (0 !== _f.indexOf('http') && 0 === _f.indexOf('//')) _f = 'https:' + _f;
                                            if (_f.indexOf('@') > 0) _f = _f.split('@')[0];
                                            if (_f.indexOf('hdslb.com') > 0 || _f.indexOf('biliimg.com') > 0) _f += '@32w_32h_1e_1c.jpg';
                                            self.accountAvatar = _f;
                                            _system2.default.set({ key: 'bili_avatar_cached', value: _f });
                                        }
                                    } else {
                                        self.accountName = '未登录';
                                        self.accountStatus = '点击登录账号';
                                    }
                                },
                                fail: function() {
                                    // 请求失败：保留缓存显示（老设备网络慢时不闪变为"未登录"）
                                }
                            });
                        }
```

首页登录态兜底（`HOME_LOGIN_NEW`，占位丢失时请求 nav 确认真实登录并补写占位）：

```js
                            _system3.default.get({
                                key: 'bili_cookie',
                                success: function(data) {
                                    self.cookie = data || '';
                                    self.logged = self.cookie.length > 10;
                                    // log49b：占位标记可能丢失（手机端已登录/手环存储被清）——兜底请求 nav 确认真实登录态
                                    if (!self.logged) {
                                        try {
                                            __wbFetch({
                                                url: 'https://api.bilibili.com/x/web-interface/nav',
                                                responseType: 'json',
                                                timeout: 15000,
                                                success: function(_r2) {
                                                    var _d2 = _r2.data;
                                                    if ('string' == typeof _d2) _d2 = JSON.parse(_d2);
                                                    if (_d2 && _d2.data && _d2.data.isLogin) {
                                                        _system3.default.set({ key: 'bili_login_ok', value: '1' });
                                                        _system3.default.set({ key: 'bili_cookie', value: 'bili_logged=1;' });
                                                        self.logged = true;
                                                        if (self.loadTip && self.loadTip.indexOf('未登录') >= 0) self.loadTip = '已登录';
                                                    }
                                                },
                                                fail: function() {}
                                            });
                                        } catch (e) {}
                                    }
```

### 4.9 分页加载（log42 / log45）

**作用**：每个分页页面底部是 **[上一页] [第 X 页] [下一页]** 三件套 + 独立翻页逻辑。首页 5 个/页 × 4 页（ps 由手机端 BiliApiMap 统一），评论区 pn 1-10，动态 page 1-3，历史 max 游标，私信客户端 6/页。

首页 `applyList` 分页填充（`HOME_APPLY_NEW`）：

```js
                        applyList (list) {
                            var i = 0;
                            var page = this.refreshPage || 1;
                            this.sectionTitle = '首页推荐';
                            this.stopPicLoader();
                            this.stopFramePreloader();
                            this.pendingPics = [];
                            // log42：5 个一页分页填充（清空 20 格，仅填当前页 5 格；refresh 翻页 1→4）
                            for(i = 1; i <= 20; i++) {
                                this['v' + i + 'Title'] = '';
                                this['v' + i + 'Stat'] = '';
                                this['v' + i + 'Bvid'] = '';
                                this['v' + i + 'Cid'] = '';
                                this['v' + i + 'Pic'] = '';
                            }
                            var startIdx = (page - 1) * 5;
                            for(i = 0; i < list.length && i < 5; i++)this.setItem(startIdx + i + 1, list[i]);
                            this.loadedCount = list.length;
                            if (list.length >= 3) this.stopSpinner();
                            this.startPicLoader();
                            this.buildPreloadFrames(list);
                            this.startFramePreloader();
                        },
```

首页翻页/刷新方法（`HOME_PREVNEXT_NEW`，log45 追加 `refreshFeed` 换全新批次）：

```js
                        prevPage () {
                            this.refreshPage = (this.refreshPage || 1) <= 1 ? 4 : this.refreshPage - 1;
                            this.checkLoginAndLoad();
                        },
                        nextPage () {
                            this.refreshPage = (this.refreshPage || 1) >= 4 ? 1 : this.refreshPage + 1;
                            this.checkLoginAndLoad();
                        },
                        refreshFeed () {
                            this.freshBase = (this.freshBase || 0) + 4;
                            this.refreshPage = 1;
                            this.checkLoginAndLoad();
                        },
```

首页语义化参数（`HOME_POPULAR_NEW`，fresh_idx 用 freshBase+refreshPage）：

```js
                            // log45：刷新翻页批号整体后移（freshBase += 4 → fresh_idx 全新批次）
                            __wbFetch({ action: 'home_rcmd', params: { page: (this.freshBase || 0) + this.refreshPage }, ... });
```

评论区分页（`COMMENT_METHODS_NEW`，pn 1-10 循环）：

```js
                        prevCommentPage () {
                            this.commentPage = (this.commentPage || 1) <= 1 ? 10 : this.commentPage - 1;
                            this.loadComments();
                        },
                        nextCommentPage () {
                            this.commentPage = ((this.commentPage || 1) >= 10) ? 1 : this.commentPage + 1;
                            this.loadComments();
                        },
```

### 4.10 按钮体系

- 分页三件套（4.9）：[上一页][第 X 页][下一页]，模板注入 pager-btn 系列样式。
- 首页"刷新"按钮（log45）：独立一行，调 `refreshFeed()`。
- Bridge 页"推送测试"卡片（3×2 按钮：首页/热门/直播/热榜/动态/我的，log12/27）：点击调 `_fetchbridge.default.fetch({action,params})` 走真实三段转发，结果记日志区 + 状态栏。
- 日志界面：最近 12 条自动滚动（log7）：`PG_ONINIT_NEW` 每秒刷新 `self.logText = l.slice(-12).join('\n')`。

### 4.11 页面级优化

- **功能栏常驻**（`HOME_TAB_VIS_NEW`，log42）：tab 栏 `shown` 恒 `true`：

```js
                                shown: function() {
                                    // log42：功能栏常驻（网桥未连接/加载失败时也显示 tab 栏，保证可进设置）
                                    return true;
                                }
```

- **头像主请求改 nav**（`AVATAR_URL_NEW`）：`myinfo`（App 端必返 -400）→ `nav`（Web 稳定）。
- **URL 清洗**（`HOME_SETITEM_NEW`，log47，修复每页第一个视频图片加载不出）：

```js
                            var pic = item.pic || '/common/images/icon.png';
                            if (0 !== pic.indexOf('http') && 0 === pic.indexOf('//')) pic = 'https:' + pic;
                            if (pic.indexOf('@') > 0) pic = pic.split('@')[0]; // log47：去掉已有 @ 参数（双 @ 无效 URL）
                            if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@96w_60h_1e_1c.jpg';
```

- **图片尺寸降级**（log40b/log44）：封面 96x60、头像 32x32、预载帧最小缩略、砍原图预载。
- **10 Pro 兼容**（log43）：背景百分比 → 固定像素；JS 判断动态 classList ≤3 处。

### 4.12 App 生命周期保护（log49c）

**作用**：右滑退出（触发 App `onDestroy`）不再关闭网桥——原版 `onDestroy(){ _fetchbridge.default.destroy(); }` 会 closeSession 断开连接 + 清空 enabled。补丁 `APP_ONDESTROY_OLD/NEW`：

```js
                            onDestroy () {
                                // log49c：右滑退出不关闭网桥（destroy() 会 closeSession 断开互联连接 + 清空 enabled，
                                // 导致"右滑退出网桥就关掉"）。互联连接由平台随 App 退出自动清理，
                                // 重进 App 时 onCreate→init() 从 storage 恢复开关（bili_bridge_enabled='on'）自动重连。
                                // ⚠️ 网桥开关核心逻辑，以后不要再改动。
                            }
```

> ⚠️ **用户硬性规则：网桥开关/连接相关代码（onDestroy、closeSession、Bridge 开关、enable/disable）以后不要再改动。**

---

## 5. 手机端功能块（WristBiliSyncServer，包 `com.wristbili.sync`）

### 5.1 WearClient —— 连接管理（完整核心代码）

**作用**：封装 xms-wearable-lib（`app/libs/xms-wearable-lib_1.4_release.aar`）——SDK 初始化、节点扫描、应用检测、权限申请、消息监听、发送。**监听先行、权限后置**（log30-32）：权限链任何一环卡住也不阻塞监听与主动握手。

```java
/** 扫描节点：监听先行 + 权限链独立并行（log30-32） */
public static void scanNodes() {
    SyncLog.i("开始扫描已连接节点...");
    try {
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    SyncLog.i("已连接节点数: " + nodes.size());
                    if (nodes.isEmpty()) { /* 无节点提示 */ return; }
                    Node n = nodes.get(0);
                    connectedNodeId = n.id;
                    connectedNodeName = n.name;
                    QrState.connected = true;
                    SyncLog.i("连接节点: " + n.id + " (" + n.name + ")");
                    // 监听先行：立即启动消息监听（成功后 2s 自动主动握手），不依赖权限链
                    startListening(n.id);
                    // 权限链独立运行：申请互联权限 → 检测手环端应用（宽容模式不阻断）
                    checkAndRequestPermission(n.id);
                })
                .addOnFailureListener(e -> SyncLog.i("获取节点列表失败: " + describeException(e)));
    } catch (Exception e) {
        SyncLog.i("扫描节点异常: " + describeException(e));
    }
}

/** 启动消息监听（防止重复注册），成功后延迟 2s 主动握手 */
public static void startListening(String nodeId) {
    if (nodeId == null || nodeId.isEmpty()) { SyncLog.i("无节点，无法开始监听"); return; }
    if (messageListenerActive) { SyncLog.i("消息监听已在运行中"); return; }
    try {
        messageApi.addListener(nodeId, messageListener)
                .addOnSuccessListener(v -> {
                    messageListenerActive = true;
                    SyncLog.i("消息监听已启动, node=" + nodeId);
                    new Thread(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        FetchBridgeServer.sendBridgeHandshake();
                    }).start();
                })
                .addOnFailureListener(e -> SyncLog.i("启动消息监听失败: " + describeException(e)));
    } catch (Exception e) {
        SyncLog.i("addListener 异常: " + describeException(e));
    }
}

/** 向手环发送文本消息（底层发送，全部协议消息出口） */
public static void sendToWatch(String nodeId, String json) {
    try {
        if (messageApi == null || nodeId == null || nodeId.isEmpty()) {
            SyncLog.i("[发送失败] messageApi=" + (messageApi != null) + " node='" + nodeId + "'");
            return;
        }
        messageApi.sendMessage(nodeId, json.getBytes("UTF-8"))
                .addOnSuccessListener(v -> {})
                .addOnFailureListener(e -> SyncLog.i("[网桥] 发送失败: " + describeException(e)));
    } catch (Exception e) {
        SyncLog.i("[网桥] 发送异常: " + e);
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
```

### 5.2 FetchBridgeServer —— 协议引擎（核心代码）

**作用**：处理手环所有互联消息 + 回传响应。静态类，入口 `onMessage(nodeId, data)`。

**入口分发（带 tag → FetchBridge v3；否则 → 官方信封）：**

```java
public static void onMessage(String nodeId, String data) {
    SyncLog.p("[手表→同步器]", truncate(data, 2000));
    // 1) 先判 FetchBridge 协议（带 tag 字段）
    try {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        if (json.has("tag")) {
            handleBridgeMessage(nodeId, json);
            return;
        }
    } catch (Exception ignored) {}
    // 2) 官方 HyperBili 信封协议（{id, message:{msgtype, message}}）
    handleLegacyPacket(nodeId, data);
}
```

**handleBridgeMessage（握手 / 心跳 pong / 登出 / 诊断 / 二维码请求 等）：**

```java
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
            SyncLog.i("[网桥] 收到 fetch-chunk #" + optStr(msg, "id", "") + "（同步器不做请求分块）");
            break;
        case "fetch-ack": {
            String ackId = optStr(msg, "id", "");
            int ack = optInt(msg, "ack", -1);
            ChunkSendState st = chunkSenders.get(ackId);
            if (st != null && !st.done) {
                if (ack > st.ackBase) st.ackBase = ack;
                sendNextChunks(st);
            }
            break;
        }
        case "logout":
            // 手环退出登录通知：真实 Cookie 只存手机端，同步清除
            LoginStore.clear();
            SyncLog.i("[登录] 收到手环退出登录通知，已清除手机端登录信息");
            break;
        case "__pong__":
            // log49：心跳回应，30s 无 pong 判定手环重启/断连 → 主动握手拉起
            lastPongAt = System.currentTimeMillis();
            if ((++pongCount % 30) == 1) SyncLog.i("[网桥] 心跳 pong 正常");
            break;
        case "__diag__":
            diagStatus = msg.has("status") ? msg.get("status").getAsInt() : -1;
            diagCode = msg.has("code") ? msg.get("code").getAsInt() : 0;
            diagMsg = optStr(msg, "msg", "");
            diagAt = System.currentTimeMillis();
            break;
        case "showqr_req":
            TvLogin.requestQr(new TvLogin.QrCallback() {
                @Override
                public void onSuccess(String qrUrl, String authCode) {
                    JsonObject out = new JsonObject();
                    out.addProperty("tag", "showqr_resp");
                    out.addProperty("code", 0);
                    out.addProperty("qrUrl", qrUrl);
                    out.addProperty("auth_code", authCode);
                    sendRaw(nodeId, gson.toJson(out));
                    TvLogin.startPolling(nodeId, authCode);
                }
                @Override
                public void onFailure(int bizCode, String message) {
                    JsonObject out = new JsonObject();
                    out.addProperty("tag", "showqr_resp");
                    out.addProperty("code", bizCode);
                    out.addProperty("message", message == null ? "" : message);
                    sendRaw(nodeId, gson.toJson(out));
                }
            });
            break;
        default:
            SyncLog.i("[网桥] 未知 tag: " + tag);
    }
}
```

**handleBridgeFetch（收到请求立即回 fetch-progress → 语义化组装 → 转发）：**

```java
private static void handleBridgeFetch(String nodeId, JsonObject msg) {
    String id = optStr(msg, "id", "");
    // log49b：收到请求立即回"正在传输"信号——手环端确认连接正常并重置该请求超时
    sendProgress(nodeId, id);
    String url = optStr(msg, "url", "");
    JsonObject options = msg.has("options") && msg.get("options").isJsonObject()
            ? msg.getAsJsonObject("options") : new JsonObject();
    String method = optStr(options, "method", "GET");
    boolean raw = options.has("raw") && options.get("raw").getAsBoolean();

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
            options = new JsonObject();
            options.addProperty("method", method);
            // wbi 签名：首页个性化推荐 rcmd 强制要求
            if ("home_rcmd".equals(action) && !apiUrls.isEmpty()) {
                String signed = WbiSigner.sign(apiUrls.get(0));
                if (signed != null && !signed.isEmpty()) {
                    apiUrls.set(0, signed);
                    if (!multiUrl) url = signed;
                }
            }
        }
    }
    // 多候选 URL 依次尝试 / 单 URL 直接转发（HttpRelay）
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
            sendFetchResponse(nodeId, id, 502, new HashMap<>(), errBody.getBytes(StandardCharsets.UTF_8), "text", raw);
        }
    });
}
```

**finishAndSend（业务码 + 风控 + 响应裁剪 + 图片占位替换 + 回传）：**

```java
private static void finishAndSend(String nodeId, String id, int status,
        Map<String, List<String>> respHeaders, byte[] bodyBytes,
        String encoding, boolean raw, RequestTracker.Record rec, String action) {
    String bodyText = "text".equals(encoding) ? new String(bodyBytes, StandardCharsets.UTF_8) : "";
    int biz = extractBizCode(bodyText);
    rec.bizCode = biz;
    RiskControl.onBizCode(biz, action, rec.url);   // 风控封锁判断（log36）
    RequestTracker.finish(rec, status, bodyBytes.length, bodyText);
    // log42：大响应裁剪（只保留手环端渲染白名单字段）
    if ("text".equals(encoding) && bodyBytes.length > 2048) {
        String slim = slimResponse(action, bodyText);
        if (slim != null && !slim.isEmpty() && slim.length() < bodyText.length()) {
            bodyBytes = slim.getBytes(StandardCharsets.UTF_8);
        }
    }
    // log49：图片 base64 分片方案——内容图字段 URL 替换为 file://img_N 占位
    if ("text".equals(encoding) && bodyBytes.length > 0 && !isImageSkipAction(action)) {
        String cur = new String(bodyBytes, StandardCharsets.UTF_8);
        String replaced = replaceImageUrls(cur, nodeId);
        if (!replaced.equals(cur)) bodyBytes = replaced.getBytes(StandardCharsets.UTF_8);
    }
    sendFetchResponse(nodeId, id, status, respHeaders, bodyBytes, encoding, raw);
}
```

**sendFetchResponse（≤16384 单发；否则分块 + 滑动窗口 ACK）：**

```java
private static void sendFetchResponse(String nodeId, String id, int status,
        Map<String, List<String>> respHeaders, byte[] bodyBytes, String encoding, boolean raw) {
    // headers → JsonObject；body → base64 或 text
    String bodyStr = "base64".equals(encoding)
            ? Base64.encodeToString(bodyBytes, Base64.NO_WRAP)
            : new String(bodyBytes, StandardCharsets.UTF_8);
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
        sendRaw(nodeId, gson.toJson(out));
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
        sendRaw(nodeId, gson.toJson(head));
        // 分片列表 → 滑动窗口发送（sendNextChunks：窗口内发送、等 fetch-ack 推进、3s 超时兜底）
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
```

**图片占位替换（JSON 树遍历 + 字段白名单，log49）：**

```java
/** 内容图字段白名单：只有这些字段的 URL 走 base64 分片（图标类字段名不在此列 → 保持原样直接显示） */
private static final Set<String> IMG_CONTENT_FIELDS = new HashSet<>(Arrays.asList(
        "pic", "cover", "face", "avatar", "src", "url",
        "avatar_url", "user_face", "talker_face", "sub_pic",
        "keyframe", "cover_url", "image", "user_cover",
        "thumbnail", "bphoto", "preview", "poster"));

private static String replaceImageUrls(String text, String nodeId) {
    if (text == null || text.isEmpty()) return text;
    try {
        JsonElement el = JsonParser.parseString(text);
        JsonElement out = replaceImgInTree(el, nodeId);
        String outStr = out.toString();
        if (!outStr.equals(text)) {
            pumpImageSender();   // 触发单线程图片分发器
            return outStr;
        }
        return text;
    } catch (Exception e) {
        return text;  // 非 JSON 文本：保守不替换
    }
}

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
            if (!r.equals(s)) { v = new JsonPrimitive(r); changed = true; }
        } else if (v != null && (v.isJsonObject() || v.isJsonArray())) {
            JsonElement nv = replaceImgInTree(v, nodeId);
            if (nv != v) { v = nv; changed = true; }
        }
        out.add(k, v);
    }
    return changed ? out : el;
}

/** 把字符串值内的 B 站图片 URL（排除 /bfs/wbi/）替换为 file://img_N 占位并入队下载 */
private static String replaceUrlInField(String s, String nodeId) {
    if (s == null || s.isEmpty() || s.indexOf("hdslb.com") < 0 && s.indexOf("biliimg.com") < 0) return s;
    Matcher m = BILI_IMG_URL.matcher(s);
    StringBuilder sb = new StringBuilder();
    boolean hit = false;
    while (m.find()) {
        String u = m.group();
        if (u.contains("/bfs/wbi/")) continue;   // wbi 密钥图不替换
        Integer cached = imgUrlCache.get(u);
        int id;
        if (cached != null) {
            id = cached;
        } else {
            id = imgIdCounter.getAndIncrement();
            Integer prev = imgUrlCache.putIfAbsent(u, id);
            if (prev != null) { id = prev; }
            else { imgQueue.add(new ImgJob(nodeId, u, id)); }
        }
        hit = true;
        m.appendReplacement(sb, Matcher.quoteReplacement("file://img_" + id));
    }
    if (!hit) return s;
    m.appendTail(sb);
    if (imgUrlCache.size() > 500) imgUrlCache.clear();
    return sb.toString();
}
```

**图片分片下发（sendImageChunks + 单线程分发器）：**

```java
/** 单线程图片分发器：串行处理队列（下载→分片下发→休息），防互联拥塞/手环过载 */
private static void pumpImageSender() {
    if (!imgPumpRunning.compareAndSet(false, true)) return;
    imgSender.submit(() -> {
        try {
            while (true) {
                ImgJob job = imgQueue.poll();
                if (job == null) break;
                handleImgJob(job);
                try { Thread.sleep(IMG_INTERVAL_MS); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            SyncLog.i("[图片] 分发异常: " + e);
        } finally {
            imgPumpRunning.set(false);
            if (!imgQueue.isEmpty()) pumpImageSender();
        }
    });
}

/** 单张图片：下载 → 转 PNG ≤20KB → base64 → 分片下发；失败兜底 1x1 透明 PNG */
private static void handleImgJob(ImgJob job) {
    String b64 = ImageTransfer.downloadAsPngBase64(job.url);
    if (b64 == null) {
        SyncLog.i("[图片] 下载/转码失败 #" + job.id + "（兜底 1x1）");
        b64 = ImageTransfer.FALLBACK_PNG_B64;
    }
    sendImageChunks(job.nodeId, job.id, b64);
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
```

**心跳（startHeartbeat）与传输进度信号（sendProgress）：**

```java
/** 10s 心跳：ping 手环验活；30s 无 pong → 判定手环重启/断连 → 主动握手拉起（幂等） */
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
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (now - lastPongAt > 30000) {
            SyncLog.i("[网桥] 心跳超时（>30s 无 pong，手环可能重启/断连），主动握手拉起");
            sendBridgeHandshake();
            lastPongAt = now;
        }
    }, 10, 10, TimeUnit.SECONDS);
    SyncLog.i("[网桥] 心跳已启动（10s ping / 30s 超时拉起）");
}

/** 请求处理中信号：手环端收到后确认连接正常并重置该请求超时（慢响应不误判网络错误） */
private static void sendProgress(String nodeId, String id) {
    if (nodeId == null || nodeId.isEmpty() || id == null || id.isEmpty()) return;
    try {
        JsonObject p = new JsonObject();
        p.addProperty("tag", "fetch-progress");
        p.addProperty("id", id);
        p.addProperty("ts", System.currentTimeMillis());
        sendRaw(nodeId, gson.toJson(p));
    } catch (Exception ignored) {}
}
```

### 5.3 HttpRelay —— HTTP 转发器（核心代码）

**作用**：OkHttp 任意 method/headers/body 原样转发；**登录态统一注入**（B 站域名且有登录 Cookie 时无条件覆盖请求头）；**写操作 csrf 自动补齐**（bili_jct）；UA/Referer 兜底；UTF-8 校验选 text/base64。

```java
public static void execute(String url, String method, Map<String, String> headers,
                           String body, boolean raw, RelayCallback cb) {
    try {
        // 登录态统一注入：真实登录凭证只存手机端，手环传的占位/空 Cookie 一律替换
        String storedCookie = LoginStore.cookie();
        if (!storedCookie.isEmpty() && url != null
                && (url.contains("bilibili.com") || url.contains("biliapi.net") || url.contains("biligame.com"))) {
            Map<String, String> nh = headers != null ? new HashMap<>(headers) : new HashMap<>();
            nh.remove("Cookie");
            nh.remove("cookie");
            nh.put("Cookie", storedCookie);
            headers = nh;
            SyncLog.i("[登录态] 统一注入登录 Cookie（" + storedCookie.length() + " 字符）");
        }

        Request.Builder rb = new Request.Builder().url(url);
        // 逐头设置；无 UA 补默认
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        if (!hasHeader(headers, "user-agent")) rb.header("User-Agent", DEFAULT_UA);
        if (url != null && url.contains("bilibili.com") && !hasHeader(headers, "referer")) {
            rb.header("Referer", "https://www.bilibili.com/");
        }

        String m = method == null ? "GET" : method.toUpperCase();

        // 写操作 csrf 补齐：手环已不持有真实 Cookie（取不到 bili_jct），POST body 缺 csrf 时由手机端补齐
        if (("POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m)) && body != null && !body.isEmpty()) {
            String jct = LoginStore.biliJct();
            if (!jct.isEmpty()) {
                if (body.contains("csrf=") || body.contains("csrf_token=")) {
                    body = body.replaceAll("csrf=[^&;]*", "csrf=" + android.net.Uri.encode(jct))
                            .replaceAll("csrf_token=[^&;]*", "csrf_token=" + android.net.Uri.encode(jct));
                } else {
                    body = body + (body.endsWith("&") ? "" : "&") + "csrf=" + android.net.Uri.encode(jct);
                }
            }
        }

        switch (m) {
            case "POST": case "PUT": case "PATCH": case "DELETE":
                MediaType mt = ...; // content-type 或 application/octet-stream
                rb.method(m, RequestBody.create(body.getBytes(StandardCharsets.UTF_8), mt));
                break;
            default:
                rb.method("GET", null);
        }

        httpClient.newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                int status = response.code();
                Map<String, List<String>> respHeaders = new HashMap<>();
                for (String name : response.headers().names()) {
                    respHeaders.put(name, response.headers().values(name));
                }
                byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                String encoding = isValidUtf8(bytes) ? "text" : "base64";
                cb.onResponse(status, respHeaders, bytes, encoding);
            }
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                cb.onFailure("{\"code\":-1,\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
    } catch (Exception e) {
        cb.onFailure("{\"code\":-1,\"message\":\"" + escapeJson(String.valueOf(e.getMessage())) + "\"}");
    }
}
```

### 5.4 BiliApiMap —— 语义化映射表（核心代码）

**作用**：手环 `{action, params}` → 真实请求 URL，多候选依次尝试。覆盖约 40 个 action。

```java
private static final Map<String, ApiDef> MAP = new HashMap<>();

static {
    // ===== 首页 =====
    // 个性化推荐（wbi 签名由手机端附加）；HTTP 层失败自动兜底热门；log42：ps=5（首页 5 个/页分页）
    MAP.put("home_rcmd", new ApiDef("GET",
            "https://api.bilibili.com/x/web-interface/index/top/feed/rcmd?ps=5&fresh_type=4&fresh_idx={page}",
            "https://api.bilibili.com/x/web-interface/popular?ps=5&pn={page}"));
    MAP.put("home_popular", new ApiDef("GET",
            "https://api.bilibili.com/x/web-interface/popular?ps=5&pn={page}"));
    // 直播列表：4 个接口依次尝试
    MAP.put("home_live_list", new ApiDef("GET",
            "https://api.live.bilibili.com/room/v2/Area/getRoomList?parent_area_id=0&area_id=0&sort_type=online&page=1&page_size=10",
            "https://api.live.bilibili.com/room/v1/Area/getRoomList?...",
            "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList?...",
            "https://api.live.bilibili.com/room/v1/AppIndex/getAllList?..."));
    // ===== 我的 =====
    MAP.put("nav", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/nav"));
    MAP.put("relation_stat", new ApiDef("GET", "https://api.bilibili.com/x/relation/stat?vmid={vmid}"));
    // ===== 视频 =====
    MAP.put("video_view", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/view?bvid={bvid}"));
    MAP.put("video_videoshot", new ApiDef("GET", "https://api.bilibili.com/x/player/videoshot?bvid={bvid}&cid={cid}&index=1"));
    MAP.put("video_playurl", new ApiDef("GET", "https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&qn={qn}&fnval=0&fourk=0"));
    MAP.put("video_reply", new ApiDef("GET", "https://api.bilibili.com/x/v2/reply?type=1&oid={oid}&sort=1&ps={ps}&pn={pn}"));
    // 写操作（点赞/投币/收藏/关注：V2 与 Web 独立 action）
    MAP.put("video_like_v2", new ApiDef("POST", "https://app.bilibili.com/x/v2/view/like"));
    MAP.put("video_like_web", new ApiDef("POST", "https://api.bilibili.com/x/web-interface/archive/like"));
    MAP.put("video_coin_v2", new ApiDef("POST", "https://app.bilibili.com/x/v2/view/coin/add"));
    MAP.put("video_coin_web", new ApiDef("POST", "https://api.bilibili.com/x/web-interface/coin/add"));
    MAP.put("video_fav_deal", new ApiDef("POST", "https://api.bilibili.com/x/v3/fav/resource/deal"));
    MAP.put("relation_modify", new ApiDef("POST", "https://api.bilibili.com/x/relation/modify"));
    // ===== 动态 =====
    MAP.put("dynamic_feed", new ApiDef("GET",
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?timezone_offset=-480&type=all&page={page}"));
    // ===== 直播 / 热榜 / 搜索 / UP空间 / 评论 / 收藏 / 历史 / 私信 =====
    MAP.put("live_room", new ApiDef("GET", "https://api.live.bilibili.com/room/v1/Room/get_info?room_id={room_id}"));
    MAP.put("hot_search", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/search/square?limit={limit}"));
    MAP.put("search", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/search/type?search_type={search_type}&keyword={kw}&page=1&page_size=8"));
    MAP.put("search_v2", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/search/all/v2?keyword={kw}&page=1&page_size=8"));
    MAP.put("up_card", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/card?mid={mid}"));
    MAP.put("post_comment_web", new ApiDef("POST", "https://api.bilibili.com/x/v2/reply/add"));
    MAP.put("post_comment_app", new ApiDef("POST", "https://app.bilibili.com/x/v2/reply/add"));
    MAP.put("fav_folder_list", new ApiDef("GET", "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid={up_mid}&type=2"));
    MAP.put("fav_resource_list", new ApiDef("GET", "https://api.bilibili.com/x/v3/fav/resource/list?media_id={media_id}&pn=1&ps=20&platform=web&type=0"));
    MAP.put("history_cursor", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/history/cursor?max={max}&view_at=0&business=archive"));
    MAP.put("msg_sessions_v2", new ApiDef("GET", "https://api.vc.bilibili.com/session_svr/v2/session_svr/get_sessions?session_type=1&group_fold=1&unfollow_fold=0&sort_rule=2"));
    MAP.put("msg_sessions_v1", new ApiDef("GET", "https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions?session_type=1&group_fold=1&unfollow_fold=0&sort_rule=2"));
    MAP.put("msg_send", new ApiDef("POST", "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg"));
    MAP.put("space_acc", new ApiDef("GET", "https://api.bilibili.com/x/space/acc/info?mid={mid}"));
    // ===== AI 总结（第三方）=====
    MAP.put("video_page", new ApiDef("GET", "https://www.bilibili.com/video/{bvid}"));
    MAP.put("ai_summary_bili", new ApiDef("GET", "https://api.bilibili.com/x/web-interface/view/conclusion/get?bvid={bvid}&cid={cid}"));
    MAP.put("ai_summary_quark", new ApiDef("POST", "https://quark.sm.cn/api/rest?method=ai.video.summary"));
    MAP.put("ai_summary_ark", new ApiDef("POST", "https://ark.cn-beijing.volces.com/api/v3/chat/completions"));
    MAP.put("ai_summary_dashscope", new ApiDef("POST", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
    // ===== 漫画（第三方）=====
    MAP.put("manga_home", new ApiDef("GET", "https://apis.netstart.cn/bcomic/HomeFeed?pageNum=1&pageSize=4"));
    MAP.put("manga_search", new ApiDef("GET", "https://apis.netstart.cn/bcomic/Search?key_word={kw}&page_num=1&page_size=4"));
    MAP.put("manga_detail", new ApiDef("GET", "https://apis.netstart.cn/bcomic/ComicDetail?comic_id={comic_id}"));
    MAP.put("manga_images", new ApiDef("GET", "https://apis.netstart.cn/bcomic/GetImageIndex?ep_id={ep_id}"));
}
```

### 5.5 WbiSigner —— wbi 签名（核心代码）

**作用**：首页个性化推荐 rcmd 强制 wbi 签名。算法：nav 取 img_key/sub_key → `MIXIN_KEY_ENC_TAB` 置换取 32 → 参数过滤 `!'()*` + `wts` → 排序 → `w_rid = md5(query+mixin_key)`。

```java
public static String sign(String url) {
    if (url == null || url.isEmpty()) return url;
    if (!ensureKeys()) return url;   // 密钥不可用原样返回（走候选兜底）
    Map<String, String> params = new TreeMap<>();
    // 解析 query 参数（Uri.decode）
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
```

### 5.6 ImageTransfer —— 图片下载转 PNG 分片（完整代码）

**作用**：把 B 站图片转为 PNG ≤20KB 的 base64，供分片下发手环。

```java
public final class ImageTransfer {
    /** interconnect 单条消息上限约 30KB，推荐 20KB/片 */
    public static final int CHUNK_LEN = 20000;
    /** 单图 base64 上限（PNG ≤20KB 可单片直发；超过则分片） */
    public static final int MAX_B64_BYTES = 20000;
    /** 下载图片最大边（封面缩略 96~160px，够手环小屏显示且省流量） */
    private static final int MAX_EDGE = 160;

    /** 下载失败兜底：1x1 透明 PNG 的 base64（保证手环端 file://img_N 占位必定被替换） */
    public static final String FALLBACK_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    /** 下载 B 站图片 → 缩放 → PNG 压缩（循环降尺寸保证 ≤maxBytes）→ base64；失败返回 null */
    public static String downloadAsPngBase64(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .build();
            byte[] bytes;
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                bytes = resp.body().bytes();
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null || bmp.isRecycled()) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            float scale = 1f;
            if (w > MAX_EDGE || h > MAX_EDGE) {
                scale = Math.min((float) MAX_EDGE / w, (float) MAX_EDGE / h);
            }
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            byte[] png = compressToPng(bmp, tw, th);
            if (png == null) return null;
            String b64 = Base64.encodeToString(png, Base64.NO_WRAP);
            if (b64.length() > MAX_B64_BYTES) {
                // 仍超限：继续缩小
                float ratio = (float) MAX_B64_BYTES / b64.length();
                int nw = Math.max(16, (int) (tw * Math.sqrt(ratio)));
                int nh = Math.max(16, (int) (th * Math.sqrt(ratio)));
                png = compressToPng(bmp, nw, nh);
                if (png == null) return null;
                b64 = Base64.encodeToString(png, Base64.NO_WRAP);
            }
            bmp.recycle();
            return b64;
        } catch (Exception e) {
            SyncLog.i("[图片] 下载/转 PNG 失败: " + e);
            return null;
        }
    }

    private static byte[] compressToPng(Bitmap src, int w, int h) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (scaled != src && !scaled.isRecycled()) scaled.recycle();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** base64 按 CHUNK_LEN 分片（interconnect 单消息 ≤30KB，推荐 20KB） */
    public static List<String> splitBase64(String b64) {
        List<String> parts = new ArrayList<>();
        if (b64 == null || b64.isEmpty()) return parts;
        for (int i = 0; i < b64.length(); i += CHUNK_LEN) {
            parts.add(b64.substring(i, Math.min(i + CHUNK_LEN, b64.length())));
        }
        return parts;
    }
}
```

### 5.7 TvLogin + LoginStore —— 登录（核心代码）

**作用**：Web OAuth 扫码登录（手机端驱动）。`requestQr` 申请二维码 → `startPolling` 2.5s 轮询 → 确认后 `extractCookies`（SSO 重定向 Set-Cookie）→ `sanitizeCookie` 裁剪（≤1200 字符防闪退）→ `ensureBuvid` 补设备指纹 → `LoginStore.saveLogin` + `fetchUserInfo`（nav）→ `showqr_done` 下发确认。

```java
/** 申请 Web 登录二维码（qrcode_key 流程，与网页版扫码一致） */
public static void requestQr(final QrCallback cb) {
    new Thread(() -> {
        try {
            Request request = new Request.Builder()
                    .url(GENERATE_URL)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                String text = resp.body() != null ? resp.body().string() : "";
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                int code = json.has("code") ? json.get("code").getAsInt() : -1;
                if (code != 0 || !json.has("data") || !json.get("data").isJsonObject()) { /* 失败回调 */ return; }
                JsonObject data = json.getAsJsonObject("data");
                String url = data.has("url") ? data.get("url").getAsString() : "";
                String qrcodeKey = data.has("qrcode_key") ? data.get("qrcode_key").getAsString() : "";
                cb.onSuccess(url, qrcodeKey);
            }
        } catch (Exception e) {
            cb.onFailure(-1, e.getMessage());
        }
    }).start();
}

/** 轮询确认（每 2.5s）：已确认 → 提取 Cookie 入库 + showqr_done 下发 */
private static void pollOnce(String nodeId, String qrcodeKey, int[] tries) {
    tries[0]++;
    if (tries[0] > POLL_MAX_TRIES) { sendStatus(nodeId, "二维码已过期，请重新登录"); cancelPolling(); return; }
    // GET POLL_URL?qrcode_key=... → code==0 且 data.url 非空 → 已确认：
    String ssoUrl = data.url;
    String cookie = ensureBuvid(sanitizeCookie(extractCookies(ssoUrl)));
    LoginStore.saveLogin(cookie, mid, accessToken, refreshToken);
    fetchUserInfo(cookie);
    // 登录凭证只留在手机端：showqr_done 仅下发确认与 UID
    JsonObject out = new JsonObject();
    out.addProperty("tag", "showqr_done");
    out.addProperty("code", 0);
    if (!mid.isEmpty()) out.addProperty("mid", Long.parseLong(mid));
    sendRaw(nodeId, out);
    cancelPolling();
    // 未确认：86101 等待扫码 / 86090 已扫码 / 86038 已过期（sendStatus 变化才发）
}

/** 提取登录 Cookie：query 参数 + 跟随 SSO URL 收集全部重定向 Set-Cookie（ticket 场景关键） */
private static String extractCookies(String ssoUrl) {
    Map<String, String> cookies = new LinkedHashMap<>();
    // 1) query 参数（正常场景：DedeUserID/SESSDATA/bili_jct 直接在此）
    int q = ssoUrl.indexOf('?');
    if (q >= 0) for (String pair : ssoUrl.substring(q + 1).split("&")) { /* k=v → cookies */ }
    // 2) 跟随 SSO URL，收集全部重定向（含最终响应）的 Set-Cookie
    try {
        Request request = new Request.Builder().url(ssoUrl)
                .header("User-Agent", UA).header("Referer", REFERER).build();
        try (Response resp = client.newCall(request).execute()) {
            List<Response> chain = new ArrayList<>();
            for (Response cur = resp; cur != null; cur = cur.priorResponse()) chain.add(cur);
            Collections.reverse(chain);   // 最早的重定向在前
            for (Response r : chain) {
                for (String sc : r.headers("Set-Cookie")) {
                    int semi = sc.indexOf(';');
                    String pair = (semi > 0 ? sc.substring(0, semi) : sc).trim();
                    int eq = pair.indexOf('=');
                    if (eq > 0) cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
    } catch (Exception e) { /* 失败不阻断（query 已有核心字段） */ }
    // 拼接 "k=v; k=v; ..."
    return sb.toString();
}

/** 只保留核心字段（SESSDATA/bili_jct/DedeUserID/DedeUserID__ckMd5/bili_ticket/buvid3/buvid4/_uuid/b_nut/sid），≤1200 字符 */
private static String sanitizeCookie(String raw) { /* COOKIE_KEEP 白名单过滤 + 截断 */ }

/** 补齐设备指纹：缺 buvid3/buvid4 时生成 UUID 格式（B 站 web 风控要求，否则 nav 返回 412/非 JSON） */
private static String ensureBuvid(String cookie) {
    if (cookie == null || cookie.isEmpty()) return cookie;
    if (!cookie.contains("buvid3=")) {
        cookie += "; buvid3=" + UUID.randomUUID().toString().replace("-", "");
    }
    if (!cookie.contains("buvid4=")) {
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append('-');
            sb.append(String.format("%04X", r.nextInt(0x10000)));
        }
        cookie += "; buvid4=" + sb.toString();
    }
    return cookie;
}
```

**LoginStore**（SharedPreferences 持久化登录凭证 + 用户信息）：

```java
public final class LoginStore {
    public static boolean isLoggedIn() { /* cookie 非空 */ }
    public static void saveLogin(String cookie, String mid, String accessToken, String refreshToken) { /* apply() */ }
    public static void saveUserInfo(String uname, String mid, String level, String coins, String face) { /* apply() */ }
    public static void clear() { sp().edit().clear().apply(); }
    public static String cookie() { return sp().getString("cookie", ""); }
    /** 从登录 Cookie 提取 bili_jct（写操作 CSRF，由手机端补齐） */
    public static String biliJct() {
        String c = cookie();
        if (c == null || c.isEmpty()) return "";
        for (String part : c.split(";")) {
            String p = part.trim();
            if (p.startsWith("bili_jct=")) return p.substring("bili_jct=".length()).trim();
        }
        return "";
    }
    public static String summary() { /* 登录凭证在上、用户信息（手环格式）在下 */ }
}
```

### 5.8 RequestTracker —— 请求跟踪（核心代码）

**作用**：记录手环每个请求的三段链路，供调试面板展示。`Record`：id/method/url/action/transport（interconnect|fetch）/httpStatus/bizCode/bytes/detail/耗时；`label()` 前缀 `[互联]`/`[fetch]`。

```java
public final class RequestTracker {
    public static final class Record {
        public final long time;
        public String id = "", method = "GET", url = "", state = "pending";
        public int httpStatus = 0, bizCode = 0;
        public long bytes = 0, costMs = 0;
        public String detail = "", transport = "interconnect";
        public String label() {
            String tag = "fetch".equals(transport) ? "[fetch] " : "[互联] ";
            return tag + "[HH:mm:ss] " + method + " " + state
                    + (httpStatus > 0 ? " HTTP" + httpStatus : "")
                    + (bizCode != 0 ? " code=" + bizCode : "")
                    + " " + bytes + "B " + costMs + "ms\n" + url;
        }
    }
    // begin(id, method, url) / beginDirect(method, url)（手环端直连标注）/ finish / fail / all / clear
}
```

### 5.9 RiskControl —— 风控封锁判断（核心代码）

**作用**：检测风控码（-412/-352/-403）并显示 30 分钟封锁窗口。

```java
public final class RiskControl {
    public static final long BLOCK_WINDOW_MS = 30 * 60 * 1000L; // 30 分钟
    private static final int[] RISK_CODES = {-412, -352, -403};
    /** 业务码回调（请求完成时调用）：命中风控码则进入/延续封锁窗口 */
    public static synchronized void onBizCode(int code, String action, String url) {
        if (code == 0) return;
        boolean risk = false;
        for (int c : RISK_CODES) if (c == code) { risk = true; break; }
        if (!risk) return;
        if (!blocked) { blocked = true; blockedAt = System.currentTimeMillis(); hitCount = 0; }
        lastCode = code;
        if (action != null && !action.isEmpty()) lastAction = action;
        if (url != null && !url.isEmpty()) lastUrl = url;
        hitCount++;
    }
    public static boolean isBlocked() { /* 窗口过期自动解除 */ }
    public static long remainingMs() { /* 剩余毫秒 */ }
    public static synchronized void reset() { /* 手动清除 */ }
    public static String summary() { /* 调试面板完整信息 */ }
    public static String shortText() { /* 主界面单行 */ }
}
```

### 5.10 UI 层

- **MainActivity**：连接状态卡 + 实时日志（5000 条、丝滑滚动）+ 风控状态行 + 重连/握手入口 + 特别鸣谢卡（`activity_main.xml` 顶部：@雪松（个性化工具箱作者，思路提供）@B4QAQ（永昼天气、腕上信驿作者，思路指导），log49）。
- **DebugActivity**：调试面板三页签——协议日志 / 请求详情（列表+响应预览+复制按钮+互联绿/fetch 红标注）/ 工具（推送测试、环境自检、手动消息、登录信息卡、风控状态卡）。

---

## 6. 构建工具链

### 6.1 RPK 补丁 + 重签（主脚本）

```
node patch-standard.js <源rpk> <输出rpk> <private.pem> <certificate.pem>
```

例：
```
node patch-standard.js "WristBili1.5.6.rpk" "WristBili_log49.rpk" "C:\Users\Bob\Documents\HyperBilibili\sign\private.pem" "C:\Users\Bob\Documents\HyperBilibili\sign\certificate.pem"
```

- **关键机制**：RPK 是 zip，含 `META-INF/CERT`（内嵌 zip 的 `hash.json`：全部文件 SHA-256 清单）+ 外层签名块。**改任何文件后必须重建 hash.json + CERT zip + 重签**，否则手环完整性校验失败、互联被拒（log1-2 最隐蔽的坑）。
- 依赖（NODE_PATH 硬编码）：`@aiot-toolkit/packager` 1.1.4（必须用它重建 zip，保留 157 条目 + comment；.NET ZipFile 丢条目 → `InstallFailed install.rs:397`）；jsrsasign 必须 7.2.2。
- 产物日志核对：各 bundle `fetchbridge补丁:N`、页面补丁数、`hash.json 重新生成`、`重签完成`。

### 6.2 APK 构建

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleRelease
```

- 产物：`WristBiliSyncServer\app\build\outputs\apk\release\app-release.apk`（签名 `app/android.jks`，SHA-256 7640E1AB）。
- 验证：`aapt2 dump badging`（包名 `com.example.band.bilibili.lite`）、`apksigner verify --print-certs`（7640e1ab...82ee7）。

### 6.3 验证手段（每次改动后必做）

- **dump 检查**：解包 RPK 检查补丁注入（`node` + `createFileListFromZipBuffer`），确认关键字符串/方法存在、旧逻辑无残留。
- **语法检查**：`node --check <bundle>.js`（fetchbridge 各 bundle）。
- **括号平衡校验**：整函数替换/模板注入后校验。
- **实机日志**：手环 Bridge 页运行日志 + 手机 logcat（`[手表→同步器]`/`[同步器→服务器]`/`[同步器→手表]`/`[图片]`/`[网桥]`/`[登录态]` 前缀）。

---

## 7. 关键文件清单

| 端 | 文件 | 作用 |
|---|---|---|
| 手环 | `patch-standard.js` | 唯一补丁+重签脚本（全部补丁常量 + 构建主循环） |
| 手环 | `WristBili1.5.6.rpk` | 基线 RPK（195 条目，log40 起） |
| 手环 | `WristBili_log49.rpk` | 最新产物（整合 log1-log49d） |
| 手机 | `FetchBridgeServer.java` | 协议引擎 |
| 手机 | `HttpRelay.java` | HTTP 转发 + 登录注入 + csrf |
| 手机 | `BiliApiMap.java` | 语义化映射 |
| 手机 | `WbiSigner.java` | wbi 签名 |
| 手机 | `ImageTransfer.java` | 图片下载转 PNG + 分片 |
| 手机 | `WearClient.java` | 互联连接管理 |
| 手机 | `TvLogin.java` / `LoginStore.java` | 登录 |
| 手机 | `RequestTracker.java` / `RiskControl.java` | 请求跟踪 / 风控 |
| 手机 | `MainActivity.java` / `DebugActivity.java` | UI |
| 其他 | `sign/private.pem`、`sign/certificate.pem` | RPK 签名材料 |
| 其他 | `app/android.jks` | APK 签名密钥（正确密钥） |
| 其他 | `edge-image-service/` | Vercel + sharp 图片转码（已弃用） |

---

## 8. 版本更新记录（log1 ~ log49d）

| 版本 | 日期 | 端 | 核心变更 |
|---|---|---|---|
| log1-2 | 08-02 | RPK | 定位互联不通：send 记错误码；确认 CERT hash.json 未重建导致完整性校验失败 |
| log3 | 08-02 | RPK | **决定性修复**：`conn.send` 的 `data` 改传 Object（修复 202 invalid data），握手成功 |
| log4 | 08-02 | RPK | 网桥保持开启：Bridge 页 onInit 恢复开关 + 保活轮询重连 |
| log5 | 08-02 | RPK | Settings 页网桥状态恢复 |
| log6 | 08-02 | RPK | 全页面网桥化：15 个直连 bundle 注入 `__wbFetch` + 全局暴露 + 保活自愈 |
| log7 | 08-02 | RPK | 日志界面最近 12 条自动滚动 |
| log8-9 | 08-02 | 双端 | 开发者模式推送（`__dev_fetch__` 三段转发）+ 推送后自动跳页 |
| log10-12 | 08-02 | 双端 | 登录 Cookie 自动附加 + 启动自动联网 + Bridge 推送按钮 |
| log13 | 08-02 | 双端 | 图片 base64 内联（后弃用）+ 推送语义化 fallback |
| log13b/c | 08-02 | 双端 | SHOWQR 登录方向试错（手机发码→闪退回滚→官方方向） |
| log14 | 08-02 | 双端 | 官方 qrcode 组件登录 + 请求监听 |
| log14b-15 | 08-02 | RPK | 日志版本号驱动 + 登录自动请求 + 握手活性判断 |
| log16-17 | 08-02 | 双端 | interconnect/fetch 上报与彩色标注（后随 bridge-only 移除） |
| log18 | 08-02 | RPK | **bridge-only**：删除全部直连，网桥未就绪等待 30s |
| log19 | 08-02 | 双端 | Web OAuth 扫码登录（手机端驱动）+ 登录态自动携带 |
| log20 | 08-03 | 双端 | Cookie 提取修复（SSO 重定向 Set-Cookie）+ fetchNav 直连 nav |
| log20b/c | 08-03 | APK | 登录信息栏 + Cookie 裁剪（≤1200 字符防闪退） |
| log21 | 08-03 | 双端 | **登录凭证留手机端**：手环只存占位标记，HttpRelay 统一注入 |
| log21b | 08-03 | APK | ensureBuvid 设备指纹补齐 |
| log22 | 08-03 | 双端 | **语义化请求协议**：手环发 action+params |
| log23 | 08-03 | RPK | 默认开网桥 + 未就绪等待自动发送 + cookie/csrf 放行 |
| log23b | 08-03 | APK | 移除图片内联，响应原样返回 |
| log24-25 | 08-03 | 双端 | 刷新/登录后加载统一 + 全量语义化（多候选 URL） |
| log26 | 08-03 | 双端 | Settings 登录态以 nav 为准 + **wbi 签名** |
| log27 | 08-03 | 双端 | 推送语义化 + **全局响应分发** + 业务码兜底 |
| log28 | 08-03 | APK | 日志丝滑滚动 + 检测收窄 + 版本标注卡 |
| log29 | 08-03 | RPK | **genId 全局唯一**（跨实例 id 冲突根治） |
| log30 | 08-03 | APK | 权限申请提前到应用检测之前 |
| log31 | 08-03 | APK | 握手状态机对称（count>=1）+ 监听宽容模式 |
| log32 | 08-03 | APK | 监听先行（startListening 不再依赖权限链） |
| log33 | 08-03 | RPK | 握手会话缺失自动重建 |
| log34 | 08-04 | 双端 | 退出登录双向同步（logout → LoginStore.clear） |
| log35 | 08-04 | RPK | 网桥就绪时序治理 |
| log36 | 08-04 | APK | 风控封锁判断窗口（-412/-352/-403） |
| log37 | 08-04 | RPK | sendMessage 会话重建 + enable 全局同步 |
| log38 | 08-04 | 双端 | 互联诊断（connect.diagnosis() 上报） |
| log39-39e | 08-04 | 双端 | 图标替换 + 超时延长 + 分块头清 pending 超时 + 活性 90s + 设置返回刷新 |
| log40 | 08-05 | 双端 | **基线迁移 WristBili1.5.6**（Fullscreen 第 5 个 fetchbridge bundle） |
| log40b | 08-05 | RPK | 首页图片尺寸降级 + 预载帧缩减 |
| log42 | 08-05 | 双端 | 头像改 nav + 大响应裁剪 + **四页面分页** + 功能栏常驻 |
| log43 | 08-05 | RPK | 10 Pro 兼容（百分比→像素、动态 classList ≤3） |
| log44 | 08-06 | RPK | 砍原图预载 + 全页降尺寸 |
| log45 | 08-06 | RPK | 评论区分页 + 首页"刷新"按钮 |
| log46 | 08-06 | RPK | 缩略图后缀 jpg→png（由 log49 取代） |
| log47 | 08-06 | RPK | setItem URL 清洗 |
| log48-48d | 08-06 | APK | 图片外链转码探索（ESA/Vercel/wsrv，被 log49 取代） |
| log49 | 08-06 | 双端 | **图片 base64 分片** + 字段白名单 + 心跳 10s/30s 拉起 |
| log49b | 08-06 | 双端 | 收到任意消息刷新活性 + fetch-progress + 登录态以手机端为准 |
| log49c | 08-06 | RPK | 右滑退出不关网桥（onDestroy 不调 destroy） |
| log49d | 08-06 | RPK | 图片逐张出图 + 本地资源不经手机中转 |

---

## 9. 经验与踩坑总结

### 9.1 Vela interconnect 通道层
- `conn.send` 的 `data` 必须 Object，String 报 `202 invalid data`（log3）。
- conn 是单例，多实例 onmessage 互相覆盖 → 全局回调表根治（log27）。
- 单条消息大小上限：大消息单发导致手环闪退 → 裁剪/分片。
- Vela img 不支持 base64 data URI（log43）。
- `getReadyState` status=1 只反映宿主在线 → 用"最近收到消息"活性判断。
- 手环处理网络 jpg 有 BUG（10 Pro 感叹号）→ 本地 PNG 方案（log49）。

### 9.2 RPK 补丁与签名
- 重签必须重建 CERT 内嵌 zip 的 hash.json（log1-2 卡最久）。
- zip 重建必须用 `@aiot-toolkit/packager`，.NET ZipFile 丢条目。
- jsrsasign 必须 7.2.2。
- 整函数替换靠平衡括号匹配，需精确缩进；不同 bundle 缩进可能不同。
- **编辑补丁常量务必串行执行**（并行 SearchReplace 互相覆盖）；改完必须 dump 核对。

### 9.3 架构演进主线
- bridge-only（log18）→ 登录态手机端持有（log21）→ 语义化请求协议（log22-25）→ wbi 签名（log26-27）→ 全局分发+genId（log27/29）→ 分页裁剪（log42）→ 图片分片（log49）。
- 用户侧反馈区分"协议层"（日志看不到/收不到）与"业务层"（响应 code!=0）：前者查互联/分块，后者查签名/登录态/Cookie/风控。

### 9.4 用户硬性规则
- **网桥开关/连接相关代码（onDestroy、closeSession、Bridge 开关、enable/disable）以后不要再改动**（log49c）。
- **图片分片只用于必要内容图**：能从本地 icon/快应用内置资源获取的图标直接显示，不走手机中转（log49d）。

---

## 10. 从零复刻步骤

1. **准备环境**：Node.js；Android Studio + JDK（`C:\Program Files\Android\Android Studio\jbr`）；依赖路径（NODE_PATH）配好 `@aiot-toolkit/packager` 1.1.4、jsrsasign 7.2.2；签名材料（`sign/*.pem` + `app/android.jks`）。
2. **构建 RPK**：`node patch-standard.js WristBili1.5.6.rpk <输出>.rpk <private.pem> <certificate.pem>`；核对补丁命中 + `node --check` + dump。
3. **构建 APK**：`WristBiliSyncServer>` `.\gradlew.bat :app:assembleRelease`；`apksigner verify --print-certs` 验证 7640E1AB。
4. **安装与验证**：卸载旧版 → 装 APK → 手环装 RPK → 验证链路（握手 → 首页 → 图片逐张出 → 登录 → 分页 → 右滑不关网桥）。
5. **迭代**：改手环改 `patch-standard.js`（串行）→ 重构建 → dump 核对；改手机改 Java → gradle 构建；两端配套安装。

---

## 11. 已知问题与待办

1. **待实测**（log49 起）：10 Pro 封面/头像/视频详情封面从感叹号变 PNG；手环重启频率与心跳拉起；分页去重缓存复用。
2. **视频雪碧图帧**：仍由手环端 fetch 雪碧图 URL + `@x-y-w-ha.jpg` 裁切直接加载（video_videoshot 已排除在图片分片外）；10 Pro 若帧图异常需另行处理。
3. **图片下载失败兜底**：当前 1x1 透明 PNG（视觉空白），可考虑换默认封面。
4. **外链转码链路已弃用**：edge-image-service/、wsrv、ESA 均已停用（被 log49 取代）。

---

## 附录 A. B 站官方 API 接口大全

> 说明：B 站官方接口未提供统一公开文档，以下为社区长期整理 + 本项目实际覆盖的**常用官方接口**（按域名与路径版本组织：`x/v1`、`x/v2`、`x/v3`、`x/web-interface`（无版本号）、`x/polymer/web-dynamic/v1` 等）。接口与参数可能随 B 站调整，实际调用以返回为准；本项目通过手机端 `BiliApiMap` 语义化映射统一调用（见 5.4）。

### A.1 api.bilibili.com —— 主站 API（无版本号 x/web-interface，网页接口）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/web-interface/nav` | GET | — | 我的信息 / 登录态（`isLogin`/`uname`/`face`/`level_info`/`wbi_img`） |
| `x/web-interface/nav/stat` | GET | — | 我的信息统计（硬币/关注/粉丝/黑名单数） |
| `x/web-interface/view` | GET | `bvid` | 视频详情（标题/封面/UP主/分P/简介等） |
| `x/web-interface/view/conclusion/get` | GET | `bvid`,`cid` | 视频 AI 总结 |
| `x/web-interface/popular` | GET | `ps`,`pn` | 热门视频列表（本项目首页兜底） |
| `x/web-interface/index/top/feed/rcmd` | GET | `ps`,`fresh_type`,`fresh_idx` + **wbi 签名** | 首页个性化推荐（本项目首页主源） |
| `x/web-interface/index/top/rcmd` | GET | `ps`,`fresh_idx` | 首页推荐（旧版） |
| `x/web-interface/online` | GET | — | 在线人数 |
| `x/web-interface/search/type` | GET | `search_type`,`keyword`,`page`,`page_size` | 分类搜索（视频/番剧/用户…） |
| `x/web-interface/search/all/v2` | GET | `keyword` | 聚合搜索 |
| `x/web-interface/search/square` | GET | `limit` | 搜索热榜关键词 |
| `x/web-interface/card` | GET | `mid` | UP 主卡片（昵称/粉丝/简介） |
| `x/web-interface/history/cursor` | GET | `max`,`view_at`,`business` | 观看历史（游标分页，本项目历史页） |
| `x/web-interface/history/list` | GET | — | 观看历史（旧） |
| `x/web-interface/archive/like` | POST | `aid`,`bvid`,`like` | 点赞/取消（Web） |
| `x/web-interface/archive/dislike` | POST | `aid` | 踩 |
| `x/web-interface/coin/add` | POST | `aid`,`multiply`,`select_like` | 投币（Web，本项目 video_coin_web） |
| `x/web-interface/archive/coins` | GET | `aid`,`bvid` | 视频投币状态 |
| `x/web-interface/archive/stat` | GET | `aid`,`bvid` | 视频数据（播放/点赞/投币/收藏/分享） |
| `x/web-interface/archive/related` | GET | `bvid` | 相关视频推荐 |
| `x/web-interface/archive/desc` | GET | `aid` | 视频简介 |
| `x/web-interface/bangumi/timeline` | GET | — | 番剧时间表 |
| `x/web-interface/region/tag/children` | GET | — | 分区子分区 |
| `x/web-interface/zone` | GET | `rid` | 分区视频列表 |
| `x/web-interface/ranking` | GET | `rid` | 排行榜 |
| `x/web-interface/ugc-season` | GET | `season_id` | 视频合集 |
| `x/web-interface/season` | GET | — | 番剧/影视 |
| `x/web-interface/emote/...` | GET | — | 表情 |
| `x/web-interface/but/...` | GET | — | 长评/杂项 |

### A.2 api.bilibili.com —— x/v1

| 路径 | 方法 | 用途 |
|---|---|---|
| `x/v1/...` | GET/POST | v1 版本接口（旧；当前业务接口多以 v2/v3 或 web-interface 存在，v1 常见于早期历史接口，如部分旧版 tag/zone 接口） |

### A.3 api.bilibili.com —— x/v2（评论 / APP 写操作）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/v2/reply` | GET | `type`,`oid`,`pn`,`ps`,`sort` | 评论列表（本项目 video_reply） |
| `x/v2/reply/main` | GET | `type`,`oid`,`mode` | 评论主楼 |
| `x/v2/reply/reply` | GET | `type`,`oid`,`root` | 楼中楼评论 |
| `x/v2/reply/add` | POST | `oid`,`type`,`message` | 发评论（本项目 post_comment_web） |
| `x/v2/reply/hot` | GET | `oid`,`type` | 热门评论 |
| `x/v2/reply/action` | POST | `oid`,`rpid`,`action` | 点赞评论 |
| `x/v2/view/like` | POST | `aid`,`like` | 点赞（APP，本项目 video_like_v2） |
| `x/v2/view/coin/add` | POST | `aid`,`multiply` | 投币（APP，本项目 video_coin_v2） |
| `x/v2/view/fav/add` | POST | `aid` | 收藏（APP） |
| `x/v2/account/myinfo` | GET | — | 我的信息（APP；桌面/无 APP UA 必返 -400，已弃用改用 nav） |
| `x/v2/account/mine` | GET | — | 我的信息（APP） |
| `x/v2/elec/...` | GET | — | 充电相关 |

### A.4 api.bilibili.com —— x/v3（收藏等）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/v3/fav/resource/deal` | POST | `rid`,`type`,`add_media_ids` | 收藏/取消收藏（本项目 video_fav_deal） |
| `x/v3/fav/resource/list` | GET | `media_id`,`pn`,`ps`,`platform`,`type` | 收藏夹内容（本项目 fav_resource_list） |
| `x/v3/fav/folder/created/list-all` | GET | `up_mid`,`type` | 我创建的收藏夹（本项目 fav_folder_list） |
| `x/v3/fav/folder/created/list` | GET | `up_mid` | 收藏夹列表 |
| `x/v3/fav/folder/added/list` | GET | `up_mid` | 收藏过的收藏夹 |
| `x/v3/fav/resource/count` | GET | `rid`,`type` | 收藏数 |
| `x/v3/fav/tag/...` | GET | — | 收藏夹标签 |
| `x/v3/internal/space/...` | GET | — | 空间内部接口 |

### A.5 api.bilibili.com —— x/polymer/web-dynamic/v1（动态）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/polymer/web-dynamic/v1/feed/all` | GET | `timezone_offset`,`type`,`page` | 全部动态（本项目 dynamic_feed） |
| `x/polymer/web-dynamic/v1/feed/space` | GET | `host_mid`,`offset` | 用户动态 |
| `x/polymer/web-dynamic/v1/detail` | GET | `id` | 动态详情 |
| `x/polymer/web-dynamic/v1/dynamic/repost` | GET | `id` | 转发动态 |
| `x/polymer/web-dynamic/v1/dynamic/new` | GET | — | 新动态 |

### A.6 api.bilibili.com —— x/relation（关注）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/relation/stat` | GET | `vmid` | 关注/粉丝统计（本项目 relation_stat） |
| `x/relation/modify` | POST | `fid`,`act` | 关注/取关（本项目 relation_modify） |
| `x/relation/followers` | GET | `vmid` | 粉丝列表 |
| `x/relation/followings` | GET | `vmid` | 关注列表 |
| `x/relation/batch` | GET | `fids` | 批量关注状态 |
| `x/relation/whispers` | GET | — | 悄悄关注 |

### A.7 api.bilibili.com —— x/space（用户空间）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/space/acc/info` | GET | `mid` | 用户信息（本项目 space_acc） |
| `x/space/wbi/acc/info` | GET | `mid` + wbi | 用户信息（wbi） |
| `x/space/arc/search` | GET | `mid`,`pn`,`ps`,`order` | 用户投稿列表 |
| `x/space/upstat` | GET | `mid` | UP 数据（播放/点赞） |
| `x/space/notice` | GET | `mid` | 用户公告 |

### A.8 api.bilibili.com —— x/player（播放）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/player/playurl` | GET | `bvid`,`cid`,`qn`,`fnval` | 视频播放地址（本项目 video_playurl） |
| `x/player/wbi/playurl` | GET | + wbi | 播放地址（wbi） |
| `x/player/videoshot` | GET | `bvid`,`cid`,`index` | 雪碧图/预览帧（本项目 video_videoshot） |
| `x/player/pagelist` | GET | `bvid` | 分 P 列表 |
| `x/player/online/...` | GET | — | 在线观看人数 |
| `x/player/playing/...` | GET | — | 播放信息 |

### A.9 api.bilibili.com —— x/tag / 其他

| 路径 | 方法 | 用途 |
|---|---|---|
| `x/tag/info` | GET | 标签信息 |
| `x/tag/ranking` | GET | 标签排行 |
| `x/tag/subscription/...` | GET | 订阅标签 |
| `x/tag/detail` | GET | 标签详情 |
| `x/web-show/...` | GET | 首页轮播图组件 |
| `x/steampunk/web-interface/...` | GET | 互动视频 |
| `x/garb/v2/...` | GET | 装扮 |
| `x/emote/user/panel` | GET | 表情面板 |
| `x/mark/...` | GET | 视频标记 |
| `x/wbi/...` | GET | wbi 系列接口 |

### A.10 api.live.bilibili.com —— 直播

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `room/v1/Room/get_info` | GET | `room_id` | 房间信息（本项目 live_room） |
| `room/v1/Room/room_init` | GET | `id` | 房间初始化 |
| `room/v1/Room/stream_url` | GET | `room_id` | 直播流地址 |
| `room/v1/Area/getRoomList` | GET | `parent_area_id`,`area_id`,`page`,`page_size` | 分区房间（v1，本项目 home_live_list 候选） |
| `room/v2/Area/getRoomList` | GET | 同上 | 分区房间（v2，本项目 home_live_list 候选） |
| `room/v1/AppIndex/getAllList` | GET | `platform`,`device` | 全部分区（本项目 home_live_list 候选） |
| `xlive/web-interface/v1/second/getList` | GET | `platform`,`parent_area_id` | 二级分区列表（本项目 home_live_list 候选） |
| `xlive/web-room/v1/index/getInfoByRoom` | GET | `room_id` | 房间完整信息 |
| `xlive/web-room/v1/index/getRoomBaseInfo` | GET | `room_id` | 房间基础信息 |
| `xlive/web-interface/v1/rank/...` | GET | — | 直播排行 |
| `xlive/web-interface/v1/guard/...` | GET | — | 大航海 |
| `xlive/web-interface/v1/fans_medal/...` | GET | — | 粉丝勋章 |

### A.11 api.vc.bilibili.com —— 私信 / 动态（旧）

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `session_svr/v1/session_svr/get_sessions` | GET | `session_type`,`group_fold` | 私信会话（v1，本项目 msg_sessions_v1） |
| `session_svr/v2/session_svr/get_sessions` | GET | `session_type`,`group_fold` | 私信会话（v2，本项目 msg_sessions_v2） |
| `web_im/v1/web_im/send_msg` | POST | `msg[sender_uid]`,`msg[receiver_id]`,`msg[msg_type]`,`msg[content]` | 发私信（本项目 msg_send） |
| `web_im/v1/web_im/fetch_session_msgs` | GET | `talker_id`,`session_type` | 聊天记录 |
| `dynamic_svr/v1/dynamic_svr/space_history` | GET | `host_uid` | 用户动态（旧） |
| `dynamic_svr/v1/dynamic_svr/dynamic_new` | GET | — | 新动态（旧） |

### A.12 passport.bilibili.com —— 登录

| 路径 | 方法 | 主要参数 | 用途 |
|---|---|---|---|
| `x/passport-login/web/qrcode/generate` | GET | — | 网页二维码申请（本项目 TvLogin.requestQr） |
| `x/passport-login/web/qrcode/poll` | GET | `qrcode_key` | 轮询扫码状态（本项目 TvLogin 轮询） |
| `x/passport-login/web/sso/check` | GET | — | SSO 校验 |
| `x/passport-login/oauth2/refresh_token` | POST | `refresh_token` | 刷新 token |
| `x/passport-login/web/cookie/info` | GET | `csrf` | Cookie 信息 |
| `x/passport-tv-login/qrcode/auth_code` | GET | `appkey`,`sign` | TV 登录二维码（旧方向） |
| `x/passport-tv-login/qrcode/poll` | GET | `auth_code` | TV 轮询（旧方向） |

### A.13 app.bilibili.com —— APP 接口

| 路径 | 方法 | 用途 |
|---|---|---|
| `x/v2/account/myinfo` | GET | 我的信息（**无 APP UA 必返 -400**，已弃用） |
| `x/v2/account/mine` | GET | 我的信息 |
| `x/v2/view/like` | POST | 点赞（APP） |
| `x/v2/view/coin/add` | POST | 投币（APP） |
| `x/v2/view/fav/add` | POST | 收藏（APP） |
| `x/v2/reply/add` | POST | 发评论（APP） |
| `x/v2/reply/reply` | GET | 楼中楼 |
| `x/v2/elec/...` | GET | 充电 |
| `x/resource/...` | GET | 资源类 |

### A.14 第三方接口（本项目使用）

| 域名 | 路径 | 用途 |
|---|---|---|
| `quark.sm.cn` | `api/rest?method=ai.video.summary` | 夸克 AI 视频总结（本项目 ai_summary_quark） |
| `ark.cn-beijing.volces.com` | `api/v3/chat/completions` | 豆包 AI（本项目 ai_summary_ark） |
| `dashscope.aliyuncs.com` | `compatible-mode/v1/chat/completions` | 千问 AI（本项目 ai_summary_dashscope） |
| `apis.netstart.cn` | `bcomic/HomeFeed` | 漫画首页（本项目 manga_home） |
| `apis.netstart.cn` | `bcomic/Search` | 漫画搜索（本项目 manga_search） |
| `apis.netstart.cn` | `bcomic/ComicDetail` | 漫画详情（本项目 manga_detail） |
| `apis.netstart.cn` | `bcomic/GetImageIndex` | 漫画图片索引（本项目 manga_images） |

> 注：B 站接口较多且持续演进，上述列表覆盖本项目全部用到的官方接口与社区常用接口。完整、最新的接口清单建议参考社区维护文档（如 bilibili-API-collect 等开源整理），并以 B 站实际返回为准。



