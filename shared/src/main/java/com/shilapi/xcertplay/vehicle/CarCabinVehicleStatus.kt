package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 长安欧尚 B561 车辆数据源 —— 通过 VHAL 把车辆信息交给 CarPlay。
 *
 * ## 通道
 *
 * ```
 * iPhone --0xA100 StartVehicleStatusUpdates--> 车机
 * 车机    --0xA101 VehicleStatusUpdate-->    iPhone     ← 电量 / 续航
 * iPhone --0xFFFB LocationInformation-->    车机
 * 车机    --$PASCD 轮速句-->                iPhone     ← 车速
 * ```
 *
 * ## 数据来源：原厂同源的 VHAL
 *
 * `android.car.hardware.cabin.CarCabinManager`。原厂仪表 `Mointerservice`
 * 与原厂桌面 `NewEnergyDeskView2CtrManager` 读的是同一个 HAL，
 * 所以拿到的值与仪表显示**同源**，不是另找一路估算。
 *
 * ## ★ 连接方式用官方范式，不是 getSystemService
 *
 * 原厂 `com.chinatsp.smartlights` 的 `CarConnectManager` 是这样连的：
 * ```java
 * Car car = Car.createCar(context, serviceConnectionListener);
 * car.connect();
 * mCarCabinManager = (CarCabinManager) car.getCarManager("cabin");
 * ```
 * 即**必须经 `android.car.Car` 这条路**才能拿到 manager。
 * 历史实现（v46-v75 用的那份）走的是 `context.getSystemService("car_cabin")`
 * 并找名为 `getCarCabinIntValue` 的方法 —— 实车日志里 CarCabinManager
 * 根本没有这个方法名（真实方法是 `getIntProperty`/`getFloatProperty`/
 * `getBooleanProperty`/`getProperty`），所以那一层**从来没读到过任何值**。
 * v117 改用官方范式 + 真实方法名。
 *
 * ## 为什么读不到就返回 null
 *
 * `VehicleStatusProvider.snapshot()` 返回 null 时，iAP2 识别信息里
 * `vehicleStatusEnabled` 会被置 false —— 这是**故意**的：
 * 宁可告诉 iPhone「本机没有车辆数据」，也不能报一个假的续航/电量，
 * 否则 Apple 地图会给出错误的低电量提醒和充电站推荐。
 */
