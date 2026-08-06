// 对 WristBili 打补丁（含手表端日志界面）：
// 1) 握手完成条件修正 (count>=1)
// 2) 网桥改用 Vela 标准 interconnect API：instance()/onopen/onmessage/send
// 3) 状态上报：onopen/onclose/onerror/getReadyState/握手完成 -> {tag:'__status__'} 发到手机端
// 4) 手表端日志：fetchbridge 记录运行日志 state.logs，网桥页面新增"运行日志"显示区
// 5) 保活：getReadyState 轮询检测静默断开自动重连；send 连续失败后自动重连（网桥开关保持开启）
// 6) 网桥页面 onInit 调 init()：从 storage 恢复开关状态（退出设置界面后仍保持开启）
// 7) directFetch 已恢复（不再禁用直连）：网桥未就绪时请求走直连兜底
// 8) 全页面网桥化：app.js 全局暴露 __wb_bridge；纯直连 bundle 注入 __wbFetch（网桥就绪走网桥，否则直连兜底）
// 9) 保活自愈：!connected 时 getReadyState 主动查 status=1 恢复状态（多实例回调被覆盖也能恢复）
// 10) 关键修复：重新生成 hash.json + 重建 META-INF/CERT（保证签名完整性校验通过）
// 用法: node patch-standard.js <源rpk> <输出rpk> <private.pem> <certificate.pem>
process.env.NODE_PATH = 'C:/Users/Bob/AppData/Local/Yarn/Cache/v6/npm-jsrsasign-7.2.2-ae5230cb5574451bb979a9cc697428c60f598d20-integrity/node_modules;C:/Users/Bob/Documents/HyperBilibili/node_modules';
require('module').Module._initPaths();

const fs = require('fs');
const crypto = require('crypto');
const packagerBase = 'C:/Users/Bob/AppData/Local/Yarn/Cache/v6/npm-@aiot-toolkit-packager-1.1.4-ae301a7bbdccac62d9d54699980123105518ecf1-integrity/node_modules/@aiot-toolkit/packager';
const { signZipBufferForPackage } = require(packagerBase + '/lib/signature/index.js');
const { createFileListFromZipBuffer, createZipBufferFromFileList } = require(packagerBase + '/lib/common/ziputil.js');

const CERT_PATH = 'META-INF/CERT';
const HASH_JSON = 'hash.json';

// 含 fetchbridge 模块（内联）的所有 bundle
// 1.5.6 新增 Fullscreen（视频全屏播放页，同样内联 fetchbridge）→ 纳入补丁
const TARGETS = ['app.js', 'Bridge/bridge.js', 'Settings/settings.js', 'Video/video.js', 'Fullscreen/fullscreen.js'];

// ===================== fetchbridge 模块补丁（4 个 bundle）=====================

// 补丁1：握手完成条件
const HS_OLD = 'if (count >= 2 && !state.handshakeDone) {';
const HS_NEW = '// fix: complete handshake on any reply\nif (count >= 1 && !state.handshakeDone) {';

// 补丁1b：握手完成时上报手机端 + 记日志
const HS_REP_OLD = "notifyStatus('handshake_done');";
const HS_REP_NEW = "notifyStatus('handshake_done');\n" +
  "                                addLog('握手完成');\n" +
  "                                try {\n" +
  "                                    if (state.session) state.session.send({\n" +
  "                                        data: { tag: '__status__', status: 'handshake_done' }, // Vela: data 必须为 Object\n" +
  "                                        success: function() {},\n" +
  "                                        fail: function() {}\n" +
  "                                    });\n" +
  "                                } catch (e) {}";

// 补丁3+4+5a：createSession 标准 API + 状态上报 + 日志
const CS_REPLACEMENT = `                        function createSession() {
                            if (state.session) return;
                            try {
                                // 改用 Vela 标准 interconnect API（instance/onopen/onmessage/send）
                                var conn = _system3.default.instance();
                                if (!conn) throw new Error('no interconnect instance');
                                addLog('interconnect 实例已获取');
                                // 诊断上报 + 日志
                                function report(extra) {
                                    try {
                                        var o = { tag: '__status__' };
                                        for (var k in extra) o[k] = extra[k];
                                        conn.send({
                                            data: o, // Vela: data 必须为 Object（String 报 202 invalid data）
                                            success: function() {},
                                            fail: function() {}
                                        });
                                    } catch (e) {}
                                }
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
                                    report({ status: 'closed', code: d ? d.code : '', msg: d ? d.data : '' });
                                    state.connected = false;
                                    state.session = null;
                                    notifyStatus('connect_failed');
                                    scheduleReconnect();
                                };
                                conn.onerror = function(d) {
                                    addLog('连接错误 code=' + (d ? d.code : '') + ' msg=' + (d ? d.data : ''));
                                    report({ status: 'error', code: d ? d.code : '', msg: d ? d.data : '' });
                                    state.connected = false;
                                    state.session = null;
                                    notifyStatus('connect_failed');
                                    scheduleReconnect();
                                };
                                conn.onmessage = function(d) {
                                    var payload = d;
                                    if (d && 'object' == typeof d && 'data' in d) payload = d.data;
                                    addLog('收到消息: ' + String(payload).substring(0, 60));
                                    // 不再逐条上报 __status__（避免分片高频消息触发发送拥塞/误重连）
                                    onMessage(payload);
                                };
                                state.session = conn;
                                if (conn.getReadyState) {
                                    conn.getReadyState({
                                        success: function(d) {
                                            addLog('就绪状态 status=' + (d ? d.status : ''));
                                            report({ status: 'readyState', value: d ? d.status : '' });
                                            if (d && d.status === 1 && !state.connected) {
                                                state.connected = true;
                                                state.failCount = 0;
                                                notifyStatus('connected');
                                                startHandshake();
                                            } else if (d && d.status === 2 && state.enabled) {
                                                // 静默断开（无 onclose/onerror 事件）：主动恢复连接
                                                addLog('就绪状态 status=2，检测到断开，自动重连');
                                                state.connected = false;
                                                state.session = null;
                                                state.handshakeDone = false;
                                                notifyStatus('connect_failed');
                                                scheduleReconnect();
                                            }
                                        },
                                        fail: function(d) {
                                            addLog('就绪状态查询失败');
                                            report({ status: 'readyState_fail', code: d ? d.code : '' });
                                        }
                                    });
                                }
                                // 保活轮询：每3秒检查连接状态（快速自愈：多实例回调覆盖后尽快恢复），静默断开自动重连、握手卡住重新握手
                                if (!state.keepAliveTimer) {
                                    state.keepAliveTimer = setInterval(function() {
                                        if (!state.enabled) return;
                                        if (!state.connected) {
                                            if (!state.reconnectTimer) scheduleReconnect();
                                            // 主动查询连接状态：即使 onopen 回调被其他 bundle 实例覆盖，也能恢复本实例状态
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
                                        // 握手活性校验：>90秒未收到 __hs__ 回复 → 视为网桥失联，重新握手验证（时间戳全局共享，多实例状态一致）
                                        var _hsNow2 = new Date().getTime();
                                        var _hsTs2 = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                                        var _hsFresh = _hsTs2 && (_hsNow2 - _hsTs2 <= 90000);
                                        if (!_hsFresh) {
                                            // log39d：分块传输进行中（存在未组装完的 chunkBuffers）跳过重新握手——
                                            // 慢速分块期间握手回复间隔可能超过活性窗口，但通道实际活跃，重新握手会打断传输
                                            var _activeChunk = false;
                                            try {
                                                var _cb = (typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) ? globalThis.__wb_chunk_buffers : null;
                                                for (var _ck in _cb) {
                                                    if (_cb[_ck] && _cb[_ck].received < _cb[_ck].chunkCount) {
                                                        _activeChunk = true;
                                                        break;
                                                    }
                                                }
                                            } catch (e) {}
                                            if (!_activeChunk) {
                                                if (state.handshakeDone) {
                                                    state.handshakeDone = false;
                                                    notifyStatus('handshake_timeout');
                                                }
                                                // 每实例 8 秒节流，避免多 bundle 并发轰炸握手；回复会刷新全局时间戳，各实例随即恢复就绪
                                                if (!state._hsProbeTs || (_hsNow2 - state._hsProbeTs > 8000)) {
                                                    state._hsProbeTs = _hsNow2;
                                                    addLog('保活: 握手过期，重新发起握手');
                                                    startHandshake();
                                                }
                                            }
                                        } else if (!state.handshakeDone && _hsFresh) {
                                            // 握手恢复：刷新本地状态并通知界面（设置页自动从"连接中"回到"就绪"）
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
                        }`;

// 补丁5b：state 增加 logs + cookie（cookie 用于网桥请求自动附加登录态）
const LOG_STATE_OLD = 'handshakeDone: false,\n                            negotiated: null,';
const LOG_STATE_NEW = 'handshakeDone: false,\n                            logs: [],\n                            cookie: "",\n                            lastHs: 0,\n                            _hsProbeTs: 0,\n                            negotiated: null,';

// 补丁5c：addLog 工具函数
const ADDLOG_OLD = 'function genId() {';
const ADDLOG_NEW = `function nowStr() {
                            try {
                                var d = new Date();
                                return (d.getHours() < 10 ? '0' : '') + d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes() + ':' + (d.getSeconds() < 10 ? '0' : '') + d.getSeconds();
                            } catch (e) { return ''; }
                        }
                        function addLog(t) {
                            state.logs.push('[' + nowStr() + '] ' + t);
                            if (state.logs.length > 60) state.logs.shift();
                        }
                        // 全局开关同步：任何实例每3秒跟随 storage 中的 bili_bridge_enabled
                        // （修复：Settings/Bridge 开关状态与各实例不一致，app.js 全局实例不 enable 导致 My 页请求失败）
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
                        function genId() {`;

// 补丁5d：getLogs 导出
const GETLOGS_OLD = 'getStatus: function() {';
const GETLOGS_NEW = `getLogs: function() {
                            return state.logs;
                        },
                        getStatus: function() {`;

// 补丁5s：getStatus 基于握手活性判断（>12秒未收到 __hs__ 回复即视为未就绪），
// 修复"设置界面网桥一栏永远显示就绪"：网桥失联/未开时按实际情况显示
const GETSTATUS_NEW = `getStatus: function() {
                            if (!state.enabled) return 'disabled';
                            var _hsTs = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                            if (_hsTs && (new Date().getTime() - _hsTs <= 90000)) return 'ready';
                            if (!state.connected) return 'connecting';
                            return 'handshaking';
                        }`;

// 补丁5t：isReady 同样基于握手活性（全局共享时间戳），使 __wbFetch 等走网桥的判断与真实状态一致
const ISREADY_NEW = `isReady: function() {
                            if (!state.enabled) return false;
                            if (!state.connected) return false;
                            var _hsTs = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : (state.lastHs || 0);
                            return !!_hsTs && (new Date().getTime() - _hsTs <= 90000);
                        }`;

// 补丁5e：handleHandshake 记录收到握手 + 会话缺失自动重建（log33）
const HH_OLD = 'function handleHandshake(msg) {\n                            var count = msg.count;';
const HH_NEW = `function handleHandshake(msg) {
                            var count = msg.count;
                            addLog('收到握手 count=' + count);
                            // 会话缺失保障（log33）：多实例 conn 回调可能被已失效实例持有（session 已因 onclose/页面销毁置空），
                            // 此时 sendMessage 会因 session=null 直接失败 → 不回包 → 手机端永远收不到 count>=1 → 握手永不完成。
                            // 收到握手时若会话缺失且网桥开启，立即重建会话再回包。
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
                            } catch (e) {}`;

// 补丁5f：bridgeFetch 记录请求
const BF_OLD = 'function bridgeFetch(options) {\n                            if (!state.connected || !state.handshakeDone) return void directFetch(options);';
const BF_NEW = `function bridgeFetch(options) {
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
                            }`;

// 补丁5g：processSingleResponse 记录响应
const PSR_OLD = 'function processSingleResponse(id, resp, pending) {\n                            if (pending.timer) clearTimeout(pending.timer);';
const PSR_NEW = `function processSingleResponse(id, resp, pending) {
                            addLog('收到响应 status=' + (resp.status || 0) + ' body=' + String(resp.body || '').length + '字符');
                            if (pending.timer) clearTimeout(pending.timer);`;

// 补丁5h：sendMessage 失败记录（含错误码）
const SM_OLD = 'fail: function() {\n                                        state.failCount++;';
const SM_NEW = `fail: function(d) {
                                        addLog('发送消息失败 code=' + (d ? d.code : '?') + ' data=' + (d ? String(d.data || '').substring(0, 60) : ''));
                                        state.failCount++;`;

// 补丁5h2：send 连续失败超过阈值后自动重连（网桥开关保持开启）
const RECONN_OLD = `state.connected = false;
                                            notifyStatus('disconnected');`;
const RECONN_NEW = `state.connected = false;
                                            state.session = null;
                                            notifyStatus('disconnected');
                                            scheduleReconnect(); // 保持开启：发送连续失败后自动重连`;

// 补丁5i（关键修复）：Vela interconnect send 的 data 必须为 Object（String 会报 202 invalid data）
const SM_DATA_OLD = `                                    data: str,`;
const SM_DATA_NEW = `                                    data: obj, // Vela: data 必须为 Object（String 报 202 invalid data）`;

// 补丁5i2（log37）：sendMessage 会话缺失时自动重建（原来静默 return false，
// 导致握手回包/fetch 请求全部静默丢弃——手机端收不到任何消息、握手永远过期）
const SM_SESSION_OLD = `                        function sendMessage(obj) {
                            if (!state.session) return false;`;
const SM_SESSION_NEW = `                        function sendMessage(obj) {
                            if (!state.session) {
                                try {
                                    if (state.enabled) createSession();
                                } catch (e) {}
                                if (!state.session) {
                                    addLog('发送消息失败：互联会话未建立');
                                    return false;
                                }
                            }`;

// 补丁5i3（log37）：enable()/disable() 全局同步 __wb_bridge（app.js 暴露的实例）——
// 页面层统一通过 globalThis.__wb_bridge.isReady() 判断就绪，若只改本实例 enabled，
// "关闭网桥再打开/页面切换"后 app 实例 enabled 仍为旧值 → 页面层永远显示未连接
const ENABLE_OLD = `                            enable: function() {
                                state.enabled = true;
                                _system2.default.set({
                                    key: 'bili_bridge_enabled',
                                    value: 'on'
                                });
                                if (!state.session) createSession();
                                notifyStatus('enabling');
                            },`;
const ENABLE_NEW = `                            enable: function() {
                                state.enabled = true;
                                // 全局同步（log37）：任何实例开启网桥时同步 globalThis.__wb_bridge 实例
                                try {
                                    var _gb = (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) ? globalThis.__wb_bridge : null;
                                    if (_gb && _gb !== this && _gb.enable) _gb.enable();
                                } catch (e) {}
                                _system2.default.set({
                                    key: 'bili_bridge_enabled',
                                    value: 'on'
                                });
                                if (!state.session) createSession();
                                notifyStatus('enabling');
                            },`;
const DISABLE_OLD = `                            disable: function() {
                                state.enabled = false;
                                _system2.default.set({
                                    key: 'bili_bridge_enabled',
                                    value: 'off'
                                });
                                closeSession();
                                notifyStatus('disabled');
                            },`;
const DISABLE_NEW = `                            disable: function() {
                                state.enabled = false;
                                try {
                                    var _gb2 = (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) ? globalThis.__wb_bridge : null;
                                    if (_gb2 && _gb2 !== this && _gb2.disable) _gb2.disable();
                                } catch (e) {}
                                _system2.default.set({
                                    key: 'bili_bridge_enabled',
                                    value: 'off'
                                });
                                closeSession();
                                notifyStatus('disabled');
                            },`;

// 补丁5m：init() 读取登录 Cookie 缓存到 state.cookie（网桥请求自动附加登录态）
const INIT_COOKIE_OLD = `                                _system2.default.get({
                                    key: 'bili_bridge_enabled',
                                    success: function(data) {
                                        if ('on' === data) self.enable();
                                    },
                                    fail: function() {}
                                });
                            },`;
const INIT_COOKIE_NEW = `                                _system2.default.get({
                                    key: 'bili_bridge_enabled',
                                    success: function(data) {
                                        if ('on' === data) self.enable();
                                    },
                                    fail: function() {}
                                });
                                _system2.default.get({
                                    key: 'bili_cookie',
                                    success: function(data) {
                                        if (data) state.cookie = data;
                                    },
                                    fail: function() {}
                                });
                            },`;

// 补丁5n：bridgeFetch 自动附加登录 Cookie（推送/页面请求不丢登录态）+ 语义化请求支持（action/params，URL 由手机端整合）
const BF_HDRS_OLD = `                            var reqMsg = {
                                tag: 'fetch',
                                id: id,
                                url: options.url,
                                options: {
                                    method: options.method || 'GET',
                                    headers: options.header || {},
                                    body: options.data || '',
                                    raw: false
                                }
                            };`;
const BF_HDRS_NEW = `                            var _hdrs = options.header || {};
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
                            };`;

// 补丁5v：bridge-only——sendMessage 发送失败时不再降级直连，直接回调失败
const SMFAIL_OLD = `                                directFetch(options);
                            }`;
const SMFAIL_NEW = `                                if (options.fail) options.fail({ code: -2, message: '网桥发送失败' });
                            }`;

// 补丁5x：bridge-only——删除 fetchbridge 模块中 system.fetch 的 import（directFetch 已删除，不再需要）
const SF_IMPORT_OLD = 'var _system = _interopRequireDefault($app_require$1("@app-module/system.fetch"));\n';
const SF_IMPORT_NEW = '';

// 补丁5y：bridge-only——删除 Video 页面脚本中未使用的 system.fetch import（Video 数据走 fetchbridge，页面脚本不直连）
const VF_IMPORT_OLD = 'var _system2 = _interopRequireDefault($app_require$1("@app-module/system.fetch"));\n';
const VF_IMPORT_NEW = '';

// 补丁5z：bridge-only——manifest 移除 system.fetch / system.request 特性声明（全包不再使用直连网络接口）
const MANIFEST_FETCH_OLD = '{"name":"system.fetch"},{"name":"system.request"},';
const MANIFEST_FETCH_NEW = '';

// 补丁5aa：Settings 页账号信息以手机端为准——直接请求 nav 判断登录态（不再依赖手环本地 bili_cookie 占位，
// 修复"我的已登录但设置页显示未登录"）
const ST_ACCOUNT_OLD = `loadAccountInfo () {
                                var self = this;
                                _system2.default.get({
                                    key: 'bili_cookie',
                                    success: function(cookie) {
                                        if (cookie && cookie.length > 10) {
                                            self.accountName = '已登录';
                                            self.accountStatus = '查看个人主页';
                                            _system2.default.get({
                                                key: 'bili_avatar_cached',
                                                success: function(avatar) {
                                                    if (avatar && avatar.length > 5) self.accountAvatar = avatar;
                                                },
                                                fail: function() {}
                                            });
                                            _system2.default.get({
                                                key: 'bili_uname_cached',
                                                success: function(uname) {
                                                    if (uname && uname.length > 0) self.accountName = uname;
                                                },
                                                fail: function() {}
                                            });
                                        } else {
                                            self.accountName = '未登录';
                                            self.accountStatus = '点击登录账号';
                                        }
                                    },
                                    fail: function() {
                                        self.accountName = '未登录';
                                        self.accountStatus = '点击登录账号';
                                    }
                                });
                            }`;
const ST_ACCOUNT_NEW = `loadAccountInfo () {
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
                                // 请求节流：15 秒内已请求过 nav 则跳过（onInit/onShow/onStatusChange 会重复触发，老设备避免重复请求）
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
                                                // 设置页头像缩到 32px（CDN 缩略图），保证快速稳定显示
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
                            }`;

// 补丁5y（log34）：Settings 退出登录时通知手机端同步清除登录信息
// （真实 Cookie 只存手机端，手环只清占位标记会导致手机端继续注入旧 Cookie，nav 仍返回已登录，状态错乱）
const ST_LOGOUT_OLD = `                                this.accountAvatar = '/common/images/icon.png';
                                this.status = '已退出登录';
                            },`;
const ST_LOGOUT_NEW = `                                this.accountAvatar = '/common/images/icon.png';
                                this.status = '已退出登录';
                                // 登出同步（log34）：通知手机端清除 LoginStore 中的真实登录 Cookie
                                try {
                                    var _wb2 = (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) ? globalThis.__wb_bridge : null;
                                    if (_wb2 && _wb2.sendLogout) _wb2.sendLogout();
                                    else if (_fetchbridge && _fetchbridge.default && _fetchbridge.default.sendLogout) _fetchbridge.default.sendLogout();
                                } catch (e) {}
                            },`;

// 补丁5j：closeSession 时清理保活定时器
const CS_CLOSE_OLD = `                            if (state.reconnectTimer) {
                                clearTimeout(state.reconnectTimer);
                                state.reconnectTimer = null;
                            }`;
const CS_CLOSE_NEW = `                            if (state.reconnectTimer) {
                                clearTimeout(state.reconnectTimer);
                                state.reconnectTimer = null;
                            }
                            if (state.keepAliveTimer) {
                                clearInterval(state.keepAliveTimer);
                                state.keepAliveTimer = null;
                            }`;

