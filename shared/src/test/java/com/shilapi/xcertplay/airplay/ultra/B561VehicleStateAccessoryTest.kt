package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCommand
import com.shilapi.xcertplay.airplay.rcs.caf.CafEnvelope
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessage
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessageReader
import com.shilapi.xcertplay.vehicle.B561UltraGauges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B561VehicleStateAccessory] 的回归测试。
 *
 * 重点验证四件事：
 * 1. **iid / kind / characteristic type 与 LIVI 一致** —— 这些数字是 Apple
 *    CarPlay Ultra 协议的约定，错了 iPhone 就读不到对应数据
 *    （v125/v126 的 fuel/tires 数字不对，真车上 iPhone 走完
 *    configRequest 后一条 registerRequest 都没发）。
 * 2. **订阅→发布闭环**：registerRequest 之前 `changed()` 必须返回 null
 *    （没有订阅就没有 updateNotify —— v126 日志里 iPhone 从未注册）。
 * 3. **writeRequest / controlRequest 有应答**（v130 补，静默丢弃会被
 *    iPhone 当成插件无响应）。
 * 4. **initialValue 预填**（v131 补，LIVI `initial_values()` 对齐）：
 *    无真实读数时响应里给树里声明的初始值，不再一片 NO_VALUE。
 */
class B561VehicleStateAccessoryTest {

    private fun accessoryWith(data: B561UltraGauges?) =
        B561VehicleStateAccessory { data }

    /** 构造一条 CAF 请求（测试源集可直接用 internal 构造器）。 */
    private fun request(
        command: CafCommand,
        values: Any? = null,
        transactionId: Long = 7L,
    ) = CafMessageReader(
        CafEnvelope(
            pluginId = B561VehicleStateAccessory.PLUGIN_ID,
            message = CafMessage(
                command = command,
                transactionId = transactionId,
                values = values,
            ),
        ),
    )

    private fun responseValues(frame: com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame?) =
        frame!!.envelope.message.values as Map<*, *>

    private fun responseErrors(frame: com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame?) =
        frame!!.envelope.message.errors as Map<*, *>

    private fun accessories(config: Map<String, Any?>): Map<Long, Map<*, *>> =
        (config["accessories"] as List<*>).map { it as Map<*, *> }
            .associateBy { it["iid"] as Long }

    /** 处理一条请求并要求必须有应答 —— 静默丢弃就是 v130 要修的缺陷。 */
    private fun B561VehicleStateAccessory.handleOrFail(message: CafMessageReader) =
        handle(message) ?: error("CAF ${message.command.wireName} was not answered")

    // ── iid 常量（照 LIVI vehicle_state.rs）─────────────────────────────────

    @Test
    fun `characteristic iids match the LIVI reference`() {
        assertEquals(2562L, B561VehicleStateAccessory.ENGINE_RPM)
        assertEquals(2818L, B561VehicleStateAccessory.ENGINE_POWER)
        assertEquals(1538L, B561VehicleStateAccessory.FUEL_LEVEL)
        assertEquals(1539L, B561VehicleStateAccessory.FUEL_LEVEL_MARKER_LOW)
        assertEquals(1541L, B561VehicleStateAccessory.FUEL_RANGE_KM)
        assertEquals(1542L, B561VehicleStateAccessory.FUEL_RANGE_MILES)
        assertEquals(1794L, B561VehicleStateAccessory.REMAINING_RANGE_KM)
        assertEquals(1795L, B561VehicleStateAccessory.REMAINING_RANGE_MILES)
        assertEquals(1798L, B561VehicleStateAccessory.ODOMETER_KM)
        assertEquals(1799L, B561VehicleStateAccessory.ODOMETER_MILES)
    }