class CarCabinVehicleStatus(
    private val tag: String = "B561-VehicleStatus",
    /** 低电量告警阈值，可由设置覆盖（默认 15，与原厂一致）。 */
    private val lowChargePercent: Int = 15,
) : VehicleStatusProvider {

    @Volatile
    private var context: Context? = null

    @Volatile
    private var started = false

    /**
     * 轮询线程池。**必须延迟到 [start] 时再建**。
     *
     * 构造时创建一次、而 [stop] 调 `shutdownNow()` 的话，线程池一旦终止就**永久**
     * 不能接受新任务；`CarPlayHostActivity` 里 `onStart`→`start`、
     * `onStop`→`stop` 成对出现，第二次进入 CarPlay 就会
     * `RejectedExecutionException` 崩在主线程。
     * 所以每次 [start] 都重建，[stop] 只 shutdown 不置空。
     */
    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** CarCabinManager 的反射句柄，首次连接成功后解析。 */
    @Volatile
    private var cabin: CabinAccess? = null

    /**
     * 已发起连接、等待 `onServiceConnected` 回调的 `Car` 实例。
     *
     * v120 新增：`connect()` 是异步的，回调晚到，期间 [cabin] 必须为空、
     * 这里非空 —— 两个字段合起来表达"在连 / 已连上 / 断了"。
     */
    @Volatile
    private var pendingCar: Any? = null

    /** 最近一次读数（原始形态，保留挡位/车速等 snapshot 里丢掉的字段）。 */
    @Volatile
    private var latestReading: CabinReading? = null

    /** 最近一次可上报的车辆状态快照。 */
    @Volatile
    private var latestSnapshot: VehicleStatusSnapshot? = null

    /**
     * 读数变化回调，供仪表 HUD 增量刷新。
     *
     * **在轮询线程上回调**，不是主线程 —— 订阅方必须自己切线程。
     * 只在值真的变了时才回调，静止时不会重复通知。
     */
    @Volatile
    var onReadingChanged: ((CabinReading?) -> Unit)? = null

    fun start(appContext: Context) {
        context = appContext.applicationContext
        if (started) return
        started = true
        val pool = executor?.takeUnless { it.isShutdown } ?: Executors
            .newSingleThreadScheduledExecutor { r ->
                Thread(r, "b561-vehicle-status").apply { isDaemon = true }
            }
            .also { executor = it }
        try {
            pool.scheduleWithFixedDelay(::poll, 0, READ_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // 与 onStop 抢跑时不该把 Activity 一起带崩。下次 start 会重建线程池。
            started = false
            Log.w(tag, "vehicle status poll rejected by the executor; retrying on the next start()")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        // 只 shutdown，不清空引用 —— [start] 会用 takeUnless { isShutdown } 判断是否重建。
        executor?.shutdownNow()
        // 断开 Car 连接，避免 Car Service 侧一直留着我们的客户端。
        cabin?.disconnect()
        cabin = null
        pendingCar = null
    }

    /**
     * 车辆状态快照；读不到时返回 null。
     *
     * 供 iAP2 `VehicleStatusProvider` 使用（见 [com.shilapi.xcertplay.transport.Iap2VehicleStatus]）。
     */
    override fun snapshot(): VehicleStatusSnapshot? = latestSnapshot

    /** 最近一次读到的车速（km/h）；读不到时返回 null。 */
    fun currentSpeedKph(): Float? = latestReading?.speedKph

    /** 最近一次读到的挡位原始值；读不到时返回 null。 */
    fun currentGearRaw(): Int? = latestReading?.gear

    /**
     * 电机转速（rpm）；读不到时返回 null。
     *
     * ★ 这一路**永远返回 null**：`CarCabinManager` 不提供电机转速
     * （AOSP 也没有 `ELECTRIC_MOTOR_RPM`，电动车用的是厂商自定义属性
     * `0x2160f315`）。保留这个方法只是为了满足门面
     * 「logcat 主源 + VHAL 兜底」的对称结构，
     * 真正能读到转速的只有 logcat 那一路。
     */
    fun currentMotorRpm(): Float? = null

    /** 电池电量百分比；读不到时返回 null。 */
    fun currentBatteryPercent(): Int? = latestReading?.batteryPercent

    /** 电压（V）；读不到时返回 null。 */
    fun currentVoltage(): Float? = latestReading?.voltage

    /** 电流（A）；读不到时返回 null。实测放电时为负。 */
    fun currentCurrent(): Float? = latestReading?.current

    /** 续航（km）；读不到时返回 null。 */
    fun currentRangeKm(): Int? = latestReading?.rangeKm

    /**
     * 原始读数，供仪表 HUD 用；读不到时返回 null。
     *
     * 车速为 null 时整体返回 null —— 调用方靠这个判断「该不该显示」，
     * 报一个假的 0 会让驾驶员误以为车速传感器坏了，比空白更糟。
     */
    fun currentReading(): CabinReading? = latestReading?.takeIf { it.speedKph != null }

    private fun poll() {
        val app = context ?: return
        // 在连（pendingCar 非空、cabin 还空）时 connect() 返回 null，
        // 这是**正常中间态**，不是失败 —— 不打警告，等回调。
        if (cabin == null && pendingCar != null) return
        val access = cabin ?: connect(app) ?: return
        val read = runCatching { access.read() }.getOrNull()
        if (read == null) {
            if (latestReading == null) {
                // 只在从未成功过时提示一次，避免每个周期刷一次日志。
                Log.w(tag, "CarCabinManager unavailable on this firmware")
            }
            return
        }
        val previous = latestReading
        val snapshot = read.toSnapshot(lowChargePercent)
        if (snapshot != latestSnapshot) {
            Log.i(
                tag,
                "cabin speed=${read.speedKph}km/h gear=${read.gear} " +
                    "battery=${read.batteryPercent}% voltage=${read.voltage} current=${read.current}",
            )
        }
        latestSnapshot = snapshot
        latestReading = read

        // ★ 车速每时每刻都在变，必须单独比较一次。
        //   只比 snapshot（电量/续航）的话，车从静止起步那一帧仪表还是空白 ——
        //   而那恰恰是驾驶员最需要看到数字的时刻。
        if (read != previous) {
            runCatching { onReadingChanged?.invoke(read) }
                .onFailure { Log.w(tag, "onReadingChanged failed: ${it.message}") }
        }
    }

    /**
     * 建立到 Car Service 的连接。
     *
     * 走原厂 `CarConnectManager` 的官方范式：`Car.createCar(context, listener)`
     * + `car.connect()`，连接回调里 `getCarManager("cabin")`。
     * 全程反射，编译期不依赖 `android.car` 类库 —— 普通 AOSP 环境也能编译。
     *
     * ## ★ v120 修正：connect() 是异步的，必须等 onServiceConnected
     *
     * v117 在 `connect()` 返回后**立刻** `getCarManager("cabin")`，但
     * `connect()` 只是发起绑定，实车上回调要晚 25ms 才到：
     * ```
     * 11:35:46.389 W B561-VehicleStatus: Car service connect failed: InvocationTargetException: null
     * 11:35:46.414 D CAR.L: onServiceConnected mService=android.car.ICar$Stub$Proxy@f4d72c6
     * ```
     * 所以那一路**从来没连上过**。现在改成：把 `Car` 实例挂到 [pendingCar]，
     * 由 `onServiceConnected` 回调去取 manager 并置 [cabin]，
     * [poll] 自然会等到它就绪。
     */
    private fun connect(app: Context): CabinAccess? {
        // 已经有可用的 manager 就不用重连。
        cabin?.let { return it }
        // 已发起过连接、等回调中，就不要再 createCar —— 每次 createCar 都会
        // 向 Car Service 注册一个新客户端，重复注册会泄漏且可能顶掉上一个。
        if (pendingCar != null) return null

        val carClass = runCatching { Class.forName("android.car.Car") }.getOrNull()
        if (carClass == null) {
            Log.w(tag, "android.car.Car not on this framework; vehicle data unavailable")
            return null
        }
        return runCatching {
            val listener = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    // ★ 这里才是能取 manager 的时刻。回调在主线程，
                    //   所以只做反射取值，不做阻塞操作。
                    val car = pendingCar ?: return
                    runCatching {
                        val manager = carClass.methods.firstOrNull {
                            it.name == "getCarManager" && it.parameterCount == 1 &&
                                it.parameterTypes[0] == String::class.java
                        }?.invoke(car, "cabin")
                    }.getOrElse {
                        Log.w(tag, "getCarManager(cabin) failed: ${it.javaClass.simpleName}: ${it.message}")
                        null
                    }?.let { manager ->
                        cabin = CabinAccess(manager, car)
                        Log.i(tag, "car service connected; cabin manager acquired")
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Car Service 挂了就重新走一遍 connect，poll 会再拉起。
                    Log.w(tag, "car service disconnected")
                    cabin?.disconnect()
                    cabin = null
                    pendingCar = null
                }
            }
            // Car.createCar(Context, ServiceConnection) —— 同步连接版本。
            val createCar = carClass.methods.firstOrNull {
                it.name == "createCar" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == Context::class.java
            }
            val car = when {
                createCar != null -> createCar.invoke(null, app, listener)
                else -> {
                    // 退化到单参版本 createCar(Context)
                    val single = carClass.methods.firstOrNull {
                        it.name == "createCar" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == Context::class.java
                    } ?: return null
                    single.invoke(null, app)
                }
            } ?: return null

            pendingCar = car
            // connect() 只是发起绑定；manager 要等 onServiceConnected 回调。
            runCatching { carClass.methods.firstOrNull { it.name == "connect" }?.invoke(car) }
            Log.i(tag, "car service connect requested; waiting for onServiceConnected")
            // 此刻 manager 还没到，返回 null 让 poll 下个周期再试。
            null
        }.getOrElse {
            Log.w(tag, "Car service connect failed: ${it.javaClass.simpleName}: ${it.message}")
            pendingCar = null
            null
        }
    }

    /**
     * 一次读数。
     *
     * @param speedKph 车速（km/h），null = 读不到。
     * @param gear 挡位原始值，null = 读不到。
     * @param batteryPercent 电量百分比 0..100，null = 读不到。
     * @param rangeKm 续航（km），null = 读不到。
     * @param voltage 高压电池组电压（V），null = 读不到。
     * @param current 电流（A），null = 读不到。
     */
    data class CabinReading(
        val speedKph: Float?,
        val gear: Int?,
        val batteryPercent: Int?,
        val rangeKm: Int?,
        val voltage: Float?,
        val current: Float?,
    ) {
        /** 功率（kW）；电压或电流缺失时为 null。 */
        val powerKw: Float? get() = B561VehicleProperty.powerKw(voltage, current)

        /** 挡位字母；未坐实的挡位值返回 null（不画错的字母）。 */
        val gearLabel: Char? get() = B561VehicleProperty.gearLabel(gear)

        /**
         * 转成 iAP2 VehicleStatusUpdate 需要的形态。
         *
         * 缺少电量或续航时返回 null —— 见类注释：不能报假数据。
         */
        fun toSnapshot(lowPercent: Int): VehicleStatusSnapshot? {
            val percent = batteryPercent ?: return null
            if (percent !in 0..100) return null
            val range = rangeKm ?: return null
            return VehicleStatusSnapshot(
                rangeKm = range.coerceIn(0, 0xffff),
                rangeWarning = percent <= lowPercent,
                batteryPercent = percent.toDouble(),
                // 能量值本机拿不到就不填，协议允许省略：
                // "Unknown energy is omitted, never represented as an empty battery."
                currentChargeWh = null,
                maxChargeWh = null,
                maxRangeKm = range.coerceIn(0, 0xffff),
                charging = false,
            )
        }
    }

    /**
     * CarCabinManager 的纯反射封装。
     *
     * 四个方法都可能因 MTK 固件裁剪而不存在，因此全部逐个 try，
     * 缺哪个就少报哪个字段，不整体放弃。
     */
    private class CabinAccess(private val manager: Any, private val car: Any?) {
        private val getInt: Method? = findMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getFloat: Method? = findMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getBoolean: Method? = findMethod("getBooleanProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getGeneric: Method? = findMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        private fun findMethod(name: String, vararg params: Class<*>?): Method? = runCatching {
            manager.javaClass.getMethod(name, *params)
        }.getOrNull()

        fun read(): CabinReading? {
            val intFn = getInt
            val floatFn = getFloat
            val boolFn = getBoolean
            val genericFn = getGeneric
            if (intFn == null && floatFn == null && genericFn == null) return null
            return CabinReading(
                speedKph = readSpeed(floatFn, intFn),
                gear = readGear(intFn, genericFn),
                batteryPercent = readBattery(intFn, floatFn),
                rangeKm = readRange(intFn, floatFn),
                voltage = readFloat(floatFn, B561VehicleProperty.VOLTAGE),
                current = readFloat(floatFn, B561VehicleProperty.CURRENT),
            )
        }

        /**
         * 车速：`0x11600207` Float，单位 km/h。
         *
         * 实车日志里 `Property.service: property:0x11600207 on change, values=0.5625`
         * 与原厂仪表 `speed Value() = 0.5625` 逐条相等，**已经是 km/h，无需缩放**。
         */
        private fun readSpeed(floatFn: Method?, intFn: Method?): Float? {
            val raw = readFloat(floatFn, B561VehicleProperty.SPEED)
                ?: intOf(intFn, B561VehicleProperty.SPEED)?.toFloat()
            return B561VehicleProperty.speedKph(raw)
        }

        /** 挡位：`0x11400400` Int，8 = D 挡（见 [B561VehicleProperty.GEAR_SELECTION]）。 */
        private fun readGear(intFn: Method?, genericFn: Method?): Int? =
            intOf(intFn, B561VehicleProperty.GEAR_SELECTION)
                ?: genericInt(genericFn, B561VehicleProperty.GEAR_SELECTION)

        /** 电量：候选列表逐个试，取第一个有效值。 */
        private fun readBattery(intFn: Method?, floatFn: Method?): Int? {
            for (id in B561VehicleProperty.BATTERY_PERCENT_CANDIDATES) {
                val raw = intOf(intFn, id) ?: readFloat(floatFn, id)?.toInt()
                B561VehicleProperty.batteryPercent(raw)?.let { return it }
            }
            return null
        }

        /** 续航：候选列表逐个试。读不到就是 null，绝不拿电量反推。 */
        private fun readRange(intFn: Method?, floatFn: Method?): Int? {
            for (id in B561VehicleProperty.RANGE_KM_CANDIDATES) {
                val raw = intOf(intFn, id) ?: readFloat(floatFn, id)?.toInt()
                if (raw != null && raw > 0) return raw
            }
            return null
        }

        private fun intOf(fn: Method?, id: Int): Int? {
            fn ?: return null
            return runCatching { fn.invoke(manager, id, 0) as? Int }.getOrNull()
        }

        private fun readFloat(fn: Method?, id: Int): Float? {
            fn ?: return null
            return runCatching { fn.invoke(manager, id, 0) as? Float }.getOrNull()
        }

        /**
         * 通用 `getProperty(id, area)` 入口的整数取值。
         *
         * 返回值是 `android.car.hardware.CarPropertyValue`，要再取里面的值；
         * 不同固件的取值方法名不统一（getIntValue / getValue），
         * 所以两个都试。
         */
        private fun genericInt(fn: Method?, id: Int): Int? {
            fn ?: return null
            val value = runCatching { fn.invoke(manager, id, 0) }.getOrNull() ?: return null
            for (name in listOf("getIntValue", "getValue")) {
                val extracted = runCatching {
                    value.javaClass.getMethod(name).invoke(value) as? Int
                }.getOrNull()
                if (extracted != null) return extracted
            }
            return null
        }

        fun disconnect() {
            runCatching {
                car?.javaClass?.methods?.firstOrNull { it.name == "disconnect" }?.invoke(car)
            }
        }
    }

    private companion object {
        /**
         * VHAL 轮询周期。
         *
         * 1 Hz 是权衡：再快对仪表没有感知收益，而每次 read 要打若干组反射调用，
         * 1 Hz 完全在预算内。上报频率由 iAP2 侧的
         * `Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS` 决定，与此无关。
         */
        const val READ_INTERVAL_MILLIS = 1_000L
    }
}