// 补丁5k：onMessage 处理开发者模式推送（__dev_fetch__：手机端指令 → 先刷新登录Cookie → 手表发起真实请求走网桥，完成后跳转对应页面）
const DEV_FETCH_OLD = `                              case 'fetch-ack':
                                    break;`;
const DEV_FETCH_NEW = `                              case 'fetch-ack':
                                    break;
                                case 'showqr_resp':
                                    // 手机返回的登录二维码（qrUrl + auth_code），触发 My 页回调显示二维码并开始轮询
                                    try {
                                        if (state.showQrRespCallback) state.showQrRespCallback(msg);
                                        // 全局转发：conn 回调可能被其他 bundle 覆盖，My 页通过全局槽接收 showqr_resp
                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_showqr_cb) {
                                            var _cb = globalThis.__wb_showqr_cb;
                                            globalThis.__wb_showqr_cb = null; // 防重复触发
                                            try { _cb(msg); } catch (e2) {}
                                        }
                                    } catch (e) {}
                                    break;
                                case 'showqr_status':
                                    // 手机端轮询进度（等待扫码/已扫码/已过期）
                                    try {
                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_qr_status_cb) globalThis.__wb_qr_status_cb(msg);
                                    } catch (e) {}
                                    break;
                                case 'showqr_done':
                                    // 手机端扫码确认：真实登录凭证只保存在手机端，手环端只保留登录状态；
                                    // bili_cookie 写占位标记（页面按"长度>10"判断已登录走个性化），真实 Cookie 由手机端转发时统一注入
                                    try {
                                        if (msg && 0 === msg.code) {
                                            state.cookie = ''; // 手环不持有真实 Cookie，防止占位符经网桥附加
                                            _system2.default.set({ key: 'bili_cookie', value: 'bili_logged=1;' }); // 占位登录标记
                                            _system2.default.set({ key: 'bili_login_ok', value: '1' });
                                            if (msg.mid) _system2.default.set({ key: 'bili_mid', value: '' + msg.mid });
                                        }
                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_qr_done_cb) {
                                            var _d = globalThis.__wb_qr_done_cb;
                                            globalThis.__wb_qr_done_cb = null; // 防重复触发
                                            try { _d(msg); } catch (e2) {}
                                        }
                                    } catch (e) {}
                                    break;
                                case '__dev_fetch__':
                                    // 开发者模式推送测试：支持语义化（action+params，手机端整合 URL + wbi 签名/多候选兜底）与 url 直传两种方式
                                    addLog('开发者推送: ' + (msg.name || '') + ' ' + (msg.action || msg.url || ''));
                                    (function() {
                                        var _isRcmd = function(a, u) { return 'home_rcmd' === a || (u && u.indexOf('rcmd') > 0); };
                                        var _tryFetch = function(action, params, url, depth) {
                                            var _opts = {
                                                responseType: 'json',
                                                timeout: 30000,
                                                success: function(res) {
                                                    var d = res.data;
                                                    var s = ('string' == typeof d) ? d : JSON.stringify(d || {});
                                                    var code = null;
                                                    try {
                                                        var dj = ('string' == typeof d) ? JSON.parse(d) : d;
                                                        code = dj ? dj.code : null;
                                                    } catch (e2) {}
                                                    if (null === code || 0 === code || depth >= 2) {
                                                        addLog('推送结果: ' + String(s).substring(0, 120));
                                                        jumpTo(msg.uri);
                                                    } else if (_isRcmd(action, url)) {
                                                        // 首页推荐失败（风控 code!=0）→ 自动改用热门（手机端 home_rcmd 已带 popular 兜底，这里再补一手）
                                                        addLog('首页推荐失败(code=' + code + ')，自动改用热门');
                                                        _tryFetch('home_popular', { page: '1' }, '', depth + 1);
                                                    } else {
                                                        addLog('推送结果: ' + String(s).substring(0, 120));
                                                        jumpTo(msg.uri);
                                                    }
                                                },
                                                fail: function(e) {
                                                    if (depth < 2 && _isRcmd(action, url)) {
                                                        addLog('首页推荐请求失败，自动改用热门');
                                                        _tryFetch('home_popular', { page: '1' }, '', depth + 1);
                                                    } else {
                                                        addLog('推送失败: ' + (e ? (e.message || '') : ''));
                                                        jumpTo(msg.uri);
                                                    }
                                                }
                                            };
                                            if (action && 'string' == typeof action && 'http' !== action.substring(0, 4)) {
                                                _opts.action = action;
                                                _opts.params = params || {};
                                            } else {
                                                _opts.url = url || action; // url 直传兼容
                                            }
                                            bridgeFetch(_opts);
                                        };
                                        // 登录凭证在手机端，手环请求直接发出（手机转发时统一附加登录态）
                                        if (msg.action) _tryFetch(msg.action, msg.params || {}, '', 0);
                                        else _tryFetch('', {}, msg.url || '', 0);
                                    })();
                                    break;`;

// 补丁5l：跳转辅助函数（注入到 fetchbridge 模块，供 __dev_fetch__ 完成后打开对应页面）
const JUMP_FN_OLD = 'function genId() {';
const JUMP_FN_NEW = `function jumpTo(uri) {
                            if (!uri) return;
                            try {
                                var _r = $app_require$1("@app-module/system.router");
                                _r.push({ uri: uri });
                                addLog('已打开页面: ' + uri);
                            } catch (e) {
                                addLog('打开页面失败: ' + e);
                            }
                        }
                        function genId() {`;

// 补丁5m-1（log27）：bridgeFetch 日志打印 action（语义化请求 url 为空）
const BF_LOG_OLD = `addLog('网桥请求 ' + (options.method || 'GET') + ' ' + options.url);`;
const BF_LOG_NEW = `addLog('网桥请求 ' + (options.method || 'GET') + ' ' + (options.action || options.url || ''));`;

// 补丁5m-2（log27）：请求回调全局注册 —— Vela conn 是单例，多个 fetchbridge 实例（app.js/Bridge/Settings/Video）
// 的 onmessage 回调互相覆盖，发出请求的实例可能收不到响应（表现为"手机已推送、手环已收到指令，但功能不显示/超时"）。
// 方案：pending 同时写入 globalThis.__wb_pending（分块缓冲写 __wb_chunk_buffers），
// 任一实例收到响应时先查本地再查全局，路由回发起请求的实例。
const BF_PEND_OLD = `                            state.pending[id] = {
                                success: options.success,
                                fail: options.fail,
                                responseType: options.responseType,
                                timer: setTimeout(function() {
                                    var pending = state.pending[id];
                                    if (pending) {
                                        delete state.pending[id];
                                        if (state.chunkBuffers[id]) delete state.chunkBuffers[id];
                                        if (pending.fail) pending.fail({
                                            code: -1,
                                            message: 'fetch timeout'
                                        });
                                    }
                                }, timeout)
                            };`;
const BF_PEND_NEW = `                            state.pending[id] = {
                                success: options.success,
                                fail: options.fail,
                                responseType: options.responseType,
                                timer: setTimeout(function() {
                                    var pending = state.pending[id] || (typeof globalThis !== 'undefined' && globalThis.__wb_pending && globalThis.__wb_pending[id]);
                                    if (pending) {
                                        delete state.pending[id];
                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_pending) delete globalThis.__wb_pending[id];
                                        if (state.chunkBuffers[id]) delete state.chunkBuffers[id];
                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) delete globalThis.__wb_chunk_buffers[id];
                                        if (pending.fail) pending.fail({
                                            code: -1,
                                            message: 'fetch timeout'
                                        });
                                    }
                                }, timeout)
                            };
                            if (typeof globalThis !== 'undefined') {
                                globalThis.__wb_pending = globalThis.__wb_pending || {};
                                globalThis.__wb_pending[id] = state.pending[id];
                            }`;

// 补丁5m-3（log27）：handleFetchResponse 先查本地 pending，再查全局表
const BF_HFR_OLD = `                        function handleFetchResponse(msg) {
                            var id = msg.id || '';
                            var resp = msg.resp;
                            if (!resp) return;
                            var pending = state.pending[id];
                            if (!pending) return;`;
const BF_HFR_NEW = `                        function handleFetchResponse(msg) {
                            var id = msg.id || '';
                            var resp = msg.resp;
                            if (!resp) return;
                            var pending = state.pending[id];
                            if (!pending && typeof globalThis !== 'undefined' && globalThis.__wb_pending) pending = globalThis.__wb_pending[id];
                            if (!pending) return;`;

// 补丁5m-4（log27）：processSingleResponse 清理全局 pending
const BF_PSR_OLD = `                            if (pending.timer) clearTimeout(pending.timer);
                            delete state.pending[id];
                            var body = resp.body || '';`;
const BF_PSR_NEW = `                            if (pending.timer) clearTimeout(pending.timer);
                            delete state.pending[id];
                            if (typeof globalThis !== 'undefined' && globalThis.__wb_pending) delete globalThis.__wb_pending[id];
                            var body = resp.body || '';`;

// 补丁5m-5（log27）：分块缓冲全局注册 + 超时清理全局（防块丢失导致组装超时）
const BF_CBUF_OLD = `                                state.chunkBuffers[id] = {
                                    chunkCount: resp.chunkCount || 0,
                                    total: resp.chunkCount || 0,
                                    received: 0,
                                    chunks: {},
                                    bodyEncoding: resp.bodyEncoding || 'base64',
                                    compression: resp.compression || 'none',
                                    raw: resp.raw,
                                    ack: true === resp.ack,
                                    ackBase: 0,
                                    info: resp,
                                    timer: setTimeout(function() {
                                        if (state.chunkBuffers[id]) {
                                            delete state.chunkBuffers[id];
                                            var p = state.pending[id];
                                            if (p) {
                                                delete state.pending[id];
                                                if (p.timer) clearTimeout(p.timer);
                                                if (p.fail) p.fail({
                                                    code: -1,
                                                    message: 'chunk assembly timeout'
                                                });
                                            }
                                        }
                                    }, CHUNK_ASSEMBLY_TIMEOUT)
                                };`;
const BF_CBUF_NEW = `                                state.chunkBuffers[id] = {
                                    chunkCount: resp.chunkCount || 0,
                                    total: resp.chunkCount || 0,
                                    received: 0,
                                    chunks: {},
                                    bodyEncoding: resp.bodyEncoding || 'base64',
                                    compression: resp.compression || 'none',
                                    raw: resp.raw,
                                    ack: true === resp.ack,
                                    ackBase: 0,
                                    info: resp,
                                    timer: setTimeout(function() {
                                        if (state.chunkBuffers[id]) {
                                            delete state.chunkBuffers[id];
                                            if (typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) delete globalThis.__wb_chunk_buffers[id];
                                            var p = state.pending[id] || (typeof globalThis !== 'undefined' && globalThis.__wb_pending && globalThis.__wb_pending[id]);
                                            if (p) {
                                                delete state.pending[id];
                                                if (typeof globalThis !== 'undefined' && globalThis.__wb_pending) delete globalThis.__wb_pending[id];
                                                if (p.timer) clearTimeout(p.timer);
                                                if (p.fail) p.fail({
                                                    code: -1,
                                                    message: 'chunk assembly timeout'
                                                });
                                            }
                                        }
                                    }, CHUNK_ASSEMBLY_TIMEOUT)
                                };
                                if (typeof globalThis !== 'undefined') {
                                    globalThis.__wb_chunk_buffers = globalThis.__wb_chunk_buffers || {};
                                    globalThis.__wb_chunk_buffers[id] = state.chunkBuffers[id];
                                }`;

// 补丁5m-6（log27）：handleFetchChunk 分块缓冲先查本地再查全局
const BF_HFC_OLD = `                        function handleFetchChunk(msg) {
                            var id = msg.id || '';
                            var seq = msg.seq;
                            var data = msg.data;
                            var buf = state.chunkBuffers[id];
                            if (!buf) return;`;
const BF_HFC_NEW = `                        function handleFetchChunk(msg) {
                            var id = msg.id || '';
                            var seq = msg.seq;
                            var data = msg.data;
                            var buf = state.chunkBuffers[id];
                            if (!buf && typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) buf = globalThis.__wb_chunk_buffers[id];
                            if (!buf) return;`;

// 补丁5m-7（log27）：tryAssembleChunks 组装后清理全局缓冲/pending，pending 支持全局查找
const BF_TAC_OLD = `                            delete state.chunkBuffers[id];
                            var pending = state.pending[id];
                            if (!pending) return;
                            if (pending.timer) clearTimeout(pending.timer);
                            delete state.pending[id];`;
const BF_TAC_NEW = `                            delete state.chunkBuffers[id];
                            if (typeof globalThis !== 'undefined' && globalThis.__wb_chunk_buffers) delete globalThis.__wb_chunk_buffers[id];
                            var pending = state.pending[id] || (typeof globalThis !== 'undefined' && globalThis.__wb_pending && globalThis.__wb_pending[id]);
                            if (!pending) return;
                            if (pending.timer) clearTimeout(pending.timer);
                            delete state.pending[id];
                            if (typeof globalThis !== 'undefined' && globalThis.__wb_pending) delete globalThis.__wb_pending[id];`;

// 补丁5m-8（log29）：genId 全局唯一 —— 4 个 bundle 的 fetchbridge 实例各自持有 state.reqIdCounter
// （都从 r1 开始递增），实例间请求 id 必然重复。log27 全局响应分发后，重复 id 会导致全局 pending/
// 分块缓冲互相覆盖 → 响应被错配/丢失（登录响应收不到 = "登录不了"）。
// 改用 globalThis 共享计数器，所有实例 id 全局唯一。
const BF_GENID_OLD = `                        function genId() {
                            state.reqIdCounter = state.reqIdCounter + 1;
                            return 'r' + state.reqIdCounter;
                        }`;
const BF_GENID_NEW = `                        function genId() {
                            if (typeof globalThis !== 'undefined') {
                                globalThis.__wb_reqCounter = (globalThis.__wb_reqCounter || 0) + 1;
                                return 'r' + globalThis.__wb_reqCounter;
                            }
                            state.reqIdCounter = state.reqIdCounter + 1;
                            return 'r' + state.reqIdCounter;
                        }`;

// 补丁5n（log39b）：延长请求默认超时与分块组装超时 —— 手机端已成功返回响应，但互联通道（蓝牙）
// 分块传输慢，手环端 30s pending 先超时 → 页面显示"网络失败/加载失败"。默认超时提到 120s，
// 分块组装超时提到 180s（必须 ≥ 默认超时，否则组装期间 pending 已删、响应无处回调）。
const FETCH_TO_OLD = 'var FETCH_DEFAULT_TIMEOUT = 30000;';
const FETCH_TO_NEW = 'var FETCH_DEFAULT_TIMEOUT = 120000;';
const CHUNK_TO_OLD = 'var CHUNK_ASSEMBLY_TIMEOUT = 60000;';
const CHUNK_TO_NEW = 'var CHUNK_ASSEMBLY_TIMEOUT = 180000;';

// 补丁5n-2（log39b）：Home/Live 页面显式 timeout 同步延长（loadLiveList 30000 / fetchAvatarFromV2 15000 / Live 列表 30000）
const HOME_TO_LIVE_OLD = 'timeout: 30000,';
const HOME_TO_LIVE_NEW = 'timeout: 120000,';
const HOME_TO_AVATAR_OLD = 'timeout: 15000,';
const HOME_TO_AVATAR_NEW = 'timeout: 120000,';

// 补丁5n-3（log39c）：修复分块响应双计时器竞争 —— 收到分块头时原 pending 超时未清除，
// 慢速分块传输（蓝牙）尚未完成时 pending 先超时 → 手机已返回数据、手环端仍报"fetch timeout"。
// 修复：分块模式下清除 pending 计时器，总超时改由分块组装超时（CHUNK_ASSEMBLY_TIMEOUT）统一兜底。
const BF_CHUNK_CLEAR_OLD = `                            if (true === resp.chunked) {
                                state.chunkBuffers[id] = {`;
const BF_CHUNK_CLEAR_NEW = `                            if (true === resp.chunked) {
                                // log39c：分块响应以"分块头到达"重新计时（清除原 pending 超时），
                                // 总超时改由分块组装超时 CHUNK_ASSEMBLY_TIMEOUT 兜底，避免慢速分块传输未完成时
                                // pending 先超时导致"手机已返回、手环仍 fetch timeout"
                                if (pending.timer) clearTimeout(pending.timer);
                                state.chunkBuffers[id] = {`;

// 补丁5o（图片分辨率缩减，log40b）：手环端 CDN 缩略图过大（蓝牙/低配加载慢、失败）——
// 首页推荐封面 336x210→160x100、直播封面 312x176→160x90、预载帧 312x210/208x140→160x108/128x86、
// 头像 64x64/48x48→32x32（首页与"我的"页）
const IMG_HOME_COVER_OLD = "'@336w_210h_1e_1c.jpg'";
const IMG_HOME_COVER_NEW = "'@96w_60h_1e_1c.jpg'";   // log44：160x100→96x60（文件约减半，低配/蓝牙加载更易成功）
const IMG_LIVE_COVER_OLD = "'@312w_176h_1e_1c.jpg'";
const IMG_LIVE_COVER_NEW = "'@96w_54h_1e_1c.jpg'";   // log44：160x90→96x54
const IMG_AVATAR48_OLD = "'@48w_48h_1e_1c.jpg'";
const IMG_AVATAR48_NEW = "'@32w_32h_1e_1c.jpg'";
const IMG_AVATAR64_OLD = "'@64w_64h_1e_1c.jpg'";
const IMG_AVATAR64_NEW = "'@32w_32h_1e_1c.jpg'";
const IMG_FRAME_L_OLD = "'@312w_210h_1e_1c.jpg'";
const IMG_FRAME_L_NEW = "'@96w_60h_1e_1c.jpg'";      // log44：预载帧 160x108→96x60
const IMG_FRAME_S_OLD = "'@208w_140h_1e_1c.jpg'";
const IMG_FRAME_S_NEW = "'@64w_40h_1e_1c.jpg'";      // log44：预载帧 128x86→64x40

// ===================== log44（进一步减少图片传输开支：砍原图预载 + 全页降尺寸）=====================
// 首页：预载帧机制原样预载【原图】（无缩略图参数，数百 KB）+ 2 个缩略图，4 个视频共 12 帧 → 蓝牙/低配
// 加载超时失败，这是首页图片开支大头。整体替换 buildFrameUrls：不再预载原图，只留 1 个最小缩略图。
const HOME_FRAMES_OLD = `                        buildFrameUrls (pic) {
                            var cover = this.cleanImageUrl(pic);
                            var frames = [];
                            if (!cover) return frames;
                            frames.push(cover);
                            if (cover.indexOf('hdslb.com') > 0 || cover.indexOf('biliimg.com') > 0) {
                                frames.push(cover + '@312w_210h_1e_1c.jpg');
                                frames.push(cover + '@208w_140h_1e_1c.jpg');
                            }
                            return frames;
                        },`;
const HOME_FRAMES_NEW = `                        buildFrameUrls (pic) {
                            var cover = this.cleanImageUrl(pic);
                            var frames = [];
                            if (!cover) return frames;
                            // log44：不再预载原图（数百 KB，蓝牙/低配加载失败），只取最小缩略图，传输开支降到最低
                            if (cover.indexOf('hdslb.com') > 0 || cover.indexOf('biliimg.com') > 0) frames.push(cover + '@96w_60h_1e_1c.jpg');
                            return frames;
                        },`;
// 首页：预载帧数量 4 个视频 → 2 个（进一步减少图片下载量）
const HOME_PRELOAD_OLD = `                            var max = list.length > 4 ? 4 : list.length;`;
const HOME_PRELOAD_NEW = `                            // log44：预载数量 4→2，减少图片传输开支
                            var max = list.length > 2 ? 2 : list.length;`;
// Video：详情封面 312x210→160x100、UP 头像 64x64→32x32
const VIDEO_COVER_OLD = "return cover + '@312w_210h_1e_1c.jpg';";
const VIDEO_COVER_NEW = "return cover + '@160w_100h_1e_1c.jpg';";
const VIDEO_AVATAR_OLD = "if (face.indexOf('hdslb.com') > 0 || face.indexOf('biliimg.com') > 0) face += '@64w_64h_1e_1c.jpg';";
const VIDEO_AVATAR_NEW = "if (face.indexOf('hdslb.com') > 0 || face.indexOf('biliimg.com') > 0) face += '@32w_32h_1e_1c.jpg';";
// Video：图片帧轮播裁剪列表 26 项（624x420 等大图，每张 50-150KB）→ 5 项最小缩略（160/128/96）
const VIDEO_FRAMES_OLD = `                                var crops = [
                                    '624w_420h_1e_1c.jpg',
                                    '624w_420h_1e_2c.jpg',
                                    '624w_420h_1e_3c.jpg',
                                    '624w_420h_2e_1c.jpg',
                                    '624w_420h_2e_2c.jpg',
                                    '624w_420h_2e_3c.jpg',
                                    '480w_320h_1e_1c.jpg',
                                    '480w_320h_1e_2c.jpg',
                                    '480w_320h_1e_3c.jpg',
                                    '312w_175h_0e_0c.jpg',
                                    '300w_210h_1e_1c.jpg',
                                    '300w_210h_1e_2c.jpg',
                                    '300w_210h_1e_3c.jpg',
                                    '260w_176h_1e_1c.jpg',
                                    '260w_176h_1e_2c.jpg',
                                    '260w_176h_1e_3c.jpg',
                                    '240w_162h_1e_1c.jpg',
                                    '240w_162h_1e_2c.jpg',
                                    '240w_162h_1e_3c.jpg',
                                    '208w_140h_1e_1c.jpg',
                                    '208w_140h_1e_2c.jpg',
                                    '208w_140h_1e_3c.jpg',
                                    '180w_120h_1e_1c.jpg',
                                    '180w_120h_1e_2c.jpg',
                                    '180w_120h_1e_3c.jpg',
                                    '160w_108h_1e_1c.jpg',
                                    '160w_108h_1e_2c.jpg'
                                ];`;
