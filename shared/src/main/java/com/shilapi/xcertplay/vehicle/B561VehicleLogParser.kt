package com.shilapi.xcertplay.vehicle

/**
 * 长安欧尚 B561 车辆数据的**logcat 解析器**（纯逻辑，无 Android 依赖，可单测）。
 *
 * ## 为什么走日志而不是 VHAL
 *
 * v117 按官方范式实现了 VHAL 读取（`Car.createCar` + `getCarManager("cabin")`），
 * 实车日志里明确失败：
 * ```
 * 11:35:46.389 W B561-VehicleStatus: Car service connect failed: InvocationTargetException: null
 * 11:35:46.414 D CAR.L: onServiceConnected mService=android.car.ICar$Stub$Proxy@f4d72c6
 * ```
 * —— **`car.connect()` 是异步的**，`onServiceConnected` 在 25ms 后才回调，
 * 而 v117 在 `connect()` 返回后立刻 `getCarManager("cabin")`，此时 service 还没连上，
 * 于是每 1Hz 抛一次 `InvocationTargetException`，`cabin` 永远是 null。
 *
 * 但**数据本身是好的**——同一份日志里原厂 app（pid 2202 `NewEnergyDeskView2CtrManager`）
 * 读得好好的：
 * ```
 * 11:35:40.818 D CarCabinManager: getIntProperty 0x2140f4a9 data=4
 * 11:35:46.389 D CarCabinManager: getIntProperty 0x2140f636 data=49
 * ```
 * 说明 VHAL 侧没问题，纯粹是**连接时序**问题。
 *
 * 与其跟异步时序死磕，不如换一条**已经验证在跑的路**：原厂组件本来就在
 * 每次 VHAL 变化时打 logcat，我们把这些行解析出来即可。相对 VHAL 的优势：
 * - 绕开 Car Service 的异步连接与 `InvocationTargetException`；
 * - 不需要 `android.car` 权限与 AIDL 绑定；
 * - **同源** —— 打日志的就是读 VHAL 的原厂组件，值与仪表一致。
 *
 * ## 三类日志行（全部来自实车日志实测，非推测）
 *
 * | 数据 | 行形态 | 实测样例 |
 * |---|---|---|
 * | 车速/电量/电压/电流 | `Property.service: property:0x... on change, values=...` | `property:0x11600207 on change, values=16.70625` |
 * | 挡位 | `FastControlManager: _ID_GEAR_SELECTION  gearValue: 8` | 同左 |
 * | （备用）任意属性 | `CarCabinManager: getIntProperty 0x... data=N` | `getIntProperty 0x2140f636 data=49` |
 *
 * `Property.service` 那条是**变化即打点**，车速 1Hz 级、电量只在变化时出现，
 * 所以读数新鲜度足够；`FastControlManager` 的挡位行与车速行**成对出现**
 * （实测 330 vs 331、2 vs 2），说明同一原厂组件在刷，两个字段天然同步。
 *
 * ## ★ 解析器是纯函数
 *
 * [parseLine] 输入一行文本、输出增量字段，不碰 logcat、不碰 Android。
 * 真正的读取在 [LogcatVehicleStatus]（需要 shell/日志权限）。
 * 这样单测可以直接喂实车日志行，无需设备。
 */
object B561VehicleLogParser {

    /** 车速 `0x11600207` 的日志键。 */
    const val KEY_SPEED = "speed"

    /** 电量 `0x2140f636` 的日志键。 */
    const val KEY_BATTERY = "battery"

    /** 电压 `0x2160f503` 的日志键。 */
    const val KEY_VOLTAGE = "voltage"

    /** 电流 `0x2160f502` 的日志键。 */
    const val KEY_CURRENT = "current"

    /** 挡位 `0x11400400` 的日志键。 */
    const val KEY_GEAR = "gear"

    /** 电机转速 `0x2160f315` 的日志键。 */
    const val KEY_MOTOR_RPM = "motorRpm"

    /** 四轮胎压 `0x11610309` 的日志键（Float 数组，bar，左前/右前/左后/右后）。 */
    const val KEY_TIRE_PRESSURE = "tirePressure"

    /** 总里程 `0x21600309` 的日志键。 */
    const val KEY_ODOMETER = "odometer"

    /** 油量百分比（燃油版）的日志键。 */
    const val KEY_FUEL_PERCENT = "fuelPercent"

    /** 续航 `0x2140f47c` 的日志键（实车未见上报，读不到就报 null）。 */
    const val KEY_RANGE = "range"

