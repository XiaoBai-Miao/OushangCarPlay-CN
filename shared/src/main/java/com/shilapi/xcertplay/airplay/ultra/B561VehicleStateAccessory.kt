package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafCommand
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessageReader
import com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.vehicle.B561UltraGauges

/**
 * B561 的 **CarPlay Ultra 车辆状态 accessory**（CAF 侧）。
 *
 * ## 它做什么
 *
 * 把我们的车辆数据（转速/功率/续航/里程/胎压…）通过 CAF characteristic
 * 暴露给 iPhone，iPhone 据此渲染自己的仪表与能耗页面。
 *
 * ## ★ v130: 为什么要重写这棵树
 *
 * v125/v126 只声明了 4 个 accessory（combustion / electric / fuel / tires），
 * 而且 fuel 与 tires 的 **kind 与 LIVI 对不上**。真车实测（v126 日志）：
 * iPhone 走完 `configRequest → configResponse` 之后**一条 registerRequest
 * 都没发**，RCS 通道静默到 EOF —— 没有订阅就没有 updateNotify，
 * 车辆数据一条都到不了 iPhone，Ultra 仪表自然出不来（仪表上只有地图流）。
 *
 * v130 按 **LIVI（f-io/LIVI，`vehicle_state.rs`）的最小可用集**重建：
 * navigation / automaker_settings / vehicle_resources / fuel / trip_computer /
 * tires / audio + engines，iid、kind、characteristic type 逐项对齐。
 * 其中最关键的两处修正：
 *
 * 1. **fuel accessory**：kind `0x0100_0002` → `0x0900_0006`，
 *    service `0x1A00_0002` → `0x1A00_0005`，
 *    fuelLevel type `0x3000_0007` → `0x4100_0002`（LIVI 的 `fuel()`）；
 *    续航/里程从"挤在一个 service 里"拆成 trip_computer 的 distance service，
 *    每个距离补 miles 孪生特性（`0x3000_004B`）与 hidden 标志（`0x3600_0023`）。
 * 2. **tires accessory**：kind `0x0100_0004` → `0x0A00_0001`（LIVI 的 `tires()`）。
 *
 * 另外补上 **navigation**（iid 256）—— 它带 `vehicleEnabled=true`，
 * 是"这台车的仪表能显示导航"的声明；v125/v126 完全没有它。
 *
 * ## ★ v131: initialValue 预填（LIVI `initial_values()` 对齐）
 *
 * v130 真车日志实证：iPhone 订阅之后拿到的 register 响应**全是
 * -6727（NO_VALUE）**，车辆 UI 一片空白 —— 车机两个数据源
 * （logcat 子进程 / CarCabinManager）在那台车上全废。
 * LIVI 的量产做法（`initial_values()`）是把 config 树里声明的
 * initialValue 直接预填进响应：RPM=0 / 音量=50 / 传感器 WAITING…
 * iPhone 至少看到合法下限，数据源恢复后真实值覆盖。
 *
 * 取值优先级：**iPhone 写过的值 > 真实读数 > initialValue**。
 * register 响应同时记为"已发送"基线 —— 不同步的话，第一条 gauges
 * 更新会把全部预填值当变化重发（0→0 也算变），白灌全量帧。
 *
 * ## iid 与 type 的来源
 *
 * 全部照 LIVI 的 `vehicle_state.rs`（Rust，开源里最完整的一套）：
 *
 * | 语义 | iid | characteristic type | 格式 |
 * |---|---|---|---|
 * | 转速 `rotationalSpeed` | 2562 | `0x3000_0024` | UINT32，单位 rpm(981) |
 * | 转速上限 | 2563 | `0x3000_004E` | UINT32 |
 * | 红线 | 2564 | `0x3000_004F` | UINT32 |
 * | 功率 `power` | 2818 | `0x3000_0028` | FLOAT，单位 kW(884) |
 * | 油量 `fuelLevel` | 1538 | `0x4100_0002` | UINT8，% |
 * | 油量低标记 | 1539 | `0x4100_001A` | UINT8，% |
 * | 燃油续航 km / mi | 1541 / 1542 | `0x3000_004A` / `0x3000_004B` | UINT64 |
 * | 剩余续航 km / mi | 1794 / 1795 | 同上 | UINT64 |
 * | 总里程 km / mi | 1798 / 1799 | 同上 | UINT64 |
 * | 胎压 kPa | 2050+5n | `0x3000_001B` | UINT32，单位 kPa(932) |
 * | 音量 | 2306+… | `0x3300_0006` | UINT8，% |
 *
 * accessory / service 的 kind：
 * - `navigation`：iid 256，kind `0x0E00_0002` —— **Ultra 导航门面**
 * - `automaker settings`：iid 1280，kind `0x0500_0001`
 * - `vehicle resources`：iid 768，kind `0x0120_0002`（interiorStyle）
 * - `combustion engine`：iid 2560，kind `0x0900_0005`，service iid 2561、
 *   kind `0x1A00_000F` —— **有发动机转速**（燃油/混动）
 * - `electric engine`：iid 2816，kind `0x0900_0004`，service iid 2817、
 *   kind `0x1A00_000D` —— **只有功率**（纯电）
 * - `fuel`：iid 1536，kind `0x0900_0006`
 * - `trip computer`：iid 1792，kind `0x0400_0001`
 * - `tires`：iid 2048，kind `0x0A00_0001`
 * - `audio`：iid 2304，kind `0x0200_0001`
 *
 * ## ★ 纯电车没有「电机转速」这个字段
 *
 * Apple 的 Ultra 协议里，**转速属于发动机**（`combustion_engine`）。
 * 纯电车只挂 `electric_engine`，那个 accessory 只有功率。
 * 所以我们把电机转速（`0x2160f315`）**映射到发动机的转速槽位** ——
 * 对驾驶员来说「有一个转速表」比「转速表空着」有用。
 * 混动/燃油车若能拿到发动机转速，会优先用真的那个。
 *
 * ## ★ 读不到就不给
 *
 * 没有值的字段**不声明**（不给 characteristic），而不是给个 0 ——
 * 报一个假的 0 转速会让驾驶员以为传感器坏了。
 */
