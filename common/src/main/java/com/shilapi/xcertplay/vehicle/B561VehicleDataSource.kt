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

    /**
     * 最近一次读数；两条源都没读到时返回 null。
     *
     * 车速为 null 时整体返回 null —— 调用方靠这个判断"该不该显示"。
     */
    fun currentReading(): CabinReading? =
        logcat.currentReading() ?: vhal.currentReading()

    /** 最近一次车速（km/h）；读不到返回 null。 */
    fun currentSpeedKph(): Float? =
        logcat.currentSpeedKph() ?: vhal.currentSpeedKph()

    /** 最近一次挡位原始值；读不到返回 null。 */
    fun currentGearRaw(): Int? =
        logcat.currentGearRaw() ?: vhal.currentGearRaw()

    /**
     * 最近一次电机转速（rpm）；读不到返回 null。
     *
     * 0 = 电机停转，是**合法值**（实车证据链末尾就有 `value=0.0`）。
     * 这条只有 logcat 源有（VHAL 的 `CarCabinManager` 不报电机转速）。
     */
    fun currentMotorRpm(): Float? =
        logcat.currentMotorRpm() ?: vhal.currentMotorRpm()

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
    }
}

/** 读数形态的别名，让调用方不必记住它嵌套在哪个类里。 */
typealias CabinReading = CarCabinVehicleStatus.CabinReading