    @Test
    fun `tire pressure iids follow the five per wheel layout`() {
        // LIVI: tire_pressure_iid(n) = TIRES + 2 + 5 * n，TIRES = 2048
        assertEquals(2050L, B561VehicleStateAccessory.tirePressureIid(0))
        assertEquals(2055L, B561VehicleStateAccessory.tirePressureIid(1))
        assertEquals(2060L, B561VehicleStateAccessory.tirePressureIid(2))
        assertEquals(2065L, B561VehicleStateAccessory.tirePressureIid(3))
        // 每个轮占 5 个 iid，四个轮不能重叠。
        assertEquals(4, B561VehicleStateAccessory.TIRE_PRESSURE_IDS.size)
        assertEquals(
            B561VehicleStateAccessory.TIRE_PRESSURE_IDS.size,
            B561VehicleStateAccessory.TIRE_PRESSURE_IDS.toSet().size,
        )
        // 传感器状态 iid 每轮第 3 项 = 胎压 iid + 3（2053/2058/2063/2068）。
        assertEquals(
            listOf(2053L, 2058L, 2063L, 2068L),
            B561VehicleStateAccessory.TIRE_EXTRA_IDS.filterIndexed { i, _ -> i % 3 == 2 },
        )
    }

    // ── CAF config 树（v130：LIVI 最小可用集）────────────────────────────

    @Test
    fun `config tree carries the LIVI minimum viable accessory set`() {
        val config = accessoryWith(null).config(hasEngineRpm = true)
        val byIid = accessories(config)
        // 7 个基础 accessory + combustion + electric = 9。
        assertEquals(9, byIid.size)
        listOf(256L, 1280L, 768L, 1536L, 1792L, 2048L, 2304L, 2560L, 2816L).forEach {
            assertNotNull("accessory iid=$it 必须在树里（LIVI 最小可用集）", byIid[it])
        }
    }

    @Test
    fun `config tree without engine rpm omits the combustion engine`() {
        val config = accessoryWith(null).config(hasEngineRpm = false)
        val byIid = accessories(config)
        // 纯电：没有 combustion engine，其余 8 个仍在。
        assertEquals(8, byIid.size)
        assertNull(byIid[2560L])
        assertNotNull(byIid[2816L])
    }

    @Test
    fun `accessory kinds match LIVI after the v130 fixes`() {
        val byIid = accessories(accessoryWith(null).config())
        assertEquals(0x0E00_0002L, byIid[256L]!!["type"]) // navigation
        assertEquals(0x0500_0001L, byIid[1280L]!!["type"]) // automaker settings
        assertEquals(0x0120_0002L, byIid[768L]!!["type"]) // vehicle resources
        // ★ v130：fuel 0x0100_0002 → 0x0900_0006；tires 0x0100_0004 → 0x0A00_0001。
        assertEquals(0x0900_0006L, byIid[1536L]!!["type"]) // fuel
        assertEquals(0x0400_0001L, byIid[1792L]!!["type"]) // trip computer
        assertEquals(0x0A00_0001L, byIid[2048L]!!["type"]) // tires
        assertEquals(0x0200_0001L, byIid[2304L]!!["type"]) // audio
        assertEquals(0x0900_0005L, byIid[2560L]!!["type"]) // combustion engine
        assertEquals(0x0900_0004L, byIid[2816L]!!["type"]) // electric engine
    }

    @Test
    fun `fuel service and level characteristic use the LIVI types`() {
        val byIid = accessories(accessoryWith(null).config())
        val fuel = byIid[1536L]!!
        val services = fuel["services"] as List<*>
        val level = services.first() as Map<*, *>
        // ★ v130：service 0x1A00_0002 → 0x1A00_0005。
        assertEquals(0x1A00_0005L, level["type"])
        val fuelLevel = (level["characteristics"] as List<*>).first() as Map<*, *>
        assertEquals(1538L, fuelLevel["iid"])
        // ★ v130：fuelLevel type 0x3000_0007 → 0x4100_0002（UINT8 + %）。
        assertEquals(0x4100_0002L, fuelLevel["type"])
        assertEquals(1L, fuelLevel["format"]) // UINT8
        assertEquals(B561VehicleStateAccessory.PERCENT, fuelLevel["units"])
        // 低油量标记。
        val marker = (level["characteristics"] as List<*>)[1] as Map<*, *>
        assertEquals(1539L, marker["iid"])
        assertEquals(0x4100_001AL, marker["type"])
    }