    /**
     * `Property.service` 的一行。
     *
     * ```
     * 10-22 11:49:34.141  1250  1376 D Property.service: property:0x2140f4a9 on change, values=-103
     * ```
     *
     * 用「`property:0x` + 6 位十六进制 + … `values=` + 数值」做锚点，
     * 不锚定行首时间戳与 pid —— 那部分格式随 ROM 变化，最不稳。
     */
    private val PROPERTY_CHANGE = Regex(
        """property:(0x[0-9a-fA-F]+)\s+on change,\s*values=(-?[0-9]+(?:\.[0-9]+)?)""",
    )

    /**
     * `CarCabinManager.getIntProperty` / `getFloatProperty` 的一行（备用源）。
     *
     * ```
     * 10-22 11:49:30.210  2202  2202 D CarCabinManager: getIntProperty 0x2140f636 data=49
     * ```
     *
     * 这类是**原厂主动拉取值**时打的，只在原厂界面刷新时出现，
     * 所以只作 `Property.service` 之外的补漏，不作主源。
     */
    private val CAR_CABIN_GET = Regex(
        """get(?:Int|Float)Property\s+(0x[0-9a-fA-F]+)\s+data=(-?[0-9]+(?:\.[0-9]+)?)""",
    )

    /**
     * `CmdSmdManager` 的 MCU 原始报文行。
     *
     * ```
     * 10-22 11:41:31.903   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=500.000000
     * ```
     *
     * ★ **电机转速只走这条路，不走 `Property.service`** ——
     * `Property.service` 是 VHAL 变化回调，而 `0x2160f315` 由 pid 417 的
     * `CmdSmdManager`（MCU 报文层）直接打点，实测 `Property.service` 里
     * 一次都没有它。所以要抓转速，logcat 订阅里**必须**加 `CmdSmdManager` 这个 tag。
     *
     * 该tag 有五种报文形态（实测计数）：
     * ```
     * 23517  commInt32Data----prop=<ID>,value=N
     * 15545  commFloatData----prop=<ID>,value=F
     *  4559  commFloatVectData----prop=<ID>,size=N,vec=[...]
     *  4012  commInt32VecData----prop=<ID>,size=N,vec=[...]
     *    32  commBytesData----prop=<ID>,value=<bytes>
     * ```
     * 只有 `commFloatData` / `commInt32Data` 是标量，够用了；
     * 向量与字节形态不解析（我们不关心多维数组）。
     */
    private val CMD_SMD_SCALAR = Regex(
        """----comm(?:Float|Int32)Data----prop=(0x[0-9a-fA-F]+),value=(-?[0-9]+(?:\.[0-9]+)?)""",
    )

    /**
     * `CmdSmdManager` 的**向量**报文行（胎压走这条）。
     *
     * ```
     * 19:01:54.232 D CmdSmdManager: ----commFloatVectData----prop=0x11610309,size=4,vec=[2.48513,2.41648,2.48513,2.38902]
     * ```
     *
     * ★ 胎压是四轮数组，只能走向量形态，所以必须单独一条正则
     * —— 标量的 [CMD_SMD_SCALAR] 匹配不到它。
     */
    private val CMD_SMD_VECTOR = Regex(
        """----comm(?:Float|Int32)VectData----prop=(0x[0-9a-fA-F]+),size=(\d+),vec=\[([^\]]*)\]""",
    )

    /**
     * 原厂 `FastControlManager` 的挡位行。
     *
     * ```
     * 10-22 11:35:40.819 I FastControlManager: _ID_GEAR_SELECTION  gearValue: 8
     * 10-22 11:35:40.819 D FastControlManager: getGearStatus: 8
     * 10-22 11:35:40.819 D FastControlManager: _getSensorGearPosition === event.getGearData(null).gear: 8
     * 10-22 11:35:40.819 D FastControlManager: _getCarCabinProperty success. property:GEAR_SELECTION , areaId:0 , value:8
     * ```
     *
     * **四种形态在实车里都出现过**，取值都是同一个
     * [B561VehicleProperty.GEAR_SELECTION]。四个分支全列上而不是挑一个短的：
     * 实车日志里 `FastControlManager` 的这四行是**同一次刷里连续打出**的，
     * 哪个分支没覆盖到都会在固件调整日志级别时静默丢挡位。
     *
     * 第 4 条的 `property:GEAR_SELECTION` 后面跟的是**名字**不是 `0x` 十六进制，
     * 所以不能复用 [PROPERTY_CHANGE]（那里锚的是 `property:0x...`）。
     */
    private val GEAR_STATUS = Regex(
        """(?:_ID_GEAR_SELECTION\s+gearValue|getGearStatus|getGearData\(null\)\.gear)\s*[:=]\s*(-?\d+)""" +
            """|property:GEAR_SELECTION\s*,.*?\bvalue\s*[:=]\s*(-?\d+)""",
    )

