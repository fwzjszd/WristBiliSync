package moe.mcns.BiliMax.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import moe.mcns.BiliMax.AppPrefs
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手表连接状态（转码服务页卡片展示用）。
 */
enum class WearState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * 消息通道所经配套服务（SDK 后端渠道）。
 */
enum class BackendChannel(val label: String) {
    ORONBOX("Oronbox"),
    MI_HEALTH("小米运动健康"),
    MI_WEAR("小米穿戴"),
    NONE("未检测到配套服务");

    companion object {
        /**
         * 按净室 SDK AUTO 策略检测当前可用渠道：
         * 优先 OronBox（org.zxor.oronbox），其次小米运动健康（com.mi.health），
         * 最后小米穿戴（com.xiaomi.wearable）。AAR manifest 已声明这三个包名可见性。
         */
        fun detect(context: Context): BackendChannel = try {
            val pm = context.packageManager
            when {
                isPackageInstalled(pm, "org.zxor.oronbox") -> ORONBOX
                isPackageInstalled(pm, "com.mi.health") -> MI_HEALTH
                isPackageInstalled(pm, "com.xiaomi.wearable") -> MI_WEAR
                else -> NONE
            }
        } catch (_: Throwable) {
            NONE
        }

        private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * 手表传输层：基于 Xiaomi Wearable SDK（XMS）维护与手表快应用的消息通道。
 *
 * 流程（参照 Shell++ WearMessageCenter 净室重写，业务协议替换为标准 SimpleFetch）：
 *   发现已连接节点 -> 校验/申请权限（设备管理 + 通知）-> 注册消息监听
 *   -> 按偏好拉起手表快应用（/pages/index）-> 主动发送 SF_HANDSHAKE；
 *   收到手表侧任意 SF 消息即视为通道可用（CONNECTED）。
 *
 * 约束：
 * - 工程未引入协程，状态通过 [OnStateChanged] 监听器在主线程回调；
 * - XMS 单条消息上限 4096 字节，超出文本按 {__c} 信封分片发送（兜底），
 *   接收侧支持 {__c} 分片重组（防御性兼容，BiliMax 手表端不发分片）；
 * - 心跳由手表端 SF_PING 驱动，本类不发自定义心跳；
 * - 失败/无节点时 5 秒后自动重试整个会话流程。
 */
class WearTransport private constructor(private val context: Context) : DeviceChannel {

    override val transport: TransportType get() = TransportType.XMS

    /** XMS 通道对端标识：当前节点 nodeId（未发现节点时为空串） */
    override val peerId: String get() = currentNode?.id.orEmpty()

    override fun isAlive(): Boolean = state == WearState.CONNECTED && currentNode != null

    fun interface OnStateChanged {
        fun onStateChanged(state: WearState, deviceName: String, detail: String)
    }

    private class ChunkBuffer(
        val total: Int,
        val parts: Array<ByteArray?>,
        var count: Int = 0,
        var updatedAt: Long = System.currentTimeMillis()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<OnStateChanged>()
    private val chunkBuffers = ConcurrentHashMap<String, ChunkBuffer>()

    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var authApi: AuthApi? = null
    private var currentNode: Node? = null
    private var hasPermission = false
    private var listenerRegistered = false
    private var listenerNodeId: String? = null

    @Volatile private var state: WearState = WearState.DISCONNECTED
    @Volatile private var deviceName: String = ""
    @Volatile private var detail: String = "未连接"
    @Volatile private var sessionInProgress = false

    // 已连接后的连续探测失败次数：瞬时失败保持 CONNECTED，
    // 超过 [MAX_RECHECK_FAILURES] 次（约 15s 重检均失败）才判定真实断开。
    @Volatile private var recheckFailures = 0

    private var reconnectRunnable: Runnable? = null
    // 上次拉起手表快应用的时间，用于节流（避免重连循环里反复重启快应用）
    private var lastLaunchAt = 0L
    // 握手发出后等待手表首条消息的超时任务：超时未收到任何回包则重扫，
    // 避免「握手发出时快应用未就绪 / 回包丢失」导致永久停在「连接中」。
    private var handshakeTimeoutRunnable: Runnable? = null

    /** 完整 SF 文本消息回调（由 SfBridge 注入，可能在 binder 线程触发） */
    @Volatile
    var messageHandler: ((String) -> Unit)? = null

    private val messageListener = OnMessageReceivedListener { nodeId, bytes ->
        try {
            val text = String(bytes, Charsets.UTF_8)
            // 严禁在本回调内同步调用任何 XMS 接口（sendMessage / addListener / removeListener）。
            // 本回调由配套服务（com.mi.health:device 的 WearableXmsService.handlePacket）在
            // RemoteCallbackList 广播回调过程中同步调用；若在回调内回发消息，会重入该服务的
            // 回调表广播并抛 IllegalStateException，其 finishBroadcast 不再执行，服务从此永久
            // 卡在 "beginBroadcast() called while already in a broadcast"，后续所有手表消息都
            // 无法投递 —— 手机端于是永远收不到消息、显示「未连接」。
            // 因此这里只转投主线程，让本回调立即返回，由主线程再处理与回发。
            mainHandler.post { onRawMessage(text) }
        } catch (t: Throwable) {
            Log.e(TAG, "message listener error", t)
        }
    }

    /**
     * App 启动时调用：获取三个 SDK API 并立即尝试建立会话。
     * 任何异常都不允许影响 App 启动。
     */
    fun initialize() {
        try {
            nodeApi = Wearable.getNodeApi(context)
            messageApi = Wearable.getMessageApi(context)
            authApi = Wearable.getAuthApi(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Wearable SDK init failed: ${t.message}")
            return
        }
        ensureConnection()
    }

    /** 转码服务页 onResume / 手动重连入口 */
    fun ensureConnection() {
        if (nodeApi == null || messageApi == null || authApi == null) {
            try {
                nodeApi = Wearable.getNodeApi(context)
                messageApi = Wearable.getMessageApi(context)
                authApi = Wearable.getAuthApi(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Wearable SDK unavailable: ${t.message}")
                updateState(WearState.DISCONNECTED, "", "穿戴服务不可用")
                return
            }
        }
        startSession()
    }

    fun addOnStateChanged(l: OnStateChanged) {
        listeners.addIfAbsent(l)
        l.onStateChanged(state, deviceName, detail)
    }

    fun removeOnStateChanged(l: OnStateChanged) {
        listeners.remove(l)
    }

    private fun emitLog(line: String) {
        Log.i(TAG, line)
        SyncLog.i(TAG, line)
    }

    fun currentState(): WearState = state

    /** 当前已发现/已连接手表的设备名（设备注册表登记时作为缺省名） */
    fun currentDeviceName(): String =
        currentNode?.name?.takeIf { it.isNotEmpty() } ?: deviceName

    /**
     * 发送一条完整文本消息。
     * XMS 单条消息上限为 4096 字节（按 UTF-8 字节计，中文 3 字节/字符），
     * 不超过 [CHUNK_SIZE] 字节直接直发；超出按 {__c} 信封顺序分片（兜底路径）。
     * 注意：BiliMax 手表端不处理 {__c}，SfBridge 必须保证每条 SF 消息 <= 4096 字节。
     */
    override fun sendText(text: String, onResult: ((Boolean) -> Unit)?) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= CHUNK_SIZE) {
            sendRaw(bytes, onResult)
        } else {
            sendChunked(text, onResult)
        }
    }

    private fun startSession() {
        synchronized(this) {
            if (sessionInProgress) return
            sessionInProgress = true
        }
        cancelReconnect()
        cancelHandshakeTimeout()
        refreshBackendChannel(context)
        // 已连接时的重扫为静默校验：不回退到「连接中」，避免界面闪烁「未连接」
        if (state != WearState.CONNECTED) {
            emitLog("重新扫描附近设备…")
            updateState(WearState.CONNECTING, deviceName, "正在连接手表…")
        } else {
            Log.d(TAG, "silent recheck: keep CONNECTED while rescanning")
        }
        discoverNode()
    }

    /**
     * 瞬时探测失败的滞回处理：已连接（或已有节点/监听）时不降级 UI，
     * 仅调度一次静默重检；只有从未建立过会话才判定为未连接。
     * 返回 true 表示已容错（调用方应结束本轮会话并等待重检）。
     */
    private fun tolerateTransient(reason: String): Boolean {
        val healthy = state == WearState.CONNECTED || currentNode != null || listenerRegistered
        if (healthy) {
            recheckFailures++
            Log.w(TAG, "transient failure #$recheckFailures ignored (keep state=$state): $reason")
            if (recheckFailures >= MAX_RECHECK_FAILURES) {
                // 连续多轮重检均失败：判定为真实断开，正常降级
                emitLog("连续 $recheckFailures 次未能确认手表连接，判定已断开")
                recheckFailures = 0
                return false
            }
            // 保持当前状态与设备登记，节流后静默重扫确认
            finishSession(false)
            return true
        }
        return false
    }

    private fun finishSession(success: Boolean) {
        synchronized(this) {
            sessionInProgress = false
        }
        if (success) {
            cancelReconnect()
        } else {
            scheduleReconnect()
        }
    }

    private fun discoverNode() {
        val api = nodeApi ?: run {
            markFailed("穿戴服务不可用")
            return
        }
        api.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isNullOrEmpty()) {
                    // 滞回：已连接时 connectedNodes 瞬时返回空（配套服务重绑/缓存刷新）
                    // 不代表手表断开，保持当前状态并静默重检，避免误显「未连接」。
                    if (tolerateTransient("connectedNodes empty")) {
                        emitLog("设备列表瞬时为空，保持当前连接并稍后复检")
                        return@addOnSuccessListener
                    }
                    unregisterListener()
                    currentNode = null
                    hasPermission = false
                    emitLog("未发现已连接的手表")
                    markDisconnected("未发现已连接的手表")
                    return@addOnSuccessListener
                }
                recheckFailures = 0
                val node = nodes[0]
                if (currentNode?.id != node.id) {
                    unregisterListener()
                    hasPermission = false
                    Log.d(TAG, "wear node found: ${node.name} (${node.id})")
                    emitLog("发现手表：${node.name}")
                }
                currentNode = node
                // 发现设备即上报设备名：连接中也明确显示「正在连接 <设备名>」，
                // 不再等到收到回包才让界面出现设备信息。
                updateState(
                    WearState.CONNECTING,
                    node.name.orEmpty(),
                    if (!node.name.isNullOrEmpty()) "正在连接 ${node.name}…" else "正在连接手表…"
                )
                ensurePermission()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "getConnectedNodes failed: ${e.message}")
                // 滞回：查询失败多为配套服务瞬时绑定问题，已连接时不降级
                if (!tolerateTransient("connectedNodes query failed: ${e.message}")) {
                    markFailed("获取手表设备失败")
                }
            }
    }

