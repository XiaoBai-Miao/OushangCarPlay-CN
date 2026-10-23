package com.shilapi.xcertplay.vehicle

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 从 **logcat** 读取 B561 车辆数据（车速/挡位/电量/电压/电流）。
 *
 * ## 为什么不是 VHAL
 *
 * v117 走 `Car.createCar` + `getCarManager("cabin")`，实车失败：
 * ```
 * 11:35:46.389 W B561-VehicleStatus: Car service connect failed: InvocationTargetException: null
 * 11:35:46.414 D CAR.L: onServiceConnected mService=android.car.ICar$Stub$Proxy@f4d72c6
 * ```
 * `connect()` 异步，`onServiceConnected` 25ms 后才回调，我们提前取 manager 必然抛异常。
 * 详见 [B561VehicleLogParser] 的类注释。
 *
 * ## 读取方式
 *
 * 起一条常驻的 `logcat` 子进程，**只订阅需要的 tag**
 * （`Property.service:F`、`FastControlManager:F`），把 stdout 逐行喂给
 * [B561VehicleLogParser]。
 *
 * 为什么不用 `logcat -d`（dump 一次）：`-d` 是快照，读完就没了，
 * 而车辆数据是持续变化的。必须 `-v` 长驻 + 逐行消费。
 *
 * 为什么不用 `logcat --pid`（Android 8+）：我们要听的是**原厂组件**
 * （`Property.service` 在 pid 1250、原厂桌面在 2202）的日志，
 * 不是我们自己的。
 *
 * ## 权限
 *
 * 读 logcat 需要 `READ_LOGS`。B561 是**平台签名**交付（证书
 * `c8a2e9bc…92ab8`），`READ_LOGS` 在 AOSP 里是 `signature|privileged`，
 * 平台签名的 app 可以拿到。若实车被拒（`logcat` 子进程立刻退出且无输出），
 * [isRunning] 为 false，调用方退回"无车辆数据"——**不报假值**。
 *
 * ## 子进程生命周期
 *
 * 与 [CarCabinVehicleStatus] 同样的坑：`CarPlayHostActivity` 里
 * `onStart`→`start` / `onStop`→`stop` 成对出现，线程池一旦 `shutdownNow()`
 * 就永久不能接受新任务。所以 [stop] 只停子进程不置空 executor，
 * 每次 [start] 用 `takeUnless { isShutdown }` 判断是否重建。
 */