const VIDEO_FRAMES_NEW = `                                var crops = [
                                    // log44：图片帧轮播 26 项→5 项，移除 624x420/480x320 等大图，仅留小缩略，减少传输开支
                                    '160w_108h_1e_1c.jpg',
                                    '160w_108h_1e_2c.jpg',
                                    '160w_108h_1e_3c.jpg',
                                    '128w_86h_1e_1c.jpg',
                                    '96w_64h_1e_1c.jpg'
                                ];`;
// Search：封面 336x210→160x100、UP 头像 64x64→32x32
const SEARCH_COVER_OLD = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@336w_210h_1e_1c.jpg';";
const SEARCH_COVER_NEW = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@160w_100h_1e_1c.jpg';";
const SEARCH_AVATAR_OLD = "if (face.indexOf('hdslb.com') > 0 || face.indexOf('biliimg.com') > 0) face += '@64w_64h_1e_1c.jpg';";
const SEARCH_AVATAR_NEW = "if (face.indexOf('hdslb.com') > 0 || face.indexOf('biliimg.com') > 0) face += '@32w_32h_1e_1c.jpg';";
// Live：直播封面 312x176→160x90、主播头像 48x48→32x32
const LIVE_COVER_OLD = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@312w_176h_1e_1c.jpg';";
const LIVE_COVER_NEW = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@160w_90h_1e_1c.jpg';";
const LIVE_AVATAR_OLD = "if (face.indexOf('hdslb.com') > 0 || face.indexOf('biliimg.com') > 0) face += '@48w_48h_1e_1c.jpg';";
const LIVE_AVATAR_NEW = "if (face.indexOf('hdslb.com') > 0 || face.indexOf('biliimg.com') > 0) face += '@32w_32h_1e_1c.jpg';";
// UpHome：视频封面 96x64→64x43、头像 64x64→32x32
const UPH_COVER_OLD = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@96w_64h_1e_1c.jpg';";
const UPH_COVER_NEW = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@64w_43h_1e_1c.jpg';";
const UPH_AVATAR_OLD = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@64w_64h_1e_1c.jpg';";
const UPH_AVATAR_NEW = "if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@32w_32h_1e_1c.jpg';";
// Messages：私信会话头像 64x64→32x32
const MSG_AVATAR_OLD = "if (url.indexOf('hdslb.com') > 0 || url.indexOf('biliimg.com') > 0) url += '@64w_64h_1e_1c.jpg';";
const MSG_AVATAR_NEW = "if (url.indexOf('hdslb.com') > 0 || url.indexOf('biliimg.com') > 0) url += '@32w_32h_1e_1c.jpg';";

// ===================== log45（评论区分页 + 首页刷新按钮）=====================
// 评论区：video_reply 接口支持 pn 分页（每页 3 条），但基线只加载 pn=1 一页 3 条 → 大评论视频加载不出/内容少。
// 修复：pn 状态 + prev/next 方法（1-10 循环）+ 底部 [上一页][第X页][下一页] 三件套（与动态/历史/私信页一致）。
// 1) 页面 data 增加 commentPage
const COMMENT_DATA_OLD = `                            c3Show: false,
                            c3User: '',
                            c3Text: ''
                        },`;
const COMMENT_DATA_NEW = `                            c3Show: false,
                            c3User: '',
                            c3Text: '',
                            // log45：评论区 pn 分页（1-10 循环，每页 3 条）
                            commentPage: 1
                        },`;
// 2) 翻页方法（点击才发送对应页请求）
const COMMENT_METHODS_OLD = `                        back () {
                            _system.default.back();
                        }`;
const COMMENT_METHODS_NEW = `                        prevCommentPage () {
                            // log45：评论区上一页（1-10 循环），点击发送上一页评论请求
                            this.commentPage = (this.commentPage || 1) <= 1 ? 10 : this.commentPage - 1;
                            this.loadComments();
                        },
                        nextCommentPage () {
                            // log45：评论区下一页（1-10 循环），点击发送下一页评论请求
                            this.commentPage = (this.commentPage || 1) >= 10 ? 1 : this.commentPage + 1;
                            this.loadComments();
                        },
                        back () {
                            _system.default.back();
                        }`;
// 3) 模板尾部（c3 评论卡之后）插入 pager 三件套
const COMMENT_PAGER_OLD = `                            ];
                        })
                    ]);
                };
                $app_exports$['entry'] = function($app_exports$) {`;
const COMMENT_PAGER_NEW = `                            ];
                        }),
                        aiot.__ce__("div", {
                            __vm__: _vm_,
                            __opts__: {
                                classList: [
                                    "pager"
                                ]
                            }
                        }, [
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager-btn"
                                    ],
                                    events: {
                                        click: function(evt) {
                                            return _vm_.prevCommentPage(evt);
                                        }
                                    }
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn-text"
                                        ],
                                        value: "上一页"
                                    }
                                }, [])
                            ]),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager-page"
                                    ]
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-page-text"
                                        ],
                                        value: function() {
                                            return '第 ' + _vm_.commentPage + '/10 页';
                                        }
                                    }
                                }, [])
                            ]),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager-btn"
                                    ],
                                    events: {
                                        click: function(evt) {
                                            return _vm_.nextCommentPage(evt);
                                        }
                                    }
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn-text"
                                        ],
                                        value: "下一页"
                                    }
                                }, [])
                            ])
                        ])
                    ]);
                };
                $app_exports$['entry'] = function($app_exports$) {`;
// 4) 样式追加 pager 系列（与动态/历史/私信页一致）
const COMMENT_STYLE_OLD = `                    [
                        [
                            [
                                0,
                                "content"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "15px",
                            marginTop: "8px",
                            width: "294px",
                            lines: 4,
                            textOverflow: "ellipsis"
                        }
                    ]
                ];`;
const COMMENT_STYLE_NEW = `                    [
                        [
                            [
                                0,
                                "content"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "15px",
                            marginTop: "8px",
                            width: "294px",
                            lines: 4,
                            textOverflow: "ellipsis"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager"
                            ]
                        ],
                        {
                            width: "318px",
                            height: "34px",
                            marginTop: "10px",
                            flexDirection: "row",
                            alignItems: "center",
                            justifyContent: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn"
                            ]
                        ],
                        {
                            width: "88px",
                            height: "34px",
                            borderRadius: "17px",
                            backgroundColor: "#fb7299",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page"
                            ]
                        ],
                        {
                            minWidth: "70px",
                            height: "34px",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold"
                        }
                    ]
                ];`;

// ===================== log47（每页第一个视频图片加载不出 + 直播封面同款）=====================
// 根因：首页 setItem 对 item.pic 只做"追加缩略图参数"，缺少 URL 清洗——B 站部分返回
// （推荐头图/广告位，即每页 list[0]）的 pic 已带 @ 尺寸参数（如 ...xxx.jpg@672w_378h_1e_1c.jpg），
// 直接追加会得到双 @ 无效 URL → 该卡片图片加载不出（"每个分页的第一个视频"即此）。
// 修复：追加前先补 https: 前缀 + split('@')[0] 去掉已有参数（与 cleanImageUrl 一致）。
const HOME_SETITEM_OLD = `                            var lowPic = item.pic || '/common/images/icon.png';
                            if (lowPic.indexOf('hdslb.com') > 0 || lowPic.indexOf('biliimg.com') > 0) lowPic += '@336w_210h_1e_1c.jpg';
                            this['v' + index + 'Pic'] = lowPic;`;
const HOME_SETITEM_NEW = `                            var lowPic = item.pic || '/common/images/icon.png';
                            // log47：URL 清洗（补 https: + 去掉已有 @ 尺寸参数）——B 站部分返回（头图/广告）pic 已带 @ 参数，
                            // 直接追加得双 @ 无效 URL，该卡片图片加载不出
                            if (0 !== lowPic.indexOf('http') && 0 === lowPic.indexOf('//')) lowPic = 'https:' + lowPic;
                            if (lowPic.indexOf('@') > 0) lowPic = lowPic.split('@')[0];
                            if (lowPic.indexOf('hdslb.com') > 0 || lowPic.indexOf('biliimg.com') > 0) lowPic += '@336w_210h_1e_1c.jpg';
                            this['v' + index + 'Pic'] = lowPic;`;
// 直播列表封面同款问题（item.user_cover 可能带 @ 参数）
const HOME_LIVE_SETITEM_OLD = `                            var pic = item.cover || item.user_cover || '/common/images/icon.png';
                            if (0 !== pic.indexOf('http') && 0 === pic.indexOf('//')) pic = 'https:' + pic;
                            if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@312w_176h_1e_1c.jpg';`;
const HOME_LIVE_SETITEM_NEW = `                            var pic = item.cover || item.user_cover || '/common/images/icon.png';
                            if (0 !== pic.indexOf('http') && 0 === pic.indexOf('//')) pic = 'https:' + pic;
                            if (pic.indexOf('@') > 0) pic = pic.split('@')[0]; // log47：直播封面同款，去掉已有 @ 参数
                            if (pic.indexOf('hdslb.com') > 0 || pic.indexOf('biliimg.com') > 0) pic += '@312w_176h_1e_1c.jpg';`;

// ===================== log49（图片 base64 分片 → 手环保存本地文件 → image 加载）=====================
// 思路（大佬 @B4QAQ/@雪松 指点）：10 Pro 等无网络/低配设备 image 组件加载网络 jpg 有 BUG（感叹号），
// 且 Vela img 不支持 base64 data URI。可行路径：手机端把图片压缩成 PNG(≤20KB) → base64 → 经互联分片
// （≤30KB/片）发给手环 → 手环用 @system.file 把 base64 解码写为 internal://files/img_N.png → image 加载本地路径。
// 手环性能差：连续写文件/解码 3 分钟内可能重启 → 保存必须串行 + 节流休息（800ms/张）。
// fetchbridge 5 个 bundle 同步注入；页面零改动（响应中的 file://img_N 占位由 fetchbridge 等待图片就绪后替换）。

// P1：import @system.file（锚点：fetchbridge 的 storage import 行）
const FILE_IMPORT_OLD = `                        var _system2 = _interopRequireDefault($app_require$1("@app-module/system.storage"));`;
const FILE_IMPORT_NEW = `                        var _system2 = _interopRequireDefault($app_require$1("@app-module/system.storage"));
                        // log49：文件存储（把手机端下发的图片 base64 解码写为 internal://files/img_N.png，image 加载本地路径）
                        var _wfile = _interopRequireDefault($app_require$1("@app-module/system.file"));`;

// P2：state 增加图片分片缓冲与保存队列（锚点=前面补丁改造后的 state：含 logs/cookie/lastHs/_hsProbeTs）
const FILE_STATE_OLD = `                        var state = {
                            session: null,
                            connected: false,
                            handshakeDone: false,
                            logs: [],
                            cookie: "",
                            lastHs: 0,
                            _hsProbeTs: 0,
                            negotiated: null,
                            enabled: false,
                            reqIdCounter: 0,
                            pending: {},
                            chunkBuffers: {},
                            handshakeCallbacks: [],
                            statusCallback: null,
                            reconnectTimer: null,
                            failCount: 0
                        };`;
const FILE_STATE_NEW = `                        var state = {
                            session: null,
                            connected: false,
                            handshakeDone: false,
                            logs: [],
                            cookie: "",
                            lastHs: 0,
                            _hsProbeTs: 0,
                            negotiated: null,
                            enabled: false,
                            reqIdCounter: 0,
                            pending: {},
                            chunkBuffers: {},
                            handshakeCallbacks: [],
                            statusCallback: null,
                            reconnectTimer: null,
                            failCount: 0,
                            // log49：图片 base64 分片缓冲（imgParts: id → {parts, total, got}）与保存队列（串行+节流防重启）
                            imgParts: {},
                            imgSaveQueue: [],
                            imgSaving: false
                        };`;

// P3：onMessage 增加 img_begin / img_chunk / __ping__ 分支（锚点=fetch-ack case，改造后仍存在，插入在其后）
const FILE_ONMSG_OLD = `                                case 'fetch-ack':
                                    break;`;
const FILE_ONMSG_NEW = `                                case 'fetch-ack':
                                    break;
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
                                    break;`;

// P4：注入图片保存队列函数（pumpImgSave：base64 解码 → writeArrayBuffer → 全局就绪表 → 节流休息再下一张）
const FILE_PUMP_OLD = `                        function tryAssembleChunks(id, buf) {`;
const FILE_PUMP_NEW = `                        // log49：图片保存队列（串行 + 每张保存后休息 800ms，防低配手环连续解码写文件 3 分钟内重启）
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
                                    fail: function() {
                                        _relax();
                                    }
                                });
                            } catch (e) {
                                _relax();
                            }
                        }
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
                        function tryAssembleChunks(id, buf) {`;

// P5：单发响应（processSingleResponse）——JSON 响应统一走 resolveImgWait（含图片占位等待）
const FILE_PSR_OLD = `                            var finalData = decodedBody;
                            if ('json' === pending.responseType && 'string' == typeof finalData) {
                                finalData = safeJsonParse(finalData);
                                if (!finalData) {
                                    if (pending.fail) pending.fail({
                                        code: -1,
                                        message: 'JSON parse failed'
                                    });
                                    return;
                                }
                            }
                            if (pending.success) pending.success({
                                data: finalData,
                                statusCode: resp.status || 200,
                                headers: resp.headers || {}
                            });`;
const FILE_PSR_NEW = `                            var finalData = decodedBody;
                            if ('json' === pending.responseType && 'string' == typeof finalData) {
                                // log49：JSON 响应统一走图片占位等待（含 file://img_N → internal://files/img_N.png 替换）
                                resolveImgWait(finalData, pending, resp.headers, resp.status);
                                return;
                            }
                            if (pending.success) pending.success({
                                data: finalData,
                                statusCode: resp.status || 200,
                                headers: resp.headers || {}
                            });`;

// P6：分块响应（tryAssembleChunks）——JSON 响应统一走 resolveImgWait
const FILE_TAC_OLD = `                            var finalData = decodedBody;
                            if ('json' === pending.responseType && 'string' == typeof finalData) {
                                finalData = safeJsonParse(finalData);
                                if (!finalData) {
                                    if (pending.fail) pending.fail({
                                        code: -1,
                                        message: 'JSON parse failed (chunked)'
                                    });
                                    return;
                                }
                            }
                            if (pending.success) pending.success({
                                data: finalData,
                                statusCode: buf.info.status || 200,
                                headers: buf.info.headers || {}
                            });`;
const FILE_TAC_NEW = `                            var finalData = decodedBody;
                            if ('json' === pending.responseType && 'string' == typeof finalData) {
                                // log49：分块响应同样走图片占位等待
                                resolveImgWait(finalData, pending, buf.info.headers, buf.info.status);
                                return;
                            }
                            if (pending.success) pending.success({
                                data: finalData,
                                statusCode: buf.info.status || 200,
                                headers: buf.info.headers || {}
                            });`;

// ===================== log49b（连接活性：收到任意手机端消息即刷新握手活性时间戳 + fetch-progress 传输信号 + 登录态以手机端为准）=====================
// 问题：手环端活性判断（_bfReady/isReady/首页 _rdy）依赖 __wb_hs_ts（最近握手时间）90s 窗口。
// 90s 内没有握手（只有普通数据/心跳）→ 活性判断 false → 请求失败"网桥未就绪"→ 首页/My 显示网络错误。
// 且 My 页 onInit 的 800ms 定时器用本地占位 bili_login_ok 判断登录态，独立于 loadProfile 的 nav 请求，
// 占位标记丢失（手机端已登录）时仍跳二维码 → "手机有登录却一直跳二维码"。修复如下。

// P-A：onMessage 收到手机端任意消息 → 刷新 __wb_hs_ts（由"最近握手"升级为"最近收到消息"），
// 心跳 ping / fetch-progress / 图片分片 / 响应 都能保活，90s 窗口内不误判"网桥未就绪/网络错误"
const ACTIVE_TS_OLD = `                            if (!msg || !msg.tag) return;
                            switch(msg.tag){`;
const ACTIVE_TS_NEW = `                            if (!msg || !msg.tag) return;
                            // log49b：收到手机端任意消息即刷新握手活性时间戳（__wb_hs_ts 由"最近握手"升级为"最近收到消息"），
                            // 心跳 ping / fetch-progress / 图片分片 / 响应 都能保活，90s 窗口内不误判"网桥未就绪/网络错误"
                            try {
                                state.lastHs = new Date().getTime();
                                if (typeof globalThis !== 'undefined') globalThis.__wb_hs_ts = state.lastHs;
                            } catch (e) {}
                            switch(msg.tag){`;

// P-B：手机端"正在传输"信号（fetch-progress）：连接正常 + 重置该请求超时（慢响应不误判超时）
// 追加在 __ping__ case 之后（FILE_ONMSG_NEW 末尾）
const FETCH_PROGRESS_CASE = `                                case 'fetch-progress':
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
                                    break;`;

// P-C：首页 checkLoginAndLoad —— 本地占位 cookie 丢失时兜底请求 nav 确认真实登录态（手机端注入真实 Cookie），
// 已登录则补写占位标记并更新提示（不重载列表，下次进页即个性化）
const HOME_LOGIN_OLD = `                            _system3.default.get({
                                key: 'bili_cookie',
                                success: function(data) {
                                    self.cookie = data || '';
                                    self.logged = self.cookie.length > 10;`;
const HOME_LOGIN_NEW = `                            _system3.default.get({
                                key: 'bili_cookie',
                                success: function(data) {
                                    self.cookie = data || '';
                                    self.logged = self.cookie.length > 10;
                                    // log49b：占位标记可能丢失（手机端已登录/手环存储被清）——兜底请求 nav 确认真实登录态
                                    // （手机端注入真实 Cookie）；已登录则补写占位标记并更新提示，不重载列表
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
                                    }`;

// P-D：App 级 onDestroy 不关闭网桥——原版 `onDestroy(){ _fetchbridge.default.destroy(); }` 在右滑退出
// （触发 App onDestroy）时 closeSession 断开互联连接 + 清空 enabled → "右滑退出网桥就关掉"。
// 互联连接由平台随 App 退出自动清理，重进 App 时 onCreate→init() 从 storage 恢复开关自动重连。
// ⚠️ 本段为网桥开关核心逻辑，以后不要再改动。
const APP_ONDESTROY_OLD = `                            onDestroy () {
                                _fetchbridge.default.destroy();
                            }`;
const APP_ONDESTROY_NEW = `                            onDestroy () {
                                // log49c：右滑退出不关闭网桥（destroy() 会 closeSession 断开互联连接 + 清空 enabled，
                                // 导致"右滑退出网桥就关掉"）。互联连接由平台随 App 退出自动清理，
                                // 重进 App 时 onCreate→init() 从 storage 恢复开关（bili_bridge_enabled='on'）自动重连。
                                // ⚠️ 网桥开关核心逻辑，以后不要再改动。
                            }`;

// ===================== log42（1.5.6 UI：功能栏常驻 + 头像改 nav + 首页 5 个/页分页）=====================
// 需求4：Home tab 功能栏常驻 —— 1.5.6 版 UI 把 nav-glass/bili-tabs 放进 shown:!showError 块，
// 未连接网桥（showError=true）时整条 tab 栏消失 → 进不了设置界面。改为始终显示。
const HOME_TAB_VIS_OLD = `                                shown: function() {
                                    return !_vm_.showError;
                                }`;
const HOME_TAB_VIS_NEW = `                                shown: function() {
                                    // log42：功能栏常驻（网桥未连接/加载失败时也显示 tab 栏，保证可进设置）
                                    return true;
                                }`;
// 需求1：头像主请求 myinfo 改 nav（myinfo 稳定返 -400，导致头像时有时无/靠 fallback 碰运气）
const AVATAR_URL_OLD = "url: 'https://app.bilibili.com/x/v2/account/myinfo'";
const AVATAR_URL_NEW = "url: 'https://api.bilibili.com/x/web-interface/nav'";
// 需求2/3：首页推荐 5 个一页（ps=5 由手机端 BiliApiMap 统一控制——不能在 RPK 侧改 URL 文本，
// 否则会破坏下方 SEMANTIC_PATCHES 对 ps=20 文本的匹配，导致首页语义化/wbi 签名失效），
// applyList 分页填充，共 4 页 20 卡；refresh 翻页 1→4
const HOME_APPLY_OLD = `                        applyList (list) {
                            var i = 0;
                            this.sectionTitle = '首页推荐';
                            this.stopPicLoader();
                            this.stopFramePreloader();
                            this.pendingPics = [];
                            for(i = 1; i <= 20; i++)this.setItem(i, list[i - 1]);
                            this.loadedCount = list.length;
                            if (list.length >= 3) this.stopSpinner();
                            this.startPicLoader();
                            this.buildPreloadFrames(list);
                            this.startFramePreloader();
                        },`;
