package moe.mcns.BiliMax.sync

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 设备传输通道抽象。
 *
 * 当前唯一实现是 [WearTransport]（XMS 消息通道，XMS 场景下手机同时只与一块手表
 * 保持会话，peer 固定为该通道）。未来 WebSocket 网关接入时，每个 WebSocket 连接
 * 提供一个本接口实现，即可让 SF 桥接、胶卷推送、转码任务分发等业务逻辑零改动地
 * 复用到多设备场景。
 */
interface DeviceChannel {
    /** 通道类型（XMS 消息通道 / 未来的 WebSocket 等） */
    val transport: TransportType

    /** 通道对端标识：XMS 为 nodeId；WebSocket 为连接 id */
    val peerId: String

    /** 通道是否仍然在线可用 */
    fun isAlive(): Boolean

    /** 发送一条完整文本消息（JSON 信封），结果经 onResult 回调（成功=已投递） */
    fun sendText(text: String, onResult: ((Boolean) -> Unit)? = null)
}

enum class TransportType {
    /** Xiaomi Wearable SDK 配套 App 消息通道（当前实现） */
    XMS,

    /**
     * 预留：未来局域网/公网 WebSocket 通道。
     * 手表（含其它品牌手表）作为 WS 客户端连入手机服务器，
     * 握手时上报设备信息，服务器按设备归属与负载分配资源。
     */
    WEBSOCKET
}

/** 设备归属平台（握手 data.device.platform） */
enum class DevicePlatform(val key: String, val label: String) {
    VELA("vela", "Vela 手表"),
    UNKNOWN("unknown", "未知设备");

    companion object {
        fun fromKey(key: String?): DevicePlatform =
            entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/**
 * 已注册设备快照（不可变），供 UI 状态展示与服务器状态接口使用。
 */
data class DeviceInfo(
    /** 稳定设备 id：优先握手 device.deviceId，缺省回退通道 peerId */
    val deviceId: String,
    val name: String,
    val platform: DevicePlatform,
    val model: String,
    val transport: TransportType,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenShape: String,
    /** 本端 @system.fetch 直连能力（胶卷推送据此选直连/消息通道） */
    val fetchSupported: Boolean,
    /** 本端是否支持 SF 桥接代发 */
    val bridgeSupported: Boolean,
    val connectedAt: Long,
    val lastSeenAt: Long,
    val activeJobs: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("name", name)
        .put("platform", platform.key)
        .put("model", model)
        .put("transport", transport.name)
        .put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight)
        .put("screenShape", screenShape)
        .put("fetchSupported", fetchSupported)
        .put("bridgeSupported", bridgeSupported)
        .put("connectedAt", connectedAt)
        .put("lastSeenAt", lastSeenAt)
        .put("activeJobs", activeJobs)
}

/**
 * 设备注册表：所有传输通道（当前 XMS，未来 WebSocket）统一在此登记。
 *
 * 预留的多设备能力：
 *  1. 握手登记：任意通道收到 SF_HANDSHAKE / SF_HANDSHAKE_ACK 时调 [registerOrUpdate]，
 *     解析握手 data 中的设备信息（平台/型号/屏幕/fetch 能力）；WebSocket 通道未来
 *     直接复用同一握手 JSON 结构，无需新增协议。
 *  2. 归属识别：按 platform / deviceId 区分设备归属，未来可据此把任务路由到指定手表。
 *  3. 负载分配：[activeJobs] 记录每设备在途任务数，[pickLeastLoaded] 供未来多设备
 *     同时在线时按负载挑目标设备（当前 XMS 单设备场景直接返回唯一在线设备）。
 *  4. 资源钩子：[onDeviceRegistered] / [onDeviceLost] 监听器供未来 WebSocket 网关、
 *     转码调度器订阅设备上下线事件。
 *
 * 线程安全：全部结构并发容器 + 原子计数，任意通道线程可直接调用。
 */
object DeviceRegistry {

    private const val TAG = "BiliMax-DeviceReg"