class LogcatVehicleStatus(
    private val tag: String = "B561-VehicleLog",
) {

    @Volatile
    private var started = false

    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** 正在运行的 `logcat` 进程；null 表示未启动或已退出。 */
    @Volatile
    private var logcat: Process? = null

    /** 最近一次读到的字段；空表示还没读到任何东西。 */
    @Volatile
    private var fields: Map<String, Number> = emptyMap()

    /**
     * 字段变化回调（**在读取线程上回调**，不是主线程）。
     *
     * 只在值真的变了时触发，静止时不会重复通知。
     */
    @Volatile
    var onFieldsChanged: ((Map<String, Number>) -> Unit)? = null

    /** 读数是否已经成功抓到过（用来区分"没数据"与"还没读到"）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (started) return
        started = true
        val pool = executor?.takeUnless { it.isShutdown } ?: Executors
            .newSingleThreadScheduledExecutor { r ->
                Thread(r, "b561-vehicle-log").apply { isDaemon = true }
            }
            .also { executor = it }
        try {
            pool.scheduleWithFixedDelay(::drainOnce, 0, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            started = false
            Log.w(tag, "logcat poll rejected by the executor; retrying on the next start()")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        executor?.shutdownNow()
        logcat?.destroy()
        logcat = null
        isRunning = false
    }

    /** 最近一次读到的车速（km/h）；读不到返回 null。 */
    fun currentSpeedKph(): Float? = (fields[B561VehicleLogParser.KEY_SPEED] as? Number)?.toFloat()

    /** 最近一次读到的挡位原始值；读不到返回 null。 */
    fun currentGearRaw(): Int? = (fields[B561VehicleLogParser.KEY_GEAR] as? Number)?.toInt()

    /** 最近一次读到的电量百分比；读不到返回 null。 */
    fun currentBatteryPercent(): Int? =
        (fields[B561VehicleLogParser.KEY_BATTERY] as? Number)?.toInt()

    /** 最近一次读到的电压（V）；读不到返回 null。 */
    fun currentVoltage(): Float? = (fields[B561VehicleLogParser.KEY_VOLTAGE] as? Number)?.toFloat()

    /** 最近一次读到的电流（A）；读不到返回 null。实测放电时为负。 */
    fun currentCurrent(): Float? = (fields[B561VehicleLogParser.KEY_CURRENT] as? Number)?.toFloat()

    /**
     * 把当前读数转成 [CarCabinVehicleStatus.CabinReading]，
     * 供仪表车速表 / iAP2 车辆状态复用。
     *
     * 与 `CarCabinVehicleStatus` 一致：**车速为 null 时整体返回 null**，
     * 调用方靠这个判断"该不该显示"，报假的 0 会让驾驶员以为传感器坏了。
     */
    fun currentReading(): CarCabinVehicleStatus.CabinReading? {
        val speed = currentSpeedKph() ?: return null
        return CarCabinVehicleStatus.CabinReading(
            speedKph = speed,
            gear = currentGearRaw(),
            batteryPercent = currentBatteryPercent(),
            rangeKm = (fields[B561VehicleLogParser.KEY_RANGE] as? Number)?.toInt(),
            voltage = currentVoltage(),
            current = currentCurrent(),
        )
    }

    /**
     * 消费一次 logcat 输出。
     *
     * 做成"启动一次子进程 + 逐行读"而不是每次重起：
     * `logcat` 子进程读的是**环形缓冲**的当前尾部，重新启动会漏掉
     * 启动瞬间之前打的行（车速这种高频字段会丢一帧）。
     */
    private fun drainOnce() {
        val process = ensureLogcat() ?: return
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val before = fields
                while (true) {
                    val line = reader.readLine() ?: break
                    val parsed = B561VehicleLogParser.parseLine(line) ?: continue
                    synchronized(fields) { fields = LinkedHashMap(fields).apply { putAll(parsed) } }
                }
                val after = fields
                if (after != before) {
                    isRunning = true
                    Log.i(
                        tag,
                        "logcat reading speed=${after[B561VehicleLogParser.KEY_SPEED]} " +
                            "gear=${after[B561VehicleLogParser.KEY_GEAR]} " +
                            "battery=${after[B561VehicleLogParser.KEY_BATTERY]}% " +
                            "voltage=${after[B561VehicleLogParser.KEY_VOLTAGE]} " +
                            "current=${after[B561VehicleLogParser.KEY_CURRENT]}",
                    )
                    runCatching { onFieldsChanged?.invoke(after) }
                        .onFailure { Log.w(tag, "onFieldsChanged failed: ${it.message}") }
                }
            }
        } catch (e: Exception) {
            // 进程被回收 / 权限被拒 / stdout 关闭。子进程置空，下个周期重启。
            Log.w(tag, "logcat stream ended: ${e.javaClass.simpleName}: ${e.message}")
            logcat = null
            isRunning = false
        }
    }

    /**
     * 保证有一个活着的 `logcat` 子进程。
     *
     * 只订阅两个 tag：
     * - `Property.service` —— VHAL 变化回调，车速/电量/电压/电流都从这里出来；
     * - `FastControlManager` —— 原厂快控，挡位行从这里出来。
     *
     * `*:F` 是 filter-spec 语法（`tag:priority`），只收 priority ≤ F 的行。
     */
    private fun ensureLogcat(): Process? {
        logcat?.let { if (it.isAlive) return it }
        return runCatching {
            ProcessBuilder(
                "logcat",
                "-v", "brief",
                "Property.service:D",
                "FastControlManager:D",
            )
                // 不重定向 stderr：logcat 的权限拒绝信息会打到 stderr，
                // 让它继承本进程的 fd 才能在 logcat 里看到我们自己的失败原因。
                .start()
        }.getOrElse {
            Log.w(tag, "cannot start logcat: ${it.javaClass.simpleName}: ${it.message}")
            null
        }?.also { process ->
            logcat = process
            Log.i(tag, "logcat started, process=${describe(process)}")
        }
    }

    /**
     * 子进程身份，仅用于日志。
     *
     * `Process.pid()` 是 Java 9 才有的方法，Android 的 SDK stub 里查不到
     * （编译期 `Unresolved reference 'pid'`），minSdk 25 上也不能直接调。
     * 这里反射取，取不到就打 `?` —— 只是个诊断信息，不值得为它引依赖。
     */
    private fun describe(process: Process): String {
        val pid = runCatching {
            Process::class.java.getMethod("pid").invoke(process)
        }.getOrNull()
        return "pid=$pid"
    }

    private companion object {
        /**
         * 重启周期。
         *
         * 只有子进程死掉才会走 [drainOnce] 的重启路径，正常情况下
         * `readLine()` 会**阻塞**直到有新行 —— 所以这个周期实际只影响
         * 故障恢复节奏，1 秒足够，且不影响数据新鲜度。
         */
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}