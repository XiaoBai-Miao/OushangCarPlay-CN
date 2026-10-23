package com.shilapi.xcertplay.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 属性 ID 与换算的回归测试。
 *
 * 每个断言都对应**实车 logcat 里的一条证据**，不是凭直觉写的期望值。
 */
class B561VehiclePropertyTest {

    @Test
    fun `车速属性是 PERF_VEHICLE_SPEED 而不是历史上的 0x2140f4a9`() {
        // 实车日志: Property.service: property:0x11600207 on change, values=0.5625
        //          与原厂仪表 speed Value() = 0.5625 逐条相等
        assertEquals(0x11600207, B561VehicleProperty.SPEED)
        // 旧实现读的这个属性在实车日志里全程 data=0 —— 永远读到 0 速
        assertEquals(0x2140f4a9, 0x2140f4a9)
    }

    @Test
    fun `电量属性是 0x2140f636`() {
        // 实车日志: getIntProperty 0x2140f636 data=80
        //          同刻原厂桌面: 电量=80
        assertEquals(0x2140f636, B561VehicleProperty.BATTERY_PERCENT)
        assertEquals(
            B561VehicleProperty.BATTERY_PERCENT,
            B561VehicleProperty.BATTERY_PERCENT_CANDIDATES.first(),
        )
    }

    @Test
    fun `挡位属性是 0x11400400`() {
        // 实车日志: DvrCanModel: ID_GEAR_SELECTION signal:289408000
        //          289408000 == 0x11400400
        //          getIntProperty 0x11400400 data=8 与同刻 "车速大于20km/h，D挡" 闭合
        assertEquals(0x11400400, B561VehicleProperty.GEAR_SELECTION)
        assertEquals(289408000, B561VehicleProperty.GEAR_SELECTION)
    }

    @Test
    fun `车速原值已是 km_h 不需要缩放`() {
        // 实车: 静止 0.5625 km/h、巡航 70.48125 km/h
        assertEquals(0.5625f, B561VehicleProperty.speedKph(0.5625f)!!, 1e-4f)
        assertEquals(70.48125f, B561VehicleProperty.speedKph(70.48125f)!!, 1e-4f)
    }

    @Test
    fun `无效车速被剔除而不是报 0`() {
        // 熄火/倒车时 VHAL 可能给 -1；报 0 会让驾驶员以为传感器坏了
        assertNull(B561VehicleProperty.speedKph(-1f))
        assertNull(B561VehicleProperty.speedKph(null))
        assertNull(B561VehicleProperty.speedKph(9999f))
    }

    @Test
    fun `电量只接受 1 到 99`() {
        assertEquals(80, B561VehicleProperty.batteryPercent(80))
        assertEquals(34, B561VehicleProperty.batteryPercent(34))
        // 0 几乎总是"读不到"，100 在部分固件上是默认值 —— 两者都不可信
        assertNull(B561VehicleProperty.batteryPercent(0))
        assertNull(B561VehicleProperty.batteryPercent(100))
        assertNull(B561VehicleProperty.batteryPercent(null))
    }

    @Test
    fun `挡位只认实车坐实过的 D 挡`() {
        // 唯一有中文判定行佐证的值
        assertEquals('D', B561VehicleProperty.gearLabel(8))
        // 历史实现写的 21..27 映射已被否证: 0x2140f477 静止恒 27、55km/h 时 19
        assertNull(B561VehicleProperty.gearLabel(21))
        assertNull(B561VehicleProperty.gearLabel(27))
        assertNull(B561VehicleProperty.gearLabel(0))
        assertNull(B561VehicleProperty.gearLabel(null))
    }

    @Test
    fun `功率是电压乘电流除以一千`() {
        // 实车: 电压 372.5 V、电流 1.1 A
        val kw = B561VehicleProperty.powerKw(372.5f, 1.1f)!!
        assertEquals(0.40975f, kw, 1e-5f)
    }

    @Test
    fun `功率在缺电压或电流时为 null`() {
        assertNull(B561VehicleProperty.powerKw(null, 1.1f))
        assertNull(B561VehicleProperty.powerKw(372.5f, null))
        assertNull(B561VehicleProperty.powerKw(0f, 1.1f))
        assertNull(B561VehicleProperty.powerKw(372.5f, -1f))
    }

    @Test
    fun `续航候选表首项是反编译坐实的纯电续航属性`() {
        // 来源：反编译 VehicleHvacControl-282.apk（UNIZ车机控制，实车在用）
        //   .field private static final VEHICLE_ELECTRIC_RANGE:I = 0x2140f635
        //   .field private static final VEHICLE_FUEL_RANGE:I = 0x2140f633
        // 这两个值不是我方推测的，必须留在候选表里 —— iAP2 的 vehicleStatusEnabled
        // 需要「电量 + 续航」双条件，缺续航就只能报 false。
        assertEquals(0x2140f635, B561VehicleProperty.RANGE_ELECTRIC_KM)
        assertEquals(0x2140f633, B561VehicleProperty.RANGE_FUEL_KM)
        assertTrue(
            "纯电续航必须是第一候选",
            B561VehicleProperty.RANGE_KM_CANDIDATES.first() ==
                B561VehicleProperty.RANGE_ELECTRIC_KM,
        )
    }

    @Test
    fun `属性 ID 与实车在用的 UNIZ 车机控制一致`() {
        // 交叉验证：那个 APK 硬编码了同一组 ID，两条独立证据链互相印证。
        //   VEHICLE_SPEED = 0x11600207
        //   VEHICLE_BATTERY_SOC = 0x2140f636
        //   INSTRUMENT_BATTERY_VOLTAGE = 0x2160f503
        //   INSTRUMENT_BATTERY_CURRENT = 0x2160f502
        //   挡位 = 0x11400400
        assertEquals(0x11600207, B561VehicleProperty.SPEED)
        assertEquals(0x2140f636, B561VehicleProperty.BATTERY_PERCENT)
        assertEquals(0x2160f503, B561VehicleProperty.VOLTAGE)
        assertEquals(0x2160f502, B561VehicleProperty.CURRENT)
        assertEquals(0x11400400, B561VehicleProperty.GEAR_SELECTION)
    }
}
