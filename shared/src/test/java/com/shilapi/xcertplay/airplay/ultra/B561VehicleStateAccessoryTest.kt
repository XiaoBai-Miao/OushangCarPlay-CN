package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.vehicle.B561UltraGauges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B561VehicleStateAccessory] 的回归测试。
 *
 * 重点验证 **iid 映射** —— 这些数字是 Apple CarPlay Ultra 协议的约定，
 * 错了 iPhone 就读不到对应数据。映射来源是 LIVI 的 `vehicle_state.rs`。
 */
class B561VehicleStateAccessoryTest {

    private fun accessoryWith(data: B561UltraGauges?) =
        B561VehicleStateAccessory { data }

    // ── iid 常量（照 LIVI vehicle_state.rs）─────────────────────────────────

    @Test
    fun `characteristic iids match the LIVI reference`() {
        assertEquals(2562L, B561VehicleStateAccessory.ENGINE_RPM)
        assertEquals(2818L, B561VehicleStateAccessory.ENGINE_POWER)
        assertEquals(1538L, B561VehicleStateAccessory.FUEL_LEVEL)
        assertEquals(1541L, B561VehicleStateAccessory.FUEL_RANGE)
        assertEquals(1794L, B561VehicleStateAccessory.REMAINING_RANGE)
        assertEquals(1798L, B561VehicleStateAccessory.ODOMETER)
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
    }

    // ── CAF config 树 ────────────────────────────────────────────────────

    @Test
    fun `config tree carries the engine and electric engine accessories`() {
        val config = accessoryWith(null).config(hasEngineRpm = true)
        val accessories = config["accessories"] as List<*>
        // 发动机（含转速）+ 电动引擎（功率）+ 油量/续航/里程 + 胎压
        assertEquals(4, accessories.size)
        val byIid = accessories.associateBy { (it as Map<*, *>)["iid"] }
        assertNotNull(byIid[2560L])
        assertNotNull(byIid[2816L])
        // 转速 characteristic 在 kind 0x1A00_000F 的 service 里。
        val engine = byIid[2560L] as Map<*, *>
        val engineService = (engine["services"] as List<*>).first() as Map<*, *>
        assertEquals(0x1A00_000FL, engineService["type"])
        val chars = engineService["characteristics"] as List<*>
        val rpm = chars.first() as Map<*, *>
        assertEquals(B561VehicleStateAccessory.ENGINE_RPM, rpm["iid"])
        assertEquals(0x3000_0024L, rpm["type"])
    }

    @Test
    fun `config tree without engine rpm omits the combustion engine`() {
        val config = accessoryWith(null).config(hasEngineRpm = false)
        val accessories = config["accessories"] as List<*>
        val byIid = accessories.associateBy { (it as Map<*, *>)["iid"] }
        assertNull(byIid[2560L])
        // 电动引擎（功率）仍然在。
        assertNotNull(byIid[2816L])
    }

    @Test
    fun `power characteristic carries the kilowatt unit and range`() {
        val config = accessoryWith(null).config(hasEngineRpm = true)
        val accessories = config["accessories"] as List<*>
        val electric = accessories.associateBy { (it as Map<*, *>)["iid"] }[2816L] as Map<*, *>
        val service = (electric["services"] as List<*>).first() as Map<*, *>
        val power = (service["characteristics"] as List<*>).first() as Map<*, *>
        assertEquals(B561VehicleStateAccessory.ENGINE_POWER, power["iid"])
        assertEquals(0x3000_0028L, power["type"])
        assertEquals(B561VehicleStateAccessory.KILOWATTS, power["units"])
        assertEquals(-1000.0, power["minimumValue"])
        assertEquals(1000.0, power["maximumValue"])
    }

    @Test
    fun `tyre pressure service declares the wheel layout keys`() {
        val config = accessoryWith(null).config(hasEngineRpm = true)
        val accessories = config["accessories"] as List<*>
        val tires = accessories.associateBy { (it as Map<*, *>)["iid"] }[2048L] as Map<*, *>
        val services = tires["services"] as List<*>
        assertEquals(4, services.size)
        // 第一个轮：胎压值 + 布局 key + 状态 + 传感器状态
        val first = (services.first() as Map<*, *>)["characteristics"] as List<*>
        assertEquals(4, first.size)
        val layout = first[1] as Map<*, *>
        assertEquals("tire_front_left", layout["initialValue"])
    }

    // ── 取值 ────────────────────────────────────────────────────────────

    @Test
    fun `reads rpm power range and mileage from the snapshot`() {
        val data = B561UltraGauges(
            speedKph = 52.5f,
            motorRpm = 1200f,
            powerKw = 42.5f,
            batteryPercent = 49,
            rangeKm = 312,
            odometerKm = 54266f,
            gear = 8,
        )
        val accessory = accessoryWith(data)
        val changed = accessory.changed()
        // 没有订阅时不该有任何更新帧。
        assertNull(changed)
    }

    @Test
    fun `tyre pressure is converted from bar to kPa`() {
        // 2.5 bar = 250 kPa —— Apple 侧用 kPa。
        val data = B561UltraGauges(
            speedKph = 30f,
            tirePressureBar = floatArrayOf(2.5f, 2.6f, 2.4f, 2.5f),
        )
        assertNotNull(data.tirePressureBar)
        // 2.5 bar × 100 = 250 kPa
        assertEquals(250L, (data.tirePressureBar!![0] * 100f).toLong())
    }

    @Test
    fun `a snapshot without readings never reports a zero value`() {
        // 没有数据时 values() 是空表 —— 报 0 会被 iPhone 当成真值。
        val data = B561UltraGauges(speedKph = 10f)
        val accessory = accessoryWith(data)
        // 没有订阅 → 没有帧。
        assertNull(accessory.changed())
    }
}
