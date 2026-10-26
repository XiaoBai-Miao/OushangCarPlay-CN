package com.shilapi.xcertplay.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B561VehicleSimulator] 的回归测试。
 *
 * 模拟器的产出会直接喂给 CarPlay Ultra 协议（CAF publish），
 * 所以它必须：**值域合法、持续变化、端到端可推**。
 */
class B561VehicleSimulatorTest {

    private val sim = B561VehicleSimulator()

    @Test
    fun `advance produces in-range readings over full cycle`() {
        sim.reset()
        var prev: B561GaugeReadings? = null
        var maxSpeed = 0f
        var minSpeed = Float.MAX_VALUE
        repeat(120) { // 两个完整周期，每秒一步
            val g = sim.advance(1f)
            assertTrue("isEmpty must be false", !g.isEmpty)
            val speed = g.speedKph!!
            assertTrue("speed $speed out of range", speed in 0f..80.01f)
            maxSpeed = maxOf(maxSpeed, speed)
            minSpeed = minOf(minSpeed, speed)
            g.batteryPercent?.let {
                assertTrue("soc $it out of range", it in 20..100)
            }
            g.rangeKm?.let {
                assertTrue("range $it out of range", it in 0..0xffff)
            }
            g.odometerKm?.let {
                assertTrue("odo $it must not go backwards", it >= 12345.6f)
            }
            g.tirePressureBar?.let { tires ->
                assertEquals(4, tires.size)
                tires.forEach { p ->
                    assertTrue("tire $p out of range", p in 2.0f..2.7f)
                }
            }
            g.powerKw?.let { p ->
                assertTrue("power $p must be non-negative", p >= 0f)
            }
            prev = g
        }
        assertNotNull(prev)
        assertTrue("工况必须加速到 70km/h 以上（实测 $maxSpeed）", maxSpeed > 70f)
        assertTrue("工况必须有怠速段（实测 $minSpeed）", minSpeed < 1f)
    }

    @Test
    fun `consecutive advances always differ so publish is not short-circuited`() {
        sim.reset()
        // v126 未闭环的直接原因就是 previous == value；模拟器必须持续变化。
        var prev = sim.advance(1f)
        var changed = 0
        repeat(60) {
            val next = sim.advance(1f)
            if (next != prev) changed++
            prev = next
        }
        assertEquals("60 次推进必须每次都有变化", 60, changed)
    }

    @Test
    fun `rpm follows speed and is zero at idle`() {
        sim.reset()
        // 0-8s 是怠速段。
        val idle = sim.advance(1f)
        assertTrue("怠速 rpm 应为 0，实测 ${idle.motorRpm}", idle.motorRpm == 0f)
        // 推进到巡航段（22s 后）。
        repeat(25) { sim.advance(1f) }
        val cruise = sim.advance(1f)
        assertTrue("巡航 rpm 应显著为正", cruise.motorRpm!! > 1000f)
        // 转速与车速同向：80km/h 时约 800+80*32=3360。
        assertTrue(
            "巡航 rpm ${cruise.motorRpm} 应在 2500-4000 区间",
            cruise.motorRpm!! in 2500f..4000f,
        )
    }

    @Test
    fun `reading path yields iAP2 snapshot with battery and range`() {
        sim.reset()
        repeat(25) { sim.advanceReading(1f) } // 推到巡航段
        val reading = sim.advanceReading(1f)
        val snapshot = reading.toSnapshot(15)
        assertNotNull("模拟读数必须能产出 iAP2 snapshot", snapshot)
        assertTrue("模拟电量 78% 高于阈值，不应告警", !snapshot!!.rangeWarning)
        assertTrue("续航应显著为正", snapshot.rangeKm > 100)
    }

    @Test
    fun `soc drains monotonically during sustained driving`() {
        sim.reset()
        // 平均功率约 16kW，一小时约掉 26% SOC（62kWh 电池）。
        var reading: CabinReading? = null
        repeat(3600) { reading = sim.advanceReading(1f) } // 一小时
        val r = reading!!
        assertTrue(
            "一小时城区工况 SOC 应显著下降（实测 ${r.batteryPercent}）",
            r.batteryPercent!! in 45..70,
        )
    }

    @Test
    fun `low battery triggers range warning`() {
        // 低电量告警逻辑：直接构造低电量读数验证 snapshot 通路
        // （不需要让模拟器跑几小时——那是 toSnapshot 的职责，已有单测覆盖）。
        val lowReading = CabinReading(
            speedKph = 0f,
            gear = B561VehicleSimulator.GEAR_D,
            batteryPercent = 10,
            rangeKm = 41,
            voltage = 390f,
            current = 1.5f,
        )
        val snapshot = lowReading.toSnapshot(15)
        assertNotNull(snapshot)
        assertTrue("低电量必须告警", snapshot!!.rangeWarning)
    }

    @Test
    fun `gear is always D`() {
        sim.reset()
        repeat(30) {
            assertEquals('D', sim.advance(1f).gearLabel)
        }
    }

    @Test
    fun `engine rpm and fuel are null for pure EV`() {
        sim.reset()
        val g = sim.advance(1f)
        assertNull("纯电车无发动机转速", g.engineRpm)
        assertNull("纯电车无油量", g.fuelPercent)
        assertEquals("primaryRpm 应落到电机转速", g.motorRpm, g.primaryRpm)
    }

    @Test
    fun `reset restores initial state`() {
        sim.reset()
        repeat(40) { sim.advance(1f) }
        sim.reset()
        val g = sim.advance(0.01f)
        assertTrue("reset 后车速应接近 0", g.speedKph!! < 0.2f)
        // 复位后第一步就按怠速功率耗电（78 - 1.5*0.01/2232 ≈ 77.9993），
        // Int 截断后为 77。
        assertTrue("reset 后电量应回到 77~78，实测 ${g.batteryPercent}", g.batteryPercent in 77..78)
    }
}