    /** 属性 ID → 日志键。挡位走 [GEAR_STATUS]，不在这张表里。 */
    private val KEY_BY_PROPERTY: Map<Int, String> = mapOf(
        B561VehicleProperty.SPEED to KEY_SPEED,
        B561VehicleProperty.BATTERY_PERCENT to KEY_BATTERY,
        B561VehicleProperty.VOLTAGE to KEY_VOLTAGE,
        B561VehicleProperty.CURRENT to KEY_CURRENT,
        B561VehicleProperty.MOTOR_RPM to KEY_MOTOR_RPM,
        B561VehicleProperty.ODOMETER to KEY_ODOMETER,
    )

    /** 电量候选属性 → [KEY_BATTERY]。 */
    private val BATTERY_PROPERTY_IDS: Set<Int> =
        B561VehicleProperty.BATTERY_PERCENT_CANDIDATES.toSet()

    /** 续航候选属性 → [KEY_RANGE]。 */
    private val RANGE_PROPERTY_IDS: Set<Int> =
        B561VehicleProperty.RANGE_KM_CANDIDATES.toSet()

    /** 油量百分比候选属性 → [KEY_FUEL_PERCENT]。 */
    private val FUEL_PROPERTY_IDS: Set<Int> =
        B561VehicleProperty.FUEL_PERCENT_CANDIDATES.toSet()

    /**
     * 解析一行日志。
     *
     * @return 该行携带的字段增量；不是车辆数据行时返回 null。
     */
    fun parseLine(line: String): Map<String, Any>? {
        // 挡位优先：FastControlManager 的挡位行不携带 property ID，
        // 若先跑 PROPERTY_CHANGE 会因为这行里没有 0x 而直接落空，所以顺序无讲究，
        // 但两种正则都要试（有的行只匹配其一）。
        parsePropertyLine(line)?.let { return it }
        parseGearLine(line)?.let { return it }
        // MCU 报文行（电机转速只走这条路）。
        parseCmdSmdLine(line)?.let { return it }
        return null
    }

    /** 解析 `Property.service` / `CarCabinManager.get*Property` 两类行。 */
    private fun parsePropertyLine(line: String): Map<String, Any>? {
        val match = PROPERTY_CHANGE.find(line) ?: CAR_CABIN_GET.find(line) ?: return null
        val propertyId = parseHexId(match.groupValues[1]) ?: return null
        val raw = match.groupValues[2].toDoubleOrNull() ?: return null
        return fieldsFor(propertyId, raw)
    }

    /**
     * 解析 `CmdSmdManager` 的 MCU 报文行。
     *
     * 电机转速 `0x2160f315` **只**在这里出现（`Property.service` 里没有），
     * 所以这条路径是转速的唯一来源。
     */
    private fun parseCmdSmdLine(line: String): Map<String, Any>? {
        // 先用 contains 过滤：这个 tag 在实车里每秒能出十几行，
        // 无条件对每行跑正则太浪费。
        if (!line.contains("CmdSmdManager")) return null
        // 向量优先：标量正则匹配不到 commFloatVectData 那行。
        parseCmdSmdVector(line)?.let { return it }
        val match = CMD_SMD_SCALAR.find(line) ?: return null
        val propertyId = parseHexId(match.groupValues[1]) ?: return null
        val raw = match.groupValues[2].toDoubleOrNull() ?: return null
        return fieldsFor(propertyId, raw)
    }

