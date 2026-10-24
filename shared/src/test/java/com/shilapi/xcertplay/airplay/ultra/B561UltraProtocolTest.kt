package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.vehicle.B561UltraGauges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B561UltraProtocol] 的接线回归测试。
 *
 * ## 为什么有这些用例
 *
 * v125 装车后用 logcat 抓日志，**Ultra/CAF 层的痕迹一条都看不到** ——
 * 因为协议的日志只写 app 私有目录的 `diplay.log`，不写 logcat。
 * 协议层等于黑的，没法判断卡在哪一层（feature 协商 / RCS 建链 / CAF 握手 /
 * accessory 响应）。v126 改成两条通道都写，这些用例把「日志确实会流出去」
 * 这件事钉住，避免以后再退化。
 */
class B561UltraProtocolTest {

    /** 收集协议层日志，验证它真的会流到 trace 通道。 */
    private class Recorder {
        val lines = mutableListOf<String>()

        fun accept(message: String) {
            lines += message
        }
    }

    @Test
    fun `vehicle state changes are traced so logcat can show them`() {
        val recorder = Recorder()
        val protocol = B561UltraProtocol(
            sample = { null },
            trace = { recorder.accept(it) },
        )
        protocol.onGaugesChanged(
            B561UltraGauges(
                speedKph = 42f,
                gear = 4,
                motorRpm = 1200f,
                powerKw = 18.5f,
                batteryPercent = 76,
                fuelPercent = null,
                rangeKm = 310,
                odometerKm = 54268f,
                tirePressureBar = floatArrayOf(2.59f, 2.52f, 2.56f, 2.49f),
            ),
        )
        assertTrue(
            "协议层日志必须能流出去（v126 修复点）",
            recorder.lines.any { it.contains("VEHICLE") && it.contains("rpm=1200") },
        )
    }

    @Test
    fun `identical readings do not produce another trace line`() {
        val recorder = Recorder()
        val protocol = B561UltraProtocol(
            sample = { null },
            trace = { recorder.accept(it) },
        )
        val reading = B561UltraGauges(
            speedKph = 10f,
            gear = 1,
            motorRpm = 800f,
            powerKw = 5f,
            batteryPercent = 50,
            fuelPercent = null,
            rangeKm = 200,
            odometerKm = 1000f,
            tirePressureBar = null,
        )
        protocol.onGaugesChanged(reading)
        val after = recorder.lines.size
        protocol.onGaugesChanged(reading)
        assertEquals("同样的读数不该重复刷日志", after, recorder.lines.size)
    }

    @Test
    fun `feature is offered only when the phone proposed it`() {
        val protocol = B561UltraProtocol(sample = { null })
        assertEquals(
            listOf("vehicleStateProtocol"),
            protocol.enabledFeatures(listOf("video", "vehicleStateProtocol")),
        )
        assertEquals(
            emptyList<String>(),
            protocol.enabledFeatures(listOf("video", "audio")),
        )
        assertEquals(emptyList<String>(), protocol.enabledFeatures(null))
    }

    @Test
    fun `info decoration declares the vehicle state protocol and the display panels`() {
        val protocol = B561UltraProtocol(sample = { null })
        val info = linkedMapOf<String, Any?>(
            "displays" to listOf(
                linkedMapOf<String, Any?>(
                    "uuid" to "b7e6c5a0-1111-4000-8000-000000000001",
                    "type" to 110,
                    "widthPixels" to 1920,
                    "heightPixels" to 910,
                    "widthPhysical" to 325,
                ),
            ),
        )
        protocol.decorateInfo(info)

        // vehicleStateProtocolInfo：协议版本 + 插件映射。
        val vsp = info["vehicleStateProtocolInfo"] as? Map<*, *>
        assertNotNull("INFO 必须声明 vehicleStateProtocolInfo", vsp)
        assertEquals("1.0", vsp!!["protocolVersion"])
        assertEquals(1, (vsp["pluginCount"] as Number).toInt())

        // pluginConfigs 里要带完整的 CAF accessory 树。
        val configs = vsp["pluginConfigs"] as? List<*>
        assertNotNull(configs)
        val plugin = configs!!.firstOrNull() as? Map<*, *>
        assertNotNull(plugin)
        assertEquals(
            B561VehicleStateAccessory.PLUGIN_ID,
            (plugin!!["pluginID"] as Number).toLong(),
        )

        // displayPanels：★ v130 themed 结构 —— 每个真实 display 一个 panel，
        // 不追加虚拟 panel（LIVI 测试断言："the cluster carries the
        // plug-in, no extra panel"）。只有主屏时就是 1 个 panel。
        val panels = info["displayPanels"] as? List<*>
        assertNotNull("INFO 必须声明 displayPanels", panels)
        assertEquals(1, panels!!.size)
        val main = panels[0] as Map<*, *>
        assertEquals("Main_Display", main["uid"])
        // 主屏不是仪表屏 → 不带 showsInstruments，也不带 displayPlugins。
        assertEquals(emptyList<Any?>(), main["properties"])
        assertNull("主屏不该挂 displayPlugins", main["displayPlugins"])
    }

