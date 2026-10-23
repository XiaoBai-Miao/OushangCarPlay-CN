package com.shilapi.xcertplay.vehicle

import kotlin.math.abs
import kotlin.math.sin

/**
 * B561 车辆数据**模拟器** —— 测试专用，不在真车上启用。
 *
 * ## 为什么需要它
 *
 * v126 实机验证暴露了两个未闭环项：
 * 1. iPhone 只发了 CAF `configRequest`，**从未发过特征读取**；
 * 2. Ultra `displayPlugins` 绘图路径**从未触发**。
 *
 * 两者的共同根因：真车上数据源（logcat 解析 + VHAL）有值，
 * 而**脱离原车车机**（拿手机当车机测）时两条源都是空的 ——
 * `B561VehicleDataSource.currentUltraGauges()` 返回全 null 的
 * [B561UltraGauges]，`onGaugesChanged` 里 `previous == value`
 * 永远成立，**一次都不会 publish**。iPhone 没有数据可画，
 * 自然不会去画。
 *
 * 本类用一条 60 秒的驾驶工况（怠速 → 加速 → 匀速 → 减速 → 回怠速）
 * 生成**物理上自洽**的读数，喂给同一条数据链路：
 * ```
 * simulator.advance(dt) → B561VehicleDataSource.pushReading()
 *   → onReadingChanged → onGaugesChanged → CAF publish → iPhone
 * ```
 * 下游（自绘仪表、CAF、iAP2 snapshot）**零改动**，验证的是端到端真链路。
 *
 * ## 数值标定（B561 纯电，参照 v121 属性表与实车日志量级）
 *
 * | 量 | 范围 | 依据 |
 * |---|---|---|
 * | 车速 | 0–80 km/h | 城区工况 |
 * | 电机转速 | 0–3360 rpm | 怠速 0，80km/h 时约 800+80×32 |
 * | 功率 | 1.5 / 12 / 45 kW | 附件 / 巡航 / 加速 |
 * | 电压 | 388–398 V | 高压电池 nominal |
 * | 电量 | 78% 起缓降 | 62 kWh 电池积分 |
 * | 续航 | SOC × 4.15 km/% | ≈ 400 km @ 96% |
 * | 胎压 | 2.32–2.48 bar | 四轮正弦微抖 |
 *
 * ## 铁律：只用于测试
 *
 * 默认关闭；开启方式只有两个：设置页「车辆」段的开关，或
 * adb broadcast 后门。**真车上必须关闭** —— 模拟值一旦混进
 * iAP2 snapshot，iPhone 地图会显示假的续航/电量。
 */
class B561VehicleSimulator {

    /** 工况周期（秒）。 */
    private val cycleSeconds = CYCLE_SECONDS

    /** 周期内已累计时间（秒）。 */
    private var elapsed = 0f

    /** 当前车速 km/h（一阶滞后逼近目标，避免跳变）。 */
    private var speedKph = 0f

    /** 电池电量 %。 */
    private var batteryPercent = START_SOC

    /** 总里程 km。 */
    private var odometerKm = START_ODOMETER

    /** 最近一次推进的原始量（[lastReading] 的数据源）。 */
    private var lastRaw: SimulatedRaw? = null

    /** 上一次的目标车速（用于判定加/减速意图）。 */
    private var lastTarget = 0f