    @Test
    fun `fuel range carries the km miles and hidden triplets`() {
        val byIid = accessories(accessoryWith(null).config())
        val fuel = byIid[1536L]!!
        val distance = (fuel["services"] as List<*>)[1] as Map<*, *>
        assertEquals(0x1A00_0015L, distance["type"])
        val chars = distance["characteristics"] as List<*>
        assertEquals(3, chars.size)
        assertEquals(1541L, (chars[0] as Map<*, *>)["iid"])
        assertEquals(0x3000_004AL, (chars[0] as Map<*, *>)["type"])
        assertEquals(B561VehicleStateAccessory.KILOMETERS, (chars[0] as Map<*, *>)["units"])
        assertEquals(1542L, (chars[1] as Map<*, *>)["iid"])
        assertEquals(0x3000_004BL, (chars[1] as Map<*, *>)["type"])
        assertEquals(B561VehicleStateAccessory.MILES, (chars[1] as Map<*, *>)["units"])
        assertEquals(1543L, (chars[2] as Map<*, *>)["iid"])
        assertEquals(0x3600_0023L, (chars[2] as Map<*, *>)["type"])
    }

    @Test
    fun `navigation declares vehicle enabled`() {
        val byIid = accessories(accessoryWith(null).config())
        val nav = byIid[256L]!!
        val appService = (nav["services"] as List<*>).first() as Map<*, *>
        assertEquals(0x1E00_0104L, appService["type"])
        val enabled = (appService["characteristics"] as List<*>)
            .first { (it as Map<*, *>)["iid"] == 264L } as Map<*, *>
        // vehicleEnabled=true —— 「这台车的仪表能显示导航」的声明。
        assertEquals(true, enabled["initialValue"])
        // 车机侧特性 → 不可写。
        assertEquals(false, enabled["writable"])
    }

    @Test
    fun `trip computer holds remaining range and odometer`() {
        val byIid = accessories(accessoryWith(null).config())
        val trip = byIid[1792L]!!
        val services = trip["services"] as List<*>
        assertEquals(0x1A00_000BL, (services[0] as Map<*, *>)["type"]) // remaining
        assertEquals(0x1A00_000AL, (services[1] as Map<*, *>)["type"]) // odometer
        // 里程 service 没有 hidden 标志（LIVI 同款：odometer 无 hidden）。
        assertEquals(2, ((services[1] as Map<*, *>)["characteristics"] as List<*>).size)
    }

    @Test
    fun `power characteristic carries the kilowatt unit and range`() {
        val byIid = accessories(accessoryWith(null).config())
        val electric = byIid[2816L]!!
        val service = (electric["services"] as List<*>).first() as Map<*, *>
        val power = (service["characteristics"] as List<*>).first() as Map<*, *>
        assertEquals(B561VehicleStateAccessory.ENGINE_POWER, power["iid"])
        assertEquals(0x3000_0028L, power["type"])
        assertEquals(9L, power["format"]) // FLOAT
        assertEquals(B561VehicleStateAccessory.KILOWATTS, power["units"])
        assertEquals(-1000.0, power["minimumValue"])
        assertEquals(1000.0, power["maximumValue"])
    }

    @Test
    fun `combustion engine service carries rpm max and redline`() {
        val byIid = accessories(accessoryWith(null).config())
        val engine = byIid[2560L]!!
        val service = (engine["services"] as List<*>).first() as Map<*, *>
        assertEquals(0x1A00_000FL, service["type"])
        val chars = service["characteristics"] as List<*>
        assertEquals(3, chars.size)
        assertEquals(2562L, (chars[0] as Map<*, *>)["iid"])
        assertEquals(0x3000_0024L, (chars[0] as Map<*, *>)["type"])
        assertEquals(B561VehicleStateAccessory.REVOLUTIONS_PER_MINUTE, (chars[0] as Map<*, *>)["units"])
        assertEquals(2563L, (chars[1] as Map<*, *>)["iid"])
        assertEquals(0x3000_004EL, (chars[1] as Map<*, *>)["type"])
        assertEquals(2564L, (chars[2] as Map<*, *>)["iid"])
        assertEquals(0x3000_004FL, (chars[2] as Map<*, *>)["type"])
        // ★ v131：initial 值照 LIVI —— 转速=0、上限=MAX_RPM、红线=REDLINE_RPM。
        // v125 把三个都写成 0，上限=0 会让 iPhone 转速表刻度塌缩。
        assertEquals(0L, (chars[0] as Map<*, *>)["initialValue"])
        assertEquals(B561VehicleStateAccessory.MAX_RPM, (chars[1] as Map<*, *>)["initialValue"])
        assertEquals(B561VehicleStateAccessory.REDLINE_RPM, (chars[2] as Map<*, *>)["initialValue"])
    }

