package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮速源的行为测试。
 *
 * 重点是**单位换算**（km/h → m/s）—— `$PASCD` 的速度字段单位是 m/s，
 * 换算错会让 CarPlay 地图把车速放大 3.6 倍。
 */
class B561VehicleSpeedSourceTest {

    private var speed: Float? = null
    private var gear: Int? = null
    private var clock = 1_000L

    private fun source() = B561VehicleSpeedSource(
        speedProvider = { speed },
        gearProvider = { gear },
        elapsedMillis = { clock },
    )

    @Test
    fun `速度从 km_h 换算成 m_s`() {
        // 36 km/h = 10 m/s
        speed = 36f
        val s = source()
        s.start()
        s.sample()
        val reading = s.drain()!!
        assertEquals(1, reading.samples.size)
        assertEquals(10.0, reading.samples.first().metersPerSecond, 1e-6)
    }

    @Test
    fun `实车日志里的静止值 0_5625 换算成 0_15625 m_s`() {
        speed = 0.5625f
        val s = source()
        s.start()
        s.sample()
        assertEquals(0.15625, s.drain()!!.samples.first().metersPerSecond, 1e-6)
    }

    @Test
    fun `巡航 70_48125 km_h 换算成 19_578 m_s`() {
        speed = 70.48125f
        val s = source()
        s.start()
        s.sample()
        assertEquals(19.578125, s.drain()!!.samples.first().metersPerSecond, 1e-6)
    }

    @Test
    fun `静止时重复读数被去重`() {
        speed = 0f
        val s = source()
        s.start()
        s.sample()
        s.sample()
        s.sample()
        val reading = s.drain()!!
        assertEquals(1, reading.samples.size)
    }

    @Test
    fun `车速变化时记新采样`() {
        speed = 0f
        val s = source()
        s.start()
        s.sample()
        s.drain()

        speed = 36f
        clock = 2_000L
        s.sample()
        val reading = s.drain()!!
        assertEquals(1, reading.samples.size)
        assertEquals(2_000L, reading.samples.first().elapsedMillis)
    }

    @Test
    fun `没有新采样时 drain 返回 null`() {
        speed = 36f
        val s = source()
        s.start()
        s.sample()
        s.drain()
        // 第二次没有新读数 —— 协议栈据此判断"这一轮不发"
        assertNull(s.drain())
    }

    @Test
    fun `读不到车速时不产生采样`() {
        speed = null
        val s = source()
        s.start()
        s.sample()
        assertNull(s.drain())
    }

    @Test
    fun `挡位 8 映射为 D`() {
        speed = 10f
        gear = 8
        val s = source()
        s.start()
        s.sample()
        assertEquals(VehicleGear.DRIVE, s.drain()!!.gear)
    }

    @Test
    fun `读不到挡位时按 D 处理`() {
        speed = 10f
        gear = null
        val s = source()
        s.start()
        s.sample()
        // iAP2 侧对挡位的唯一用途是判断"是否倒车"，
        // "读不到"与"不在倒车"对这个判断等价
        assertEquals(VehicleGear.DRIVE, s.drain()!!.gear)
    }

    @Test
    fun `停止后不再累积采样`() {
        speed = 36f
        val s = source()
        s.start()
        s.sample()
        s.stop()
        s.sample()
        assertNull(s.drain())
    }

    @Test
    fun `采样点不超过上限`() {
        speed = 0f
        val s = source()
        s.start()
        // 每一次都变，攒到超过上限
        for (i in 1..200) {
            speed = i.toFloat()
            clock = 1_000L + i
            s.sample()
        }
        val reading = s.drain()!!
        assertTrue("采样点应被截断", reading.samples.size <= 64)
    }
}