    /**
     * 推进 [dtSeconds] 秒，返回新的仪表快照。
     *
     * 每个字段都保证在物理范围内；连续调用返回值**必然不同**
     * （车速/电量/里程/胎压至少有一个在变），
     * 因此上层 `previous == value` 的短路不会拦住推送。
     */
    fun advance(dtSeconds: Float): B561UltraGauges {
        val raw = advanceRaw(dtSeconds)
        lastRaw = raw

        // 四轮胎压各相位正弦微抖；速度高时胎压略升（温升效应）。
        val warm = raw.speedKph / MAX_SPEED * TIRE_WARM_RISE
        val tires = FloatArray(4) { i ->
            TIRE_BASE + warm + sin(elapsed * TIRE_RIPPLE_HZ + i * TIRE_PHASE_STEP) *
                TIRE_RIPPLE_AMP
        }

        return B561UltraGauges(
            speedKph = raw.speedKph,
            gear = raw.gear,
            motorRpm = if (raw.speedKph < IDLE_SPEED) {
                0f
            } else {
                IDLE_RPM + raw.speedKph * RPM_PER_KPH
            },
            engineRpm = null, // 纯电：与实车 logcat 源一致（v121 属性表无发动机转速）
            powerKw = B561VehicleProperty.powerKw(raw.voltage, raw.current),
            batteryPercent = raw.batteryPercent,
            fuelPercent = null, // 纯电无油量
            rangeKm = raw.rangeKm,
            odometerKm = odometerKm,
            tirePressureBar = tires,
        )
    }

    /** 当前工况的 [CabinReading]（与 [advance] 同步推进，状态只推进一次）。 */
    fun advanceReading(dtSeconds: Float): CabinReading {
        val raw = advanceRaw(dtSeconds)
        lastRaw = raw
        return raw.toReading()
    }

    /**
     * 与最近一次 [advance] / [advanceReading] **同一次推进**的读数。
     *
     * 上层数据源用它在推进后直接取读数（不再推进状态），
     * 避免"推进行驶数据、再拉一次读数又推进一步"的双重推进。
     */
    fun lastReading(): CabinReading? = lastRaw?.toReading()

    /** 复位到初始状态（测试用）。 */
    fun reset() {
        elapsed = 0f
        speedKph = 0f
        batteryPercent = START_SOC
        odometerKm = START_ODOMETER
        lastTarget = 0f
        lastRaw = null
    }

    // ---- 内部 ----

    /** 推进状态但跳过快照构造（[advance] / [advanceReading] 共用同一推进逻辑）。 */
    private fun advanceRaw(dtSeconds: Float): SimulatedRaw {
        val dt = dtSeconds.coerceIn(0f, MAX_DT)
        elapsed += dt
        if (elapsed >= cycleSeconds) elapsed -= cycleSeconds

        val target = targetSpeedAt(elapsed)
        val rate = if (target > speedKph) ACCEL_RATE else DECEL_RATE
        val delta = (target - speedKph).coerceIn(-rate * dt, rate * dt)
        speedKph = (speedKph + delta).coerceIn(0f, MAX_SPEED)

        // 工况意图看**目标速度的走向**，不看 speed 与 target 的误差：
        // 一阶滞后的追随误差太小（target 斜率峰值 < 滞后限速），
        // 误差判定会让加速/刹车段永远落在巡航功率上（实测过）。
        val targetDelta = target - lastTarget
        lastTarget = target
        val accelerating = targetDelta > TARGET_EPS
        val braking = targetDelta < -TARGET_EPS
        val powerKw = when {
            speedKph < IDLE_SPEED -> IDLE_POWER
            accelerating -> ACCEL_POWER
            braking -> REGEN_POWER
            else -> CRUISE_POWER
        }
        val voltage = BASE_VOLTAGE + batteryPercent * SOC_VOLTAGE_SLOPE +
            sin(elapsed * VOLTAGE_RIPPLE_HZ) * VOLTAGE_RIPPLE_AMP
        val current = powerKw * 1000f / voltage

        // ΔSOC% = P(kW)·t(s)/3600/E(kWh)·100 = P·t/(36·E)。
        // 少那个 36（=3600s/100%）会让能耗大 36 倍——实测过。
        batteryPercent = (batteryPercent - powerKw * dt / (BATTERY_KWH * SEC_PER_KWH_PERCENT))
            .coerceIn(MIN_SOC, MAX_SOC)
        odometerKm += speedKph * dt / 3600f

        return SimulatedRaw(
            speedKph = speedKph,
            gear = GEAR_D,
            batteryPercent = batteryPercent.toInt(),
            rangeKm = rangeKmFor(batteryPercent),
            voltage = voltage,
            current = current,
        )
    }

