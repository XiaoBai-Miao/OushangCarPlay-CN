package com.shilapi.xcertplay.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B561GaugeReadings] 的回归测试。
 *
 * 核心原则：**读不到就是 null，仪表少画一块，不画假数字。**
 */
class B561GaugeReadingsTest {

    @Test
    fun `an empty snapshot reports isEmpty`() {
        val gauges = B561GaugeReadings()
        assertTrue(gauges.isEmpty)
        assertFalse(gauges.hasRpm)
        assertNull(gauges.primaryRpm)
        assertNull(gauges.gearLabel)
    }

    @Test
    fun `a snapshot with only speed is not empty`() {
        val gauges = B561GaugeReadings(speedKph = 52.5f)
        assertFalse(gauges.isEmpty)
    }

    @Test
    fun `engine rpm wins over motor rpm for the tacho`() {
        // 混动车：仪表转速表传统上显示发动机转速。
        val gauges = B561GaugeReadings(motorRpm = 1900f, engineRpm = 2400f)
        assertTrue(gauges.hasRpm)
        assertEquals(2400f, gauges.primaryRpm!!, 0.01f)
    }

    @Test
    fun `electric vehicle falls back to motor rpm`() {
        // 纯电车没有发动机转速，只能用电机转速。
        val gauges = B561GaugeReadings(motorRpm = 1900f)
        assertTrue(gauges.hasRpm)
        assertEquals(1900f, gauges.primaryRpm!!, 0.01f)
    }

    @Test
    fun `power is derived from voltage and current`() {
        val gauges = B561GaugeReadings(
            speedKph = 30f,
            powerKw = B561VehicleProperty.powerKw(330f, -120f),
        )
        // 放电时电流为负 → 功率为 null（与 B561VehicleProperty.powerKw 一致）
        assertNull(gauges.powerKw)
    }

    @Test
    fun `gear label only maps the verified D value`() {
        assertEquals('D', B561GaugeReadings(gear = 8).gearLabel)
        assertNull(B561GaugeReadings(gear = 27).gearLabel)
        assertNull(B561GaugeReadings(gear = null).gearLabel)
    }

    @Test
    fun `tyre pressure array is copied not shared`() {
        // 解析器交出来的数组不能被外部改动 —— 否则会污染后续读数。
        val source = floatArrayOf(2.5f, 2.5f, 2.5f, 2.5f)
        val fields = mapOf<String, Any>(B561VehicleLogParser.KEY_TIRE_PRESSURE to source)
        val gauges = B561GaugeReadings(fields)
        source[0] = 9.9f
        assertEquals(2.5f, gauges.tirePressureBar!![0], 0.0001f)
    }

    @Test
    fun `equals compares tyre pressure by value`() {
        // data class 对数组默认用引用比较，覆写后必须按值比较 ——
        // 否则「值没变」判断失效，仪表会无谓重绘。
        val a = B561GaugeReadings(speedKph = 10f, tirePressureBar = floatArrayOf(2.5f, 2.5f, 2.5f, 2.5f))
        val b = B561GaugeReadings(speedKph = 10f, tirePressureBar = floatArrayOf(2.5f, 2.5f, 2.5f, 2.5f))
        val c = B561GaugeReadings(speedKph = 10f, tirePressureBar = floatArrayOf(2.5f, 2.5f, 2.5f, 3.0f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    @Test
    fun `builds from parsed log fields`() {
        val fields = mapOf<String, Any>(
            B561VehicleLogParser.KEY_SPEED to 52.48125f,
            B561VehicleLogParser.KEY_GEAR to 8,
            B561VehicleLogParser.KEY_MOTOR_RPM to 1200f,
            B561VehicleLogParser.KEY_VOLTAGE to 331.9f,
            B561VehicleLogParser.KEY_CURRENT to -2.4f,
            B561VehicleLogParser.KEY_BATTERY to 49,
            B561VehicleLogParser.KEY_RANGE to 312,
            B561VehicleLogParser.KEY_ODOMETER to 54266f,
            B561VehicleLogParser.KEY_TIRE_PRESSURE to floatArrayOf(2.59f, 2.52f, 2.56f, 2.49f),
        )
        val gauges = B561GaugeReadings(fields)
        assertEquals(52.48125f, gauges.speedKph!!, 0.0001f)
        assertEquals(8, gauges.gear)
        assertEquals('D', gauges.gearLabel)
        assertEquals(1200f, gauges.motorRpm!!, 0.01f)
        assertEquals(49, gauges.batteryPercent)
        assertEquals(312, gauges.rangeKm)
        assertEquals(54266f, gauges.odometerKm!!, 0.01f)
        assertEquals(4, gauges.tirePressureBar!!.size)
        // 电流为负（放电）→ 功率算不出来，报 null 而不是负 kW。
        assertNull(gauges.powerKw)
    }
}
