package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource

/**
 * 把 B561 VHAL 车速接到 CarPlay 的 `$PASCD` 轮速句上。
 *
 * ## 为什么需要它
 *
 * CarPlay 的地图在没有 GPS 锁定时（隧道、地下车库）靠**轮速推算**（dead reckoning）
 * 保持位置连续。iAP2 用 `0xFFFB` 的 `$PASCD` 句传轮速，
 * 而 `VehicleSpeedLocationProvider`（shared 层）负责把它拼进 NMEA 流。
 *
 * 本项目在 v100 重做时把 `vehicleSpeedEnabled` 硬编码成 false，
 * 理由是「B561 无轮速数据源」—— 那个结论对**旧实现**成立
 * （旧实现的属性 ID 与方法名都是错的，见 [B561VehicleProperty]），
 * 但 VHAL 本身是通的。
 *
 * ## 单位换算
 *
 * VHAL 给的是 km/h，`$PASCD` 要的是 m/s —— 除以 3.6。
 * 历史上这里最容易出错：`$PASCD` 的速度字段单位是 m/s，
 * 直接把 km/h 塞进去会让地图把车速放大 3.6 倍。
 *
 * ## 纯逻辑、无 Android 依赖
 *
 * 本类只依赖 [B561VehicleProperty] 与 shared 层的纯数据类，
 * 所以可以在 JVM 单测里完整验证（见 `B561VehicleSpeedSourceTest`）。
 * 时间戳由外部注入，测试里用固定值。
 */
class B561VehicleSpeedSource(
    /** 当前车速（km/h）；null = 读不到。 */
    private val speedProvider: () -> Float?,
    /** 当前挡位原始值；null = 读不到。 */
    private val gearProvider: () -> Int?,
    /** 单调时钟毫秒；测试里注入固定值。 */
    private val elapsedMillis: () -> Long,
) : VehicleSpeedSource {

    @Volatile
    private var running = false

    /** 上一次 drain 之后新攒下的采样。 */
    private val pending = ArrayDeque<VehicleSpeedSample>()

    @Synchronized
    override fun start() {
        running = true
        // 启动时先取一次当前值，作为后续比较的基准。
        sample()
    }

    @Synchronized
    override fun stop() {
        running = false
        pending.clear()
    }

    /**
     * 记一个采样。
     *
     * 只在**车速真的变了**时记 —— 静止时 1 Hz 的重复读数会把
     * `$PASCD` 句灌成一串 0，既浪费带宽也没信息量。
     * 阈值取 0.05 km/h（约 0.014 m/s），远低于任何有意义的显示精度。
     */
    @Synchronized
    fun sample() {
        if (!running) return
        val speedKph = speedProvider() ?: return
        val metersPerSecond = speedKph / 3.6f
        val last = pending.lastOrNull()
        if (last != null &&
            kotlin.math.abs(last.metersPerSecond - metersPerSecond) < MIN_DELTA_METERS_PER_SECOND
        ) {
            return
        }
        pending.addLast(
            VehicleSpeedSample(
                elapsedMillis = elapsedMillis(),
                metersPerSecond = metersPerSecond.toDouble(),
            ),
        )
        // 兜底上限：万一读数抖动异常，句子里也不该堆积上百个点。
        while (pending.size > MAX_PENDING_SAMPLES) pending.removeFirst()
    }

    /**
     * 取走自上次调用以来的采样。
     *
     * 没有新采样时返回 null —— 协议栈据此判断「这一轮不发」，
     * 而不是发一个空句。
     */
    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        if (pending.isEmpty()) return null
        val samples = pending.toList()
        pending.clear()
        return VehicleSpeedReading(
            gear = gearOf(gearProvider()),
            samples = samples,
        )
    }

    /**
     * 挡位原始值 → `$PASCD` 用的挡位字母。
     *
     * `$PASCD` 的挡位字段是单个字母（P/R/N/D）。B561 上只坐实了 D 挡
     * （见 [B561VehicleProperty.GEAR_SELECTION]），其余挡位一律给 'D' ——
     * 因为 iAP2 侧对挡位的唯一用途是决定「是否倒车」，而
     * 「读不到挡位」与「不在倒车」对这个判断是等价的。
     * 真读到 R 挡时必须如实上报，所以单列出 R。
     */
    private fun gearOf(raw: Int?): VehicleGear = when (B561VehicleProperty.gearLabel(raw)) {
        'R' -> VehicleGear.REVERSE
        'P' -> VehicleGear.PARK
        'N' -> VehicleGear.NEUTRAL
        else -> VehicleGear.DRIVE
    }

    private companion object {
        /** 采样去重阈值（m/s），约 0.05 km/h。 */
        const val MIN_DELTA_METERS_PER_SECOND = 0.0139

        /** 单次 drain 最多带走的采样数。 */
        const val MAX_PENDING_SAMPLES = 64
    }
}