    /** [CabinReading] 的原始量（不含胎压等仪表专属字段）。 */
    private data class SimulatedRaw(
        val speedKph: Float,
        val gear: Int,
        val batteryPercent: Int,
        val rangeKm: Int,
        val voltage: Float,
        val current: Float,
    ) {
        fun toReading() = CabinReading(
            speedKph = speedKph,
            gear = gear,
            batteryPercent = batteryPercent,
            rangeKm = rangeKm,
            voltage = voltage,
            current = current,
        )
    }

    /** 周期 [t] 秒处的目标车速（km/h）。 */
    private fun targetSpeedAt(t: Float): Float = when {
        t < IDLE_END -> 0f
        t < ACCEL_END -> MAX_SPEED * smoothstep((t - IDLE_END) / ACCEL_SECONDS)
        t < CRUISE_END -> MAX_SPEED
        t < BRAKE_END -> MAX_SPEED - (MAX_SPEED - CRAWL_SPEED) *
            smoothstep((t - CRUISE_END) / BRAKE_SECONDS)
        t < CRAWL_END -> CRAWL_SPEED
        else -> CRAWL_SPEED * (1f - smoothstep((t - CRAWL_END) / STOP_SECONDS))
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun rangeKmFor(soc: Float): Int =
        (soc * KM_PER_PERCENT + sin(elapsed * 0.21f) * RANGE_JITTER).toInt()
            .coerceIn(0, MAX_RANGE)

    companion object {
        /** 周期总长。 */
        const val CYCLE_SECONDS = 60f

        // 工况分段（秒）
        private const val IDLE_END = 8f
        private const val ACCEL_SECONDS = 14f
        private const val ACCEL_END = 22f
        private const val CRUISE_END = 38f
        private const val BRAKE_SECONDS = 10f
        private const val BRAKE_END = 48f
        private const val CRAWL_END = 54f
        private const val STOP_SECONDS = 6f

        private const val MAX_SPEED = 80f
        private const val CRAWL_SPEED = 15f
        private const val IDLE_SPEED = 1f
        private const val ACCEL_RATE = 12f
        private const val DECEL_RATE = 18f

        private const val IDLE_POWER = 1.5f
        private const val CRUISE_POWER = 12f
        private const val ACCEL_POWER = 45f
        private const val REGEN_POWER = 8f

        private const val BASE_VOLTAGE = 380f
        private const val SOC_VOLTAGE_SLOPE = 0.12f
        private const val VOLTAGE_RIPPLE_AMP = 1.5f
        private const val VOLTAGE_RIPPLE_HZ = 0.9f

        private const val START_SOC = 78f
        private const val BATTERY_KWH = 62f

        /** kW·s → %SOC 的换算分母：3600s/h ÷ 100% = 36。 */
        private const val SEC_PER_KWH_PERCENT = 36f

        private const val MIN_SOC = 20f
        private const val MAX_SOC = 100f

        private const val START_ODOMETER = 12345.6f

        private const val IDLE_RPM = 800f
        private const val RPM_PER_KPH = 32f

        /** 目标速度变化超过该值才算「有加/减速意图」（km/h·s⁻¹）。 */
        private const val TARGET_EPS = 0.05f

        private const val KM_PER_PERCENT = 4.15f
        private const val RANGE_JITTER = 1.5f
        private const val MAX_RANGE = 0xffff

        private const val TIRE_BASE = 2.32f
        private const val TIRE_RIPPLE_AMP = 0.08f
        private const val TIRE_RIPPLE_HZ = 0.35f
        private const val TIRE_PHASE_STEP = 1.7f
        private const val TIRE_WARM_RISE = 0.08f

        /** D 挡原始值（v121 属性表：8 = D）。 */
        const val GEAR_D = 8

        /** 单次 advance 的最大步长（秒），防外部传巨大 dt 把状态推飞。 */
        private const val MAX_DT = 5f
    }
}