const HOME_APPLY_NEW = `                        applyList (list) {
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
                        },`;
const HOME_REFRESH_MAX_OLD = "                            this.refreshPage = this.refreshPage + 1;\n                            if (this.refreshPage > 5) this.refreshPage = 1;";
const HOME_REFRESH_MAX_NEW = "                            this.refreshPage = this.refreshPage + 1;\n                            if (this.refreshPage > 4) this.refreshPage = 1; // log42：共 4 页（20 卡 / 5 个一页）";
// 需求3（修正）：首页推荐列表"最底部"放翻页按钮（点击才发送下一页加载请求）——原首页翻页只能靠点击顶部 section-title
const HOME_PAGEBTN_OLD = `                                                value: function() {
                                                    return _vm_.v20Title;
                                                }
                                            }
                                        }, [])
                                    ])
                                ])
                            ];
                        }),
                        aiot.__ci__({
                            __vm__: _vm_,
                            __opts__: {
                                shown: function() {
                                    return _vm_.showRecList && !_vm_.showError;
                                }
                            }
                        }, function() {
                            return [
                                aiot.__ce__("image", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "preload-img"
                                        ],`;
const HOME_PAGEBTN_NEW = `                                                value: function() {
                                                    return _vm_.v20Title;
                                                }
                                            }
                                        }, [])
                                    ])
                                ]),
                                aiot.__ce__("div", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager"
                                        ]
                                    }
                                }, [
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-btn"
                                            ],
                                            events: {
                                                click: function(evt) {
                                                    return _vm_.prevPage(evt);
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("text", {
                                            __vm__: _vm_,
                                            __opts__: {
                                                classList: [
                                                    "pager-btn-text"
                                                ],
                                                value: "上一页"
                                            }
                                        }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-page"
                                            ]
                                        }
                                    }, [
                                        aiot.__ce__("text", {
                                            __vm__: _vm_,
                                            __opts__: {
                                                classList: [
                                                    "pager-page-text"
                                                ],
                                                value: function() {
                                                    return '第 ' + _vm_.refreshPage + '/4 页';
                                                }
                                            }
                                        }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-btn"
                                            ],
                                            events: {
                                                click: function(evt) {
                                                    return _vm_.nextPage(evt);
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("text", {
                                            __vm__: _vm_,
                                            __opts__: {
                                                classList: [
                                                    "pager-btn-text"
                                                ],
                                                value: "下一页"
                                            }
                                        }, [])
                                    ])
                                ]),
                                aiot.__ce__("div", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn"
                                        ],
                                        events: {
                                            click: function(evt) {
                                                return _vm_.refreshFeed(evt);
                                            }
                                        }
                                    }
                                }, [
                                    aiot.__ce__("text", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-btn-text"
                                            ],
                                            value: "刷新"
                                        }
                                    }, [])
                                ])
                            ];
                        }),
                        aiot.__ci__({
                            __vm__: _vm_,
                            __opts__: {
                                shown: function() {
                                    return _vm_.showRecList && !_vm_.showError;
                                }
                            }
                        }, function() {
                            return [
                                aiot.__ce__("image", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "preload-img"
                                        ],`;
// 需求3（修正2）：首页推荐上一页/下一页方法（点击才发送对应页加载请求，1-4 循环）
const HOME_PREVNEXT_OLD = `                        refresh () {`;
const HOME_PREVNEXT_NEW = `                        prevPage () {
                            // log42：首页推荐上一页（1-4 循环），点击发送上一页加载请求
                            this.refreshPage = (this.refreshPage || 1) <= 1 ? 4 : this.refreshPage - 1;
                            this.loadPopular();
                        },
                        nextPage () {
                            // log42：首页推荐下一页（1-4 循环），点击发送下一页加载请求
                            this.refreshPage = (this.refreshPage || 1) >= 4 ? 1 : this.refreshPage + 1;
                            this.loadPopular();
                        },
                        refreshFeed () {
                            // log45：首页最底部"刷新"按钮——批号整体后移 4（fresh_idx 5-8 等），获取与之前 20 条不同的新内容
                            this.freshBase = (this.freshBase || 0) + 4;
                            this.refreshPage = 1;
                            this.checkLoginAndLoad();
                        },
                        refresh () {`;

// ===================== log43（小米手环 10 Pro 兼容修复）：首页图片加载不出来 =====================
// 10 Pro 特性1：百分比不准 → 页面背景 backgroundSize "120%" 改固定像素
// 10 Pro 特性2：class/style 内嵌 JS 判断的样式不要超过 3 个 → 首页原有 7 处 classList: function()
// （tab 高亮 6 处 + section-more 1 处）精简为 3 处：tab-bar×3 改 __ci__ shown 条件渲染 + 静态 class，
// section-more 改静态 class，仅保留 tab-text×3 的选中高亮（active-text）
const BG_SIZE_OLD = '                            backgroundSize: "120%",';
const BG_SIZE_NEW = '                            backgroundSize: "340px",';
const TAB_BAR_LIVE_OLD = `                                            aiot.__ce__("div", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: function() {
                                                        const $classValue$ = "bili-tab-bar " + _vm_.tabLiveBarClass;
                                                        if ('string' == typeof $classValue$) return $classValue$.split(' ').map((item)=>item.trim()).filter(Boolean);
                                                        return $classValue$;
                                                    }
                                                }
                                            }, [])`;
const TAB_BAR_LIVE_NEW = `                                            aiot.__ci__({
                                                __vm__: _vm_,
                                                __opts__: {
                                                    shown: function() {
                                                        return 'hide-bar' !== _vm_.tabLiveBarClass;
                                                    }
                                                }
                                            }, function() {
                                                return [
                                                    aiot.__ce__("div", {
                                                        __vm__: _vm_,
                                                        __opts__: {
                                                            classList: [
                                                                "bili-tab-bar"
                                                            ]
                                                        }
                                                    }, [])
                                                ];
                                            })`;
const TAB_BAR_REC_OLD = `                                            aiot.__ce__("div", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: function() {
                                                        const $classValue$ = "bili-tab-bar " + _vm_.tabRecBarClass;
                                                        if ('string' == typeof $classValue$) return $classValue$.split(' ').map((item)=>item.trim()).filter(Boolean);
                                                        return $classValue$;
                                                    }
                                                }
                                            }, [])`;
const TAB_BAR_REC_NEW = `                                            aiot.__ci__({
                                                __vm__: _vm_,
                                                __opts__: {
                                                    shown: function() {
                                                        return 'hide-bar' !== _vm_.tabRecBarClass;
                                                    }
                                                }
                                            }, function() {
                                                return [
                                                    aiot.__ce__("div", {
                                                        __vm__: _vm_,
                                                        __opts__: {
                                                            classList: [
                                                                "bili-tab-bar"
                                                            ]
                                                        }
                                                    }, [])
                                                ];
                                            })`;
const TAB_BAR_HOT_OLD = `                                            aiot.__ce__("div", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: function() {
                                                        const $classValue$ = "bili-tab-bar " + _vm_.tabHotBarClass;
                                                        if ('string' == typeof $classValue$) return $classValue$.split(' ').map((item)=>item.trim()).filter(Boolean);
                                                        return $classValue$;
                                                    }
                                                }
                                            }, [])`;
const TAB_BAR_HOT_NEW = `                                            aiot.__ci__({
                                                __vm__: _vm_,
                                                __opts__: {
                                                    shown: function() {
                                                        return 'hide-bar' !== _vm_.tabHotBarClass;
                                                    }
                                                }
                                            }, function() {
                                                return [
                                                    aiot.__ce__("div", {
                                                        __vm__: _vm_,
                                                        __opts__: {
                                                            classList: [
                                                                "bili-tab-bar"
                                                            ]
                                                        }
                                                    }, [])
                                                ];
                                            })`;
const SECTION_MORE_OLD = `                                                classList: function() {
                                                    const $classValue$ = "section-more " + (_vm_.liveLoadError ? "live-refresh-hint" : "");
                                                    if ('string' == typeof $classValue$) return $classValue$.split(' ').map((item)=>item.trim()).filter(Boolean);
                                                    return $classValue$;
                                                },`;
const SECTION_MORE_NEW = `                                                classList: [
                                                    "section-more"
                                                ],`;

// Home 翻页样式（[上一页][第X页][下一页] 三件套）
const HOME_STYLE_OLD = `                    [
                        [
                            [
                                0,
                                "hot-list"
                            ]
                        ],
                        {
                            minHeight: "480px"
                        }
                    ]
                ];`;
const HOME_STYLE_NEW = `                    [
                        [
                            [
                                0,
                                "hot-list"
                            ]
                        ],
                        {
                            minHeight: "480px"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager"
                            ]
                        ],
                        {
                            width: "318px",
                            height: "34px",
                            marginTop: "10px",
                            flexDirection: "row",
                            alignItems: "center",
                            justifyContent: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn"
                            ]
                        ],
                        {
                            width: "88px",
                            height: "34px",
                            borderRadius: "17px",
                            backgroundColor: "#fb7299",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page"
                            ]
                        ],
                        {
                            width: "96px",
                            height: "34px",
                            marginLeft: "8px",
                            marginRight: "8px",
                            borderRadius: "8px",
                            backgroundColor: "rgba(255,255,255,0.15)",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold",
                            textAlign: "center"
                        }
                    ]
                ];`;

// ===================== log42 需求3：动态/历史/私信分页 =====================
// 动态：服务端 page 参数分页（1→2→3 循环），复用原"刷新动态"按钮为翻页按钮
const DYN_DATA_OLD = `                            d6HasPic: false
                        },`;
const DYN_DATA_NEW = `                            d6HasPic: false,
                            dynPage: 1 // log42：动态分页页码（1→2→3 循环）
                        },`;
const DYN_NEXT_OLD = `                        loadDynamic () {`;
const DYN_NEXT_NEW = `                        prevDynPage () {
                            // log42：动态上一页（1-3 循环），点击发送上一页加载请求
                            this.dynPage = (this.dynPage || 1) <= 1 ? 3 : this.dynPage - 1;
                            this.loadDynamic();
                        },
                        nextDynPage () {
                            // log42：动态下一页（1-3 循环），点击发送下一页加载请求
                            this.dynPage = (this.dynPage || 1) >= 3 ? 1 : this.dynPage + 1;
                            this.loadDynamic();
                        },
                        loadDynamic () {`;
const DYN_APPLY_STATUS_OLD = `this.status = '已加载 ' + (list.length > 6 ? 6 : list.length) + ' 条动态';`;
const DYN_APPLY_STATUS_NEW = `this.status = '第 ' + (this.dynPage || 1) + '/3 页 · 已加载 ' + (list.length > 6 ? 6 : list.length) + ' 条动态';`;
const DYN_BTN_OLD = `                        aiot.__ce__("div", {
                            __vm__: _vm_,
                            __opts__: {
                                classList: [
                                    "refresh"
                                ],
                                events: {
                                    click: function(evt) {
                                        return _vm_.loadDynamic(evt);
                                    }
                                }
                            }
                        }, [
                            aiot.__ce__("text", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "refresh-text"
                                    ],
                                    value: "刷新动态"
                                }
                            }, [])
                        ])`;
const DYN_BTN_NEW = `                        aiot.__ce__("div", {
                            __vm__: _vm_,
                            __opts__: {
                                classList: [
                                    "pager"
                                ]
                            }
                        }, [
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager-btn"
                                    ],
                                    events: {
                                        click: function(evt) {
                                            return _vm_.prevDynPage(evt);
                                        }
                                    }
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn-text"
                                        ],
                                        value: "上一页"
                                    }
                                }, [])
                            ]),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager-page"
                                    ]
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-page-text"
                                        ],
                                        value: function() {
                                            return '第 ' + _vm_.dynPage + '/3 页';
                                        }
                                    }
                                }, [])
                            ]),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager-btn"
                                    ],
                                    events: {
                                        click: function(evt) {
                                            return _vm_.nextDynPage(evt);
                                        }
                                    }
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn-text"
                                        ],
                                        value: "下一页"
                                    }
                                }, [])
                            ])
                        ])`;
// 动态 pager 样式（追加到 refresh/refresh-text 之后）
const DYN_STYLE_OLD = `                    [
                        [
                            [
                                0,
                                "refresh-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "13px",
                            fontWeight: "bold"
                        }
                    ]
                ];`;
const DYN_STYLE_NEW = `                    [
                        [
                            [
                                0,
                                "refresh-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "13px",
                            fontWeight: "bold"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager"
                            ]
                        ],
                        {
                            width: "318px",
                            height: "34px",
                            marginTop: "10px",
                            flexDirection: "row",
                            alignItems: "center",
                            justifyContent: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn"
                            ]
                        ],
                        {
                            width: "88px",
                            height: "34px",
                            borderRadius: "17px",
                            backgroundColor: "#fb7299",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page"
                            ]
                        ],
                        {
                            width: "96px",
                            height: "34px",
                            marginLeft: "8px",
                            marginRight: "8px",
                            borderRadius: "8px",
                            backgroundColor: "rgba(255,255,255,0.15)",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold",
                            textAlign: "center"
                        }
                    ]
                ];`;

// 历史：history_cursor 的 max 游标分页（已到最后自动回到第一页），FavHistory 无刷新按钮 → 新增翻页按钮 + 样式
const HIS_DATA_OLD = `                            i6Bvid: ''
                        },`;
const HIS_DATA_NEW = `                            i6Bvid: '',
                            hisMax: 0, // log42：历史游标（history_cursor 的 max）
                            hisPage: 1, // log42：历史当前页码
                            historyCache: [], // log42：已加载页缓存（上一页回退直接显示，不发请求）
                            hisMore: true // log42：是否还有下一页
                        },`;
const HIS_NEXT_OLD = `                        loadHistory () {`;
const HIS_NEXT_NEW = `                        prevHistoryPage () {
                            // log42：历史上一页（回退到已缓存页，不发请求）
                            if ((this.hisPage || 1) <= 1) return;
                            this.hisPage = this.hisPage - 1;
                            if (this.historyCache && this.historyCache[this.hisPage]) {
                                this.applyList(this.historyCache[this.hisPage], 'history');
                            } else {
                                this.hisPage = this.hisPage + 1;
                            }
                        },
                        nextHistoryPage () {
                            // log42：历史下一页（游标翻页；已到最后则回到第一页）
                            this.hisPage = (this.hisPage || 1) + 1;
                            if (this.historyCache && this.historyCache[this.hisPage]) {
                                this.applyList(this.historyCache[this.hisPage], 'history');
                            } else if (!this.hisMore) {
                                // 已到最后：回到第一页重新开始
                                this.hisPage = 1;
                                this.hisMax = 0;
                                this.hisMore = true;
                                this.loadHistory();
                            } else {
                                // 未缓存：用当前 hisMax 游标请求下一页
                                this.loadHistory();
                            }
                        },
                        loadHistory () {`;
const HIS_SUCCESS_OLD = `                                success: function(res) {
                                    var data = res.data;
                                    if ('string' == typeof data) data = JSON.parse(data);
                                    var list = data && data.data && data.data.list ? data.data.list : [];
                                    self.applyList(list, 'history');
                                },`;
const HIS_SUCCESS_NEW = `                                success: function(res) {
                                    var data = res.data;
                                    if ('string' == typeof data) data = JSON.parse(data);
                                    var list = data && data.data && data.data.list ? data.data.list : [];
                                    // log42：记录下一页游标（history_cursor 的 max），供翻页使用
                                    if (data && data.data && data.data.cursor && data.data.cursor.max) self.hisMax = data.data.cursor.max;
                                    self.hisMore = list.length > 0;
                                    // log42：缓存当前页（上一页回退直接显示，不发请求）
                                    self.historyCache = self.historyCache || [];
                                    self.historyCache[self.hisPage || 1] = list;
                                    self.applyList(list, 'history');
                                },`;
const HIS_APPLY_STATUS_OLD = `this.status = 'fav' === type ? '收藏加载完成' : '历史加载完成';`;
const HIS_APPLY_STATUS_NEW = `this.status = 'fav' === type ? '收藏加载完成' : ('第 ' + (this.hisPage || 1) + ' 页 · 历史' + (this.hisMore ? '' : ' · 已到最后'));`;
const HIS_BTN_OLD = `                                ];
                            })
                        ])
                    ]);`;
const HIS_BTN_NEW = `                                ];
                            }),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "pager"
                                    ]
                                }
                            }, [
                                aiot.__ce__("div", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn"
                                        ],
                                        events: {
                                            click: function(evt) {
                                                return _vm_.prevHistoryPage(evt);
                                            }
                                        }
                                    }
                                }, [
                                    aiot.__ce__("text", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-btn-text"
                                            ],
                                            value: "上一页"
                                        }
                                    }, [])
                                ]),
                                aiot.__ce__("div", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-page"
                                        ]
                                    }
                                }, [
                                    aiot.__ce__("text", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-page-text"
                                            ],
                                            value: function() {
                                                return '第 ' + _vm_.hisPage + ' 页';
                                            }
                                        }
                                    }, [])
                                ]),
                                aiot.__ce__("div", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "pager-btn"
                                        ],
                                        events: {
                                            click: function(evt) {
                                                return _vm_.nextHistoryPage(evt);
                                            }
                                        }
                                    }
                                }, [
                                    aiot.__ce__("text", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: [
                                                "pager-btn-text"
                                            ],
                                            value: "下一页"
                                        }
                                    }, [])
                                ])
                            ])
                        ])
                    ]);`;
const HIS_STYLE_OLD = `                        {
                            color: "rgba(255, 255, 255, 0.55)",
                            fontSize: "10px",
                            marginTop: "3px",
                            lines: 1,
                            textOverflow: "ellipsis"
                        }
                    ]
                ];`;
const HIS_STYLE_NEW = `                        {
                            color: "rgba(255, 255, 255, 0.55)",
                            fontSize: "10px",
                            marginTop: "3px",
                            lines: 1,
                            textOverflow: "ellipsis"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager"
                            ]
                        ],
                        {
                            width: "318px",
                            height: "34px",
                            marginTop: "10px",
                            flexDirection: "row",
                            alignItems: "center",
                            justifyContent: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn"
                            ]
                        ],
                        {
                            width: "88px",
                            height: "34px",
                            borderRadius: "17px",
                            backgroundColor: "#fb7299",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-btn-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page"
                            ]
                        ],
                        {
                            width: "96px",
                            height: "34px",
                            marginLeft: "8px",
                            marginRight: "8px",
                            borderRadius: "8px",
                            backgroundColor: "rgba(255,255,255,0.15)",
                            justifyContent: "center",
                            alignItems: "center"
                        }
                    ],
                    [
                        [
                            [
                                0,
                                "pager-page-text"
                            ]
                        ],
                        {
                            color: "#ffffff",
                            fontSize: "12px",
                            fontWeight: "bold",
                            textAlign: "center"
                        }
                    ]
                ];`;

// 私信：客户端分页（会话列表一次拉取（已裁剪），每页显示 6 个，点击翻页循环；openItem 用当前页切片）
const MSG_DATA_OLD = `                                m6Face: '/common/images/icon.png'
                            },`;
const MSG_DATA_NEW = `                                m6Face: '/common/images/icon.png',
                                msgPage: 1 // log42：私信会话分页页码
                            },`;
const MSG_NEXT_OLD = `                            loadMessages () {`;
const MSG_NEXT_NEW = `                            prevMsgPage () {
                                // log42：私信会话上一页（客户端分页循环）
                                var _total = Math.max(1, Math.ceil((this.sessions || []).length / 6));
                                this.msgPage = (this.msgPage || 1) <= 1 ? _total : this.msgPage - 1;
                                this.applyList(this.sessions || []);
                            },
                            nextMsgPage () {
                                // log42：私信会话下一页（客户端分页循环）
                                var _total = Math.max(1, Math.ceil((this.sessions || []).length / 6));
                                this.msgPage = (this.msgPage || 1) >= _total ? 1 : this.msgPage + 1;
                                this.applyList(this.sessions || []);
                            },
                            loadMessages () {`;
const MSG_OPEN_OLD = `                            openItem (n) {
                                var item = this.sessions[n - 1];`;
const MSG_OPEN_NEW = `                            openItem (n) {
                                var item = (this.pageSessions || this.sessions)[n - 1];`;
