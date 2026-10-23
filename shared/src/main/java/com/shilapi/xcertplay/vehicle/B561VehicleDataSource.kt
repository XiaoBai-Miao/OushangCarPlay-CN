package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/**
 * B561 车辆数据门面：**logcat 为主源，VHAL 为兜底**。
 *
 * ## 为什么要门面而不是直接把 [CarCabinVehicleStatus] 换成 [LogcatVehicleStatus]
 *
 * 两条路的**可用性会随车机状态变化**：
 * - logcat 需要 `READ_LOGS`（平台签名可拿，但个别 ROM 上 `logcat` 子进程可能起不来）；
 * - VHAL 需要 Car Service 连上，且 v117 的连接时序是坏的（见下）。
 *
 * 所以门面按 **logcat → VHAL** 顺序取，谁先有值用谁；两条都空时返回 null
 * （**不报假值**）。这样上层（仪表车速表、`$PASCD` 轮速、iAP2 车辆状态）
 * 一行代码都不用改，只看门面一个对象。
 *
 * ## VHAL 那一路为什么留着
 *
 * v117 的失败原因已定位（不是属性 ID 错）：`car.connect()` 异步，
 * `onServiceConnected` 回调晚 25ms，我们提前 `getCarManager("cabin")`，
 * 每秒抛一次 `InvocationTargetException`：
 * ```
 * 11:35:46.389 W B561-VehicleStatus: Car service connect failed: InvocationTargetException: null
 * 11:35:46.414 D CAR.L: onServiceConnected mService=android.car.ICar$Stub$Proxy@f4d72c6
 * ```
 * 这一路现在只作为兜底，即使仍不通也不影响主流程。
 *
 * @see B561VehicleLogParser 解析的行形态与实车证据
 */