    class RegisteredDevice(
        @Volatile var channel: DeviceChannel,
        val deviceId: String,
        @Volatile var name: String,
        @Volatile var platform: DevicePlatform,
        @Volatile var model: String,
        @Volatile var screenWidth: Int,
        @Volatile var screenHeight: Int,
        @Volatile var screenShape: String,
        @Volatile var fetchSupported: Boolean,
        @Volatile var bridgeSupported: Boolean,
        val connectedAt: Long,
        @Volatile var lastSeenAt: Long
    ) {
        val activeJobs = AtomicInteger(0)

        fun snapshot(): DeviceInfo = DeviceInfo(
            deviceId = deviceId,
            name = name,
            platform = platform,
            model = model,
            transport = channel.transport,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenShape = screenShape,
            fetchSupported = fetchSupported,
            bridgeSupported = bridgeSupported,
            connectedAt = connectedAt,
            lastSeenAt = lastSeenAt,
            activeJobs = activeJobs.get()
        )
    }

    /** 设备事件监听器（预留：WebSocket 网关/转码调度器订阅上下线） */
    interface DeviceListener {
        fun onDeviceRegistered(device: RegisteredDevice) {}
        fun onDeviceUpdated(device: RegisteredDevice) {}
        fun onDeviceLost(device: RegisteredDevice) {}
    }

    private val devices = ConcurrentHashMap<String, RegisteredDevice>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<DeviceListener>()

    fun addListener(l: DeviceListener) { listeners.addIfAbsent(l) }
    fun removeListener(l: DeviceListener) { listeners.remove(l) }

    /**
     * 握手登记/更新。由任意通道（XMS 的 SfBridge、未来 WebSocket 网关）在收到
     * SF_HANDSHAKE / SF_HANDSHAKE_ACK 时调用。
     *
     * @param channel      消息来源通道
     * @param handshakeData 握手信封的 data 对象（含 bridge/fetch/device 字段）
     * @param fallbackName 设备名缺省值（如 XMS Node 名）
     */
    fun registerOrUpdate(
        channel: DeviceChannel,
        handshakeData: JSONObject?,
        fallbackName: String = ""
    ): RegisteredDevice {
        val dev = handshakeData?.optJSONObject("device")
        val rawId = dev?.optString("deviceId").orEmpty()
        val deviceId = rawId.ifBlank { channel.peerId }
        val now = System.currentTimeMillis()

        val existing = devices[deviceId]
        if (existing != null) {
            existing.lastSeenAt = now
            applyHandshakeFields(existing, channel, handshakeData, dev, fallbackName)
            listeners.forEach { runCatching { it.onDeviceUpdated(existing) } }
            return existing
        }

        val reg = RegisteredDevice(
            channel = channel,
            deviceId = deviceId,
            name = fallbackName,
            platform = DevicePlatform.fromKey(dev?.optString("platform")),
            model = dev?.optString("model").orEmpty(),
            screenWidth = dev?.optInt("screenWidth") ?: 0,
            screenHeight = dev?.optInt("screenHeight") ?: 0,
            screenShape = dev?.optString("screenShape").orEmpty(),
            fetchSupported = handshakeData?.optBoolean("fetch", false) ?: false,
            bridgeSupported = handshakeData?.optBoolean("bridge", false) ?: false,
            connectedAt = now,
            lastSeenAt = now
        )
        devices[deviceId] = reg
        Log.i(TAG, "设备登记：$deviceId（${reg.platform.label}/${reg.model}，" +
            "fetch=${reg.fetchSupported}，通道=${channel.transport}）")
        listeners.forEach { runCatching { it.onDeviceRegistered(reg) } }
        return reg
    }