const MSG_APPLY_OLD = `                            applyList (list) {
                                var i = 0;
                                this.sessions = list;
                                this.listShow = true;
                                this.replyMode = false;
                                for(i = 1; i <= 6; i++){
                                    this['m' + i + 'Show'] = false;
                                    this['m' + i + 'Face'] = '/common/images/icon.png';
                                }
                                for(i = 0; i < list.length && i < 6; i++){
                                    var item = list[i];
                                    var n = i + 1;
                                    this['m' + n + 'Show'] = true;
                                    this['m' + n + 'Name'] = this.getName(item);
                                    this['m' + n + 'Text'] = this.parseText(item.last_msg);
                                    this['m' + n + 'Face'] = this.getFace(item);
                                    var talkerMid = this.getTalkerId(item);
                                    var face = this.getFace(item);
                                    var name = this.getName(item);
                                    if (!face || '/common/images/icon.png' === face || !name || '私信用户' === name) this.fetchV2UserInfo(talkerMid, n);
                                }
                                this.status = '已加载 ' + (list.length > 6 ? 6 : list.length) + ' 个会话';
                            },`;
const MSG_APPLY_NEW = `                            applyList (list) {
                                var i = 0;
                                this.sessions = list;
                                this.listShow = true;
                                this.replyMode = false;
                                // log42：客户端分页（每页 6 个，点击翻页循环）
                                var _page = this.msgPage || 1;
                                var _start = (_page - 1) * 6;
                                var _slice = [];
                                for(i = _start; i < list.length && i < _start + 6; i++) _slice.push(list[i]);
                                this.pageSessions = _slice;
                                for(i = 1; i <= 6; i++){
                                    this['m' + i + 'Show'] = false;
                                    this['m' + i + 'Face'] = '/common/images/icon.png';
                                }
                                for(i = 0; i < _slice.length && i < 6; i++){
                                    var item = _slice[i];
                                    var n = i + 1;
                                    this['m' + n + 'Show'] = true;
                                    this['m' + n + 'Name'] = this.getName(item);
                                    this['m' + n + 'Text'] = this.parseText(item.last_msg);
                                    this['m' + n + 'Face'] = this.getFace(item);
                                    var talkerMid = this.getTalkerId(item);
                                    var face = this.getFace(item);
                                    var name = this.getName(item);
                                    if (!face || '/common/images/icon.png' === face || !name || '私信用户' === name) this.fetchV2UserInfo(talkerMid, n);
                                }
                                var _total = Math.max(1, Math.ceil(list.length / 6));
                                this.status = '第 ' + _page + '/' + _total + ' 页 · ' + list.length + ' 个会话';
                            },`;
const MSG_BTN_OLD = `                                        aiot.__ce__("div", {
                                            __vm__: _vm_,
                                            __opts__: {
                                                classList: [
                                                    "refresh"
                                                ],
                                                events: {
                                                    click: function(evt) {
                                                        return _vm_.loadMessages(evt);
                                                    }
                                                }
                                            }
                                        }, [
                                            aiot.__ce__("text", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: [
                                                        "refresh-text"
                                                    ],
                                                    value: "刷新私信"
                                                }
                                            }, [])
                                        ])`;
const MSG_BTN_NEW = `                                        aiot.__ce__("div", {
                                            __vm__: _vm_,
                                            __opts__: {
                                                classList: [
                                                    "pager"
                                                ]
                                            }
                                        }, [
                                            aiot.__ce__("div", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: [
                                                        "pager-btn"
                                                    ],
                                                    events: {
                                                        click: function(evt) {
                                                            return _vm_.prevMsgPage(evt);
                                                        }
                                                    }
                                                }
                                            }, [
                                                aiot.__ce__("text", {
                                                    __vm__: _vm_,
                                                    __opts__: {
                                                        classList: [
                                                            "pager-btn-text"
                                                        ],
                                                        value: "上一页"
                                                    }
                                                }, [])
                                            ]),
                                            aiot.__ce__("div", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: [
                                                        "pager-page"
                                                    ]
                                                }
                                            }, [
                                                aiot.__ce__("text", {
                                                    __vm__: _vm_,
                                                    __opts__: {
                                                        classList: [
                                                            "pager-page-text"
                                                        ],
                                                        value: function() {
                                                            return '第 ' + _vm_.msgPage + ' 页';
                                                        }
                                                    }
                                                }, [])
                                            ]),
                                            aiot.__ce__("div", {
                                                __vm__: _vm_,
                                                __opts__: {
                                                    classList: [
                                                        "pager-btn"
                                                    ],
                                                    events: {
                                                        click: function(evt) {
                                                            return _vm_.nextMsgPage(evt);
                                                        }
                                                    }
                                                }
                                            }, [
                                                aiot.__ce__("text", {
                                                    __vm__: _vm_,
                                                    __opts__: {
                                                        classList: [
                                                            "pager-btn-text"
                                                        ],
                                                        value: "下一页"
                                                    }
                                                }, [])
                                            ])
                                        ])`;
// 私信 pager 样式（追加到样式表末尾 waiting-keys 之后）
const MSG_STYLE_OLD = `                            [
                                [
                                    [
                                        0,
                                        "waiting-keys"
                                    ]
                                ],
                                {
                                    width: "36px",
                                    height: "40px",
                                    textAlign: "center"
                                }
                            ]
                        ];`;
const MSG_STYLE_NEW = `                            [
                                [
                                    [
                                        0,
                                        "waiting-keys"
                                    ]
                                ],
                                {
                                    width: "36px",
                                    height: "40px",
                                    textAlign: "center"
                                }
                            ],
                            [
                                [
                                    [
                                        0,
                                        "pager"
                                    ]
                                ],
                                {
                                    width: "318px",
                                    height: "34px",
                                    marginTop: "10px",
                                    flexDirection: "row",
                                    alignItems: "center",
                                    justifyContent: "center"
                                }
                            ],
                            [
                                [
                                    [
                                        0,
                                        "pager-btn"
                                    ]
                                ],
                                {
                                    width: "88px",
                                    height: "34px",
                                    borderRadius: "17px",
                                    backgroundColor: "#fb7299",
                                    justifyContent: "center",
                                    alignItems: "center"
                                }
                            ],
                            [
                                [
                                    [
                                        0,
                                        "pager-btn-text"
                                    ]
                                ],
                                {
                                    color: "#ffffff",
                                    fontSize: "12px",
                                    fontWeight: "bold"
                                }
                            ],
                            [
                                [
                                    [
                                        0,
                                        "pager-page"
                                    ]
                                ],
                                {
                                    width: "96px",
                                    height: "34px",
                                    marginLeft: "8px",
                                    marginRight: "8px",
                                    borderRadius: "8px",
                                    backgroundColor: "rgba(255,255,255,0.15)",
                                    justifyContent: "center",
                                    alignItems: "center"
                                }
                            ],
                            [
                                [
                                    [
                                        0,
                                        "pager-page-text"
                                    ]
                                ],
                                {
                                    color: "#ffffff",
                                    fontSize: "12px",
                                    fontWeight: "bold",
                                    textAlign: "center"
                                }
                            ]
                        ];`;

// 补丁5o（官方 SHOWQR 适配）：fetchbridge 导出 sendShowQr —— 手环发起 TV 登录后，
// 把 qrUrl 通过官方 SHOWQR 消息（{msgtype:'SHOWQR', message: qrUrl}）发给手机，
// 手机 WebView 显示二维码，手机 B 站 App 扫码（官方原版方向，手环端不渲染图片）。
// 补丁5ad（log38）：互联诊断——收到手机端 __diag_req__ 指令后调用官方 connect.diagnosis()
// 并把结果上报 __diag__（手机端"环境自检"面板显示诊断码：0=OK / 204=超时 / 1001=对端未安装 / 1000=其他）
const DIAG_REQ_OLD = `                                case '__dev_fetch__':
                                    // 开发者模式推送测试：支持语义化（action+params，手机端整合 URL + wbi 签名/多候选兜底）与 url 直传两种方式`;
const DIAG_REQ_NEW = `                                case '__diag_req__':
                                    // 互联诊断（log38）：调用官方 connect.diagnosis() 并上报结果给手机端
                                    try {
                                        if (!state.session) {
                                            addLog('收到互联诊断请求，但会话未建立');
                                            break;
                                        }
                                        addLog('收到互联诊断请求，开始诊断...');
                                        state.session.diagnosis({
                                            timeout: 10000,
                                            success: function(d) {
                                                var _st = d ? d.status : -1;
                                                addLog('互联诊断结果: status=' + _st);
                                                try {
                                                    state.session.send({
                                                        data: { tag: '__diag__', status: _st, ts: new Date().getTime() },
                                                        success: function() {},
                                                        fail: function() {}
                                                    });
                                                } catch (e) {}
                                            },
                                            fail: function(d, code) {
                                                addLog('互联诊断失败 code=' + code + ' msg=' + (d ? String(d.data || '').substring(0, 60) : ''));
                                                try {
                                                    state.session.send({
                                                        data: { tag: '__diag__', status: -1, code: code, msg: d ? d.data : '', ts: new Date().getTime() },
                                                        success: function() {},
                                                        fail: function() {}
                                                    });
                                                } catch (e) {}
                                            }
                                        });
                                    } catch (e) {}
                                    break;
                                case '__dev_fetch__':
                                    // 开发者模式推送测试：支持语义化（action+params，手机端整合 URL + wbi 签名/多候选兜底）与 url 直传两种方式`;

const SHOWQR_SEND_OLD = 'getStatus: function() {';
const SHOWQR_SEND_NEW = `sendShowQr: function(qrUrl) {
                            try {
                                if (!state.session) return;
                                var _pkt = {
                                    id: 'showqr-' + new Date().getTime(),
                                    message: JSON.stringify({ msgtype: 'SHOWQR', message: qrUrl || '' })
                                };
                                state.session.send({
                                    data: _pkt,
                                    success: function() {},
                                    fail: function() {}
                                });
                            } catch (e) {}
                        },
                        sendLogout: function() {
                            // 手环退出登录（log34）→ 通知手机端同步清除登录信息（真实 Cookie 只存手机端，
                            // 手环只清占位标记是不够的：手机端仍会注入旧 Cookie，nav 仍返回已登录，状态错乱/换账号失效）
                            try {
                                if (state.session) {
                                    state.session.send({
                                        data: { tag: 'logout', id: 'logout-' + new Date().getTime() },
                                        success: function() {},
                                        fail: function() {}
                                    });
                                }
                            } catch (e) {}
                        },
                        requestShowQr: function() {
                            // 手环进入登录界面 → 向手机请求二维码（手机向 passport 申请后回 showqr_resp，并自行轮询登录状态）
                            try {
                                if (!state.session) {
                                    // 会话缺失：尝试立即建立（网桥已开启但会话未就绪的情况），避免静默失败
                                    if (state.enabled) createSession();
                                    if (!state.session) return;
                                }
                                state.session.send({
                                    data: { tag: 'showqr_req', id: 'showqrreq-' + new Date().getTime() },
                                    success: function() {},
                                    fail: function() {}
                                });
                            } catch (e) {}
                        },
                        onShowQrResp: function(cb) {
                            state.showQrRespCallback = cb;
                        },
                        getStatus: function() {`;

// 补丁5p（官方 qrcode 组件登录适配）：My 页面 createQr 改为
// "手环请求二维码 → 手机向 passport 请求 → 回 showqr_resp → 手环用官方 qrcode 组件显示 → 手机扫码 → 手环 startPoll"
// 新 createQr：只发 showqr_req 并注册 showqr_resp 回调，不再自己 fetch passport（登录网络请求全部由手机完成）
const MY_CREATEQR_NEW = `createQr () {
                            var self = this;
                            this.loginStatus = '正在请求二维码...';
                            try {
                                // 全局网桥实例由 app.js 暴露在 globalThis.__wb_bridge（__webpack_require__.g 仅在 app.js 定义，其他 bundle 取不到，必须用 globalThis）
                                var _wb = null;
                                try {
                                    if (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) _wb = globalThis.__wb_bridge;
                                    else if (typeof __webpack_require__ !== 'undefined' && __webpack_require__.g && __webpack_require__.g.__wb_bridge) _wb = __webpack_require__.g.__wb_bridge;
                                } catch (e) {}
                                if (_wb && _wb.requestShowQr) {
                                    var _st = _wb.getStatus ? _wb.getStatus() : '';
                                    if ('disabled' === _st) {
                                        self.loginStatus = '请先开启网桥（Bridge 页面）';
                                        return;
                                    }
                                    if ('ready' !== _st) {
                                        // 网桥已开启但尚未就绪（连接中/握手协商中）：等待就绪（最长30秒，每秒检查）后自动请求，
                                        // 避免一直卡"网桥连接中"导致登录请求永远发不出去
                                        self.loginStatus = '网桥连接中，请稍候...';
                                        var _waited = 0;
                                        var _qrW = setInterval(function() {
                                            _waited++;
                                            var _st2 = _wb.getStatus ? _wb.getStatus() : '';
                                            if ('ready' === _st2) {
                                                clearInterval(_qrW);
                                                self.createQr();
                                            } else if (_waited >= 30) {
                                                clearInterval(_qrW);
                                                self.loginStatus = '网桥未就绪，请先开启网桥（Bridge 页面）';
                                            }
                                        }, 1000);
                                        return;
                                    }
                                    self._qrGot = false;
                                    // showqr_resp：手机返回二维码 → 手环用官方 qrcode 组件显示（手机端负责轮询登录状态）
                                    try { if (typeof globalThis !== 'undefined') globalThis.__wb_showqr_cb = function(data) {
                                        if (!data) return;
                                        self._qrGot = true;
                                        if (0 === data.code && data.qrUrl) {
                                            self.qrUrl = data.qrUrl;
                                            self.authCode = data.auth_code || '';
                                            self.loginStatus = '请用手机 B站 扫二维码';
                                        } else {
                                            self.loginStatus = '二维码获取失败: ' + (data.code || '?') + (data.message ? ' ' + data.message : '');
                                            setTimeout(function() { self.createQr(); }, 3000);
                                        }
                                    }; } catch (e) {}
                                    // showqr_status：手机轮询进度（等待扫码/已扫码/已过期）
                                    try { if (typeof globalThis !== 'undefined') globalThis.__wb_qr_status_cb = function(m) {
                                        if (m && m.text) self.loginStatus = m.text;
                                    }; } catch (e) {}
                                    // showqr_done：手机扫码确认（登录凭证留在手机端，手环请求由手机统一附加登录态）
                                    try { if (typeof globalThis !== 'undefined') globalThis.__wb_qr_done_cb = function(m) {
                                        if (m && 0 === m.code) {
                                            self.cookie = ''; // 手环不持有 Cookie
                                            self.loginStatus = '登录成功';
                                            try {
                                                self.showLogin = false;
                                                self.showProfile = true;
                                                self.fetchNav();
                                            } catch (e) {}
                                        } else {
                                            self.loginStatus = '登录失败: ' + (m && m.message ? m.message : '未知');
                                        }
                                    }; } catch (e) {}
                                    _wb.requestShowQr();
                                    // 超时兜底：30秒内未收到手机二维码则自动重新请求
                                    if (self._qrTimer) clearTimeout(self._qrTimer);
                                    self._qrTimer = setTimeout(function() {
                                        if (typeof globalThis !== 'undefined' && globalThis.__wb_showqr_cb && !self._qrGot) {
                                            self.createQr();
                                        }
                                    }, 30000);
                                    return;
                                }
                            } catch (e) {}
                            this.loginStatus = '网桥未就绪，请先开启网桥';
                        }`;

// 补丁5r：My 页面 onInit 自动请求二维码（进入登录界面即向手机发 showqr_req，无需点按钮；已登录则跳过）
const MY_ONINIT_OLD = `onInit () {
                            var self = this;
                            _system3.default.get({
                                key: 'bili_avatar_cached',
                                success: function(pic) {
                                    if (pic && pic.length > 10) self.avatar = pic;
                                },
                                fail: function() {}
                            });
                            this.loadProfile();
                        }`;
const MY_ONINIT_NEW = `onInit () {
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
                        }`;

// 补丁5s：My 页面 fetchNav 不再先请求 app 端 myinfo（桌面 UA 必返 -400），直接走 Web nav（与 fetchNavFallback 相同逻辑）
const MY_FETCHNAV_NEW = `fetchNav () {
                            var self = this;
                            this.status = '正在请求个人数据...';
                            self.fetchNavFallback();
                        }`;

// 补丁5t：My 页 loadProfile 不再读 bili_cookie（登录凭证只存手机端），改用 bili_login_ok 判断登录态
const MY_LOADPROFILE_OLD = `loadProfile () {
                            var self = this;
                            _system3.default.get({
                                key: 'bili_cookie',
                                success: function(data) {
                                    if (data && data.length > 10) {
                                        self.cookie = data;
                                        self.mode = '个性化';
                                        self.showLogin = false;
                                        self.showProfile = true;
                                        self.mainBtnText = '刷新个人数据';
                                        self.fetchNav();
                                    } else {
                                        self.status = '未登录';
                                        self.mode = '热门';
                                        self.showLogin = true;
                                        self.showProfile = false;
                                        self.mainBtnText = '生成登录二维码';
                                        self.createQr();
                                    }
                                },
                                fail: function() {
                                    self.status = '未登录';
                                    self.mode = '热门';
                                    self.showLogin = true;
                                    self.showProfile = false;
                                    self.mainBtnText = '生成登录二维码';
                                    self.createQr();
                                }
                            });
                        }`;
const MY_LOADPROFILE_NEW = `loadProfile () {
                            var self = this;
                            self.cookie = ''; // 手环不持有 Cookie，登录态由手机端转发时统一附加
                            // 与登录成功后的自动加载完全同一套逻辑：直接请求 nav（登录状态以手机端为准，不再本地判断）
                            self.mode = '个性化';
                            self.showLogin = false;
                            self.showProfile = true;
                            self.mainBtnText = '刷新个人数据';
                            self.fetchNav();
                        }`;

// 补丁5u：My 页 useSavedCookie 同样改用 bili_login_ok（不再读取/持有 Cookie）
const MY_USESAVED_OLD = `useSavedCookie () {
                            var self = this;
                            _system3.default.get({
                                key: 'bili_cookie',
                                success: function(data) {
                                    if (data && data.length > 10) {
                                        if (self.timer) {
                                            clearInterval(self.timer);
                                            self.timer = null;
                                        }
                                        self.cookie = data;
                                        self.showLogin = false;
                                        self.showProfile = true;
                                        self.mainBtnText = '刷新个人数据';
                                        self.fetchNav();
                                    } else {
                                        self.loginStatus = '还没有导入 Cookie';
                                        self.tip = '请使用B站客户端扫描二维码登录。';
                                    }
                                },
                                fail: function() {
                                    self.loginStatus = '还没有导入 Cookie';
                                }
                            });
                        }`;
const MY_USESAVED_NEW = `useSavedCookie () {
                            var self = this;
                            if (self.timer) {
                                clearInterval(self.timer);
                                self.timer = null;
                            }
                            self.cookie = ''; // 手环不持有 Cookie，登录态由手机端转发时统一附加
                            self.showLogin = false;
                            self.showProfile = true;
                            self.mainBtnText = '刷新个人数据';
                            self.fetchNav();
                        }`;

// ===================== 语义化请求调用点改造（核心页面 url → action + params）=====================
// 架构：请求 URL 整合到手机端 BiliApiMap，手环页面只发 {action, params} 指令。
// 未收录的接口（第三方 AI/漫画、多接口重试的写操作等）保留 url 直传兜底。
// 注意 OLD 按 bundle 限定应用（不同 bundle 里 URL 文本可能相同）。

// Home 首页推荐/热门（动态 URL → 语义化）
const HOME_POPULAR_OLD = `var url = this.logged ? 'https://api.bilibili.com/x/web-interface/index/top/feed/rcmd?ps=20&fresh_type=4&fresh_idx=' + this.refreshPage : 'https://api.bilibili.com/x/web-interface/popular?ps=20&pn=' + this.refreshPage;
                            __wbFetch({
                                url: url,
                                header: this.logged ? {
                                    Cookie: this.cookie,
                                    'User-Agent': 'Mozilla/5.0'
                                } : {
                                    'User-Agent': 'Mozilla/5.0'
                                },
                                responseType: 'json',`;
const HOME_POPULAR_NEW = `__wbFetch({
                                action: this.logged ? 'home_rcmd' : 'home_popular',
                                params: { page: (this.freshBase || 0) + this.refreshPage }, // log45：刷新按钮批号整体后移（fresh_idx 5-8 等全新内容，与之前 20 条不同）
                                responseType: 'json',`;

// Home 直播列表：多 URL 重试搬到手机端（BiliApiMap home_live_list 依次尝试）
const HOME_LIVE_OLD = `var url = urls[urlIndex];
                            __wbFetch({
                                url: url,`;
const HOME_LIVE_NEW = `__wbFetch({
                                action: 'home_live_list',`;

// 搜索：主/备接口（index 0=type 接口，1=all/v2），URL 由手机端映射
const SEARCH_OLD = "url: this.buildSearchUrl(kw, index),";
const SEARCH_NEW = "action: 0 === index ? 'search' : 'search_v2', params: { kw: kw, search_type: 'up' === self.searchType ? 'bili_user' : 'video' },";

// AiSummary B 站 AI 接口（动态 URL → 语义化）
const AI_BILL_OLD = "url: url,";
const AI_BILL_NEW = "action: 'ai_summary_bili', params: { bvid: this.bvid, cid: this.cid },";