class B561VehicleDataSource(
    private val tag: String = "B561-VehicleData",
) : VehicleStatusProvider {

    /** 主源：logcat 解析。 */
    private val logcat = LogcatVehicleStatus()

    /** 兜底：VHAL。 */
    private val vhal = CarCabinVehicleStatus()

    /** 读数变化回调；上层（仪表 HUD）订阅它做增量刷新。**在读取线程上回调。** */
    @Volatile
    var onReadingChanged: ((CabinReading?) -> Unit)? = null

    // ---- v127: 车辆数据模拟器（测试专用，默认关闭）----

    /** 模拟器实例；null = 使用真实数据源（logcat/VHAL）。 */
    @Volatile
    private var simulator: B561VehicleSimulator? = null

    /** 模拟器最近一次推进的仪表快照（推进线程写，回调线程读）。 */
    @Volatile
    private var simGauges: B561UltraGauges? = null

    /** 模拟器最近一次推进的读数（与 [simGauges] 同一次推进产出）。 */
    @Volatile
    private var simReading: CabinReading? = null

    /** 模拟推进线程。 */
    private var simThread: Thread? = null

    private val simLock = Any()

    /**
     * 开关车辆数据模拟（**测试专用**）。
     *
     * 开启后：一个 1Hz 线程推进 [B561VehicleSimulator] 并走
     * [pushReading] 同一条路径，所有下游（自绘仪表、CAF 推送、
     * iAP2 snapshot）一起激活；所有 `current*()` 也改为返回模拟值。
     * 关闭后恢复真实数据源。
     */
    fun setSimulationEnabled(enabled: Boolean) {
        synchronized(simLock) {
            if (enabled) {
                if (simulator == null) {
                    simulator = B561VehicleSimulator()
                    Log.i(tag, "vehicle simulation ENABLED (test only)")
                }
                if (simThread == null) {
                    simThread = Thread(::simulateLoop, "b561-vehicle-sim").apply {
                        isDaemon = true
                        start()
                    }
                }
            } else {
                simulator = null
                simGauges = null
                simReading = null
                simThread?.let {
                    runCatching { it.interrupt() }
                }
                simThread = null
                Log.i(tag, "vehicle simulation disabled; back to real sources")
            }
        }
    }

    /** 模拟器是否在跑。 */
    val simulationEnabled: Boolean get() = simulator != null

    private fun simulateLoop() {
        Log.i(tag, "vehicle simulation loop started (${SIM_INTERVAL_MS}ms)")
        while (!Thread.currentThread().isInterrupted) {
            val sim = simulator ?: break
            runCatching { simGauges = sim.advance(SIM_INTERVAL_SECONDS) }
            simReading = sim.lastReading()
            runCatching { pushReading() }
            try {
                Thread.sleep(SIM_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
        Log.i(tag, "vehicle simulation loop stopped")
    }

    /**
     * 最近一次读数；两条源都没读到时返回 null。
     *
     * 车速为 null 时整体返回 null —— 调用方靠这个判断"该不该显示"。
     */
    fun currentReading(): CabinReading? =
        simReading ?: (logcat.currentReading() ?: vhal.currentReading())

    /** 最近一次车速（km/h）；读不到返回 null。 */
    fun currentSpeedKph(): Float? =
        simGauges?.speedKph ?: (logcat.currentSpeedKph() ?: vhal.currentSpeedKph())

    /** 最近一次挡位原始值；读不到返回 null。 */
    fun currentGearRaw(): Int? =
        simGauges?.gear ?: (logcat.currentGearRaw() ?: vhal.currentGearRaw())

    /**
     * 最近一次电机转速（rpm）；读不到返回 null。
     *
     * 0 = 电机停转，是**合法值**（实车证据链末尾就有 `value=0.0`）。
     * 这条只有 logcat 源有（VHAL 的 `CarCabinManager` 不报电机转速）。
     */
    fun currentMotorRpm(): Float? =
        simGauges?.motorRpm ?: (logcat.currentMotorRpm() ?: vhal.currentMotorRpm())

    /** 最近一次电池电量百分比；读不到返回 null。 */
    fun currentBatteryPercent(): Int? =
        simGauges?.batteryPercent ?: (logcat.currentBatteryPercent() ?: vhal.currentBatteryPercent())

    /** 最近一次电压（V）；读不到返回 null。 */
    fun currentVoltage(): Float? = simReading?.voltage ?: (logcat.currentVoltage() ?: vhal.currentVoltage())

    /** 最近一次电流（A）；读不到返回 null。实测放电时为负。 */
    fun currentCurrent(): Float? = simReading?.current ?: (logcat.currentCurrent() ?: vhal.currentCurrent())

    /** 最近一次续航（km）；读不到返回 null。 */
    fun currentRangeKm(): Int? =
        simGauges?.rangeKm ?: (logcat.currentRangeKm() ?: vhal.currentRangeKm())

    /**
     * 构造一份 **Ultra 仪表数据**（转速/功率/续航/里程/胎压…）。
     *
     * 供自绘仪表盘 [com.shilapi.xcertplay.ClusterUltraGaugeView] 使用。
     * 读不到的字段就是 null —— 仪表会少画那几块，而不是画假数字。
     */
    fun currentUltraGauges(): B561UltraGauges {
        // v127: 模拟开启时直接返回模拟器推进的快照（它自带全套字段，
        // 不需要再从 logcat/VHAL 拼）。
        simGauges?.let { return it }
        val speed = currentSpeedKph()
        val rpm = currentMotorRpm()
        val voltage = currentVoltage()
        val current = currentCurrent()
        val battery = currentBatteryPercent()
        return B561UltraGauges(
            speedKph = speed,
            gear = currentGearRaw(),
            motorRpm = rpm,
            engineRpm = null,
            powerKw = B561VehicleProperty.powerKw(voltage, current),
            batteryPercent = battery,
            fuelPercent = logcat.currentFuelPercent(),
            rangeKm = logcat.currentRangeKm(),
            odometerKm = logcat.currentOdometerKm(),
            tirePressureBar = logcat.currentTirePressureBar(),
        )
    }

    /**
     * iAP2 车辆状态快照；读不到返回 null。
     *
     * 走 [VehicleStatusProvider] 接口，[Iap2VehicleStatus] 会在返回 null 时
     * 把 identification 里的 `vehicleStatusEnabled` 置 false ——
     * 这是**故意**的：宁可告诉 iPhone「本机没有车辆数据」，
     * 也不能报假的续航/电量，否则 Apple 地图会给出错误的低电量提醒。
     */
    override fun snapshot(): VehicleStatusSnapshot? {
        val reading = currentReading() ?: return null
        // 低电量阈值与 [CarCabinVehicleStatus] 保持一致（默认 15，与原厂同）。
        return reading.toSnapshot(DEFAULT_LOW_CHARGE_PERCENT)
    }

    /** 主源（logcat）是否已经成功读到过数据。诊断与日志用。 */
    val logcatHealthy: Boolean get() = logcat.isRunning

    fun start(appContext: Context) {
        logcat.onFieldsChanged = {
            // logcat 是主源，它一变就往下游推。
            pushReading()
        }
        logcat.start()
        vhal.onReadingChanged = { pushReading() }
        vhal.start(appContext)
        Log.i(tag, "vehicle data started; logcat=${if (logcat.isRunning) "ready" else "starting"}")
    }

    fun stop() {
        setSimulationEnabled(false)
        logcat.onFieldsChanged = null
        logcat.stop()
        vhal.onReadingChanged = null
        vhal.stop()
        Log.i(tag, "vehicle data stopped")
    }

    private fun pushReading() {
        runCatching { onReadingChanged?.invoke(currentReading()) }
            .onFailure { Log.w(tag, "onReadingChanged failed: ${it.message}") }
    }

    private companion object {
        /** 低电量告警阈值（%），与原厂一致。 */
        const val DEFAULT_LOW_CHARGE_PERCENT = 15

        /** 模拟推进间隔（毫秒）—— 1Hz，与实车 VHAL 读数刷新率一致。 */
        const val SIM_INTERVAL_MS = 1000L

        /** 模拟推进间隔（秒）。 */
        const val SIM_INTERVAL_SECONDS = 1f
    }
}

/** 读数形态的别名，让调用方不必记住它嵌套在哪个类里。 */
typealias CabinReading = CarCabinVehicleStatus.CabinReading