    /**
     * 解析 `CmdSmdManager` 的向量报文行（目前只有四轮胎压用得上）。
     *
     * 只认长度正好等于 [B561VehicleProperty.TIRE_PRESSURE_COUNT] 的胎压向量；
     * 其它向量属性（车上有 4559 条 commFloatVectData）一律不解析 ——
     * 我们不关心多维数组，乱认反而会画错东西。
     */
    private fun parseCmdSmdVector(line: String): Map<String, Any>? {
        val match = CMD_SMD_VECTOR.find(line) ?: return null
        val propertyId = parseHexId(match.groupValues[1]) ?: return null
        if (propertyId != B561VehicleProperty.TIRE_PRESSURE) return null
        val size = match.groupValues[2].toIntOrNull() ?: return null
        if (size != B561VehicleProperty.TIRE_PRESSURE_COUNT) return null
        val values = match.groupValues[3]
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
        if (values.size != B561VehicleProperty.TIRE_PRESSURE_COUNT) return null
        // 四个值都要在合理区间才算有效 —— 混进 0（TPMS 未连接）就整组丢弃，
        // 免得仪表上出现"三个轮有压一个轮 0"的怪相。
        if (values.any {
                it < B561VehicleProperty.MIN_TIRE_PRESSURE_BAR ||
                    it > B561VehicleProperty.MAX_TIRE_PRESSURE_BAR
            }
        ) {
            return null
        }
        // 用 FloatArray 而不是 toTypedArray() —— 后者得到的是 Array<Float>（装箱），
        // 取值处还得再转一遍，而且 equals 是引用比较。
        return mapOf(KEY_TIRE_PRESSURE to FloatArray(values.size) { values[it] })
    }

    /** 解析 `FastControlManager` 的挡位行。 */
    private fun parseGearLine(line: String): Map<String, Any>? {
        // 只在真的出现这个 tag 时才去跑正则：实车 logcat 流量很大，
        // 无条件对每行跑多个正则是白烧 CPU。
        if (!line.contains("FastControlManager")) return null
        val match = GEAR_STATUS.find(line) ?: return null
        // 两个分支各有一个捕获组，哪个先匹配上就用哪个；
        // 未参与匹配的组是 null，`orEmpty()` 兜住。
        val raw = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull() ?: return null
        return mapOf(KEY_GEAR to raw)
    }

    /** 属性 ID + 原始值 → 日志键。值无效时返回 null（不产出垃圾字段）。 */
    private fun fieldsFor(propertyId: Int, raw: Double): Map<String, Any>? = when {
        KEY_BY_PROPERTY.containsKey(propertyId) -> {
            // 车速/电压/电流是 Float；电量是 Int。按目标键的类型转换，
            // 免得 0x2140f636 的 49 被当成 49.0 交给 batteryPercent 做整型比较。
            when (KEY_BY_PROPERTY[propertyId]) {
                KEY_BATTERY -> B561VehicleProperty.batteryPercent(raw.toInt())
                    ?.let { mapOf(KEY_BATTERY to it) }
                KEY_SPEED -> B561VehicleProperty.speedKph(raw.toFloat())
                    ?.let { mapOf(KEY_SPEED to it) }
                KEY_VOLTAGE -> if (raw > 0f) mapOf(KEY_VOLTAGE to raw.toFloat()) else null
                // 电流实测为负（-0.4..-2.4，放电），所以这里**不**做正负过滤。
                KEY_CURRENT -> mapOf(KEY_CURRENT to raw.toFloat())
                // 电机转速：负值与超上限都判为无效（理由同车速）。
                KEY_MOTOR_RPM -> B561VehicleProperty.motorRpm(raw.toFloat())
                    ?.let { mapOf(KEY_MOTOR_RPM to it) }
                // 总里程：只接受正数（0 视为未上报）。
                KEY_ODOMETER -> if (raw > 0f) mapOf(KEY_ODOMETER to raw.toFloat()) else null
                else -> null
            }
        }

        propertyId in BATTERY_PROPERTY_IDS ->
            B561VehicleProperty.batteryPercent(raw.toInt())?.let { mapOf(KEY_BATTERY to it) }

        propertyId in RANGE_PROPERTY_IDS ->
            if (raw > 0) mapOf(KEY_RANGE to raw.toInt()) else null

        propertyId in FUEL_PROPERTY_IDS ->
            // 油量百分比：0 视为未加注，读不到就报 null ——
            // 绝不能拿电量冒充油量，那会让燃油版仪表显示一个假数字。
            if (raw in 1.0..100.0) mapOf(KEY_FUEL_PERCENT to raw.toInt()) else null

        else -> null
    }

    /** `0x` 前缀的十六进制属性 ID → Int。 */
    private fun parseHexId(text: String): Int? =
        runCatching { text.substring(2).toLong(16).toInt() }.getOrNull()

    /**
     * 把一个日志行数组折叠成完整读数。
     *
     * 后出现的行覆盖先出现的（logcat 是时序流，后到的更新）。
     * 字段用 [LinkedHashMap] 保留插入顺序，方便单测断言与日志输出。
     */
    fun fold(lines: Iterable<String>): Map<String, Any> {
        val fields = LinkedHashMap<String, Any>()
        for (line in lines) {
            val parsed = parseLine(line) ?: continue
            fields.putAll(parsed)
        }
        return fields
    }
}