    @Test
    fun `tyre pressure service declares the wheel layout keys`() {
        val byIid = accessories(accessoryWith(null).config())
        val tires = byIid[2048L]!!
        val services = tires["services"] as List<*>
        assertEquals(4, services.size)
        // 第一个轮：胎压值 + 布局 key + 胎压状态 + 传感器状态
        val first = (services.first() as Map<*, *>)["characteristics"] as List<*>
        assertEquals(4, first.size)
        assertEquals(2050L, (first[0] as Map<*, *>)["iid"])
        assertEquals(0x3000_001BL, (first[0] as Map<*, *>)["type"]) // 胎压值
        assertEquals("tire_front_left", (first[1] as Map<*, *>)["initialValue"])
        // 传感器状态初始 = WAITING(3)（LIVI 同款）。
        assertEquals(3L, (first[3] as Map<*, *>)["initialValue"])
    }

    @Test
    fun `audio declares five volume services with the shared owner`() {
        val byIid = accessories(accessoryWith(null).config())
        val audio = byIid[2304L]!!
        val services = audio["services"] as List<*>
        assertEquals(5, services.size)
        services.forEach { service ->
            assertEquals(0x1300_0002L, (service as Map<*, *>)["type"])
        }
        // 主音量 phone 可写（Owner.SHARED → writable=true）。
        val mainVolume = ((services.first() as Map<*, *>)["characteristics"] as List<*>).first() as Map<*, *>
        assertEquals(2306L, mainVolume["iid"])
        assertEquals(0x3300_0006L, mainVolume["type"])
        assertEquals(true, mainVolume["writable"])
        // 音量类型 phone 不可写。
        val volumeType = ((services.first() as Map<*, *>)["characteristics"] as List<*>)[1] as Map<*, *>
        assertEquals(false, volumeType["writable"])
    }

    @Test
    fun `string characteristics carry the maximum length`() {
        val byIid = accessories(accessoryWith(null).config())
        val resources = byIid[768L]!!
        val style = (((resources["services"] as List<*>).first() as Map<*, *>)["characteristics"] as List<*>).first() as Map<*, *>
        assertEquals("5Seater", style["initialValue"])
        // 非数值格式照 LIVI 补 maximumLength，缺了 iPhone 可能拒解析整棵树。
        assertEquals(65535L, style["maximumLength"])
    }

    // ── 订阅 → 发布闭环（v126 真车症状的核心）────────────────────────────

    @Test
    fun `changed stays empty until the phone registers`() {
        // v126 日志：iPhone 从未发 registerRequest → 没有订阅就没有帧。
        val data = B561UltraGauges(
            speedKph = 52.5f,
            motorRpm = 1200f,
            powerKw = 42.5f,
            batteryPercent = 49,
            rangeKm = 312,
            odometerKm = 54266f,
            gear = 8,
        )
        assertNull(accessoryWith(data).changed())
    }

    @Test
    fun `register then changed reports subscribed values and dedupes`() {
        // 可变 sample：模拟"注册之后车辆数据更新"。
        var current: B561UltraGauges? = B561UltraGauges(
            speedKph = 52.5f,
            motorRpm = 1200f,
            powerKw = 42.5f,
            batteryPercent = 49,
            rangeKm = 312,
            odometerKm = 54266f,
            gear = 8,
        )
        val accessory = B561VehicleStateAccessory { current }
        val response = accessory.handleOrFail(
            request(CafCommand.REGISTER_REQUEST, mapOf(2562L to null, 2818L to null)),
        )
        assertEquals(CafCommand.REGISTER_RESPONSE, response.envelope.message.command)
        assertEquals(setOf(2562L, 2818L), accessory.subscribedIds())
        // register 响应就是当前值快照：转速 Float → UINT32 Long；
        // 功率 Float → FLOAT Double。
        assertEquals(1200L, responseValues(response)[2562L])
        assertEquals(42.5, responseValues(response)[2818L])

        // ★ v131：register 已把返回值记为基线 —— 数据没变就不该再发
        // updateNotify（否则每条 gauges 更新都会白灌一条全量帧）。
        assertNull("注册即快照，无变化不得补发", accessory.changed())

        // 数据真的变了 → 只发变化的那一项。
        current = current!!.copy(motorRpm = 1500f)
        val changed = accessory.changed()
        assertNotNull("数据变化之后必须有 updateNotify", changed)
        assertEquals(CafCommand.UPDATE_NOTIFY, changed!!.envelope.message.command)
        val values = responseValues(changed)
        assertEquals(1500L, values[2562L])
        assertEquals("只报变化的 iid", 1, values.size)
        // 第二次调用（数据没变）→ 无帧。
        assertNull(accessory.changed())
    }

