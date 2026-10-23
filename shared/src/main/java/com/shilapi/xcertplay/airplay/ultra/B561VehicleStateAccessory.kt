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
 * ## iid 与 type 的来源
 *
 * 全部照 **LIVI**（Rust，Linux 车机）的 `vehicle_state.rs` —— 它是开源实现里
 * 最完整的一套 CarPlay Ultra 车辆状态（`set_gauges`，:2279）。
 * 关键映射：
 *
 * | 语义 | iid | characteristic type | 格式 |
 * |---|---|---|---|
 * | 转速 `rotationalSpeed` | 2562 | `0x3000_0024` | UINT32，单位 rpm(981) |
 * | 转速上限 | 2563 | `0x3000_004E` | UINT32 |
 * | 红线 | 2564 | `0x3000_004F` | UINT32 |
 * | 功率 `power` | 2818 | `0x3000_0028` | FLOAT，单位 kW(884) |
 * | 油量 `fuelLevel` | 1538 | `0x3000_0007` | UINT8，% |
 * | 续航 | 1541 / 1794 | `0x3000_004A` | UINT64，km(151) |
 * | 总里程 | 1798 | `0x3000_004A` | UINT64，km(151) |
 *
 * accessory / service 的 kind：
 * - `combustion engine` accessory：iid 2560，kind `0x0900_0005`，
 *   service iid 2561、kind `0x1A00_000F` —— **有发动机转速**（燃油/混动）
 * - `electric engine` accessory：iid 2816，kind `0x0900_0004`，
 *   service iid 2817、kind `0x1A00_000D` —— **只有功率**（纯电）
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

    /** 本 accessory 声明的全部 iid。 */
    private val knownIds: Set<Long> = buildSet {
        addAll(ENGINE_RPM_IDS.toList())
        add(ENGINE_POWER)
        add(FUEL_LEVEL)
        add(FUEL_RANGE)
        add(REMAINING_RANGE)
        add(ODOMETER)
        addAll(TIRE_PRESSURE_IDS)
    }

    /**
     * CAF config 树：告诉 iPhone「我有哪些 characteristic、什么格式、什么量程」。
     *
     * 纯电车**不声明**转速那三个 iid（Apple 协议里纯电车没有转速字段）。
     */
    fun config(hasEngineRpm: Boolean): Map<String, Any?> {
        val accessories = arrayListOf<Map<String, Any?>>()
        val engineServices = arrayListOf<Map<String, Any?>>()
        if (hasEngineRpm) {
            engineServices.add(
                service(
                    2561, 0x1A00_000F,
                    listOf(
                        rpmCharacteristic(ENGINE_RPM, 0x3000_0024, "rotationalSpeed"),
                        rpmCharacteristic(2563, 0x3000_004E, "rotationalSpeedMax", fixed = true),
                        rpmCharacteristic(2564, 0x3000_004F, "rotationalSpeedMarkerRedline", fixed = true),
                    ),
                ),
            )
            accessories.add(accessory(2560, 0x0900_0005, engineServices))
        }
        // 电动引擎：功率。纯电与混动都有。
        accessories.add(
            accessory(
                2816, 0x0900_0004,
                listOf(
                    service(
                        2817, 0x1A00_000D,
                        listOf(
                            characteristic(
                                ENGINE_POWER, 0x3000_0028, CafCharacteristicFormat.FLOAT, 0.0,
                            ).apply {
                                put("minimumValue", -1000.0)
                                put("maximumValue", 1000.0)
                                put("stepValue", 0.1)
                                put("units", KILOWATTS)
                            },
                        ),
                    ),
                ),
            ),
        )
        // 油量 / 续航 / 里程。
        accessories.add(
            accessory(
                1536, 0x0100_0002,
                listOf(
                    service(
                        1537, 0x1A00_0002,
                        listOf(
                            characteristic(
                                FUEL_LEVEL, 0x3000_0007, CafCharacteristicFormat.UINT8, 0,
                            ).apply {
                                put("minimumValue", 0L)
                                put("maximumValue", 100L)
                                put("stepValue", 1L)
                                put("units", PERCENT)
                            },
                            distanceCharacteristic(FUEL_RANGE, 0x3000_004A, "fuelRangeKm"),
                            distanceCharacteristic(REMAINING_RANGE, 0x3000_004A, "remainingRangeKm"),
                            distanceCharacteristic(ODOMETER, 0x3000_004A, "odometerKm"),
                        ),
                    ),
                ),
            ),
        )
        // 四轮胎压。照 LIVI 的 `tires()` accessory（vehicle_state.rs:1100）：
        // accessory iid 2048，每个轮一个 service（iid = 2048+1+5*n），
        // service kind `0x1B00_0001`，胎压值 characteristic `0x3000_001B`（kPa）。
        accessories.add(
            accessory(
                TIRES_ACCESSORY, 0x0100_0004,
                (0 until 4).map { wheel ->
                    val iid = tirePressureIid(wheel)
                    service(
                        iid - 1, 0x1B00_0001,
                        listOf(
                            characteristic(
                                iid, 0x3000_001B, CafCharacteristicFormat.UINT32, 0L,
                            ).apply {
                                put("minimumValue", 0L)
                                put("maximumValue", 1000L)
                                put("stepValue", 1L)
                                put("units", KILOPASCALS)
                            },
                            characteristic(
                                iid + 1, 0x3600_0065, CafCharacteristicFormat.STRING,
                                WHEEL_LAYOUT_KEYS[wheel], mutable = false,
                            ),
                            characteristic(
                                iid + 2, 0x3000_001C, CafCharacteristicFormat.UINT8, 0L,
                            ).apply {
                                put("minimumValue", 0L)
                                put("maximumValue", 5L)
                                put("stepValue", 1L)
                            },
                            characteristic(
                                iid + 3, 0x3000_001A, CafCharacteristicFormat.UINT8, 3L,
                            ).apply {
                                put("minimumValue", 0L)
                                put("maximumValue", 4L)
                                put("stepValue", 1L)
                            },
                        ),
                    )
                },
            ),
        )
        return linkedMapOf("accessories" to accessories)
    }

    /**
     * 处理一条 CAF 请求。
     *
     * 返回 null 表示这条消息不归我们管（交给上层）。
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
                CarAccessoryMessages.registerResponse(
                    PLUGIN_ID, transaction, values(ids), errors(ids),
                )
            }
            CafCommand.UNREGISTER_REQUEST -> {
                val ids = identifiers(message)
                val transaction = message.requireTransactionId()
                subscriptions.removeAll(ids.toSet())
                CarAccessoryMessages.unregisterResponse(PLUGIN_ID, transaction)
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
        val data = sample() ?: return emptyMap()
        val result = linkedMapOf<Long, Any?>()
        for (id in ids) {
            valueOf(data, id)?.let { result[id] = it }
        }
        return result
    }

    /** 按 iid 取值；null = 没有该读数。 */
    private fun valueOf(data: B561UltraGauges, id: Long): Any? {
        when (id) {
            ENGINE_RPM -> return data.primaryRpm?.toLong()
            2563L -> return MAX_RPM
            2564L -> return REDLINE_RPM
            ENGINE_POWER -> return data.powerKw?.toDouble()
            FUEL_LEVEL -> return data.fuelPercent?.toLong()
            FUEL_RANGE -> return data.rangeKm?.toLong()
            REMAINING_RANGE -> return data.rangeKm?.toLong()
            ODOMETER -> return data.odometerKm?.toLong()
        }
        val index = TIRE_PRESSURE_IDS.indexOf(id)
        if (index < 0) return null
        val tires = data.tirePressureBar ?: return null
        if (index >= tires.size) return null
        // bar → kPa（Apple 侧用 kPa；LIVI 的 set_gauges 也是这么转的）
        return (tires[index] * 100f).toLong()
    }

    /** 报错的 iid：请求了但我们没数据。 */
    private fun errors(ids: List<Long>): Map<Long, Long> {
        val data = sample()
        return ids.filter { data == null || valueOf(data, it) == null }
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

    private fun accessory(iid: Long, type: Long, services: List<Map<String, Any?>>) =
        linkedMapOf<String, Any?>(
            "type" to type, "iid" to iid, "version" to "1.0", "services" to services,
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
        writable: Boolean = false,
        mutable: Boolean = true,
    ) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "format" to format.wireValue, "writable" to writable,
        "oemWritable" to true, "mutable" to mutable, "priority" to 1L,
    ).apply {
        if (value != null) put("initialValue", value)
        if (mutable) put("supportsInvalid", true)
    }

    private fun rpmCharacteristic(iid: Long, type: Long, name: String, fixed: Boolean = false) =
        characteristic(iid, type, CafCharacteristicFormat.UINT32, 0L, mutable = !fixed).apply {
            put("minimumValue", 0L)
            put("maximumValue", MAX_RPM)
            put("stepValue", 1L)
            put("units", REVOLUTIONS_PER_MINUTE)
        }

    private fun distanceCharacteristic(iid: Long, type: Long, name: String) =
        characteristic(iid, type, CafCharacteristicFormat.UINT64, 0L).apply {
            put("minimumValue", 0L)
            put("units", KILOMETERS)
        }

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

        /** 燃油续航 iid（km）。 */
        const val FUEL_RANGE = 1541L

        /** 剩余续航 iid（km）。 */
        const val REMAINING_RANGE = 1794L

        /** 总里程 iid（km）。 */
        const val ODOMETER = 1798L

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

        private val ENGINE_RPM_IDS = longArrayOf(ENGINE_RPM, 2563L, 2564L)


        /** 转速表满量程。 */
        const val MAX_RPM = 8000L

        /** 红线。 */
        const val REDLINE_RPM = 6500L

        /** 单位：转每分钟。 */
        const val REVOLUTIONS_PER_MINUTE = 981L

        /** 单位：千瓦。 */
        const val KILOWATTS = 884L

        /** 单位：公里。 */
        const val KILOMETERS = 151L

        /** 单位：千帕。 */
        const val KILOPASCALS = 932L

        /** 单位：百分比。 */
        const val PERCENT = 1205L

        /** 「无值」的 CAF 错误码。 */
        const val NO_VALUE = -6727L

        /** 参数错误码。 */
        const val PARAMETER_ERROR = -6735L
    }
}