    private fun applyHandshakeFields(
        reg: RegisteredDevice,
        channel: DeviceChannel,
        handshakeData: JSONObject?,
        dev: JSONObject?,
        fallbackName: String
    ) {
        // 通道可能因重连/换路而更换（XMS 重扫、WebSocket 断线重连）
        reg.channel = channel
        if (dev != null) {
            dev.optString("platform").takeIf { it.isNotBlank() }?.let {
                reg.platform = DevicePlatform.fromKey(it)
            }
            dev.optString("model").takeIf { it.isNotBlank() }?.let { reg.model = it }
            dev.optString("screenShape").takeIf { it.isNotBlank() }?.let { reg.screenShape = it }
            dev.optInt("screenWidth").takeIf { it > 0 }?.let { reg.screenWidth = it }
            dev.optInt("screenHeight").takeIf { it > 0 }?.let { reg.screenHeight = it }
            dev.optString("deviceId").takeIf { it.isNotBlank() }?.let { /* id 不可变 */ }
        }
        // 注意：org.json 的 optBoolean(name) 在**缺键时返回 false**（不是 null），所以原写法
        // `handshakeData?.optBoolean("fetch")?.let { ... }` 会把手表未声明的能力覆盖成 false。
        // 改为仅当信封里真的带了该键时才更新（r92：修复 DEVICEINFO 等非握手信封把 fetch 打假）
        if (handshakeData?.has("fetch") == true) reg.fetchSupported = handshakeData.optBoolean("fetch")
        if (handshakeData?.has("bridge") == true) reg.bridgeSupported = handshakeData.optBoolean("bridge")
        if (fallbackName.isNotBlank() && reg.name.isBlank()) reg.name = fallbackName
    }

    /** 通道离线/断开时注销（XMS 重扫失败、WebSocket 断连） */
    fun unregister(channel: DeviceChannel) {
        val it = devices.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            // peerId 为空串时不能参与「按通道匹配」：两台设备都还没发现节点时 peerId 都是空串，
            // 会把别人的记录一并注销；空串只允许对象同一性匹配（r92）
            if (entry.value.channel === channel ||
                (channel.peerId.isNotBlank() && entry.value.channel.peerId == channel.peerId)
            ) {
                it.remove()
                Log.i(TAG, "设备离线：${entry.key}")
                listeners.forEach { l -> runCatching { l.onDeviceLost(entry.value) } }
            }
        }
    }

    fun get(deviceId: String): RegisteredDevice? = devices[deviceId]

    /**
     * 按「传输通道的 peerId」（XMS nodeId）查设备。
     *
     * 注册表的键是表端自报的 `device.deviceId`，与 XMS nodeId 不同源；调用方（如 FilmPush）
     * 手里往往只有 peerId，直接当键查必然落空。这里先精确查一次，再按通道 peerId 兜底扫一遍，
     * 让两类标识都能命中同一条设备记录（r92）。
     */
    fun findByPeer(peerId: String): RegisteredDevice? {
        if (peerId.isBlank()) return null
        devices[peerId]?.let { return it }
        return devices.values.firstOrNull { it.channel.peerId == peerId }
    }

    fun all(): List<DeviceInfo> = devices.values.map { it.snapshot() }

    /** 全部仍在线的传输通道（广播地址信息用，按通道去重） */
    fun allChannels(): List<DeviceChannel> =
        devices.values
            .filter { it.channel.isAlive() }
            .map { it.channel }
            .distinctBy { it.peerId }

    fun onlineCount(): Int = devices.size

    /**
     * 负载分配钩子：返回在线且在途任务最少的设备。
     * 当前 XMS 单设备场景即返回唯一设备；未来多块手表/多品牌手表同时在线时，
     * 转码/推送调度按此选择目标设备。
     */
    fun pickLeastLoaded(platform: DevicePlatform? = null): RegisteredDevice? =
        devices.values
            .filter { it.channel.isAlive() && (platform == null || it.platform == platform) }
            .minByOrNull { it.activeJobs.get() }

    /** 任务开始/结束计数（FilmPush、未来 WebSocket 转码分发调用） */
    fun beginJob(deviceId: String) { devices[deviceId]?.activeJobs?.incrementAndGet() }
    fun endJob(deviceId: String) { devices[deviceId]?.activeJobs?.decrementAndGet() }
}