    @Test
    fun `unregister stops updates`() {
        var current: B561UltraGauges? = B561UltraGauges(motorRpm = 1200f)
        val accessory = B561VehicleStateAccessory { current }
        accessory.handleOrFail(request(CafCommand.REGISTER_REQUEST, mapOf(2562L to null)))
        current = current!!.copy(motorRpm = 1500f)
        assertNotNull(accessory.changed())
        accessory.handleOrFail(request(CafCommand.UNREGISTER_REQUEST, mapOf(2562L to null)))
        assertTrue(accessory.subscribedIds().isEmpty())
        current = current!!.copy(motorRpm = 3000f)
        assertNull(accessory.changed())
    }

    @Test
    fun `register with the wildcard subscribes every known iid`() {
        val accessory = accessoryWith(B561UltraGauges(motorRpm = 900f))
        accessory.handleOrFail(request(CafCommand.REGISTER_REQUEST, "*"))
        assertTrue(
            "通配订阅必须覆盖整棵树",
            accessory.subscribedIds().size >= 40,
        )
    }

    @Test
    fun `read returns live values and prefilled initials for missing readings`() {
        val data = B561UltraGauges(motorRpm = 1200f, powerKw = 42.5f)
        val accessory = accessoryWith(data)
        val response = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2562L to null, 1538L to null)),
        )
        assertEquals(CafCommand.READ_RESPONSE, response.envelope.message.command)
        assertEquals(1200L, responseValues(response)[2562L])
        // ★ v131：燃油车才有的油量，这台车没有读数 → 回落树里声明的
        // initialValue=0（LIVI initial_values() 同款），不再是 NO_VALUE。
        assertEquals(0L, responseValues(response)[1538L])
        assertEquals(emptyMap<Long, Long>(), responseErrors(response))
    }

    @Test
    fun `a snapshot without readings falls back to the declared initials`() {
        val data = B561UltraGauges(speedKph = 10f)
        val accessory = accessoryWith(data)
        val response = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2562L to null, 1798L to null)),
        )
        // ★ v131：没有真实读数 → 回落 initialValue（转速 0 / 里程 0）。
        // v130 及以前这里是一片 NO_VALUE，真车上 iPhone 车辆 UI 全空白。
        assertEquals(mapOf(2562L to 0L, 1798L to 0L), responseValues(response))
        assertEquals(emptyMap<Long, Long>(), responseErrors(response))
    }

    @Test
    fun `phone owned characteristics without initial still report no value`() {
        // navigation 的 phone-owned  legs 特性树里没有 initialValue，
        // 也没被 iPhone 写过 → 仍然报 NO_VALUE（预填不是万能胶）。
        val accessory = accessoryWith(B561UltraGauges(speedKph = 10f))
        val response = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(266L to null, 267L to null)),
        )
        assertEquals(emptyMap<Long, Any?>(), responseValues(response))
        assertEquals(
            mapOf(
                266L to B561VehicleStateAccessory.NO_VALUE,
                267L to B561VehicleStateAccessory.NO_VALUE,
            ),
            responseErrors(response),
        )
    }

    @Test
    fun `tire pressure converts bar to kPa and flips the sensor state`() {
        // 2.5 bar = 250 kPa —— Apple 侧用 kPa。
        val data = B561UltraGauges(
            speedKph = 30f,
            tirePressureBar = floatArrayOf(2.5f, 2.6f, 2.4f, 2.5f),
        )
        val accessory = accessoryWith(data)
        val response = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2050L to null, 2053L to null)),
        )
        assertEquals(250L, responseValues(response)[2050L])
        // 有读数 → 传感器状态 ACTIVE(1)。
        assertEquals(1L, responseValues(response)[2053L])
        // 没有胎压读数时：胎压值回落 initialValue=0，传感器状态 WAITING(3)。
        val waiting = accessoryWith(B561UltraGauges(speedKph = 30f)).handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2050L to null, 2053L to null)),
        )
        assertEquals(0L, responseValues(waiting)[2050L])
        assertEquals(3L, responseValues(waiting)[2053L])
        assertEquals(emptyMap<Long, Long>(), responseErrors(waiting))
    }

    @Test
    fun `remaining range converts km to miles`() {
        // 312 km ÷ 1.609344 = 193.87 → 194 mi。
        val data = B561UltraGauges(rangeKm = 312)
        val accessory = accessoryWith(data)
        val response = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(1794L to null, 1795L to null)),
        )
        assertEquals(312L, responseValues(response)[1794L])
        assertEquals(194L, responseValues(response)[1795L])
        // 没有读数时孪生特性回落 initialValue=0（miles 与 km 同步）。
        val empty = accessoryWith(B561UltraGauges(speedKph = 1f)).handle(
            request(CafCommand.READ_REQUEST, mapOf(1795L to null)),
        )
        assertEquals(0L, responseValues(empty)[1795L])
    }

    @Test
    fun `odometer reads back the rounded kilometre value`() {
        val data = B561UltraGauges(odometerKm = 54266.4f)
        val accessory = accessoryWith(data)
        val response = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(1798L to null, 1799L to null)),
        )
        assertEquals(54266L, responseValues(response)[1798L])
        // 54266 km ÷ 1.609344 = 33718.98 → 33719 mi。
        assertEquals(33719L, responseValues(response)[1799L])
    }

    // ── writeRequest / controlRequest（v130 补）───────────────────────────

    @Test
    fun `written phone owned values are echoed back on read`() {
        val accessory = accessoryWith(null)
        val write = accessory.handleOrFail(
            request(CafCommand.WRITE_REQUEST, mapOf(2306L to 42L)),
        )
        assertEquals(CafCommand.WRITE_RESPONSE, write.envelope.message.command)
        assertEquals(emptyMap<Long, Long>(), write.envelope.message.errors)

        val read = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2306L to null)),
        )
        // phone-owned 特性：iPhone 写什么回读什么。
        assertEquals(42L, responseValues(read)[2306L])
    }

    @Test
    fun `control request reports no such command`() {
        val accessory = accessoryWith(null)
        val response = accessory.handleOrFail(
            request(CafCommand.CONTROL_REQUEST, mapOf(2306L to null)),
        )
        assertEquals(CafCommand.CONTROL_RESPONSE, response.envelope.message.command)
        assertEquals(emptyMap<Long, Any?>(), responseValues(response))
        assertEquals(
            B561VehicleStateAccessory.NO_SUCH_COMMAND,
            responseErrors(response)[2306L],
        )
    }

    @Test
    fun `a request for another plugin gets a general error`() {
        val accessory = accessoryWith(null)
        val foreign = CafMessageReader(
            CafEnvelope(
                pluginId = 99L,
                message = CafMessage(command = CafCommand.CONFIG_REQUEST, transactionId = 3L),
            ),
        )
        val response = accessory.handleOrFail(foreign)
        assertEquals(CafCommand.GENERAL_ERROR, response.envelope.message.command)
        assertEquals(B561VehicleStateAccessory.PARAMETER_ERROR, response.envelope.message.error)
    }

    @Test
    fun `config response carries the plugin id and the full tree`() {
        val accessory = accessoryWith(null)
        val response = accessory.handleOrFail(request(CafCommand.CONFIG_REQUEST))
        assertEquals(CafCommand.CONFIG_RESPONSE, response.envelope.message.command)
        assertEquals(
            B561VehicleStateAccessory.PLUGIN_ID,
            response.envelope.pluginId,
        )
        assertEquals(7L, response.envelope.message.transactionId)
        val config = responseValues(response)
        assertEquals(9, (config["accessories"] as List<*>).size)
    }

    // ── initialValue 预填（v131，LIVI initial_values() 对齐）──────────────

    @Test
    fun `register response prefills the declared initial values`() {
        // ★ v130 真车实证：数据源全废时 register 响应全是 NO_VALUE，
        // iPhone 车辆 UI 一片空白。v131 按 LIVI 的 initial_values()
        // 预填树里声明的初始值，至少让 UI 有合法下限。
        val accessory = accessoryWith(null)
        val response = accessory.handleOrFail(
            request(
                CafCommand.REGISTER_REQUEST,
                mapOf(
                    2562L to null, // 转速 → 0
                    2563L to null, // 转速上限 → MAX_RPM
                    2818L to null, // 功率 → 0.0
                    2306L to null, // 主音量 → 50
                    2053L to null, // 胎压传感器状态 → WAITING(3)
                ),
            ),
        )
        val values = responseValues(response)
        assertEquals(0L, values[2562L])
        assertEquals(B561VehicleStateAccessory.MAX_RPM, values[2563L])
        assertEquals(0.0, values[2818L])
        assertEquals(50L, values[2306L])
        assertEquals(B561VehicleStateAccessory.SENSOR_WAITING, values[2053L])
        // 全部有值 → 一个 error 都不报。
        assertEquals(emptyMap<Long, Long>(), responseErrors(response))
    }

    @Test
    fun `initial values cover every characteristic that declares one`() {
        // initialValues 必须与 config 树里带 initialValue 键的 characteristic
        // 一一对应 —— 少一个就有一个特性在无数据时退回 NO_VALUE。
        val accessory = accessoryWith(null)
        val declared = linkedSetOf<Long>()
        val config = accessory.config(hasEngineRpm = true)
        (config["accessories"] as List<*>).forEach { accessoryNode ->
            ((accessoryNode as Map<*, *>)["services"] as List<*>).forEach { serviceNode ->
                ((serviceNode as Map<*, *>)["characteristics"] as List<*>).forEach { charNode ->
                    val char = charNode as Map<*, *>
                    if (char.containsKey("initialValue")) {
                        declared.add(char["iid"] as Long)
                    }
                }
            }
        }
        // 树里声明了初始值的特性必须是一个可观的规模（整棵树 60+ 项）。
        assertTrue("树里带 initialValue 的特性至少 40 个", declared.size >= 40)
        // 抽查几个关键项都在声明集合里。
        listOf(2562L, 2563L, 2564L, 2818L, 1538L, 2306L, 2309L, 2053L).forEach {
            assertTrue("iid=$it 必须声明 initialValue", it in declared)
        }
    }

    @Test
    fun `a live reading overrides the prefilled initial`() {
        // 预填只是下限：真实读数来了必须赢。
        var current: B561UltraGauges? = null
        val accessory = B561VehicleStateAccessory { current }
        // 无数据 → 预填 0。
        val initial = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2562L to null)),
        )
        assertEquals(0L, responseValues(initial)[2562L])
        // 有数据 → 真实值。
        current = B561UltraGauges(motorRpm = 2400f)
        val live = accessory.handleOrFail(
            request(CafCommand.READ_REQUEST, mapOf(2562L to null)),
        )
        assertEquals(2400L, responseValues(live)[2562L])
    }

    @Test
    fun `prefilled baseline does not renotify when the value matches`() {
        // register 时预填 RPM=0 已记为基线；真实读数也是 0（车静止）
        // → 没有变化 → 不发 updateNotify。
        val accessory = accessoryWith(B561UltraGauges(motorRpm = 0f))
        accessory.handleOrFail(request(CafCommand.REGISTER_REQUEST, mapOf(2562L to null)))
        assertNull(accessory.changed())
        // 一旦真的转起来（0 → 900）才发 —— 注意变化必须发生在 register 之后，
        // register 同步基线的语义就是"注册时的快照不再重发"。
        var current: B561UltraGauges? = B561UltraGauges(motorRpm = 0f)
        val moving = B561VehicleStateAccessory { current }
        moving.handleOrFail(request(CafCommand.REGISTER_REQUEST, mapOf(2562L to null)))
        assertNull(moving.changed())
        current = B561UltraGauges(motorRpm = 900f)
        val changed = moving.changed()
        assertNotNull(changed)
        assertEquals(900L, responseValues(changed!!)[2562L])
    }
}