const SEMANTIC_PATCHES = {
  'My/my.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/nav',", "action: 'nav',"],
    ["url: 'https://api.bilibili.com/x/relation/stat?vmid=' + this.mid,", "action: 'relation_stat', params: { vmid: this.mid },"]
  ],
  'Home/home.js': [
    [HOME_POPULAR_OLD, HOME_POPULAR_NEW],
    [HOME_LIVE_OLD, HOME_LIVE_NEW]
  ],
  'Dynamic/dynamic.js': [
    ["url: 'https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?timezone_offset=-480&type=all&page=1',", "action: 'dynamic_feed', params: { page: this.dynPage || 1 }, // log42：动态分页（翻页时取 dynPage）"]
  ],
  'Comments/comments.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/view?bvid=' + this.bvid,", "action: 'video_view', params: { bvid: this.bvid },"],
    ["url: 'https://api.bilibili.com/x/v2/reply?type=1&oid=' + this.aid + '&sort=1&ps=3&pn=1',", "action: 'video_reply', params: { oid: this.aid, ps: 3, pn: this.commentPage || 1 }, // log45：评论分页（pn 取 commentPage）"]
  ],
  'Live/live.js': [
    ["url: 'https://api.live.bilibili.com/room/v1/Room/get_info?room_id=' + this.roomId,", "action: 'live_room', params: { room_id: this.roomId },"]
  ],
  'HotSearch/hotsearch.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/search/square?limit=8',", "action: 'hot_search', params: { limit: 8 },"]
  ],
  'UpHome/uphome.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/card?mid=' + self.mid,", "action: 'up_card', params: { mid: self.mid },"],
    ["url: 'https://api.bilibili.com/x/relation/stat?vmid=' + this.mid,", "action: 'relation_stat', params: { vmid: this.mid },"],
    ["url: 'https://api.bilibili.com/x/relation/modify',", "action: 'relation_modify',"]
  ],
  'FavHistory/favhistory.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/nav',", "action: 'nav',"],
    ["url: 'https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=' + this.mid + '&type=2',", "action: 'fav_folder_list', params: { up_mid: this.mid },"],
    ["url: 'https://api.bilibili.com/x/v3/fav/resource/list?media_id=' + this.mediaId + '&pn=1&ps=20&platform=web&type=0',", "action: 'fav_resource_list', params: { media_id: this.mediaId },"],
    ["url: 'https://api.bilibili.com/x/web-interface/history/cursor?max=0&view_at=0&business=archive',", "action: 'history_cursor', params: { max: this.hisMax || 0 }, // log42：历史游标分页"]
  ],
  'Messages/messages.js': [
    ["url: 'https://api.vc.bilibili.com/session_svr/v2/session_svr/get_sessions?session_type=1&group_fold=1&unfollow_fold=0&sort_rule=2',", "action: 'msg_sessions_v2',"],
    ["url: 'https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions?session_type=1&group_fold=1&unfollow_fold=0&sort_rule=2',", "action: 'msg_sessions_v1',"],
    ["url: 'https://api.bilibili.com/x/space/acc/info?mid=' + mid,", "action: 'space_acc', params: { mid: mid },"],
    ["url: 'https://api.vc.bilibili.com/web_im/v1/web_im/send_msg',", "action: 'msg_send',"]
  ],
  'Search/search.js': [
    [SEARCH_OLD, SEARCH_NEW]
  ],
  'PostComment/postcomment.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/view?bvid=' + this.bvid,", "action: 'video_view', params: { bvid: this.bvid },"],
    ["url: urls[index],", "action: 0 === index || 2 === index ? 'post_comment_web' : 'post_comment_app',"]
  ],
  'AiSummary/aisummary.js': [
    [AI_BILL_OLD, AI_BILL_NEW],
    ["url: 'https://quark.sm.cn/api/rest?method=ai.video.summary',", "action: 'ai_summary_quark',"],
    ["url: 'https://ark.cn-beijing.volces.com/api/v3/chat/completions',", "action: 'ai_summary_ark',"],
    ["url: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',", "action: 'ai_summary_dashscope',"]
  ],
  'Manga/manga.js': [
    ["url: 'https://apis.netstart.cn/bcomic/HomeFeed?pageNum=1&pageSize=4',", "action: 'manga_home',"],
    ["url: 'https://apis.netstart.cn/bcomic/Search?key_word=' + encodeURIComponent(kw) + '&page_num=1&page_size=4',", "action: 'manga_search', params: { kw: kw },"]
  ],
  'MangaDetail/mangadetail.js': [
    ["url: 'https://apis.netstart.cn/bcomic/ComicDetail?comic_id=' + this.comicId,", "action: 'manga_detail', params: { comic_id: this.comicId },"]
  ],
  'MangaReader/mangareader.js': [
    ["url: 'https://apis.netstart.cn/bcomic/GetImageIndex?ep_id=' + this.epId,", "action: 'manga_images', params: { ep_id: this.epId },"]
  ],
  'Video/video.js': [
    ["url: 'https://api.bilibili.com/x/web-interface/view?bvid=' + this.bvid,", "action: 'video_view', params: { bvid: this.bvid },"],
    ["url: 'https://api.bilibili.com/x/player/videoshot?bvid=' + this.bvid + '&cid=' + this.cid + '&index=1',", "action: 'video_videoshot', params: { bvid: this.bvid, cid: this.cid },"],
    ["url: 'https://api.bilibili.com/x/player/playurl?bvid=' + this.bvid + '&cid=' + this.cid + '&qn=32&fnval=0&fourk=0',", "action: 'video_playurl', params: { bvid: this.bvid, cid: this.cid, qn: 32 },"],
    ["url: 'https://api.bilibili.com/x/v2/reply?type=1&oid=' + this.aid + '&sort=1&ps=3&pn=1',", "action: 'video_reply', params: { oid: this.aid, ps: 3, pn: 1 },"],
    ["url: 'https://app.bilibili.com/x/v2/view/like',", "action: 'video_like_v2',"],
    ["url: 'https://api.bilibili.com/x/web-interface/archive/like',", "action: 'video_like_web',"],
    ["url: 'https://app.bilibili.com/x/v2/view/coin/add',", "action: 'video_coin_v2',"],
    ["url: 'https://api.bilibili.com/x/web-interface/coin/add',", "action: 'video_coin_web',"],
    ["url: 'https://quark.sm.cn/api/rest?method=ai.video.summary',", "action: 'ai_summary_quark',"],
    ["url: 'https://api.bilibili.com/x/relation/modify',", "action: 'relation_modify',"],
    ["url: 'https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=' + mid + '&type=2',", "action: 'fav_folder_list', params: { up_mid: mid },"],
    ["url: 'https://api.bilibili.com/x/v3/fav/resource/deal',", "action: 'video_fav_deal',"]
  ]
};

// ===================== 页面自动请求放行（log23）=====================
// 手环端不再持有真实 Cookie（登录态由手机端注入），
// 各页面"cookie 长度/csrf 缺失则拦截不请求"的判断改为放行（false &&），
// 保证"进入页面即自动向手机发请求"，无需点按钮。
// 写操作（点赞/投币/关注/评论/私信）csrf 由手机端 HttpRelay 自动补齐。
const AUTOLOAD_PATCHES = {
  'Dynamic/dynamic.js': [
    ["if (!cookie || cookie.length < 10) {", "if (false && (!cookie || cookie.length < 10)) {"]
  ],
  'FavHistory/favhistory.js': [
    ["if (!this.cookie || this.cookie.length < 10) {", "if (false && (!this.cookie || this.cookie.length < 10)) {"]
  ],
  'Messages/messages.js': [
    ["if (!cookie || cookie.length < 10) {", "if (false && (!cookie || cookie.length < 10)) {"],
    ["if (!cookie || !csrf || !sender) {", "if (false && (!cookie || !csrf || !sender)) {"]
  ],
  'Home/home.js': [
    ["if (!cookie || cookie.length < 10) return;", "if (false && (!cookie || cookie.length < 10)) return;"]
  ],
  'Video/video.js': [
    ["if (!cookie || cookie.length < 10) {", "if (false && (!cookie || cookie.length < 10)) {"],
    ["if (!cookie || !csrf) {", "if (false && (!cookie || !csrf)) {"],
    ["if (!cookie || !csrf) return;", "if (false && (!cookie || !csrf)) return;"],
    ["if (!cookie || !csrf || !mid) {", "if (false && (!cookie || !csrf || !mid)) {"]
  ],
  'PostComment/postcomment.js': [
    ["if (!cookie || cookie.length < 10 || !csrf) {", "if (false && (!cookie || cookie.length < 10 || !csrf)) {"]
  ],
  'UpHome/uphome.js': [
    ["if (!cookie || !csrf) {", "if (false && (!cookie || !csrf)) {"]
  ]
};

// 补丁5q：My 页面模板 image(qrImg) → 官方 qrcode 组件(value=qrUrl)，快应用内直接画二维码（不依赖图片下载，无闪退）
/** 将 My 页模板中显示登录二维码的 image 组件替换为官方 qrcode 组件（注意替换长度变化，需重新定位） */
function patchMyQrComponent(js) {
  const anchor = '_vm_.qrImg';
  const pos = js.indexOf(anchor);
  if (pos < 0) return { js, n: 0 };
  const imgStart = js.lastIndexOf('aiot.__ce__("image"', pos);
  if (imgStart < 0) return { js, n: 0 };
  // 1) 模板内引用 qrImg → qrUrl
  js = js.substring(0, pos) + '_vm_.qrUrl' + js.substring(pos + anchor.length);
  // 2) 元素名 image → qrcode（imgStart 在 anchor 之前，不受上一步影响）
  js = js.substring(0, imgStart) + 'aiot.__ce__("qrcode"' + js.substring(imgStart + 'aiot.__ce__("image"'.length);
  // 3) src → value（上两步改变长度，基于新的 _vm_.qrUrl 重新定位）
  const newPos = js.indexOf('_vm_.qrUrl');
  const srcPos = js.lastIndexOf('src: function()', newPos);
  if (srcPos >= 0) js = js.substring(0, srcPos) + 'value: function()' + js.substring(srcPos + 'src: function()'.length);
  return { js, n: 1 };
}

// ===================== 网桥页面补丁（仅 Bridge/bridge.js）=====================

// app.js：全局暴露网桥实例（供纯直连 bundle 的 __wbFetch 使用，实现全页面网桥化）
const BRIDGE_EXPOSE_OLD = `                        onCreate () {
                                console.log('WristBili 启动');
                                _fetchbridge.default.init();
                            },`;
const BRIDGE_EXPOSE_NEW = `                        onCreate () {
                                console.log('WristBili 启动');
                                _fetchbridge.default.init();
                                // 全局暴露网桥实例，供其他 bundle 的直连页面改走网桥
                                try {
                                    __webpack_require__.g.__wb_bridge = _fetchbridge.default;
                                } catch (e) {}
                                // 默认开启网桥：storage 未设置过开关时默认启用（进入页面即自动向手机请求，无需手动开网桥）
                                try {
                                    var _stg = $app_require$1("@app-module/system.storage");
                                    _stg.get({
                                        key: 'bili_bridge_enabled',
                                        success: function(d) {
                                            if (d !== 'on' && d !== 'off') {
                                                _stg.set({ key: 'bili_bridge_enabled', value: 'on' });
                                            }
                                        },
                                        fail: function() {
                                            _stg.set({ key: 'bili_bridge_enabled', value: 'on' });
                                        }
                                    });
                                } catch (e) {}
                            },`;

// 纯直连 bundle（不含 fetchbridge 内联）：注入 __wbFetch 包装器（网桥就绪走网桥转发，未就绪等待就绪后走网桥，网桥关闭才直连兜底）
const WB_FETCH_INJECT_OLD = 'var _system2 = _interopRequireDefault($app_require$1("@app-module/system.fetch"));';
const WB_FETCH_INJECT_NEW = `// bridge-only：移除 system.fetch 直连，页面请求统一走 __wbFetch（互联转发）
                        var __wbFetch = function(opts) {
                            try {
                                // 全局网桥实例在 globalThis.__wb_bridge（__webpack_require__.g 仅 app.js 定义，本 bundle 取不到，必须用 globalThis）
                                var b = null;
                                try {
                                    if (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) b = globalThis.__wb_bridge;
                                    else if (typeof __webpack_require__ !== 'undefined' && __webpack_require__.g && __webpack_require__.g.__wb_bridge) b = __webpack_require__.g.__wb_bridge;
                                } catch (e) {}
                                // 网桥模式（bridge-only）：手环不直连网络，请求一律经互联转发；未就绪只报错，绝不走系统 fetch
                                var _fail = function() {
                                    if (opts.fail) opts.fail({ code: -2, message: '网桥未就绪，请稍后重试' });
                                };
                                if (b && b.isReady && b.isReady()) { b.fetch(opts); return; }
                                if (b && b.getStatus) {
                                    // 网桥开启但未就绪（含 App 启动早期开关未恢复的 disabled 状态）：
                                    // 每秒检查等待就绪（最长30秒）后自动走网桥，超时才失败——保证"进入页面即自动请求，网桥就绪后自动发出"
                                    var _tries = 0;
                                    var _tm = setInterval(function() {
                                        _tries++;
                                        if (b.isReady && b.isReady()) { clearInterval(_tm); b.fetch(opts); }
                                        else if (_tries >= 30) { clearInterval(_tm); _fail(); }
                                    }, 1000);
                                    return;
                                }
                            } catch (e) {}
                            if (opts.fail) opts.fail({ code: -2, message: '网桥不可用' });
                        };`;

// Settings 页面（Settings/settings.js）：onInit 恢复网桥开关 + 状态实时刷新
const ST_ONINIT_OLD = `                                this.loadAccountInfo();
                                this.updateBridgeStatus();
                            },`;
const ST_ONINIT_NEW = `                                this.loadAccountInfo();
                                _fetchbridge.default.init(); // 从 storage 恢复网桥开关状态（设置界面不再显示"关闭"）
                                _fetchbridge.default.onStatusChange(function(st) {
                                    self.updateBridgeStatus();
                                    // 网桥就绪后自动补发登录态请求（log35：防"已登录但设置页显示未连接/未登录"）
                                    if ('ready' === st) self.loadAccountInfo();
                                });
                                this.updateBridgeStatus();
                            },
                            onShow () {
                                // 每次显示（含从 Bridge 页返回）重新恢复开关状态并刷新显示
                                _fetchbridge.default.init();
                                this.updateBridgeStatus();
                                this.loadAccountInfo(); // log35：返回设置页时刷新登录态（以手机端 nav 为准）
                                // log39e：标记"用户进过设置页"，返回首页时 Home onShow 据此主动刷新首页推荐
                                try {
                                    if (typeof globalThis !== 'undefined') globalThis.__wb_need_refresh_home = true;
                                } catch (e) {}
                            },`;

// 补丁5ab（log35）：Home 页网桥未就绪时不发首页请求（防握手期间多个请求排队爆发导致首页/直播不稳定），
// 就绪后自动加载；onShow 返回首页时若内容未加载成功则重新请求（连接成功后返回首页即可自动刷新）
const HOME_ONINIT_OLD = `                        onInit () {
                            this.startSpinner();
                            this.checkLoginAndLoad();
                            this.loadUserAvatar();
                            this.loadLiveList();
                        },`;
const HOME_ONINIT_NEW = `                        onInit () {
                            this.startSpinner();
                            var self = this;
                            // 就绪判断（log37）：优先 __wb_bridge.isReady()；多实例 enabled 状态不同步时
                            // 用全局握手活性 __wb_hs_ts 兜底（任意实例完成握手都会刷新该时间戳）
                            var _rdy = function() {
                                var _ok = false;
                                try {
                                    var _b0 = null;
                                    if (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) _b0 = globalThis.__wb_bridge;
                                    else if (typeof __webpack_require__ !== 'undefined' && __webpack_require__.g && __webpack_require__.g.__wb_bridge) _b0 = __webpack_require__.g.__wb_bridge;
                                    _ok = !!(_b0 && _b0.isReady && _b0.isReady());
                                    if (!_ok) {
                                        var _hs0 = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : 0;
                                        if (_hs0 && (new Date().getTime() - _hs0 <= 90000)) _ok = true;
                                    }
                                } catch (e) {}
                                return _ok;
                            };
                            if (!_rdy()) {
                                // 网桥未就绪：不发请求，等待就绪后自动加载（log35）
                                this.loadTip = '网桥连接中，请稍候...';
                                var _w2 = 0;
                                var _tw = setInterval(function() {
                                    _w2++;
                                    if (_rdy()) {
                                        clearInterval(_tw);
                                        self.loadTip = '连接成功，加载中...';
                                        self.checkLoginAndLoad();
                                        self.loadUserAvatar();
                                        self.loadLiveList();
                                    } else if (_w2 >= 30) {
                                        clearInterval(_tw);
                                        self.loadTip = '网桥未连接，请先开启网桥';
                                        self.isLoading = false;
                                        self.showError = true;
                                        if (self.spinTimer) {
                                            clearInterval(self.spinTimer);
                                            self.spinTimer = null;
                                        }
                                    }
                                }, 1000);
                                return;
                            }
                            this.checkLoginAndLoad();
                            this.loadUserAvatar();
                            this.loadLiveList();
                        },
                        onShow () {
                            // log39e：从设置页返回时主动刷新首页推荐（Settings onShow 会置全局刷新标志）
                            var _needRefresh = false;
                            try {
                                if (typeof globalThis !== 'undefined' && globalThis.__wb_need_refresh_home) {
                                    _needRefresh = true;
                                    globalThis.__wb_need_refresh_home = false;
                                }
                            } catch (e) {}
                            // log35：返回首页且内容未加载成功时重新请求（网桥连接成功后返回首页即可自动刷新）
                            if (_needRefresh || this.showError || (!this.isLoading && this.loadedCount <= 0)) {
                                this.showError = false;
                                this.loadTip = '加载中...';
                                this.startSpinner();
                                this.checkLoginAndLoad();
                                if (this.showLiveList) this.loadLiveList();
                            }
                        },`;

// 补丁5ac（log35）：HotSearch 页网桥未就绪时不发热搜请求（防握手期请求排队超时显示兜底词），
// 就绪后自动加载；onShow 返回时若为兜底词则重试真实接口
const HOT_ONINIT_OLD = `                        onInit () {
                            this.loadHot();
                        },`;
const HOT_ONINIT_NEW = `                        onInit () {
                            var self = this;
                            // 就绪判断（log37）：isReady + 全局握手活性兜底
                            var _rdy = function() {
                                var _ok = false;
                                try {
                                    var _b0 = null;
                                    if (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) _b0 = globalThis.__wb_bridge;
                                    else if (typeof __webpack_require__ !== 'undefined' && __webpack_require__.g && __webpack_require__.g.__wb_bridge) _b0 = __webpack_require__.g.__wb_bridge;
                                    _ok = !!(_b0 && _b0.isReady && _b0.isReady());
                                    if (!_ok) {
                                        var _hs0 = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : 0;
                                        if (_hs0 && (new Date().getTime() - _hs0 <= 90000)) _ok = true;
                                    }
                                } catch (e) {}
                                return _ok;
                            };
                            if (!_rdy()) {
                                this.status = '网桥连接中，请稍候...';
                                var _w3 = 0;
                                var _tw3 = setInterval(function() {
                                    _w3++;
                                    if (_rdy()) {
                                        clearInterval(_tw3);
                                        self.loadHot();
                                    } else if (_w3 >= 30) {
                                        clearInterval(_tw3);
                                        self.status = '网桥未连接，请先开启网桥';
                                        self.useFallback();
                                    }
                                }, 1000);
                                return;
                            }
                            this.loadHot();
                        },
                        onShow () {
                            // log35：返回热搜页且仍为兜底词时重试真实接口
                            if (this.hotFallback) {
                                this.hotFallback = false;
                                this.loadHot();
                            }
                        },`;
const HOT_FALLBACK_OLD = `                        useFallback () {
                            this.h1 = '热门动画';`;
const HOT_FALLBACK_NEW = `                        useFallback () {
                            this.hotFallback = true; // log35：标记兜底，onShow 返回时自动重试真实接口
                            this.h1 = '热门动画';`;
const HOT_APPLY_OLD = `                        applyList (list) {
                            var i = 0;
                            for(i = 1; i <= 8; i++)if (list[i - 1]) this['h' + i] = this.getKeyword(list[i - 1]);
                            this.status = '点击热搜词进入搜索';`;
const HOT_APPLY_NEW = `                        applyList (list) {
                            var i = 0;
                            this.hotFallback = false;
                            for(i = 1; i <= 8; i++)if (list[i - 1]) this['h' + i] = this.getKeyword(list[i - 1]);
                            this.status = '点击热搜词进入搜索';`;

// 补丁5ad（log35）：Live 直播详情页网桥未就绪时不发请求，就绪后自动加载（与 Home/HotSearch 同一套稳定性处理）
const LIVE_ONINIT_OLD = `                        onInit () {
                            if ('' === this.title) this.title = '直播间';
                            this.loadLiveInfo();
                        },`;