internal class B561VehicleStateAccessory(
    /** 当前读数；null 表示还没读到任何数据。 */
    private val sample: () -> B561UltraGauges?,
) {

    private val subscriptions = linkedSetOf<Long>()

    /** 上次已发出的值，用来判断「真的变了」。 */
    private var lastSent: Map<Long, Any?> = emptyMap()

    /** iPhone 写过来的值（phone-owned 特性：导航 legs / 音量…）。原样回读。 */
    private val written = linkedMapOf<Long, Any?>()

    /**
     * 树里声明了 initialValue 的特性 → 预填值（LIVI `initial_values()`）。
     *
     * ★ v131：为什么需要它。v130 真车日志实证：iPhone 订阅之后拿到的
     * register 响应**全是 -6727（NO_VALUE）**，车辆 UI 一片空白 ——
     * 我们的两个车机数据源（logcat 子进程 / CarCabinManager）在那台车上
     * 全废，一个真实读数都拿不到。LIVI 的量产做法是把 config 树里声明的
     * initialValue 直接预填进响应：iPhone 至少能看到合法初始值
     * （RPM=0 / 音量=50 / 传感器 WAITING…），数据源恢复后真实值覆盖。
     *
     * 取值优先级：**iPhone 写过的值 > 真实读数 > initialValue**。
     * 注意这与"读不到就不给"不矛盾 —— 铁律管的是 `changed()`（不得把
     * 预填的 0 当真实变化推给 iPhone），这里只管一次性响应的下限。
     */
    private val initialValues: Map<Long, Any?> by lazy {
        val result = linkedMapOf<Long, Any?>()

        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    // 只有 characteristic 带 initialValue 键；accessory/service
                    // 的 iid 不会误收（它们没有这个键）。
                    val iid = (node["iid"] as? Number)?.toLong()
                    if (iid != null && node.containsKey("initialValue")) {
                        result[iid] = node["initialValue"]
                    }
                    node.values.forEach(::walk)
                }
                is List<*> -> node.forEach(::walk)
            }
        }

        walk(config(hasEngineRpm = true))
        result
    }

    /** 本 accessory 声明的全部 iid。 */
    private val knownIds: Set<Long> = buildSet {
        addAll(NAVIGATION_IDS)
        addAll(AUTOMAKER_IDS)
        addAll(VEHICLE_RESOURCE_IDS)
        addAll(ENGINE_RPM_IDS.toList())
        add(ENGINE_POWER)
        addAll(FUEL_IDS)
        addAll(TRIP_IDS)
        addAll(TIRE_PRESSURE_IDS)
        addAll(TIRE_EXTRA_IDS)
        addAll(AUDIO_IDS)
        addAll(CLIMATE_IDS)
    }

    /**
     * CAF config 树：告诉 iPhone「我有哪些 characteristic、什么格式、什么量程」。
     *
     * 顺序照 LIVI 的默认配件表：navigation / automaker / vehicle_resources /
     * fuel / trip_computer / tires / audio，engines 追加在最后
     * （LIVI 的 `with_engines` 就是 extend 到末尾）。
     *
     * 纯电车**不声明**转速那三个 iid（Apple 协议里纯电车没有转速字段）。
     */
    fun config(hasEngineRpm: Boolean = true): Map<String, Any?> {
        val accessories = arrayListOf<Map<String, Any?>>(
            navigation(),
            automakerSettings(),
            vehicleResources(),
            climate(),
            fuel(),
            tripComputer(),
            tires(),
            audio(),
        )
        if (hasEngineRpm) accessories.add(combustionEngine())
        accessories.add(electricEngine())
        return linkedMapOf("accessories" to accessories)
    }

    /**
     * 处理一条 CAF 请求。
     *
     * 返回 null 表示这条消息不归我们管（交给上层）。
     *
     * ★ v130：补上 writeRequest / controlRequest 的应答。
     * v125/v126 对这两类命令**静默丢弃**（返回 null 不上报），
     * 而 phone-owned 特性（导航 legs、音量）一旦声明，iPhone 就一定会写 ——
     * 不应答会被当成插件无响应。
     */
    fun handle(message: CafMessageReader): CafRcsFrame? {
        if (message.pluginId != PLUGIN_ID) {
            return message.transactionId?.let {
                CarAccessoryMessages.generalError(message.pluginId, PARAMETER_ERROR, it)
            }
        }
        return when (message.command) {
            CafCommand.CONFIG_REQUEST -> CarAccessoryMessages.configResponse(
                PLUGIN_ID, message.requireTransactionId(), config(hasEngineRpm = true),
            )

            CafCommand.READ_REQUEST -> {
                val ids = identifiers(message)
                CarAccessoryMessages.readResponse(
                    PLUGIN_ID, message.requireTransactionId(), values(ids), errors(ids),
                )
            }

            CafCommand.REGISTER_REQUEST -> {
                // CarPlay Ultra 的「订阅」就是 CAF 的 registerRequest ——
                // CAF 没有单独的 subscribe 命令（那是 UAV 协议的）。
                val ids = if (message.values() == "*") knownIds.toList() else identifiers(message)
                val transaction = message.requireTransactionId()
                subscriptions.addAll(ids.filter { it in knownIds })
                val values = values(ids)
                // ★ v131：register 响应就是当前值快照 —— 把它记为"已发送"基线。
                // 不同步的话，第一条 gauges 更新会把全部预填值当变化重发
                // （0→0 也算变），白灌一条全量 updateNotify。
                lastSent = lastSent + values
                CarAccessoryMessages.registerResponse(
                    PLUGIN_ID, transaction, values, errors(ids),
                )
            }

            CafCommand.UNREGISTER_REQUEST -> {
                val ids = identifiers(message)
                val transaction = message.requireTransactionId()
                subscriptions.removeAll(ids.toSet())
                CarAccessoryMessages.unregisterResponse(PLUGIN_ID, transaction)
            }

            // ★ v130：phone-owned 特性的写入。我们不驱动这些特性，
            //   但必须原样收下并回读，否则 iPhone 认为插件无响应。
            CafCommand.WRITE_REQUEST -> {
                message.valuesMap().forEach { (iid, value) -> written[iid] = value }
                CarAccessoryMessages.writeResponse(
                    PLUGIN_ID, message.requireTransactionId(), emptyMap(),
                )
            }

            // ★ v130：control（收音机按键那类）。我们一个 control 都没声明，
            //   照 LIVI 的做法全部回 NO_SUCH_COMMAND。
            CafCommand.CONTROL_REQUEST -> {
                val ids = identifiers(message)
                CarAccessoryMessages.controlResponse(
                    PLUGIN_ID, message.requireTransactionId(), emptyMap(),
                    ids.associateWith { NO_SUCH_COMMAND },
                )
            }

            else -> null
        }
    }

    /** 当前订阅的 iid 集合。 */
    fun subscribedIds(): Set<Long> = subscriptions

    /**
     * 有订阅的 iid 值变了 → 发一条 updateNotify。
     *
     * 只报**真的变了**的 iid（与上次发出的比），没有变化就返回 null ——
     * 车辆数据每秒都在变，不做这个比较会一直往 iPhone 灌包。
     */
    fun changed(): CafRcsFrame? {
        val current = values(subscriptions.toList())
        val changed = current.filter { (iid, value) -> lastSent[iid] != value }
        if (changed.isEmpty()) return null
        lastSent = lastSent + changed
        return CarAccessoryMessages.updateNotify(PLUGIN_ID, changed)
    }

    /** 按 iid 取当前值；没有值的 iid 不出现在结果里（报 0 会被当成真值）。 */
    private fun values(ids: List<Long>): Map<Long, Any?> {
        val data = sample()
        val result = linkedMapOf<Long, Any?>()
        for (id in ids) {
            valueOf(data, id)?.let { result[id] = it }
        }
        return result
    }

    /**
     * 按 iid 取值；null = 没有该读数。
     *
     * 三级取值：iPhone 写过的值 → 真实读数 → initialValue 预填。
     */
    private fun valueOf(data: B561UltraGauges?, id: Long): Any? {
        // ① iPhone 写过的值优先回读（phone-owned 特性）—— 这与车辆数据无关，
        // **没有 sample 时也必须能回读**（v130 修：旧写法 sample 为 null 时
        // 整个 values() 直接返回空表，写进来的音量/导航 legs 读不回来，
        // iPhone 会认为插件无响应）。
        written[id]?.let { return it }
        // ② 真实读数优先于 initialValue —— 车机有真数据时报真值。
        if (data != null) {
            computedValue(data, id)?.let { return it }
        }
        // ③ initialValue 预填（LIVI initial_values()，v131）：无真实读数时
        // 至少给合法初始值，不再一片 NO_VALUE。
        return initialValues[id]
    }

    /** 从真实读数算值；null = 这个读数没有（再往下跌落 initialValue）。 */
    private fun computedValue(data: B561UltraGauges, id: Long): Any? {
        when (id) {
            ENGINE_RPM -> return data.primaryRpm?.toLong()
            2563L -> return MAX_RPM
            2564L -> return REDLINE_RPM
            ENGINE_POWER -> return data.powerKw?.toDouble()
            FUEL_LEVEL -> return data.fuelPercent?.toLong()
            FUEL_LEVEL_MARKER_LOW -> return LOW_FUEL_PCT
            FUEL_RANGE_KM -> return data.rangeKm?.toLong()
            FUEL_RANGE_MILES -> return miles(data.rangeKm)
            FUEL_RANGE_HIDDEN -> return data.rangeKm == null
            REMAINING_RANGE_KM -> return data.rangeKm?.toLong()
            REMAINING_RANGE_MILES -> return miles(data.rangeKm)
            REMAINING_RANGE_HIDDEN -> return data.rangeKm == null
            ODOMETER_KM -> return data.odometerKm?.toLong()
            ODOMETER_MILES -> return data.odometerKm?.let { miles(it.toInt()) }
        }
        val index = TIRE_PRESSURE_IDS.indexOf(id)
        if (index >= 0) {
            val tires = data.tirePressureBar ?: return null
            if (index >= tires.size) return null
            // bar → kPa（Apple 侧用 kPa；LIVI 的 set_gauges 也是这么转的）
            return (tires[index] * 100f).toLong()
        }
        // 胎压传感器状态：有读数 → ACTIVE(1)，没有 → 初始的 WAITING(3)。
        // TIRE_EXTRA_IDS 每轮 3 项（布局 key / 胎压状态 / 传感器状态），
        // 传感器状态落在每轮第 3 项 → 索引 % 3 == 2。
        val extra = TIRE_EXTRA_IDS.indexOf(id)
        if (extra >= 0 && extra % 3 == 2) {
            return if (data.tirePressureBar != null) SENSOR_ACTIVE else SENSOR_WAITING
        }
        return null
    }

    /** km → miles（Apple 侧的双单位孪生特性）。 */
    private fun miles(km: Int?): Long? = km?.let { Math.round(it / KM_PER_MILE) }

    /** 报错的 iid：请求了但我们没数据（phone 写过的值不算缺数据）。 */
    private fun errors(ids: List<Long>): Map<Long, Long> {
        val data = sample()
        return ids.filter { valueOf(data, it) == null }
            .associateWith { NO_VALUE }
    }

    /** 从请求里取 iid 列表。 */
    private fun identifiers(message: CafMessageReader): List<Long> {
        val values = message.values()
        if (values == "*") return knownIds.toList()
        // values 是一个 {iid: value} 映射 —— 取它的键就是要读的 iid。
        return when (values) {
            is Map<*, *> -> values.keys.mapNotNull { (it as? Number)?.toLong() }
            is List<*> -> values.mapNotNull { (it as? Number)?.toLong() }
            else -> emptyList()
        }
    }

    // ── CAF 树构造辅助 ──────────────────────────────────────────────────

    /** 特性归属：决定 writable / oemWritable 两个标志。 */
    private enum class Owner { PHONE, CAR, SHARED }

    private fun accessory(iid: Long, type: Long, services: List<Map<String, Any?>>) =
        linkedMapOf<String, Any?>(
            "type" to type, "iid" to iid, "version" to ACCESSORY_VERSION, "services" to services,
        )

    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>) =
        linkedMapOf<String, Any?>(
            "type" to type, "iid" to iid, "characteristics" to chars,
        )

    private fun characteristic(
        iid: Long,
        type: Long,
        format: CafCharacteristicFormat,
        value: Any?,
        owner: Owner = Owner.CAR,
        mutable: Boolean = true,
    ) = linkedMapOf<String, Any?>(
        "iid" to iid,
        "type" to type,
        "format" to format.wireValue,
        "writable" to (owner != Owner.CAR),
        "oemWritable" to (owner != Owner.PHONE),
        "mutable" to mutable,
        "priority" to HIGH_PRIORITY,
    ).apply {
        if (value != null) put("initialValue", value)
        if (mutable) put("supportsInvalid", true)
        // 非数值格式照 LIVI 补 maximumLength，缺了它 iPhone 可能拒解析整棵树。
        if (format == CafCharacteristicFormat.STRING || format == CafCharacteristicFormat.DATA ||
            format == CafCharacteristicFormat.DICTIONARY || format == CafCharacteristicFormat.ARRAY
        ) {
            put("maximumLength", MAX_LENGTH)
        }
    }

    /** 数值区间三元组（minimum / maximum / step），照 LIVI 的 up_to/between。 */
    private fun MutableMap<String, Any?>.range(min: Long, max: Long, step: Long = 1L) =
        apply {
            put("minimumValue", min)
            put("maximumValue", max)
            put("stepValue", step)
        }

    private fun MutableMap<String, Any?>.range(min: Double, max: Double, step: Double) =
        apply {
            put("minimumValue", min)
            put("maximumValue", max)
            put("stepValue", step)
        }

    /**
     * 转速类特性。
     *
     * ★ v131 修正：`initial` 不再是硬编码 0 —— LIVI 的 `combustion_engine()`
     * 里 rotationalSpeed initial=0，但 **rotationalSpeedMax initial=max、
     * rotationalSpeedMarkerRedline initial=redline**（v125 移植时把三个
     * 都写成 0，上限=0 会让 iPhone 的转速表刻度塌缩，预填后直接暴露）。
     */
    private fun rpmCharacteristic(iid: Long, type: Long, initial: Long, fixed: Boolean) =
        characteristic(iid, type, CafCharacteristicFormat.UINT32, initial, mutable = !fixed)
            .range(0L, MAX_RPM)
            .apply { put("units", REVOLUTIONS_PER_MINUTE) }

    private fun distanceCharacteristic(iid: Long, type: Long, units: Long) =
        characteristic(iid, type, CafCharacteristicFormat.UINT64, 0L)
            .apply {
                put("minimumValue", 0L)
                put("units", units)
            }

    // ── 各 accessory ───────────────────────────────────────────────────

    /**
     * navigation（LIVI `navigation()`，iid 256）。
     *
     * 三个 service：应用开关 / 路线状态 / 车机侧信息。
     * `vehicleEnabled=true`（iid 264）是「这台车的仪表能显示导航」的声明 ——
     * v125/v126 缺了整个 accessory，iPhone 无从判断集群可导航。
     */
    private fun navigation() = accessory(
        NAVIGATION, 0x0E00_0002,
        listOf(
            service(
                257, 0x1E00_0104,
                listOf(
                    characteristic(258, 0x4500_0103, CafCharacteristicFormat.BOOL, null, Owner.PHONE),
                    characteristic(259, 0x4500_0112, CafCharacteristicFormat.UINT32, null, Owner.PHONE),
                    characteristic(260, 0x4500_0109, CafCharacteristicFormat.ARRAY, null, Owner.PHONE),
                    characteristic(261, 0x4500_010D, CafCharacteristicFormat.UINT8, null, Owner.PHONE)
                        .range(0L, 4L),
                    characteristic(262, 0x4500_0104, CafCharacteristicFormat.BOOL, null, Owner.PHONE),
                    characteristic(263, 0x4500_010E, CafCharacteristicFormat.STRING, VEHICLE_BRAND, mutable = false),
                    characteristic(264, 0x4500_0105, CafCharacteristicFormat.BOOL, true),
                ),
            ),
            service(
                265, 0x1E00_0102,
                listOf(
                    characteristic(266, 0x4500_0108, CafCharacteristicFormat.DICTIONARY, null, Owner.PHONE),
                    characteristic(267, 0x4500_0106, CafCharacteristicFormat.UINT8, null, Owner.PHONE)
                        .range(0L, 2L),
                    characteristic(268, 0x4500_0107, CafCharacteristicFormat.DICTIONARY, null, Owner.PHONE),
                    characteristic(269, 0x4500_0110, CafCharacteristicFormat.UINT8, null, Owner.PHONE)
                        .range(0L, 6L),
                    characteristic(270, 0x4500_0101, CafCharacteristicFormat.UINT8, null, Owner.PHONE)
                        .range(0L, 255L),
                    characteristic(271, 0x4500_0102, CafCharacteristicFormat.STRING, null, Owner.PHONE),
                ),
            ),
            service(
                272, 0x1E00_0103,
                listOf(
                    characteristic(273, 0x4500_010C, CafCharacteristicFormat.UINT8, 0L)
                        .range(0L, 5L),
                    characteristic(
                        274, 0x4500_010A, CafCharacteristicFormat.STRING, NAVIGATION_SYSTEM_NAME,
                        mutable = false,
                    ),
                    characteristic(
                        275, 0x4500_0111, CafCharacteristicFormat.STRING, VEHICLE_BRAND,
                        mutable = false,
                    ),
                ),
            ),
        ),
    )

    /**
     * automaker settings（LIVI `automaker_settings()`，iid 1280）。
     *
     * 两行版本信息 + 一个 section —— iPhone 的 OEM 设置区里显示
     * 「Version / Version Build」，排障时能直接认出车机软件版本。
     */
    private fun automakerSettings() = accessory(
        AUTOMAKER_SETTINGS, 0x0500_0001,
        listOf(
            infoLine(1281, "B561VehicleStateVersion", "Version", ACCESSORY_VERSION, "info.circle", 0),
            infoLine(1289, "B561VehicleStateBuildDate", "Version Build", BUILD_DATE, "hammer", 1),
            service(
                1297, 0x1600_0020,
                listOf(
                    characteristic(1298, 0x3000_0019, CafCharacteristicFormat.STRING, SETTINGS_SECTION, mutable = false),
                    characteristic(1299, 0x3000_0003, CafCharacteristicFormat.UINT8, 0L, mutable = false)
                        .range(0L, 100L),
                    characteristic(1300, 0x3600_0004, CafCharacteristicFormat.STRING, SETTINGS_SECTION, mutable = false),
                ),
            ),
        ),
    )

    /** LIVI 的 `info_line`：一行 OEM 设置项。 */
    private fun infoLine(
        iid: Long,
        id: String,
        label: String,
        value: String,
        symbol: String,
        order: Long,
    ) = service(
        iid, 0x1600_0015,
        listOf(
            characteristic(iid + 1, 0x3000_0019, CafCharacteristicFormat.STRING, id, mutable = false),
            characteristic(iid + 2, 0x3000_0001, CafCharacteristicFormat.STRING, label, mutable = false),
            characteristic(iid + 3, 0x3000_0017, CafCharacteristicFormat.STRING, value, mutable = false),
            characteristic(iid + 4, 0x3600_0006, CafCharacteristicFormat.UINT8, 0L, mutable = false)
                .range(0L, 15L),
            characteristic(iid + 5, 0x3000_005E, CafCharacteristicFormat.STRING, symbol, mutable = false),
            characteristic(iid + 6, 0x3000_0003, CafCharacteristicFormat.UINT8, order, mutable = false)
                .range(0L, 100L),
            characteristic(iid + 7, 0x3600_0028, CafCharacteristicFormat.STRING, SETTINGS_SECTION, mutable = false),
        ),
    )

    /** vehicle resources（LIVI `vehicle_resources()`，iid 768）：内饰风格。 */
    private fun vehicleResources() = accessory(
        VEHICLE_RESOURCES, 0x0120_0002,
        listOf(
            service(
                769, 0x2000_0001,
                listOf(
                    characteristic(
                        770, 0x4800_0004, CafCharacteristicFormat.STRING, INTERIOR_STYLE,
                        mutable = false,
                    ),
                ),
            ),
        ),
    )

    /**
     * combustion engine（LIVI `combustion_engine()`，iid 2560）。
     *
     * 纯电车把电机转速映射到这里的转速槽位（见类注释）。
     */
    private fun combustionEngine() = accessory(
        COMBUSTION_ENGINE, 0x0900_0005,
        listOf(
            service(
                2561, 0x1A00_000F,
                listOf(
                    rpmCharacteristic(ENGINE_RPM, 0x3000_0024, initial = 0L, fixed = false),
                    rpmCharacteristic(2563, 0x3000_004E, initial = MAX_RPM, fixed = true),
                    rpmCharacteristic(2564, 0x3000_004F, initial = REDLINE_RPM, fixed = true),
                ),
            ),
        ),
    )

    /** electric engine（LIVI `electric_engine()`，iid 2816）：只有功率。 */
    private fun electricEngine() = accessory(
        ELECTRIC_ENGINE, 0x0900_0004,
        listOf(
            service(
                2817, 0x1A00_000D,
                listOf(
                    characteristic(
                        ENGINE_POWER, 0x3000_0028, CafCharacteristicFormat.FLOAT, 0.0,
                    ).range(-1000.0, 1000.0, 0.1).apply { put("units", KILOWATTS) },
                ),
            ),
        ),
    )

    /**
     * fuel（LIVI `fuel()`，iid 1536）。
     *
     * ★ v130 修正：kind/service/characteristic type 全部按 LIVI 对齐
     * （原来是 `0x0100_0002` / `0x1A00_0002` / `0x3000_0007`）。
     */
    private fun fuel() = accessory(
        FUEL, 0x0900_0006,
        listOf(
            service(
                1537, 0x1A00_0005,
                listOf(
                    characteristic(FUEL_LEVEL, 0x4100_0002, CafCharacteristicFormat.UINT8, 0L)
                        .range(0L, 100L).apply { put("units", PERCENT) },
                    characteristic(
                        FUEL_LEVEL_MARKER_LOW, 0x4100_001A, CafCharacteristicFormat.UINT8,
                        LOW_FUEL_PCT, mutable = false,
                    ).range(0L, 100L).apply { put("units", PERCENT) },
                ),
            ),
            distanceService(1540, 0x1A00_0015, FUEL_RANGE_KM, FUEL_RANGE_MILES, FUEL_RANGE_HIDDEN),
        ),
    )

    /**
     * trip computer（LIVI `trip_computer()`，iid 1792）。
     *
     * ★ v130 新增：剩余续航与总里程原来挤在 fuel accessory 里，
     * LIVI 把它们放在独立的 trip computer，各带 km + miles 孪生特性。
     */
    private fun tripComputer() = accessory(
        TRIP_COMPUTER, 0x0400_0001,
        listOf(
            distanceService(1793, 0x1A00_000B, REMAINING_RANGE_KM, REMAINING_RANGE_MILES, REMAINING_RANGE_HIDDEN),
            distanceService(1797, 0x1A00_000A, ODOMETER_KM, ODOMETER_MILES, null),
        ),
    )

    /** LIVI 的 `distance()`：一个距离 service = km + miles（+ 可选 hidden）。 */
    private fun distanceService(
        iid: Long,
        type: Long,
        kmIid: Long,
        milesIid: Long,
        hiddenIid: Long?,
    ): Map<String, Any?> {
        val chars = arrayListOf<Map<String, Any?>>(
            distanceCharacteristic(kmIid, 0x3000_004A, KILOMETERS),
            distanceCharacteristic(milesIid, 0x3000_004B, MILES),
        )
        if (hiddenIid != null) {
            chars.add(
                characteristic(hiddenIid, 0x3600_0023, CafCharacteristicFormat.BOOL, true, mutable = false),
            )
        }
        return service(iid, type, chars)
    }

    /**
     * tires（LIVI `tires()`，iid 2048）。
     *
     * ★ v130 修正：accessory kind `0x0100_0004` → `0x0A00_0001`。
     * 每个轮一个 service（iid = 2048+1+5*n），service kind `0x1B00_0001`。
     */
    private fun tires() = accessory(
        TIRES_ACCESSORY, 0x0A00_0001,
        (0 until 4).map { wheel ->
            val iid = tirePressureIid(wheel)
            service(
                iid - 1, 0x1B00_0001,
                listOf(
                    characteristic(iid, 0x3000_001B, CafCharacteristicFormat.UINT32, 0L)
                        .range(0L, 1000L).apply { put("units", KILOPASCALS) },
                    characteristic(
                        iid + 1, 0x3600_0065, CafCharacteristicFormat.STRING,
                        WHEEL_LAYOUT_KEYS[wheel], mutable = false,
                    ),
                    characteristic(iid + 2, 0x3000_001C, CafCharacteristicFormat.UINT8, 0L)
                        .range(0L, 5L),
                    characteristic(iid + 3, 0x3000_001A, CafCharacteristicFormat.UINT8, SENSOR_WAITING)
                        .range(0L, 4L),
                ),
            )
        },
    )

    /**
     * audio（LIVI `audio()`，iid 2304）：五路音量 + 总音量静音。
     *
     * 我们没有音量数据源 —— 这些特性照 LIVI 声明（initial 50 / false），
     * iPhone 写进来的值原样回读；读不到真实音量时报 NO_VALUE，不编造。
     */
    private fun audio() = accessory(
        AUDIO, 0x0200_0001,
        listOf(
            volumeService(VOLUME, 0L, VOLUME_LABEL, withMute = true),
            volumeService(NAV_VOLUME, 1L, null, withMute = false),
            volumeService(CALL_VOLUME, 2L, null, withMute = false),
            volumeService(SIRI_VOLUME, 3L, null, withMute = false),
            volumeService(MUSIC_VOLUME, 6L, MUSIC_VOLUME_LABEL, withMute = false),
        ),
    )

    /** LIVI 的 `volume()`：一路音量 = volume + volumeType（+ label / mute）。 */
    private fun volumeService(
        iid: Long,
        type: Long,
        label: String?,
        withMute: Boolean,
    ): Map<String, Any?> {
        val chars = arrayListOf<Map<String, Any?>>(
            characteristic(iid, 0x3300_0006, CafCharacteristicFormat.UINT8, 50L, Owner.SHARED)
                .range(0L, 100L),
            characteristic(iid + 1, 0x3300_0005, CafCharacteristicFormat.UINT8, type, mutable = false)
                .range(0L, LAST_VOLUME_TYPE),
        )
        if (label != null) {
            chars.add(
                characteristic(iid + 2, 0x3000_0001, CafCharacteristicFormat.STRING, label, mutable = false),
            )
        }
        if (withMute) {
            chars.add(
                characteristic(MUTE, 0x3300_0009, CafCharacteristicFormat.BOOL, false, Owner.SHARED),
            )
        }
        return service(iid - 1, 0x1300_0002, chars)
    }

    /**
     * climate accessory（LIVI `climate()`，iid 512，kind `0x0100_0001`）。
     *
     * ★ v133 新增：CarPlay Ultra 空调控制（WWDC24 "Climate"）。iPhone 的
     * CarPlay 空调 UI 按这棵树渲染；调节请求经 writeRequest 进来，回读由
     * [written] 兜底（iPhone 写什么读什么），未写的特性由 initialValue 预填。
     */
    private fun climate() = accessory(
        CLIMATE, 0x0100_0001,
        listOf(
            // 513: 主服务 —— 空调总开关 / 压缩机 / 最大 AC / 最大除霜。
            service(
                513, 0x1100_0001,
                listOf(
                    climateOn(HVAC_ON, true),
                    climateOn(COMPRESSOR, true),
                    climateOn(MAX_AC, false),
                    climateOn(MAX_DEFROST, false),
                ),
            ),
            climateZone(
                518, DRIVER_TEMPERATURE_ON, DRIVER_TARGET_TEMPERATURE,
                DRIVER_CURRENT_TEMPERATURE, "seat_front_left",
            ),
            // 523: 风扇 —— 开关 / 档位 / 自动模式。
            service(
                523, 0x1100_0007,
                listOf(
                    climateOn(FAN_ON, true),
                    characteristic(
                        FAN_LEVEL, 0x3100_0012, CafCharacteristicFormat.UINT8, 3L, Owner.SHARED,
                    ).range(0L, FAN_LEVEL_MAX),
                    climateOn(FAN_AUTO, true, 0x3000_005FL),
                    layoutKey(531, "seat_front"),
                ),
            ),
            // 527: 内循环。
            service(527, 0x1100_0011, listOf(climateOn(RECIRCULATION_ON, false))),
            // 529: 自动空调。
            service(529, 0x1100_0008, listOf(climateOn(AUTO_CLIMATE_ON, true))),
            climateZone(
                532, PASSENGER_TEMPERATURE_ON, PASSENGER_TARGET_TEMPERATURE,
                PASSENGER_CURRENT_TEMPERATURE, "seat_front_right",
            ),
            // 537: 双区同步。
            service(
                537, 0x1100_0012,
                listOf(
                    climateOn(ZONES_SYNCED, false),
                    layoutKey(539, "seat_front"),
                ),
            ),
            windowHeating(540, FRONT_WINDOW_HEATING, 542, "windshield_front"),
            windowHeating(544, REAR_WINDOW_HEATING, 546, "windshield_rear"),
            vent(548, VENT_UPPER, on = false),
            vent(553, VENT_MIDDLE, on = true),
            vent(558, VENT_LOWER, on = false),
        ),
    )

    /** LIVI 的 `on()`：一个 shared Bool 特性（默认 type `0x3000_0002`）。 */
    private fun climateOn(iid: Long, initial: Boolean, type: Long = 0x3000_0002L) =
        characteristic(iid, type, CafCharacteristicFormat.BOOL, initial, Owner.SHARED)

    /** LIVI 的 `zone()`：一个温区 = 开关 + 目标温度 + 当前温度 + 座位布局键。 */
    private fun climateZone(
        iid: Long,
        onIid: Long,
        targetIid: Long,
        currentIid: Long,
        seatKey: String,
    ): Map<String, Any?> = service(
        iid, 0x1100_0002,
        listOf(
            climateOn(onIid, true),
            characteristic(
                targetIid, 0x3100_0017, CafCharacteristicFormat.FLOAT, TEMPERATURE_INITIAL,
                Owner.SHARED,
            ).range(TEMPERATURE_LO, TEMPERATURE_HI, 0.5).apply { put("units", CELSIUS) },
            characteristic(
                currentIid, 0x3100_0019, CafCharacteristicFormat.FLOAT, TEMPERATURE_INITIAL,
            ).range(CURRENT_TEMPERATURE_LO, CURRENT_TEMPERATURE_HI, 0.5)
                .apply { put("units", CELSIUS) },
            layoutKey(currentIid + 1, seatKey),
        ),
    )

    /** LIVI 的 `window_heating()`：加热档位 + 除霜类型 + 座位布局键（后两者 fixed）。 */
    private fun windowHeating(
        iid: Long,
        levelIid: Long,
        defrostIid: Long,
        windowKey: String,
    ): Map<String, Any?> = service(
        iid, 0x1100_0005,
        listOf(
            characteristic(
                levelIid, 0x3000_0004, CafCharacteristicFormat.UINT8, 0L, Owner.SHARED,
            ).range(0L, 1L),
            characteristic(
                defrostIid, 0x3100_0014, CafCharacteristicFormat.UINT8, DEFROST_FILAMENT,
                mutable = false,
            ).range(0L, FAN_LEVEL_MAX),
            layoutKey(defrostIid + 1, windowKey),
        ),
    )

    /** LIVI 的 `vent()`：通风口 = 开关 + 自动模式 + 风向组合 + 座位布局键（后两者 fixed）。 */
    private fun vent(
        iid: Long,
        direction: Long,
        on: Boolean,
    ): Map<String, Any?> = service(
        iid, 0x1100_0006,
        listOf(
            climateOn(iid + 1, on),
            climateOn(iid + 2, true, 0x3000_005FL),
            characteristic(
                iid + 3, 0x3100_0025, CafCharacteristicFormat.ARRAY,
                listOf(direction), mutable = false,
            ),
            layoutKey(iid + 4, "seat_front"),
        ),
    )

    /** LIVI 的 `seat()`：座位布局键（car-owned + fixed）。 */
    private fun layoutKey(iid: Long, key: String) =
        characteristic(
            iid, 0x3600_0065, CafCharacteristicFormat.STRING, key, mutable = false,
        )

    companion object {
        /** CAF plugin 标识。 */
        const val PLUGIN_ID = 0L

        /** CAF plugin 名（与 INFO 里的 pluginMapping 对应）。 */
        const val PLUGIN_NAME = "B561VehicleState"

        /** 转速 characteristic 的 iid（Apple 协议：发动机转速）。 */
        const val ENGINE_RPM = 2562L

        /** 功率 characteristic 的 iid。 */
        const val ENGINE_POWER = 2818L

        /** 油量百分比 iid。 */
        const val FUEL_LEVEL = 1538L

        /** 油量低标记 iid（LIVI `fuelLevelMarkerLow`）。 */
        const val FUEL_LEVEL_MARKER_LOW = 1539L

        /** 燃油续航 iid（km）。 */
        const val FUEL_RANGE_KM = 1541L

        /** 燃油续航 iid（miles）。 */
        const val FUEL_RANGE_MILES = 1542L

        /** 燃油续航 hidden 标志 iid。 */
        const val FUEL_RANGE_HIDDEN = 1543L

        /** 剩余续航 iid（km）。 */
        const val REMAINING_RANGE_KM = 1794L

        /** 剩余续航 iid（miles）。 */
        const val REMAINING_RANGE_MILES = 1795L

        /** 剩余续航 hidden 标志 iid。 */
        const val REMAINING_RANGE_HIDDEN = 1796L

        /** 总里程 iid（km）。 */
        const val ODOMETER_KM = 1798L

        /** 总里程 iid（miles）。 */
        const val ODOMETER_MILES = 1799L

        /** 胎压 accessory 的 iid（LIVI `TIRES`）。 */
        const val TIRES_ACCESSORY = 2048L

        /** 轮位布局 key（顺序：左前/右前/左后/右后）。 */
        val WHEEL_LAYOUT_KEYS = arrayOf(
            "tire_front_left", "tire_front_right", "tire_rear_left", "tire_rear_right",
        )

        /**
         * 第 n 个轮的胎压 iid（LIVI `tire_pressure_iid`：`TIRES + 2 + 5 * n`）。
         *
         * 注意每个轮占 5 个 iid：胎压值、布局 key、胎压状态、传感器状态、还有一个空位。
         */
        fun tirePressureIid(wheel: Int): Long = TIRES_ACCESSORY + 2 + 5 * wheel

        /** 四个轮的胎压 iid。 */
        val TIRE_PRESSURE_IDS: List<Long> = (0 until 4).map { tirePressureIid(it) }

        /** 每轮额外的 iid（布局 key / 胎压状态 / 传感器状态）。 */
        val TIRE_EXTRA_IDS: List<Long> = (0 until 4).flatMap { wheel ->
            val iid = tirePressureIid(wheel)
            listOf(iid + 1, iid + 2, iid + 3)
        }

        // ── v130 新增 accessory 的 iid 组 ──

        /** navigation accessory（LIVI `navigation()`）。 */
        const val NAVIGATION = 256L

        /** navigation 三个 service 的全部 iid（258..275）。 */
        val NAVIGATION_IDS: List<Long> = (258L..275L).toList()

        /** automaker settings accessory（LIVI `automaker_settings()`）。 */
        const val AUTOMAKER_SETTINGS = 1280L

        /** automaker settings 的 iid：两行 info_line + section。 */
        val AUTOMAKER_IDS: List<Long> = listOf(
            1282L, 1283L, 1284L, 1285L, 1286L, 1287L, 1288L,
            1290L, 1291L, 1292L, 1293L, 1294L, 1295L, 1296L,
            1298L, 1299L, 1300L,
        )

        /** vehicle resources accessory（LIVI `vehicle_resources()`）。 */
        const val VEHICLE_RESOURCES = 768L

        const val INTERIOR_STYLE_IID = 770L

        /** 内饰风格值（LIVI 同款 "5Seater"）。 */
        const val INTERIOR_STYLE = "5Seater"

        val VEHICLE_RESOURCE_IDS: List<Long> = listOf(INTERIOR_STYLE_IID)

        /** combustion engine accessory（LIVI `combustion_engine()`）。 */
        const val COMBUSTION_ENGINE = 2560L

        /** electric engine accessory（LIVI `electric_engine()`）。 */
        const val ELECTRIC_ENGINE = 2816L

        /** fuel accessory（LIVI `fuel()`）。 */
        const val FUEL = 1536L

        /** trip computer accessory（LIVI `trip_computer()`）。 */
        const val TRIP_COMPUTER = 1792L

        /** fuel accessory 的 iid（LIVI `fuel()`，含距离 service）。 */
        val FUEL_IDS: List<Long> = listOf(
            FUEL_LEVEL, FUEL_LEVEL_MARKER_LOW,
            FUEL_RANGE_KM, FUEL_RANGE_MILES, FUEL_RANGE_HIDDEN,
        )

        /** trip computer 的 iid（LIVI `trip_computer()`）。 */
        val TRIP_IDS: List<Long> = listOf(
            REMAINING_RANGE_KM, REMAINING_RANGE_MILES, REMAINING_RANGE_HIDDEN,
            ODOMETER_KM, ODOMETER_MILES,
        )

        /** audio accessory（LIVI `audio()`）。 */
        const val AUDIO = 2304L

        const val VOLUME = 2306L
        const val MUTE = 2309L
        const val NAV_VOLUME = 2311L
        const val CALL_VOLUME = 2314L
        const val MUSIC_VOLUME = 2317L
        const val SIRI_VOLUME = 2321L

        val AUDIO_IDS: List<Long> = listOf(
            VOLUME, VOLUME + 1, VOLUME + 2, MUTE,
            NAV_VOLUME, NAV_VOLUME + 1,
            CALL_VOLUME, CALL_VOLUME + 1,
            MUSIC_VOLUME, MUSIC_VOLUME + 1, MUSIC_VOLUME + 2,
            SIRI_VOLUME, SIRI_VOLUME + 1,
        )

        private val ENGINE_RPM_IDS = longArrayOf(ENGINE_RPM, 2563L, 2564L)

        /**
         * climate accessory（LIVI `climate()`，iid 512，kind `0x0100_0001`）。
         *
         * ★ v133 新增：CarPlay Ultra 的空调控制 UI（WWDC24 "Climate" 分类）。
         * 12 个 service 的 iid/kind/type/initial 与 LIVI 逐项一致：
         * 主服务 513（hvacOn/compressorOn/maxACOn/maxDefrostOn）、
         * 主驾温区 518、风扇 523、内循环 527、自动空调 529、
         * 副驾温区 532、双区同步 537、前/后窗加热 540/544、
         * 上/中/下通风口 548/553/558。
         * 车机无空调读数源（B561UltraGauges 没有空调字段）：
         * iPhone 写入走 written 回读，未写的由 initialValue 预填兜底。
         */
        const val CLIMATE = 512L

        /** 主服务：空调开关 / 压缩机 / 最大 AC / 最大除霜。 */
        const val HVAC_ON = 514L
        const val COMPRESSOR = 515L
        const val MAX_AC = 516L
        const val MAX_DEFROST = 517L

        /** 主驾温区：开关 / 目标温度 / 当前温度 / 座位布局键。 */
        const val DRIVER_TEMPERATURE_ON = 519L
        const val DRIVER_TARGET_TEMPERATURE = 520L
        const val DRIVER_CURRENT_TEMPERATURE = 521L

        /** 风扇：开关 / 档位 / 自动模式 / 座位布局键。 */
        const val FAN_ON = 524L
        const val FAN_LEVEL = 525L
        const val FAN_AUTO = 526L

        /** 内循环开关。 */
        const val RECIRCULATION_ON = 528L

        /** 自动空调开关。 */
        const val AUTO_CLIMATE_ON = 530L

        /** 副驾温区：开关 / 目标温度 / 当前温度 / 座位布局键。 */
        const val PASSENGER_TEMPERATURE_ON = 533L
        const val PASSENGER_TARGET_TEMPERATURE = 534L
        const val PASSENGER_CURRENT_TEMPERATURE = 535L

        /** 双区同步开关。 */
        const val ZONES_SYNCED = 538L

        /** 前/后窗加热档位。 */
        const val FRONT_WINDOW_HEATING = 541L
        const val REAR_WINDOW_HEATING = 545L

        /** climate accessory 全部 iid（LIVI 514..562 连续无洞）。 */
        val CLIMATE_IDS: List<Long> = (514L..562L).toList()

        /** 温度单位：摄氏度（LIVI `CELSIUS = 1082`）。 */
        const val CELSIUS = 1082L

        /** 空调目标温度下限（LIVI `TEMPERATURE_LO`）。 */
        const val TEMPERATURE_LO = 16.0

        /** 空调目标温度上限（LIVI `TEMPERATURE_HI`）。 */
        const val TEMPERATURE_HI = 28.0

        /** 空调初始目标温度（LIVI zone initial 21.0）。 */
        const val TEMPERATURE_INITIAL = 21.0

        /** 当前温度下限（LIVI current temperature between -40..85）。 */
        const val CURRENT_TEMPERATURE_LO = -40.0

        /** 当前温度上限。 */
        const val CURRENT_TEMPERATURE_HI = 85.0

        /** 风扇档位上限（LIVI fanLevel up_to 7）。 */
        const val FAN_LEVEL_MAX = 7L

        /** 除霜类型：电阻丝加热（LIVI `FILAMENT = 2`）。 */
        const val DEFROST_FILAMENT = 2L

        /** 通风口风向：上 / 中 / 下（LIVI UPPER=2 / MIDDLE=16 / LOWER=4）。 */
        const val VENT_UPPER = 2L
        const val VENT_MIDDLE = 16L
        const val VENT_LOWER = 4L

        /** 转速表满量程。 */
        const val MAX_RPM = 8000L

        /** 红线。 */
        const val REDLINE_RPM = 6500L

        /** 低油量标记（%）。 */
        const val LOW_FUEL_PCT = 15L

        /** 单位：转每分钟。 */
        const val REVOLUTIONS_PER_MINUTE = 981L

        /** 单位：千瓦。 */
        const val KILOWATTS = 884L

        /** 单位：公里。 */
        const val KILOMETERS = 151L

        /** 单位：英里。 */
        const val MILES = 164L

        /** 单位：千帕。 */
        const val KILOPASCALS = 932L

        /** 单位：百分比。 */
        const val PERCENT = 1205L

        /** 胎压传感器状态：活跃。 */
        const val SENSOR_ACTIVE = 1L

        /** 胎压传感器状态：等待（初始值）。 */
        const val SENSOR_WAITING = 3L

        /** 「无值」的 CAF 错误码。 */
        const val NO_VALUE = -6727L

        /** 参数错误码。 */
        const val PARAMETER_ERROR = -6735L

        /** 「没有这个 control」的 CAF 错误码（LIVI `NO_SUCH_COMMAND`）。 */
        const val NO_SUCH_COMMAND = -6735L

        /** 特性优先级（LIVI `HIGH_PRIORITY`）。 */
        const val HIGH_PRIORITY = 1L

        /** 非数值特性的最大长度（LIVI `MAX_LENGTH`）。 */
        const val MAX_LENGTH = 65535L

        /** 音量类型上限（LIVI `LAST_VOLUME_TYPE`）。 */
        const val LAST_VOLUME_TYPE = 7L

        /** km → mile 换算（LIVI `KM_PER_MILE`）。 */
        const val KM_PER_MILE = 1.609344

        // ── OEM 设置区显示用字符串 ──

        /**
         * accessory 版本 —— 与 `mobile/build.gradle.kts` 的 versionName 对齐，
         * 出现在 iPhone 的 OEM 设置区里，排障时能认出车机软件版本。
         */
        const val ACCESSORY_VERSION = "0.2.19-oushang1"

        /** 构建日期（LIVI 同款：空串时 info_line 显示「?」）。 */
        const val BUILD_DATE = ""

        /** OEM 设置区名称。 */
        const val SETTINGS_SECTION = "B561"

        /** iPhone 仪表上显示的车辆品牌。 */
        const val VEHICLE_BRAND = "UNI-Z"

        /** iPhone 仪表上显示的导航系统名。 */
        const val NAVIGATION_SYSTEM_NAME = "Apple Maps"

        /** 音量 UI 里的主音量标签。 */
        const val VOLUME_LABEL = "CarPlay"

        /** 音量 UI 里的音乐音量标签。 */
        const val MUSIC_VOLUME_LABEL = "Music"
    }
}