    @Test
    fun `the cluster screen panel carries the caf plugin pointing at its own stream`() {
        val protocol = B561UltraProtocol(sample = { null })
        val info = linkedMapOf<String, Any?>(
            "displays" to listOf(
                linkedMapOf<String, Any?>(
                    "uuid" to "b7e6c5a0-1111-4000-8000-000000000001",
                    "type" to 110,
                    "widthPixels" to 1920,
                    "heightPixels" to 910,
                    "widthPhysical" to 325,
                ),
                linkedMapOf<String, Any?>(
                    "uuid" to "b7e6c5a0-2222-4000-8000-000000000002",
                    "type" to 111,
                    "widthPixels" to 2408,
                    "heightPixels" to 904,
                    "widthPhysical" to 292,
                ),
            ),
        )
        protocol.decorateInfo(info)
        val panels = info["displayPanels"] as List<*>

        // 主屏 + 集群屏 = 2 个 panel，没有虚拟 panel。
        assertEquals(2, panels.size)
        val main = panels[0] as Map<*, *>
        assertEquals("Main_Display", main["uid"])
        assertNull("主屏不该挂 displayPlugins", main["displayPlugins"])

        // 仪表屏 panel：带 showsInstruments。
        val cluster = panels[1] as Map<*, *>
        assertEquals("Cluster_Display", cluster["uid"])
        assertEquals(listOf("showsInstruments"), cluster["properties"])
        // 仪表屏保留自己的视频流。
        val streams = cluster["initialVideoStreams"] as List<*>
        assertEquals(1, streams.size)
        assertEquals("b7e6c5a0-2222-4000-8000-000000000002", (streams[0] as Map<*, *>)["uuid"])

        // ★ v130：插件挂在集群 panel 自身（LIVI themed 量产路径）——
        // 插件 uid 与 panel uid 相同，videoStreams 指向集群屏 uuid，
        // iPhone 把 CAF 车辆状态直接画进这条视频流。
        val plugins = cluster["displayPlugins"] as List<*>
        assertNotNull("集群屏必须挂 displayPlugins", plugins)
        assertEquals(1, plugins!!.size)
        val plugin = plugins[0] as Map<*, *>
        assertEquals("Cluster_Display", plugin["uid"])
        assertEquals(
            listOf("b7e6c5a0-2222-4000-8000-000000000002"),
            plugin["videoStreams"],
        )
    }

    @Test
    fun `caf alive heartbeat distinguishes no subscription from slow pushes`() {
        val recorder = Recorder()
        var now = 0L
        val protocol = B561UltraProtocol(
            sample = { null },
            clock = { now },
            trace = { recorder.accept(it) },
        )
        // 首条数据：lastHeartbeat 还是初值，间隔不足 → 不打心跳。
        protocol.onGaugesChanged(B561UltraGauges(speedKph = 10f))
        assertTrue(recorder.lines.none { it.startsWith("CAF ALIVE") })

        // 5 秒后：打一条；没有通道 → subs=0 明确告警
        // （v126 症状：iPhone 从未注册，日志上却看不出）。
        now = 5_000
        protocol.onGaugesChanged(B561UltraGauges(speedKph = 11f))
        val alive = recorder.lines.filter { it.startsWith("CAF ALIVE") }
        assertEquals(1, alive.size)
        assertTrue(alive[0].contains("subs=0"))
        assertTrue(alive[0].contains("phone registered nothing"))

        // 间隔内不再打。
        now = 9_000
        protocol.onGaugesChanged(B561UltraGauges(speedKph = 12f))
        assertEquals(1, recorder.lines.count { it.startsWith("CAF ALIVE") })
    }

    @Test
    fun `info decoration survives a receiver without any display`() {
        val protocol = B561UltraProtocol(sample = { null })
        val info = linkedMapOf<String, Any?>()
        protocol.decorateInfo(info)
        // 没有显示屏就没有 panel —— 更不能凭空造一块虚拟仪表屏。
        assertEquals(emptyList<Any?>(), info["displayPanels"])
    }
}