const LIVE_ONINIT_NEW = `                        onInit () {
                            if ('' === this.title) this.title = '直播间';
                            var self = this;
                            // 就绪判断（log37）：isReady + 全局握手活性兜底
                            var _rdy = function() {
                                var _ok = false;
                                try {
                                    var _b0 = null;
                                    if (typeof globalThis !== 'undefined' && globalThis.__wb_bridge) _b0 = globalThis.__wb_bridge;
                                    else if (typeof __webpack_require__ !== 'undefined' && __webpack_require__.g && __webpack_require__.g.__wb_bridge) _b0 = __webpack_require__.g.__wb_bridge;
                                    _ok = !!(_b0 && _b0.isReady && _b0.isReady());
                                    if (!_ok) {
                                        var _hs0 = (typeof globalThis !== 'undefined' && globalThis.__wb_hs_ts) ? globalThis.__wb_hs_ts : 0;
                                        if (_hs0 && (new Date().getTime() - _hs0 <= 90000)) _ok = true;
                                    }
                                } catch (e) {}
                                return _ok;
                            };
                            if (!_rdy()) {
                                this.note = '网桥连接中，请稍候...';
                                var _w4 = 0;
                                var _tw4 = setInterval(function() {
                                    _w4++;
                                    if (_rdy()) {
                                        clearInterval(_tw4);
                                        self.loadLiveInfo();
                                    } else if (_w4 >= 30) {
                                        clearInterval(_tw4);
                                        self.note = '网桥未连接，请先开启网桥';
                                    }
                                }, 1000);
                                return;
                            }
                            this.loadLiveInfo();
                        },`;

// 页面 private 增加 logText
const PG_PRIVATE_OLD = 'private: {\n                                statusText: \'已关闭\',';
const PG_PRIVATE_NEW = 'private: {\n                                statusText: \'已关闭\',\n                                logText: \'\',';

// onInit 增加定时刷新日志
const PG_ONINIT_OLD = `onInit () {
                                var self = this;
                                this.refreshStatus();
                                _fetchbridge.default.onStatusChange(function(st) {
                                    self.refreshStatus();
                                });
                            },`;
const PG_ONINIT_NEW = `onInit () {
                                var self = this;
                                _fetchbridge.default.init(); // 从 storage 恢复开关状态（退出设置界面后仍保持开启）
                                this.refreshStatus();
                                _fetchbridge.default.onStatusChange(function(st) {
                                    self.refreshStatus();
                                });
                                setInterval(function() {
                                    var l = _fetchbridge.default.getLogs() || [];
                                    // 只显示最近12条（与样式 lines:12 匹配），新日志自动顶出旧日志 = 自动滚动
                                    self.logText = l.slice(-12).join('\\n');
                                }, 1000);
                            },`;

// 页面方法：推送测试（手表端发起网桥请求，首页 rcmd 失败自动改用热门，成功后跳转对应页面让内容渲染出来）
const PG_PUSHTEST_OLD = `                            back () {`;
const PG_PUSHTEST_NEW = `                            pushTest (name, action, params, uri) {
                                var self = this;
                                this.status = '正在推送 ' + name + '...';
                                var _isRcmd = function(a, u) { return 'home_rcmd' === a || (u && u.indexOf('rcmd') > 0); };
                                var _tryFetch = function(a, p, u, depth) {
                                    var opts = {
                                        responseType: 'json',
                                        timeout: 30000,
                                        success: function(res) {
                                            var data = res.data;
                                            if ('string' == typeof data) data = JSON.parse(data);
                                            var code = (data && data.code !== undefined) ? data.code : '?';
                                            if (0 !== code && depth < 2 && _isRcmd(a, u)) {
                                                self.status = '首页推荐失败(code=' + code + ')，改用热门';
                                                self.refreshStatus();
                                                _tryFetch('home_popular', { page: '1' }, '', depth + 1);
                                                return;
                                            }
                                            self.status = '推送[' + name + ']完成 code=' + code;
                                            self.refreshStatus();
                                            if (uri) {
                                                // 推送成功后打开对应页面，页面自身通过网桥加载并渲染内容
                                                try {
                                                    _system.default.push({ uri: uri });
                                                } catch (e) {}
                                            }
                                        },
                                        fail: function() {
                                            if (depth < 2 && _isRcmd(a, u)) {
                                                self.status = '首页推荐请求失败，改用热门';
                                                self.refreshStatus();
                                                _tryFetch('home_popular', { page: '1' }, '', depth + 1);
                                                return;
                                            }
                                            self.status = '推送[' + name + ']失败';
                                            self.refreshStatus();
                                        }
                                    };
                                    if (a && 'string' == typeof a && 'http' !== a.substring(0, 4)) {
                                        opts.action = a; // 语义化：手机端整合 URL（wbi 签名/多候选兜底）
                                        opts.params = p || {};
                                    } else {
                                        opts.url = u || a; // url 直传兼容
                                    }
                                    _fetchbridge.default.fetch(opts);
                                };
                                _tryFetch(action, params, '', 0);
                            },
                            back () {`;

// 模板末尾追加日志区域
const PG_TMPL_OLD = `                            }, [])
                        ]);
                    };
                    $app_exports$['entry'] = function($app_exports$) {`;
const PG_TMPL_NEW = `                            }, []),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "log-card"
                                    ]
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "log-title"
                                        ],
                                        value: "运行日志"
                                    }
                                }, []),
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "log-text"
                                        ],
                                        value: function() {
                                            return _vm_.logText;
                                        }
                                    }
                                }, [])
                            ]),
                            aiot.__ce__("div", {
                                __vm__: _vm_,
                                __opts__: {
                                    classList: [
                                        "push-card"
                                    ]
                                }
                            }, [
                                aiot.__ce__("text", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "push-title"
                                        ],
                                        value: "推送测试"
                                    }
                                }, []),
                                aiot.__ce__("div", {
                                    __vm__: _vm_,
                                    __opts__: {
                                        classList: [
                                            "push-grid"
                                        ]
                                    }
                                }, [
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: ["push-btn"],
                                            events: {
                                                click: function(evt) {
                                                    return _vm_.pushTest('首页推荐', 'home_rcmd', { page: Math.floor(Math.random() * 1000) }, '/home');
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("image", { __vm__: _vm_, __opts__: { classList: ["push-ico"], src: "/common/images/icon_64.png" } }, []),
                                        aiot.__ce__("text", { __vm__: _vm_, __opts__: { classList: ["push-label"], value: "首页" } }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: ["push-btn"],
                                            events: {
                                                click: function(evt) {
                                                    return _vm_.pushTest('热门', 'home_popular', { page: '1' }, '/home');
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("image", { __vm__: _vm_, __opts__: { classList: ["push-ico"], src: "/common/images/icon_64.png" } }, []),
                                        aiot.__ce__("text", { __vm__: _vm_, __opts__: { classList: ["push-label"], value: "热门" } }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: ["push-btn"],
                                            events: {
                                                click: function(evt) {
                                                    // /live 是直播间详情页（需 room_id 参数），推直播列表跳首页（自带直播 Tab）
                                                    return _vm_.pushTest('直播', 'home_live_list', {}, '/home');
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("image", { __vm__: _vm_, __opts__: { classList: ["push-ico"], src: "/common/images/icon_64.png" } }, []),
                                        aiot.__ce__("text", { __vm__: _vm_, __opts__: { classList: ["push-label"], value: "直播" } }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: ["push-btn"],
                                            events: {
                                                click: function(evt) {
                                                    return _vm_.pushTest('热榜', 'hot_search', { limit: '10' }, '/hotsearch');
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("image", { __vm__: _vm_, __opts__: { classList: ["push-ico"], src: "/common/images/icon_search.png" } }, []),
                                        aiot.__ce__("text", { __vm__: _vm_, __opts__: { classList: ["push-label"], value: "热榜" } }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: ["push-btn"],
                                            events: {
                                                click: function(evt) {
                                                    return _vm_.pushTest('动态', 'dynamic_feed', { page: '1' }, '/dynamic');
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("image", { __vm__: _vm_, __opts__: { classList: ["push-ico"], src: "/common/images/icon_dynamic.png" } }, []),
                                        aiot.__ce__("text", { __vm__: _vm_, __opts__: { classList: ["push-label"], value: "动态" } }, [])
                                    ]),
                                    aiot.__ce__("div", {
                                        __vm__: _vm_,
                                        __opts__: {
                                            classList: ["push-btn"],
                                            events: {
                                                click: function(evt) {
                                                    // myinfo 是 App 端接口（Firefox UA 返回 -400），改用 Web 接口 nav
                                                    return _vm_.pushTest('我的', 'nav', {}, '/my');
                                                }
                                            }
                                        }
                                    }, [
                                        aiot.__ce__("image", { __vm__: _vm_, __opts__: { classList: ["push-ico"], src: "/common/images/icon_64.png" } }, []),
                                        aiot.__ce__("text", { __vm__: _vm_, __opts__: { classList: ["push-label"], value: "我的" } }, [])
                                    ])
                                ])
                            ])
                        ]);
                    };
                    $app_exports$['entry'] = function($app_exports$) {`;

// style 末尾追加日志样式
const PG_STYLE_OLD = `                        [
                            [
                                [
                                    0,
                                    "status"
                                ]
                            ],
                            {
                                width: "292px",
                                color: "rgba(255, 255, 255, 0.4)",
                                fontSize: "11px",
                                marginTop: "10px",
                                textAlign: "center"
                            }
                        ]
                    ];`;
const PG_STYLE_NEW = `                        [
                            [
                                [
                                    0,
                                    "status"
                                ]
                            ],
                            {
                                width: "292px",
                                color: "rgba(255, 255, 255, 0.4)",
                                fontSize: "11px",
                                marginTop: "10px",
                                textAlign: "center"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "log-card"
                                ]
                            ],
                            {
                                width: "316px",
                                marginTop: "8px",
                                borderRadius: "12px",
                                backgroundColor: "#10131a",
                                paddingTop: "8px",
                                paddingRight: "10px",
                                paddingBottom: "8px",
                                paddingLeft: "10px",
                                flexDirection: "column"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "log-title"
                                ]
                            ],
                            {
                                color: "#fb7299",
                                fontSize: "12px",
                                fontWeight: "bold",
                                marginBottom: "6px"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "log-text"
                                ]
                            ],
                            {
                                color: "rgba(255,255,255,0.7)",
                                fontSize: "10px",
                                lines: 12,
                                fontFamily: "monospace"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "push-card"
                                ]
                            ],
                            {
                                width: "316px",
                                marginTop: "8px",
                                borderRadius: "12px",
                                backgroundColor: "#10131a",
                                paddingTop: "8px",
                                paddingRight: "10px",
                                paddingBottom: "8px",
                                paddingLeft: "10px",
                                flexDirection: "column"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "push-title"
                                ]
                            ],
                            {
                                color: "#fb7299",
                                fontSize: "12px",
                                fontWeight: "bold",
                                marginBottom: "6px"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "push-grid"
                                ]
                            ],
                            {
                                flexDirection: "row",
                                flexWrap: "wrap",
                                justifyContent: "space-between"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "push-btn"
                                ]
                            ],
                            {
                                width: "96px",
                                height: "56px",
                                marginBottom: "6px",
                                borderRadius: "10px",
                                backgroundColor: "#1e2430",
                                flexDirection: "column",
                                alignItems: "center",
                                justifyContent: "center"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "push-ico"
                                ]
                            ],
                            {
                                width: "22px",
                                height: "22px",
                                marginBottom: "2px"
                            }
                        ],
                        [
                            [
                                [
                                    0,
                                    "push-label"
                                ]
                            ],
                            {
                                color: "rgba(255,255,255,0.85)",
                                fontSize: "10px"
                            }
                        ]
                    ];`;

/** 用平衡花括号匹配并替换整个 createSession 函数定义 */
function replaceCreateSession(js) {
  const marker = 'function createSession() {';
  const idx = js.indexOf(marker);
  if (idx < 0) return null;
  let depth = 0, i = idx;
  for (; i < js.length; i++) {
    const c = js[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) { i++; break; }
    }
  }
  const oldFn = js.substring(idx, i);
  return { oldFn, tail: js.substring(i) };
}

/** 用平衡花括号匹配并替换 exports 对象中的方法（getStatus/isReady 等），不受缩进影响 */
function replaceExportMethod(js, name, newText) {
  const marker = name + ': function() {';
  const idx = js.indexOf(marker);
  if (idx < 0) return { js, ok: false };
  let depth = 0, i = idx;
  for (; i < js.length; i++) {
    const c = js[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) { i++; break; }
    }
  }
  if (i >= js.length) return { js, ok: false };
  return { js: js.substring(0, idx) + newText + js.substring(i), ok: true };
}

/** 删除指定函数定义（bridge-only：移除 directFetch 直连路径） */
function removeFunction(js, marker) {
  const idx = js.indexOf(marker);
  if (idx < 0) return { js, ok: false };
  let depth = 0, i = idx;
  for (; i < js.length; i++) {
    const c = js[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) { i++; break; }
    }
  }
  if (i >= js.length) return { js, ok: false };
  return { js: js.substring(0, idx) + js.substring(i), ok: true };
}

/** log42：首页推荐隐藏未加载的空窗格——20 个 video-card 逐个用 __ci__({shown: vXTitle 非空}) 包裹（项目惯例，同 Dynamic 页） */
function hideEmptyVideoCards(js) {
  let n = 0;
  for (let i = 1; i <= 20; i++) {
    const clickMark = 'return _vm_.openVideo' + i + '(evt);';
    const cIdx = js.indexOf(clickMark);
    if (cIdx < 0) continue;
    const cardIdx = js.lastIndexOf('"video-card"', cIdx);
    if (cardIdx < 0) continue;
    const braceStart = js.lastIndexOf('aiot.__ce__("div", {', cardIdx);
    if (braceStart < 0) continue;
    // 精确定位本卡片 div 调用的 (（不能 lastIndexOf('(')，会误匹配前面已包裹卡片的 function() { 括号）
    const callIdx = js.lastIndexOf('aiot.__ce__("div",', braceStart);
    if (callIdx < 0) continue;
    const parenStart = js.indexOf('(', callIdx);
    if (parenStart < 0) continue;
    const stack = [];
    let end = -1;
    for (let j = parenStart; j < js.length; j++) {
      const ch = js[j];
      if (ch === '(' || ch === '{' || ch === '[') stack.push(ch);
      else if (ch === ')' || ch === '}' || ch === ']') {
        if (stack.length > 0) stack.pop();
        if (stack.length === 0) { end = j + 1; break; }
      }
    }
    if (end < 0) continue;
    // div 必须从 callIdx（aiot.__ce__ 的 a）开始截取，不能从 ( 开始（否则丢 aiot.__ce__ 前缀）
    const div = js.substring(callIdx, end);
    const wrapper = 'aiot.__ci__({' +
      '\n                                    __vm__: _vm_,' +
      '\n                                    __opts__: {' +
      '\n                                        shown: function() {' +
      '\n                                            return !!_vm_.v' + i + 'Title;' +
      '\n                                        }' +
      '\n                                    }' +
      '\n                                }, function() {' +
      '\n                                    return [' +
      '\n' + div +
      '\n                                    ];' +
      '\n                                })';
    js = js.substring(0, callIdx) + wrapper + js.substring(end);
    n++;
  }
  return { js, n };
}

/** 批量应用 fetchbridge 补丁，返回应用计数 */
function applyFetchBridgePatches(js) {
  let n = 0;
  const patches = [
    [HS_OLD, HS_NEW],
    [HS_REP_OLD, HS_REP_NEW],
    [LOG_STATE_OLD, LOG_STATE_NEW],
    [ADDLOG_OLD, ADDLOG_NEW],
    [GETLOGS_OLD, GETLOGS_NEW],
    [HH_OLD, HH_NEW],
    [BF_OLD, BF_NEW],
    [PSR_OLD, PSR_NEW],
    [SM_OLD, SM_NEW],
    [SM_DATA_OLD, SM_DATA_NEW],
    [SM_SESSION_OLD, SM_SESSION_NEW],
    [ENABLE_OLD, ENABLE_NEW],
    [DISABLE_OLD, DISABLE_NEW],
    [RECONN_OLD, RECONN_NEW],
    [CS_CLOSE_OLD, CS_CLOSE_NEW],
    [JUMP_FN_OLD, JUMP_FN_NEW],
    // 注：INIT_COOKIE 补丁已移除（登录凭证只存手机端，手环不再读取 bili_cookie 到 state.cookie）
    [BF_HDRS_OLD, BF_HDRS_NEW],
    [SMFAIL_OLD, SMFAIL_NEW],
    [SF_IMPORT_OLD, SF_IMPORT_NEW],
    [VF_IMPORT_OLD, VF_IMPORT_NEW],
    [MANIFEST_FETCH_OLD, MANIFEST_FETCH_NEW],
    [DEV_FETCH_OLD, DEV_FETCH_NEW],
    [DIAG_REQ_OLD, DIAG_REQ_NEW],
    [SHOWQR_SEND_OLD, SHOWQR_SEND_NEW],
    // log27：全局响应分发（多实例 conn 回调覆盖时，响应仍能路由回发起请求的实例）
    [BF_LOG_OLD, BF_LOG_NEW],
    [BF_PEND_OLD, BF_PEND_NEW],
    [BF_HFR_OLD, BF_HFR_NEW],
    [BF_PSR_OLD, BF_PSR_NEW],
    [BF_CBUF_OLD, BF_CBUF_NEW],
    [BF_HFC_OLD, BF_HFC_NEW],
    [BF_TAC_OLD, BF_TAC_NEW],
    [BF_GENID_OLD, BF_GENID_NEW],
    // log39b：延长请求默认超时与分块组装超时（首页/直播大响应互联分块传输慢导致手环端先超时）
    [FETCH_TO_OLD, FETCH_TO_NEW],
    [CHUNK_TO_OLD, CHUNK_TO_NEW],
    // log39c：分块响应清除原 pending 超时（双计时器竞争修复）
    [BF_CHUNK_CLEAR_OLD, BF_CHUNK_CLEAR_NEW],
    // log49：图片 base64 分片 → 手环 @system.file 保存本地文件 → image 加载本地路径（10 Pro jpg 解码 BUG 根治）
    [FILE_IMPORT_OLD, FILE_IMPORT_NEW],
    [FILE_STATE_OLD, FILE_STATE_NEW],
    [FILE_ONMSG_OLD, FILE_ONMSG_NEW],
    [FILE_PUMP_OLD, FILE_PUMP_NEW],
    [FILE_PSR_OLD, FILE_PSR_NEW],
    [FILE_TAC_OLD, FILE_TAC_NEW],
    // log49b：收到手机端任意消息即刷新握手活性时间戳（心跳/传输信号/图片分片都能保活，90s 内不误判"网桥未就绪"）
    [ACTIVE_TS_OLD, ACTIVE_TS_NEW]
  ];
  for (const [o, ne] of patches) {
    if (js.includes(o)) { js = js.split(o).join(ne); n++; }
  }
  const cs = replaceCreateSession(js);
  if (cs) {
    const pos = js.indexOf(cs.oldFn);
    js = js.substring(0, pos) + CS_REPLACEMENT + cs.tail;
    n++;
  }
  // getStatus / isReady 基于握手活性（替换整方法体）
  const gs = replaceExportMethod(js, 'getStatus', GETSTATUS_NEW);
  js = gs.js;
  if (gs.ok) n++;
  const ir = replaceExportMethod(js, 'isReady', ISREADY_NEW);
  js = ir.js;
  if (ir.ok) n++;
  // bridge-only：删除 directFetch 直连路径（手环不再有任何 system.fetch 网络请求）
  const df = removeFunction(js, 'function directFetch(options) {');
  js = df.js;
  if (df.ok) n++;
  return { js, n };
}

