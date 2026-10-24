package com.shilapi.xcertplay.vehicle

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * （`Property.service:D`、`FastControlManager:D`、`CmdSmdManager:D`、
 * `CarCabinManager:D`，见 [LOG_TAGS]），把 stdout 逐行喂给
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
 * 平台签名的 app 可以拿到。若实车被拒，见下面的诊断增强 ——
 * **拒绝信息必须能进我们自己的日志**，否则就是静默失败。
 *
 * ## ★ v131 诊断增强（真车「零输出」排障）
 *
 * v126/v130 真车日志连续两版出现同一个症状：`logcat started` 之后
 * **一条 `logcat reading` 都没有**（v130 是 80 秒零条、零异常），
 * 车辆数据链路整个静默。旧结构有三个盲区，v131 全部补上：
 *
 * 1. **读循环不再占用巡检线程**。旧结构把阻塞的 `readLine()` 直接放在
 *    `scheduleWithFixedDelay` 的单线程任务里 —— 第一次调用就卡在
 *    `readLine` 上，后续周期任务全部排在后面，既不会打诊断也不会走
 *    重启路径。现在巡检（[supervise]）只做状态检查，阻塞读全部移到
 *    常驻的 pump 线程。
 * 2. **stderr 主动捕获**。旧注释说"不重定向 stderr，让它继承 fd"——
 *    但 Android 上 app 进程的 fd 2 指向 /dev/null，权限拒绝信息
 *    （`unable to log: Permission denied` 之类）**根本看不见**。
 *    现在 stderr 由独立线程读走并打进我们自己的 tag。
 * 3. **三张诊断牌**：首行超时告警（10 秒没读到第一行，带 stderr 快照
 *    与进程存活状态）、未解析行样本（有行但 parseLine 匹配不上时打
 *    首行截断）、30 秒摘要心跳（`lines=/unparsed=/fields=/alive=`）。
 *    下次抓车机日志，三种故障一眼区分：
 *    - `lines=0 alive=true` → 没行（权限 / tag 不匹配）；
 *    - `lines>0 unparsed=lines` → 有行但解析不了（格式变了）；
 *    - `fields={}` 长期不变 → 有行有解析，值没变（车静止 / 桌面被遮）。
 *
 * ## 子进程生命周期
 *
 * 与 [CarCabinVehicleStatus] 同样的坑：`CarPlayHostActivity` 里
 * `onStart`→`start` / `onStop`→`stop` 成对出现，线程池一旦
 * `shutdownNow()` 就永久不能接受新任务。所以 [stop] 只停子进程不置空
 * executor，每次 [start] 用 `takeUnless { isShutdown }` 判断是否重建。
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

    /** 常驻读线程；null 表示当前没有读循环在跑。 */
    @Volatile
    private var pump: Thread? = null

    /** 当前这批子进程的启动时刻（nanoTime），用于首行超时判定。 */
    @Volatile
    private var processStartedAt = 0L

    /** stderr 最近的行（诊断快照）：权限拒绝/用法错误都走这条路。 */
    private val stderrLines: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** 读到的总行数 / parseLine 匹配失败的行数。 */
    private val linesRead = AtomicLong(0)

    private val linesUnparsed = AtomicLong(0)

    /** 是否读到过第一行（区分"还没读到"与"读不到"）。 */
    @Volatile
    private var firstLineSeen = false

    /** 「长时间无输出」告警是否已打过（一次性，不刷屏）。 */
    private val noOutputWarned = AtomicBoolean(false)

    /** 上一条诊断心跳的时刻（nanoTime）。 */
    @Volatile
    private var lastHeartbeatAt = 0L

    /** 上一批已结算的字段快照；新一批有变化才回调。 */
    private var lastFlushed: Map<String, Any> = emptyMap()

    /** 最近一次读到的字段；空表示还没读到任何东西。 */
    @Volatile
    private var fields: Map<String, Any> = emptyMap()

    /**
     * 字段变化回调（**在读取线程上回调**，不是主线程）。
     *
     * 只在值真的变了时触发，静止时不会重复通知。
     */
    @Volatile
    var onFieldsChanged: ((Map<String, Any>) -> Unit)? = null

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
            pool.scheduleWithFixedDelay(::supervise, 0, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            started = false
            Log.w(tag, "logcat poll rejected by the executor; retrying on the next start()")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        executor?.shutdownNow()
        // destroy 会让 pump 线程的 readLine() 立刻 EOF 退出；线程是 daemon，
        // 即使慢一步也不拖住进程退出。
        logcat?.destroy()
        logcat = null
        pump = null
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

    /** 最近一次读到的续航（km）；读不到返回 null。 */
    fun currentRangeKm(): Int? = (fields[B561VehicleLogParser.KEY_RANGE] as? Number)?.toInt()

    /** 最近一次读到的电机转速（rpm）；读不到返回 null。0 = 电机停转，是合法值。 */
    fun currentMotorRpm(): Float? = (fields[B561VehicleLogParser.KEY_MOTOR_RPM] as? Number)?.toFloat()

    /**
     * 最近一次读到的四轮胎压（bar，顺序：左前/右前/左后/右后）；读不到返回 null。
     *
     * 走 `commFloatVectData`（size=4），所以只能从 logcat 拿 ——
     * `CarCabinManager` 不提供向量属性。
     */
    fun currentTirePressureBar(): FloatArray? =
        (fields[B561VehicleLogParser.KEY_TIRE_PRESSURE] as? FloatArray)?.copyOf()

    /** 最近一次读到的总里程（km）；读不到返回 null。 */
    fun currentOdometerKm(): Float? = (fields[B561VehicleLogParser.KEY_ODOMETER] as? Number)?.toFloat()

    /** 最近一次读到的油量百分比；读不到返回 null。纯电车上没有这个值。 */
    fun currentFuelPercent(): Int? = (fields[B561VehicleLogParser.KEY_FUEL_PERCENT] as? Number)?.toInt()

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

    // ── 巡检 / 读循环 / 诊断（v131 重构）─────────────────────────────────

    /**
     * 每秒巡检一次（**永不阻塞**）。
     *
     * 职责只有两个：子进程不在就起一个；在读循环活着的时候打诊断。
     * 重启逻辑天然安全 —— pump 线程退出时已经自己销毁了旧进程。
     */
    private fun supervise() {
        if (!started) return
        val process = ensureLogcat() ?: return
        val thread = pump
        if (thread == null || !thread.isAlive) {
            startPump(process)
            return
        }
        reportDiagnostics(process)
    }

    /** 起一条新的 stderr 收集线程 + 常驻读线程。 */
    private fun startPump(process: Process) {
        stderrLines.clear()
        linesRead.set(0)
        linesUnparsed.set(0)
        firstLineSeen = false
        noOutputWarned.set(false)
        val now = System.nanoTime()
        processStartedAt = now
        lastHeartbeatAt = now
        lastFlushed = emptyMap()
        drainStderrAsync(process)
        val thread = Thread({ pumpLoop(process) }, "b561-vehicle-pump").apply { isDaemon = true }
        pump = thread
        thread.start()
        Log.i(tag, "logcat pump started, process=${describe(process)}")
    }

    /**
     * 常驻读循环：逐行喂 parser，缓冲读空就结算一批。
     *
     * "缓冲读空再结算"复刻了旧 `drainOnce` 的 before/after 语义 ——
     * 一批行只回调一次，不会每行都推仪表。`BufferedReader.ready()`
     * 在缓冲非空或底层可读时为 true，所以空闲时会自然落到结算点。
     */
    private fun pumpLoop(process: Process) {
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (started) {
                    val line = reader.readLine() ?: break
                    linesRead.incrementAndGet()
                    firstLineSeen = true
                    val parsed = runCatching { B561VehicleLogParser.parseLine(line) }.getOrNull()
                    if (parsed == null) {
                        val count = linesUnparsed.incrementAndGet()
                        // 未解析行样本：直接回答"有行但解析不了"还是"根本没行"。
                        if (count == 1L || count % UNPARSED_SAMPLE_EVERY == 0L) {
                            Log.d(tag, "logcat unparsed #$count: ${line.take(UNPARSED_SAMPLE_CHARS)}")
                        }
                    } else {
                        synchronized(fields) {
                            fields = LinkedHashMap(fields).apply { putAll(parsed) }
                        }
                    }
                    if (!reader.ready()) flushIfChanged()
                }
            }
        } catch (e: Exception) {
            // 进程被回收 / 权限被拒 / stdout 关闭。
            Log.w(tag, "logcat stream ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // 一律销毁旧进程，下个巡检周期全新启动 ——
            // 避免"流已断但进程还活着"时对着一条死流永久阻塞。
            runCatching { process.destroy() }
            if (logcat === process) logcat = null
            isRunning = false
        }
    }

    /** 结算一批：字段真的变了才打日志 + 回调。 */
    private fun flushIfChanged() {
        val snapshot = fields
        if (snapshot == lastFlushed) return
        lastFlushed = snapshot
        isRunning = true
        Log.i(
            tag,
            "logcat reading speed=${snapshot[B561VehicleLogParser.KEY_SPEED]} " +
                "gear=${snapshot[B561VehicleLogParser.KEY_GEAR]} " +
                "battery=${snapshot[B561VehicleLogParser.KEY_BATTERY]}% " +
                "voltage=${snapshot[B561VehicleLogParser.KEY_VOLTAGE]} " +
                "current=${snapshot[B561VehicleLogParser.KEY_CURRENT]} " +
                "motorRpm=${snapshot[B561VehicleLogParser.KEY_MOTOR_RPM]}",
        )
        runCatching { onFieldsChanged?.invoke(snapshot) }
            .onFailure { Log.w(tag, "onFieldsChanged failed: ${it.message}") }
    }

    /**
     * 诊断报告：首行超时告警 + 30 秒摘要心跳。
     *
     * 只在读循环活着时调用，所以这里看到的计数就是当前这批进程的真实状态。
     */
    private fun reportDiagnostics(process: Process) {
        val now = System.nanoTime()
        val elapsedMs = (now - processStartedAt) / 1_000_000
        // ① 首行超时（一次性）：10 秒没读到第一行，把 stderr 快照和进程
        //    存活状态一起打出来 —— 权限拒绝在这条里现形。
        if (!firstLineSeen && elapsedMs >= NO_OUTPUT_WARN_MILLIS &&
            noOutputWarned.compareAndSet(false, true)
        ) {
            Log.w(
                tag,
                "logcat no output for ${elapsedMs}ms; alive=${process.isAlive} " +
                    "stderr=[${stderrSnapshot()}]",
            )
        }
        // ② 摘要心跳：下次抓车机日志，三种故障（没行 / 解析不了 / 值不变）
        //    一眼区分。
        if (now - lastHeartbeatAt >= HEARTBEAT_NANOS) {
            lastHeartbeatAt = now
            Log.i(
                tag,
                "logcat pump lines=${linesRead.get()} unparsed=${linesUnparsed.get()} " +
                    "fields=${snapshotFieldNames()} alive=${process.isAlive}",
            )
        }
    }

    /** 心跳里的字段名清单（不带值，避免日志泄露与刷屏）。 */
    private fun snapshotFieldNames(): String =
        fields.keys.joinToString(prefix = "{", postfix = "}") { it }

    /** stderr 快照（诊断告警用）：空集合打 `(empty)`，否则取最近几行截断拼接。 */
    private fun stderrSnapshot(): String = synchronized(stderrLines) {
        if (stderrLines.isEmpty()) {
            "(empty)"
        } else {
            stderrLines.takeLast(STDERR_SNAPSHOT_LINES).joinToString(" | ") { it.take(80) }
        }
    }

    /**
     * stderr 收集线程。
     *
     * ★ v131 新增。Android 上 app 进程的 fd 2 指向 /dev/null，
     * "不重定向 stderr"等于把权限拒绝信息扔进黑洞 —— v126/v130 两版
     * 真车零输出，很可能就是被拒之后什么都看不见。
     */
    private fun drainStderrAsync(process: Process) {
        Thread({
            var printed = 0L
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    while (started) {
                        val line = reader.readLine() ?: break
                        synchronized(stderrLines) {
                            if (stderrLines.size >= STDERR_KEEP) stderrLines.removeAt(0)
                            stderrLines.add(line)
                        }
                        // 前 20 条全打（拒绝信息通常就几行），之后静默收集，
                        // 需要时仍能从告警的快照里看到。
                        if (printed < STDERR_LOG_LIMIT) {
                            printed++
                            Log.i(tag, "logcat stderr: ${line.take(STDERR_LOG_CHARS)}")
                        }
                    }
                }
            } catch (_: Exception) {
                // stderr 读失败不影响主链路。
            }
        }, "b561-vehicle-stderr").apply { isDaemon = true }.start()
    }

    /**
     * 保证有一个活着的 `logcat` 子进程。
     *
     * 订阅四个 tag：
     * - `Property.service` —— VHAL 变化回调，车速/电量/电压/电流都从这里出来；
     * - `FastControlManager` —— 原厂快控，挡位行从这里出来；
     * - `CmdSmdManager` —— **MCU 原始报文层**，电机转速 `0x2160f315` 只从这里出来
     *   （实测 `Property.service` 里一次都没有它，漏了这个 tag 就抓不到转速）；
     * - `CarCabinManager` —— **原厂车舱 API 的拉取痕迹**。电量 `0x2140f636`
     *   在真车上只以 `getIntProperty ... data=68` 的形态出现在这个 tag 里
     *   （原厂桌面轮询时打），`Property.service` 与 `CmdSmdManager` 都没有它。
     *   v128 真车日志实测：漏订这个 tag 时 `VEHICLE battery=null`，而
     *   `power`（电压×电流）正常——就是 SOC 单独走了这条路。
     *
     * `*:D` 是 filter-spec 语法（`tag:priority`），只收 priority ≤ D 的行。
     * 列表提成 [LOG_TAGS] 常量供测试守住，避免再漏。
     */
    private fun ensureLogcat(): Process? {
        logcat?.let { if (it.isAlive) return it }
        return runCatching {
            ProcessBuilder(
                "logcat",
                "-v", "brief",
                *LOG_TAGS,
            ).start()
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

    internal companion object {
        /**
         * logcat 订阅的 tag 列表（filter-spec 语法）。
         *
         * ★ 漏 tag 是静默失败：正则会等一个永远不来的输入行。
         * v128 真车日志就漏了 `CarCabinManager`——SOC 只走它，
         * 结果 `VEHICLE battery=null` 而电压/电流正常，极难排查。
         * 测试 [LogcatVehicleStatusTagTest] 逐项守住这四个。
         */
        val LOG_TAGS = arrayOf(
            "Property.service:D",
            "FastControlManager:D",
            "CmdSmdManager:D",
            "CarCabinManager:D",
        )

        /**
         * 巡检周期。
         *
         * 只做状态检查（永不阻塞），所以这个周期就是故障恢复节奏，
         * 1 秒足够，且不影响数据新鲜度。
         */
        const val POLL_INTERVAL_MILLIS = 1_000L

        /** 首行超时告警阈值：10 秒没读到第一行就打（一次性）。 */
        internal const val NO_OUTPUT_WARN_MILLIS = 10_000L

        /** 摘要心跳周期。 */
        internal const val HEARTBEAT_MILLIS = 30_000L

        /** stderr 环形缓冲容量（告警快照用）。 */
        internal const val STDERR_KEEP = 32

        /** stderr 全量打印的条数上限，之后只收集不打印。 */
        internal const val STDERR_LOG_LIMIT = 20L

        /** stderr 单行打印截断长度。 */
        internal const val STDERR_LOG_CHARS = 200

        /** stderr 快照拼接的行数。 */
        internal const val STDERR_SNAPSHOT_LINES = 3

        /** 未解析行样本的采样间隔（第 1 条必打）。 */
        internal const val UNPARSED_SAMPLE_EVERY = 500L

        /** 未解析行样本的截断长度。 */
        internal const val UNPARSED_SAMPLE_CHARS = 160

        private val HEARTBEAT_NANOS = HEARTBEAT_MILLIS * 1_000_000
    }
}
