package moe.mcns.BiliMax.sync

/**
 * 「查看日志」页顶部状态卡的运行态快照。
 *
 * 只做汇总：数据源分别是 [TranscodeServer] 的在跑任务、[FilmPush] 的推送标志、
 * 桥接/隧道最近一次成功转发的时间戳，以及 [WearTransport] 的连接状态。
 * UI 侧每秒重新取一次快照即可，不需要注册额外回调。
 */
object SyncStatus {

    /** 卡片配色语义，UI 侧映射到 StateSuccess / StateWarning / StateDanger / StateAccent。 */
    enum class Tone { SUCCESS, WARNING, DANGER, ACCENT }

    data class Snapshot(
        val title: String,
        val detail: String,
        val tone: Tone,
        /** 仅转码任务有进度，null 表示不定进度（不显示进度条）。 */
        val progress: Float? = null
    )

    /** 判定「手表正在取用产物」的时间窗：产物是逐帧拉取的，窗口取大一点避免闪烁。 */
    private const val DELIVERY_WINDOW_MS = 5_000L

    @Volatile
    private var lastDeliveryAt = 0L

    @Volatile
    private var lastDeliveryLabel = ""

    /**
     * 桥接（[SfBridge]）与云端隧道（LoopbackMuxStream）成功转发一次产物后调用。
     * 高频调用，只更新两个 volatile 字段，不做任何同步。
     */
    fun noteDelivery(label: String) {
        lastDeliveryLabel = label
        lastDeliveryAt = System.currentTimeMillis()
    }

    /** 当前进程快照，按「转码 → 下发 → 取用 → 服务/连接」的优先级取第一条命中的。 */
    fun snapshot(wearState: WearState, deviceName: String?): Snapshot {
        TranscodeServer.runningJob()?.let { job ->
            val pct = (job.progress.coerceIn(0f, 1f) * 100).toInt()
            return Snapshot(
                title = "正在处理：${job.name}",
                detail = "${job.phase} · $pct%",
                tone = Tone.ACCENT,
                progress = job.progress.coerceIn(0f, 1f)
            )
        }

        if (FilmPush.isBusy()) {
            return Snapshot(
                title = "正在下发胶卷",
                detail = "正在把手表产物推送到手表，请保持连接",
                tone = Tone.ACCENT
            )
        }

        val now = System.currentTimeMillis()
        if (lastDeliveryAt > 0 && now - lastDeliveryAt <= DELIVERY_WINDOW_MS) {
            return Snapshot(
                title = "手表正在取用产物",
                detail = lastDeliveryLabel,
                tone = Tone.SUCCESS
            )
        }

        if (!TranscodeServer.isRunning()) {
            return Snapshot(
                title = "转码服务未启动",
                detail = "可在首页打开同步服务后手表才能拉取视频",
                tone = Tone.DANGER
            )
        }

        val name = deviceName?.takeIf { it.isNotEmpty() }
        return when (wearState) {
            WearState.CONNECTING -> Snapshot(
                title = "正在连接手表",
                detail = "正在建立消息通道…",
                tone = Tone.WARNING
            )
            WearState.CONNECTED -> Snapshot(
                title = "已连接，待命中",
                detail = if (name != null) "已连接：$name · 等待手表发起任务" else "等待手表发起任务",
                tone = Tone.SUCCESS
            )
            WearState.DISCONNECTED -> Snapshot(
                title = "等待手表连接",
                detail = "请确认手表端 BiliMax 已打开",
                tone = Tone.WARNING
            )
        }
    }
}