(async () => {
  const src = process.argv[2], dst = process.argv[3];
  const key = fs.readFileSync(process.argv[4], 'utf8');
  const cert = fs.readFileSync(process.argv[5], 'utf8');

  const buf = fs.readFileSync(src);
  console.log('源文件:', src, '大小:', buf.length);

  const { fileList, comment } = await createFileListFromZipBuffer(buf);
  console.log('条目数:', fileList.length);

  for (const f of fileList) {
    // log49：manifest 声明 system.file（手环端保存图片到 internal://files 需要该 feature）
    if (f.path === 'manifest.json') {
      let mj = f.content.toString('utf8');
      if (mj.includes('"name": "system.interconnect"') && !mj.includes('"name": "system.file"')) {
        mj = mj.split('"name": "system.interconnect"').join('"name": "system.interconnect"},\n    {\n      "name": "system.file"');
        f.content = Buffer.from(mj, 'utf8');
        console.log('[已补丁] manifest.json (features 增加 system.file)');
      }
      continue;
    }
    if (!f.path.endsWith('.js')) continue;
    let js = f.content.toString('utf8');
    const before = js.length;
    let r = { n: 0 };
    let pg = 0;
    let wb = 0;

    if (TARGETS.includes(f.path)) {
      // fetchbridge 补丁（所有 bundle）
      r = applyFetchBridgePatches(js);
      js = r.js;

      // 网桥页面 UI 补丁（仅 Bridge/bridge.js）
      if (f.path === 'Bridge/bridge.js') {
        for (const [o, ne] of [
          [PG_PRIVATE_OLD, PG_PRIVATE_NEW],
          [PG_ONINIT_OLD, PG_ONINIT_NEW],
          [PG_PUSHTEST_OLD, PG_PUSHTEST_NEW],
          [PG_TMPL_OLD, PG_TMPL_NEW],
          [PG_STYLE_OLD, PG_STYLE_NEW]
        ]) {
          if (js.includes(o)) { js = js.split(o).join(ne); pg++; }
        }
      } else if (f.path === 'Settings/settings.js') {
        // Settings 页面：网桥开关恢复 + 状态实时刷新 + 账号信息以手机端为准 + 退出登录双向同步
        if (js.includes(ST_ONINIT_OLD)) { js = js.split(ST_ONINIT_OLD).join(ST_ONINIT_NEW); pg++; }
        if (js.includes(ST_ACCOUNT_OLD)) { js = js.split(ST_ACCOUNT_OLD).join(ST_ACCOUNT_NEW); pg++; }
        if (js.includes(ST_LOGOUT_OLD)) { js = js.split(ST_LOGOUT_OLD).join(ST_LOGOUT_NEW); pg++; }
      } else if (f.path === 'app.js') {
        // App 入口：全局暴露网桥实例（全页面网桥化的前提）
        if (js.includes(BRIDGE_EXPOSE_OLD)) { js = js.split(BRIDGE_EXPOSE_OLD).join(BRIDGE_EXPOSE_NEW); pg++; }
        // log49c：App 级 onDestroy 不关闭网桥（右滑退出网桥就关掉）
        if (js.includes(APP_ONDESTROY_OLD)) { js = js.split(APP_ONDESTROY_OLD).join(APP_ONDESTROY_NEW); pg++; }
      }
    }

    // 纯直连 bundle（不含 fetchbridge 内联）：注入 __wbFetch 包装器（bridge-only：一律走互联转发，网桥未就绪只报错不直连）
    if (!js.includes('./src/common/scripts/fetchbridge.js') && js.includes(WB_FETCH_INJECT_OLD)) {
      js = js.split(WB_FETCH_INJECT_OLD).join(WB_FETCH_INJECT_NEW);
      js = js.split('_system2.default.fetch(').join('__wbFetch(');
      wb++;
    }

    // My 页面（官方 qrcode 组件登录适配）：createQr 改为请求手机生成二维码 + 模板 image→qrcode
    if (f.path === 'My/my.js') {
      // 1) createQr 函数整体替换（平衡括号匹配）
      const qMarker = 'createQr () {';
      const qIdx = js.indexOf(qMarker);
      if (qIdx >= 0) {
        let depth = 0, qi = qIdx;
        for (; qi < js.length; qi++) {
          const c = js[qi];
          if (c === '{') depth++;
          else if (c === '}') { depth--; if (depth === 0) { qi++; break; } }
        }
        js = js.substring(0, qIdx) + MY_CREATEQR_NEW + js.substring(qi);
        pg++;
      }
      // 2) 模板 image → 官方 qrcode 组件
      const qr = patchMyQrComponent(js);
      js = qr.js;
      pg += qr.n;
      // 3) onInit 自动请求二维码
      if (js.includes(MY_ONINIT_OLD)) { js = js.split(MY_ONINIT_OLD).join(MY_ONINIT_NEW); pg++; }
      // 4) fetchNav 直接走 Web nav（跳过必返 -400 的 app 端 myinfo，平衡括号匹配整体替换）
      const navMarker = 'fetchNav () {';
      const navIdx = js.indexOf(navMarker);
      if (navIdx >= 0) {
        let depth = 0, ni = navIdx;
        for (; ni < js.length; ni++) {
          const c = js[ni];
          if (c === '{') depth++;
          else if (c === '}') { depth--; if (depth === 0) { ni++; break; } }
        }
        js = js.substring(0, navIdx) + MY_FETCHNAV_NEW + js.substring(ni);
        pg++;
      }
      // 5) loadProfile/useSavedCookie 改用 bili_login_ok 判断登录态（登录凭证只存手机端，手环不再读取/持有 Cookie）
      if (js.includes(MY_LOADPROFILE_OLD)) { js = js.split(MY_LOADPROFILE_OLD).join(MY_LOADPROFILE_NEW); pg++; }
      if (js.includes(MY_USESAVED_OLD)) { js = js.split(MY_USESAVED_OLD).join(MY_USESAVED_NEW); pg++; }
      // log40b："我的"页头像分辨率缩减（64x64→32x32）
      if (js.includes(IMG_AVATAR64_OLD)) { js = js.split(IMG_AVATAR64_OLD).join(IMG_AVATAR64_NEW); pg++; }
    }

    // Home/HotSearch（log35）：网桥未就绪时不发请求（防握手期请求排队爆发），就绪后自动加载；onShow 返回时自动补发
    if (f.path === 'Home/home.js') {
      if (js.includes(HOME_ONINIT_OLD)) { js = js.split(HOME_ONINIT_OLD).join(HOME_ONINIT_NEW); pg++; }
      // log39b：首页直播列表/头像请求超时同步延长（大响应互联分块传输慢）
      if (js.includes(HOME_TO_LIVE_OLD)) { js = js.split(HOME_TO_LIVE_OLD).join(HOME_TO_LIVE_NEW); pg++; }
      if (js.includes(HOME_TO_AVATAR_OLD)) { js = js.split(HOME_TO_AVATAR_OLD).join(HOME_TO_AVATAR_NEW); pg++; }
      // log47：首页卡片/直播封面 URL 清洗（补 https: + 去已有 @ 参数）——必须先于 IMG_*_COVER 尺寸替换（锚点文本重叠）
      if (js.includes(HOME_SETITEM_OLD)) { js = js.split(HOME_SETITEM_OLD).join(HOME_SETITEM_NEW); pg++; }
      if (js.includes(HOME_LIVE_SETITEM_OLD)) { js = js.split(HOME_LIVE_SETITEM_OLD).join(HOME_LIVE_SETITEM_NEW); pg++; }
      // log49b：首页 checkLoginAndLoad 占位 cookie 丢失兜底（nav 确认真实登录态，补写占位标记）
      if (js.includes(HOME_LOGIN_OLD)) { js = js.split(HOME_LOGIN_OLD).join(HOME_LOGIN_NEW); pg++; }
      // log40b/log44：首页图片分辨率缩减（封面/直播封面/头像）+ 预载帧整体替换（去原图、只留最小缩略图）+ 预载数量 4→2
      if (js.includes(IMG_HOME_COVER_OLD)) { js = js.split(IMG_HOME_COVER_OLD).join(IMG_HOME_COVER_NEW); pg++; }
      if (js.includes(IMG_LIVE_COVER_OLD)) { js = js.split(IMG_LIVE_COVER_OLD).join(IMG_LIVE_COVER_NEW); pg++; }
      // log44：整体替换 buildFrameUrls（含原图 + @312w_210h/@208w_140h 缩略图）→ 只留 @96w_60h 最小缩略图
      if (js.includes(HOME_FRAMES_OLD)) { js = js.split(HOME_FRAMES_OLD).join(HOME_FRAMES_NEW); pg++; }
      if (js.includes(HOME_PRELOAD_OLD)) { js = js.split(HOME_PRELOAD_OLD).join(HOME_PRELOAD_NEW); pg++; }
      if (js.includes(IMG_AVATAR48_OLD)) { js = js.split(IMG_AVATAR48_OLD).join(IMG_AVATAR48_NEW); pg++; }
      if (js.includes(IMG_AVATAR64_OLD)) { js = js.split(IMG_AVATAR64_OLD).join(IMG_AVATAR64_NEW); pg++; }
      // log42：功能栏常驻（tab 栏不再随 showError 隐藏）
      if (js.includes(HOME_TAB_VIS_OLD)) { js = js.split(HOME_TAB_VIS_OLD).join(HOME_TAB_VIS_NEW); pg++; }
      // log42：头像主请求改 nav（myinfo 必返 -400）
      if (js.includes(AVATAR_URL_OLD)) { js = js.split(AVATAR_URL_OLD).join(AVATAR_URL_NEW); pg++; }
      // log42：首页推荐分页填充（ps=5 由手机端 BiliApiMap 控制，这里只改分页填充与翻页上限 1→4）
      if (js.includes(HOME_APPLY_OLD)) { js = js.split(HOME_APPLY_OLD).join(HOME_APPLY_NEW); pg++; }
      if (js.includes(HOME_REFRESH_MAX_OLD)) { js = js.split(HOME_REFRESH_MAX_OLD).join(HOME_REFRESH_MAX_NEW); pg++; }
      // log42（修正）：首页推荐列表底部翻页按钮 + 样式
      if (js.includes(HOME_PAGEBTN_OLD)) { js = js.split(HOME_PAGEBTN_OLD).join(HOME_PAGEBTN_NEW); pg++; }
      if (js.includes(HOME_STYLE_OLD)) { js = js.split(HOME_STYLE_OLD).join(HOME_STYLE_NEW); pg++; }
      // log42（修正2）：首页推荐上一页/下一页方法
      if (js.includes(HOME_PREVNEXT_OLD)) { js = js.split(HOME_PREVNEXT_OLD).join(HOME_PREVNEXT_NEW); pg++; }
      // log43（小米手环 10 Pro 兼容）：背景百分比→固定像素；JS 判断动态 classList 7→3（tab-bar×3 改 __ci__ 静态、section-more 静态）
      if (js.includes(BG_SIZE_OLD)) { js = js.split(BG_SIZE_OLD).join(BG_SIZE_NEW); pg++; }
      if (js.includes(TAB_BAR_LIVE_OLD)) { js = js.split(TAB_BAR_LIVE_OLD).join(TAB_BAR_LIVE_NEW); pg++; }
      if (js.includes(TAB_BAR_REC_OLD)) { js = js.split(TAB_BAR_REC_OLD).join(TAB_BAR_REC_NEW); pg++; }
      if (js.includes(TAB_BAR_HOT_OLD)) { js = js.split(TAB_BAR_HOT_OLD).join(TAB_BAR_HOT_NEW); pg++; }
      if (js.includes(SECTION_MORE_OLD)) { js = js.split(SECTION_MORE_OLD).join(SECTION_MORE_NEW); pg++; }
      // log42（修正3）：首页推荐隐藏未加载的空窗格（20 个 video-card 用 __ci__ shown 包裹，vXTitle 为空即隐藏）
      const hc = hideEmptyVideoCards(js);
      js = hc.js;
      pg += hc.n;
    } else if (f.path === 'HotSearch/hotsearch.js') {
      if (js.includes(HOT_ONINIT_OLD)) { js = js.split(HOT_ONINIT_OLD).join(HOT_ONINIT_NEW); pg++; }
      if (js.includes(HOT_FALLBACK_OLD)) { js = js.split(HOT_FALLBACK_OLD).join(HOT_FALLBACK_NEW); pg++; }
      if (js.includes(HOT_APPLY_OLD)) { js = js.split(HOT_APPLY_OLD).join(HOT_APPLY_NEW); pg++; }
    } else if (f.path === 'Live/live.js') {
      if (js.includes(LIVE_ONINIT_OLD)) { js = js.split(LIVE_ONINIT_OLD).join(LIVE_ONINIT_NEW); pg++; }
      // log39b：直播列表请求超时同步延长
      if (js.includes(HOME_TO_LIVE_OLD)) { js = js.split(HOME_TO_LIVE_OLD).join(HOME_TO_LIVE_NEW); pg++; }
      // log44：直播封面 312x176→160x90、主播头像 48x48→32x32
      if (js.includes(LIVE_COVER_OLD)) { js = js.split(LIVE_COVER_OLD).join(LIVE_COVER_NEW); pg++; }
      if (js.includes(LIVE_AVATAR_OLD)) { js = js.split(LIVE_AVATAR_OLD).join(LIVE_AVATAR_NEW); pg++; }
    } else if (f.path === 'Dynamic/dynamic.js') {
      // log42：动态分页（页码状态 + prev/next 方法 + 状态文本 + pager 按钮 + 样式）
      if (js.includes(DYN_DATA_OLD)) { js = js.split(DYN_DATA_OLD).join(DYN_DATA_NEW); pg++; }
      if (js.includes(DYN_NEXT_OLD)) { js = js.split(DYN_NEXT_OLD).join(DYN_NEXT_NEW); pg++; }
      if (js.includes(DYN_APPLY_STATUS_OLD)) { js = js.split(DYN_APPLY_STATUS_OLD).join(DYN_APPLY_STATUS_NEW); pg++; }
      if (js.includes(DYN_BTN_OLD)) { js = js.split(DYN_BTN_OLD).join(DYN_BTN_NEW); pg++; }
      if (js.includes(DYN_STYLE_OLD)) { js = js.split(DYN_STYLE_OLD).join(DYN_STYLE_NEW); pg++; }
    } else if (f.path === 'FavHistory/favhistory.js') {
      // log42：历史分页（游标状态 + 翻页方法 + 游标记录 + 状态文本 + 翻页按钮 + 样式）
      if (js.includes(HIS_DATA_OLD)) { js = js.split(HIS_DATA_OLD).join(HIS_DATA_NEW); pg++; }
      if (js.includes(HIS_NEXT_OLD)) { js = js.split(HIS_NEXT_OLD).join(HIS_NEXT_NEW); pg++; }
      if (js.includes(HIS_SUCCESS_OLD)) { js = js.split(HIS_SUCCESS_OLD).join(HIS_SUCCESS_NEW); pg++; }
      if (js.includes(HIS_APPLY_STATUS_OLD)) { js = js.split(HIS_APPLY_STATUS_OLD).join(HIS_APPLY_STATUS_NEW); pg++; }
      if (js.includes(HIS_BTN_OLD)) { js = js.split(HIS_BTN_OLD).join(HIS_BTN_NEW); pg++; }
      if (js.includes(HIS_STYLE_OLD)) { js = js.split(HIS_STYLE_OLD).join(HIS_STYLE_NEW); pg++; }
    } else if (f.path === 'Messages/messages.js') {
      // log42：私信会话分页（页码状态 + prev/next 方法 + 客户端分页 applyList + openItem 用当前页 + pager 按钮 + 样式）
      if (js.includes(MSG_DATA_OLD)) { js = js.split(MSG_DATA_OLD).join(MSG_DATA_NEW); pg++; }
      if (js.includes(MSG_NEXT_OLD)) { js = js.split(MSG_NEXT_OLD).join(MSG_NEXT_NEW); pg++; }
      if (js.includes(MSG_APPLY_OLD)) { js = js.split(MSG_APPLY_OLD).join(MSG_APPLY_NEW); pg++; }
      if (js.includes(MSG_OPEN_OLD)) { js = js.split(MSG_OPEN_OLD).join(MSG_OPEN_NEW); pg++; }
      if (js.includes(MSG_BTN_OLD)) { js = js.split(MSG_BTN_OLD).join(MSG_BTN_NEW); pg++; }
      if (js.includes(MSG_STYLE_OLD)) { js = js.split(MSG_STYLE_OLD).join(MSG_STYLE_NEW); pg++; }
      // log44：私信会话头像 64x64→32x32
      if (js.includes(MSG_AVATAR_OLD)) { js = js.split(MSG_AVATAR_OLD).join(MSG_AVATAR_NEW); pg++; }
    } else if (f.path === 'Video/video.js') {
      // log44：视频详情封面 312x210→160x100、UP 头像 64x64→32x32、图片帧轮播 26 项→5 项（移除 624x420 等大图）
      if (js.includes(VIDEO_COVER_OLD)) { js = js.split(VIDEO_COVER_OLD).join(VIDEO_COVER_NEW); pg++; }
      if (js.includes(VIDEO_AVATAR_OLD)) { js = js.split(VIDEO_AVATAR_OLD).join(VIDEO_AVATAR_NEW); pg++; }
      if (js.includes(VIDEO_FRAMES_OLD)) { js = js.split(VIDEO_FRAMES_OLD).join(VIDEO_FRAMES_NEW); pg++; }
    } else if (f.path === 'Search/search.js') {
      // log44：搜索封面 336x210→160x100、UP 头像 64x64→32x32
      if (js.includes(SEARCH_COVER_OLD)) { js = js.split(SEARCH_COVER_OLD).join(SEARCH_COVER_NEW); pg++; }
      if (js.includes(SEARCH_AVATAR_OLD)) { js = js.split(SEARCH_AVATAR_OLD).join(SEARCH_AVATAR_NEW); pg++; }
    } else if (f.path === 'UpHome/uphome.js') {
      // log44：UP 空间视频封面 96x64→64x43、头像 64x64→32x32
      if (js.includes(UPH_COVER_OLD)) { js = js.split(UPH_COVER_OLD).join(UPH_COVER_NEW); pg++; }
      if (js.includes(UPH_AVATAR_OLD)) { js = js.split(UPH_AVATAR_OLD).join(UPH_AVATAR_NEW); pg++; }
    } else if (f.path === 'Comments/comments.js') {
      // log45：评论区 pn 分页（数据状态 + 翻页方法 + pager 三件套 + 样式）
      if (js.includes(COMMENT_DATA_OLD)) { js = js.split(COMMENT_DATA_OLD).join(COMMENT_DATA_NEW); pg++; }
      if (js.includes(COMMENT_METHODS_OLD)) { js = js.split(COMMENT_METHODS_OLD).join(COMMENT_METHODS_NEW); pg++; }
      if (js.includes(COMMENT_PAGER_OLD)) { js = js.split(COMMENT_PAGER_OLD).join(COMMENT_PAGER_NEW); pg++; }
      if (js.includes(COMMENT_STYLE_OLD)) { js = js.split(COMMENT_STYLE_OLD).join(COMMENT_STYLE_NEW); pg++; }
    }

    // 语义化请求调用点改造（核心页面 url → action + params，URL 整合到手机端 BiliApiMap）
    const sem = SEMANTIC_PATCHES[f.path];
    if (sem) {
      for (const [o, ne] of sem) {
        if (js.includes(o)) { js = js.split(o).join(ne); pg++; }
      }
    }

    // 页面自动请求放行：cookie/csrf 拦截条件改为 false &&（进入页面即自动向手机发请求）
    const auto = AUTOLOAD_PATCHES[f.path];
    if (auto) {
      for (const [o, ne] of auto) {
        if (js.includes(o)) { js = js.split(o).join(ne); pg++; }
      }
    }

    // log46：B 站缩略图参数统一转 PNG —— 小米手环 10 Pro 等设备 image 组件处理 jpg 有 BUG（显示感叹号），
    // PNG 正常。只匹配 B 站缩略图参数后缀（_1e_1c/_1e_2c/_1e_3c/_2e_1c/_2e_2c/_2e_3c/_0e_0c.jpg），
    // 不误伤原始 .jpg 路径（如 bfs/archive/xxx.jpg）。覆盖全部 bundle（含未打补丁的 Dynamic/FavHistory 等）。
    let pngChanged = false;
    const pngJs = js.split('_1e_1c.jpg').join('_1e_1c.png')
                   .split('_1e_2c.jpg').join('_1e_2c.png')
                   .split('_1e_3c.jpg').join('_1e_3c.png')
                   .split('_2e_1c.jpg').join('_2e_1c.png')
                   .split('_2e_2c.jpg').join('_2e_2c.png')
                   .split('_2e_3c.jpg').join('_2e_3c.png')
                   .split('_0e_0c.jpg').join('_0e_0c.png');
    if (pngJs !== js) {
      js = pngJs;
      pngChanged = true;
    }

    if (r.n > 0 || pg > 0 || wb > 0 || pngChanged) {
      f.content = Buffer.from(js, 'utf8');
      console.log(`[已补丁] ${f.path} (fetchbridge补丁:${r.n}, 页面UI补丁:${pg}, 网桥注入:${wb}, jpg→png:${pngChanged}, 变化:${js.length - before}字节)`);
    }
  }

  // ===== 重新生成 hash.json + 重建 META-INF/CERT（关键修复）=====
  const digests = {};
  for (const f of fileList) {
    if (f.path.endsWith('/') || f.path === CERT_PATH) continue;
    digests[f.path] = crypto.createHash('sha256').update(f.content).digest('hex');
  }
  const hashJsonStr = JSON.stringify({ algorithm: 'SHA-256', digests });
  console.log('hash.json 重新生成, 条目数:', Object.keys(digests).length);

  const certZip = await createZipBufferFromFileList(
    [{ path: HASH_JSON, content: Buffer.from(hashJsonStr, 'utf8') }],
    null
  );
  let certReplaced = false;
  for (const f of fileList) {
    if (f.path === CERT_PATH) {
      f.content = certZip;
      certReplaced = true;
      console.log('META-INF/CERT 已替换为新签名包 (', certZip.length, '字节)');
      break;
    }
  }
  if (!certReplaced) {
    console.error('ERROR: 包内没有 META-INF/CERT 条目，中止');
    process.exit(1);
  }

  const rebuilt = await createZipBufferFromFileList(fileList, comment);
  console.log('zip 重建完成:', rebuilt.length, '字节');

  const signed = await signZipBufferForPackage(rebuilt, key, cert);
  fs.writeFileSync(dst, signed);
  console.log('重签完成 ->', dst, '大小:', signed.length);
})().catch(e => {
  console.error('失败:', e);
  process.exit(1);
});