    private fun ensurePermission() {
        val node = currentNode ?: run {
            markFailed("当前设备为空")
            return
        }
        if (hasPermission) {
            prepareSession()
            return
        }
        val api = authApi ?: run {
            markFailed("授权服务不可用")
            return
        }
        api.checkPermissions(node.id, arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY))
            .addOnSuccessListener { results ->
                if (!isCurrentNode(node.id)) return@addOnSuccessListener
                if (results != null && results.all { it }) {
                    hasPermission = true
                    prepareSession()
                } else {
                    requestPermission(node)
                }
            }
            .addOnFailureListener {
                if (!isCurrentNode(node.id)) return@addOnFailureListener
                requestPermission(node)
            }
    }

    private fun requestPermission(node: Node) {
        val api = authApi ?: run {
            markFailed("授权服务不可用")
            return
        }
        api.requestPermission(node.id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
            .addOnSuccessListener { granted ->
                if (!isCurrentNode(node.id)) return@addOnSuccessListener
                if (granted != null && granted.isNotEmpty()) {
                    hasPermission = true
                    prepareSession()
                } else {
                    emitLog("已拒绝手表连接权限")
                    markFailed("已拒绝手表连接权限")
                }
            }
            .addOnFailureListener { e ->
                if (!isCurrentNode(node.id)) return@addOnFailureListener
                Log.w(TAG, "requestPermission failed: ${e.message}")
                markFailed("手表权限申请失败")
            }
    }

    private fun prepareSession() {
        registerListener { ok ->
            if (!ok) {
                markFailed("消息通道注册失败")
                return@registerListener
            }
            maybeLaunchWearApp { sendHandshake() }
        }
    }

    private fun registerListener(retried: Boolean = false, onDone: (Boolean) -> Unit) {
        val node = currentNode
        val api = messageApi
        if (node == null || api == null) {
            onDone(false)
            return
        }
        if (listenerRegistered && listenerNodeId == node.id) {
            onDone(true)
            return
        }
        // 不再「先 removeListener 再 addListener」：配套服务的回调表（RemoteCallbackList）
        // 在广播过程中被重入时会永久卡死（见 messageListener 注释），remove/add 的额外
        // churn 会放大触发概率。同一 listener 对象重复 add 由 SDK 去重，直接 add 即可。
        api.addListener(node.id, messageListener)
            .addOnSuccessListener {
                if (!isCurrentNode(node.id)) return@addOnSuccessListener
                listenerRegistered = true
                listenerNodeId = node.id
                Log.d(TAG, "message listener registered for ${node.id}")
                onDone(true)
            }
            .addOnFailureListener { e ->
                if (!isCurrentNode(node.id)) return@addOnFailureListener
                val msg = e.message.orEmpty()
                // SDK 的「已注册」是进程级静态标记（MessageApi.registeredListener），
                // 并不代表配套服务当前真的持有本回调：宿主进程重启/服务实例重建后
                // 其回调表为空，但静态标记仍是已注册，addListener 会一直失败于此。
                // 此时先 removeListener 复位静态标记，再重试一次真正的注册。
                if (!retried && msg.contains("registered")) {
                    Log.w(TAG, "addListener says already registered, resetting and retrying")
                    listenerRegistered = false
                    listenerNodeId = null
                    api.removeListener(node.id)
                    mainHandler.postDelayed({
                        if (isCurrentNode(node.id)) registerListener(true, onDone)
                    }, REGISTER_RETRY_DELAY_MS)
                    return@addOnFailureListener
                }
                Log.w(TAG, "addListener failed: $msg")
                listenerRegistered = false
                if (listenerNodeId == node.id) listenerNodeId = null
                onDone(false)
            }
    }

    private fun unregisterListener() {
        val nodeId = listenerNodeId ?: currentNode?.id ?: return
        val api = messageApi ?: return
        // 先本地复位再 remove：配套服务已卡死时 removeListener 会失败，
        // 若仍保留 listenerRegistered=true，下一轮会话会误判「已注册」而不再 addListener，
        // 导致宿主恢复后依然收不到任何消息（永久「未连接」）。
        listenerRegistered = false
        listenerNodeId = null
        try {
            api.removeListener(nodeId)
                .addOnFailureListener { Log.w(TAG, "removeListener failed: ${it.message}") }
        } catch (t: Throwable) {
            Log.w(TAG, "removeListener error: ${t.message}")
        }
    }

    private fun maybeLaunchWearApp(onComplete: () -> Unit) {
        if (!AppPrefs.get(context).autoLaunchWearApp) {
            onComplete()
            return
        }
        val node = currentNode
        val api = nodeApi
        if (node == null || api == null) {
            onComplete()
            return
        }
        // 节流：握手超时重连时不要每次都重新拉起快应用，否则会反复重启手表端页面，
        // 快应用永远来不及完成 onopen/ready 上报，形成「拉起→超时→再拉起」死循环。
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < LAUNCH_THROTTLE_MS) {
            onComplete()
            return
        }
        lastLaunchAt = now
        api.launchWearApp(node.id, WEAR_APP_ENTRY_URI)
            .addOnSuccessListener {
                if (!isCurrentNode(node.id)) return@addOnSuccessListener
                Log.d(TAG, "wear app launched")
                onComplete()
            }
            .addOnFailureListener { e ->
                if (!isCurrentNode(node.id)) return@addOnFailureListener
                // 拉起失败（手表端已在前台/未安装宿主）不阻断通道，继续握手
                Log.w(TAG, "launchWearApp failed (continue): ${e.message}")
                onComplete()
            }
    }

    private fun sendHandshake() {
        // 主动发 SF_HANDSHAKE；手表在线时会回 SF_HANDSHAKE_ACK，
        // 即便 ACK 丢失，手表随后的 SF_PING / SF_REQUEST 也会确认通道。
        // 注意：发送成功只代表消息已投递给配套服务，不代表手表快应用已收到；
        // 真正的「已连接」以收到手表首条消息（onRawMessage）为准，
        // 因此这里不能 finishSession(true)，否则回包丢失时会永久停在「连接中」。
        //
        // 快应用冷启动需要时间，单次握手极易落在「快应用还没 onopen」的窗口内，
        // 故按 HANDSHAKE_RETRY_INTERVAL_MS 间隔重发若干次（已连接则立即停止）。
        val msg = JSONObject().apply {
            put("type", SfProtocol.T_HANDSHAKE)
            put("status", "OK")
            // data 携带手机局域网服务器地址：SDK 通道用于与手表确认地址，
            // 支持 fetch 的手表直连该地址；手表若先发起握手，SfBridge 回的 ACK 带同样数据。
            put("data", SfProtocol.handshakeData())
        }
        val payload = msg.toString().toByteArray(Charsets.UTF_8)
        var attempts = 0
        fun sendAttempt() {
            if (state == WearState.CONNECTED) return
            attempts++
            sendRaw(payload) { ok ->
                if (ok) {
                    if (attempts == 1) scheduleHandshakeTimeout()
                    if (attempts < HANDSHAKE_MAX_ATTEMPTS) {
                        mainHandler.postDelayed({ sendAttempt() }, HANDSHAKE_RETRY_INTERVAL_MS)
                    }
                } else {
                    // 发送失败多为配套服务瞬时抖动：已连接时保持状态静默重检
                    if (!tolerateTransient("handshake send failed")) {
                        markFailed("握手消息发送失败")
                    }
                }
            }
        }
        sendAttempt()
    }

    /**
     * 握手发出后等待手表首条消息：[HANDSHAKE_TIMEOUT_MS] 内没收到任何回包，
     * 说明快应用未就绪或链路异常，复位本轮会话后自动重连（节流 [RECONNECT_DELAY_MS]，
     * 与断连重连共用同一调度，避免无间隔密集重扫耗电/刷屏）。
     */
    private fun scheduleHandshakeTimeout() {
        cancelHandshakeTimeout()
        val runnable = Runnable {
            handshakeTimeoutRunnable = null
            if (state == WearState.CONNECTED) return@Runnable
            Log.w(TAG, "handshake timeout, auto reconnect")
            // 保持「连接中」态：后台会自动重连，界面持续展示连接中而非闪断为未连接
            updateState(WearState.CONNECTING, deviceName, "连接超时，正在自动重连…")
            emitLog("连接超时，${RECONNECT_DELAY_MS / 1000} 秒后自动重试…")
            // 不在此注销监听（避免宿主广播重入），但必须复位本地注册标记：
            // 宿主进程可能已重启、回调表已丢失，下一轮必须真正重发 addListener 才能恢复投递。
            // 若 SDK 静态标记仍为「已注册」，registerListener 会走 remove + 重试分支。
            listenerRegistered = false
            listenerNodeId = null
            hasPermission = false
            // finishSession(false) 复位会话守卫并调度节流自动重连
            finishSession(false)
        }
        handshakeTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, HANDSHAKE_TIMEOUT_MS)
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        handshakeTimeoutRunnable = null
    }

    private fun sendRaw(bytes: ByteArray, onResult: ((Boolean) -> Unit)? = null) {
        val node = currentNode
        val api = messageApi
        if (node == null || api == null) {
            onResult?.invoke(false)
            return
        }
        api.sendMessage(node.id, bytes)
            .addOnSuccessListener { onResult?.invoke(true) }
            .addOnFailureListener { e ->
                Log.w(TAG, "sendMessage failed: ${e.message}")
                onResult?.invoke(false)
            }
    }

    private fun sendChunked(message: String, onResult: ((Boolean) -> Unit)? = null) {
        val id = "chunk-${System.currentTimeMillis()}"
        val chunks = message.chunked(CHUNK_PART_SIZE)
        val total = chunks.size
        fun sendNext(index: Int) {
            if (index >= total) {
                onResult?.invoke(true)
                return
            }
            val envelope = JSONObject().apply {
                put("__c", true)
                put("id", id)
                put("i", index)
                put("t", total)
                put("d", chunks[index])
            }
            sendRaw(envelope.toString().toByteArray(Charsets.UTF_8)) { ok ->
                if (!ok) {
                    onResult?.invoke(false)
                } else {
                    sendNext(index + 1)
                }
            }
        }
        sendNext(0)
    }

    private fun onRawMessage(text: String) {
        if (!text.trim().startsWith("{")) return
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "bad json message: ${text.take(80)}")
            return
        }
        if (json.optBoolean("__c", false)) {
            handleChunk(json)
            return
        }
        // 收到手表任意合法 SF 消息即说明通道双向可用，正式置为已连接。
        recheckFailures = 0
        if (state != WearState.CONNECTED) {
            cancelHandshakeTimeout()
            refreshBackendChannel(context)
            val node = currentNode
            val name = node?.name?.takeIf { it.isNotEmpty() } ?: deviceName
            emitLog(if (name.isNotEmpty()) "已连接手表：$name" else "已连接手表")
            updateState(WearState.CONNECTED, name, if (name.isNotEmpty()) "已连接 · $name" else "已连接")
            finishSession(true)
        }
        try {
            messageHandler?.invoke(text)
        } catch (t: Throwable) {
            Log.e(TAG, "messageHandler error", t)
        }
    }

    private fun handleChunk(json: JSONObject) {
        try {
            val id = json.optString("id")
            val index = json.optInt("i", -1)
            val total = json.optInt("t", -1)
            val data = json.optString("d")
            if (id.isEmpty() || index < 0 || total !in 1..MAX_TOTAL_CHUNKS || index >= total || data.isEmpty()) {
                return
            }
            pruneStaleChunkBuffers()
            if (chunkBuffers.size >= MAX_CONCURRENT_CHUNKS && !chunkBuffers.containsKey(id)) return
            val partBytes = if (json.optString("e") == "b64") {
                Base64.decode(data, Base64.DEFAULT)
            } else {
                data.toByteArray(Charsets.UTF_8)
            }
            val buffer = chunkBuffers.getOrPut(id) {
                ChunkBuffer(total = total, parts = arrayOfNulls(total))
            }
            if (buffer.total != total) {
                chunkBuffers.remove(id)
                return
            }
            var fullText: String? = null
            synchronized(buffer) {
                buffer.updatedAt = System.currentTimeMillis()
                if (buffer.parts[index] == null) {
                    buffer.parts[index] = partBytes
                    buffer.count++
                }
                if (buffer.count == buffer.total) {
                    chunkBuffers.remove(id)
                    val out = ByteArrayOutputStream()
                    for (part in buffer.parts) {
                        if (part != null) out.write(part)
                    }
                    fullText = out.toByteArray().toString(Charsets.UTF_8)
                }
            }
            fullText?.let { onRawMessage(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "handleChunk failed: ${t.message}")
        }
    }

    private fun pruneStaleChunkBuffers() {
        val now = System.currentTimeMillis()
        chunkBuffers.entries.removeIf { (_, buffer) ->
            now - buffer.updatedAt > CHUNK_BUFFER_TTL_MS
        }
    }

    private fun isCurrentNode(nodeId: String): Boolean = currentNode?.id == nodeId

    private fun markDisconnected(reason: String) {
        cancelHandshakeTimeout()
        refreshBackendChannel(context)
        updateState(WearState.DISCONNECTED, "", reason)
        // 通道离线：从设备注册表注销（重连握手后会重新登记）
        try { DeviceRegistry.unregister(this) } catch (_: Throwable) {}
        finishSession(false)
    }

    private fun markFailed(reason: String) {
        cancelHandshakeTimeout()
        refreshBackendChannel(context)
        updateState(WearState.DISCONNECTED, "", reason)
        try { DeviceRegistry.unregister(this) } catch (_: Throwable) {}
        finishSession(false)
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        val runnable = Runnable {
            reconnectRunnable = null
            startSession()
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun updateState(newState: WearState, name: String, newDetail: String) {
        state = newState
        if (name.isNotEmpty()) deviceName = name
        detail = newDetail
        val snapshot = listeners.toList()
        mainHandler.post {
            for (l in snapshot) {
                try {
                    l.onStateChanged(state, deviceName, detail)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun destroy() {
        cancelReconnect()
        cancelHandshakeTimeout()
        chunkBuffers.clear()
        unregisterListener()
        currentNode = null
        hasPermission = false
        listeners.clear()
        updateState(WearState.DISCONNECTED, "", "已停止")
    }

    companion object {
        private const val TAG = "BiliMax-Wear"
        private const val CHUNK_SIZE = 4096
        // 按字符切片：最坏 3 字节/字符（中文），1300 字符 ≈ 3900 字节，加信封仍 < 4096 字节
        private const val CHUNK_PART_SIZE = 1300
        private const val MAX_CONCURRENT_CHUNKS = 16
        private const val MAX_TOTAL_CHUNKS = 16384
        private const val CHUNK_BUFFER_TTL_MS = 60_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        // 握手发出后等待手表首条回包的超时（留足快应用启动/前台切换时间）
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
        // 快应用冷启动期间单次握手容易丢失：按 1.5s 间隔重发，最多 3 次
        private const val HANDSHAKE_RETRY_INTERVAL_MS = 1_500L
        private const val HANDSHAKE_MAX_ATTEMPTS = 3
        // 「已注册」误判时：remove 复位 SDK 静态标记后延迟重试注册的间隔
        private const val REGISTER_RETRY_DELAY_MS = 300L
        // 拉起快应用的节流窗口：重连循环内不再反复重启手表端页面
        private const val LAUNCH_THROTTLE_MS = 30_000L
        // 已连接后允许的连续静默重检失败次数（每次间隔 RECONNECT_DELAY_MS），
        // 超过后才真实降级为未连接——容忍配套服务瞬时抖动导致的空节点/查询失败
        private const val MAX_RECHECK_FAILURES = 3
        private const val WEAR_APP_ENTRY_URI = "/pages/index"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: WearTransport? = null

        /** 当前消息通道所经配套服务渠道（Oronbox / 小米运动健康 / 小米穿戴） */
        @Volatile
        @JvmField
        var backendChannel: BackendChannel = BackendChannel.NONE

        /**
         * 按持久化偏好配置净室 SDK 后端（AUTO/小米/Oronbox）。
         * 必须在首次 [Wearable.getNodeApi]/[Wearable.getMessageApi]/[Wearable.getAuthApi]
         * 之前调用（App.onCreate 早于任何传输层初始化）。
         */
        fun applyBackendConfig(context: Context) {
            try {
                val backend = when (AppPrefs.get(context).sdkBackend) {
                    AppPrefs.SDK_BACKEND_XIAOMI -> org.zxor.oronbox.xms.WearableBackend.XIAOMI
                    AppPrefs.SDK_BACKEND_ORONBOX -> org.zxor.oronbox.xms.WearableBackend.ORONBOX
                    else -> org.zxor.oronbox.xms.WearableBackend.AUTO
                }
                org.zxor.oronbox.xms.WearableBackendConfig.setBackend(
                    context.applicationContext, backend
                )
            } catch (t: Throwable) {
                Log.w(TAG, "applyBackendConfig failed: ${t.message}")
            }
        }

        fun get(context: Context): WearTransport =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    backendChannel = resolveBackendChannel(app)
                    WearTransport(app).also { instance = it }
                }
            }

        /**
         * 刷新渠道显示：以净室 SDK 实际绑定的配套服务包名为准
         * （SDK 内部经过 resolveService + bind + getConnectedNodes 三级探测，
         * 装了 Oronbox 但它未连手表时会回落到小米系通道）；尚未绑定任何服务时，
         * 显式选择显示所选渠道，AUTO 回落到包安装探测。
         */
        fun refreshBackendChannel(context: Context) {
            backendChannel = resolveBackendChannel(context.applicationContext)
        }

        private fun resolveBackendChannel(app: Context): BackendChannel {
            try {
                val bound = org.zxor.oronbox.xms.internal.WearableClient.get(app).boundPackage
                channelOfPackage(bound)?.let { return it }
            } catch (_: Throwable) {
            }
            return when (val pref = try {
                AppPrefs.get(app).sdkBackend
            } catch (_: Throwable) {
                AppPrefs.SDK_BACKEND_AUTO
            }) {
                AppPrefs.SDK_BACKEND_ORONBOX -> BackendChannel.ORONBOX
                AppPrefs.SDK_BACKEND_XIAOMI -> BackendChannel.MI_HEALTH
                else -> BackendChannel.detect(app)
            }
        }

        private fun channelOfPackage(pkg: String?): BackendChannel? = when (pkg) {
            "org.zxor.oronbox" -> BackendChannel.ORONBOX
            "com.mi.health" -> BackendChannel.MI_HEALTH
            "com.xiaomi.wearable" -> BackendChannel.MI_WEAR
            else -> null
        }
    }
}
