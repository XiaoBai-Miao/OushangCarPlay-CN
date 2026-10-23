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

        // displayPanels：真实屏 + 虚拟 cluster panel。
        val panels = info["displayPanels"] as? List<*>
        assertNotNull("INFO 必须声明 displayPanels", panels)
        assertEquals(2, panels!!.size)
        val main = panels[0] as Map<*, *>
        assertEquals("b7e6c5a0-1111-4000-8000-000000000001-panel", main["uid"])
        // 主屏不是仪表屏 → 不带 showsInstruments，也不带 displayPlugins。
        assertEquals(emptyList<Any?>(), main["properties"])
        assertNull("主屏不该挂 displayPlugins", main["displayPlugins"])
    }

    @Test
    fun `the cluster screen panel is marked as showing instruments`() {
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

        // 仪表屏 panel：带 showsInstruments，但**不能**带 displayPlugins ——
        // 插件会把该屏拉黑（LIVI 的注释：a plug-in would black out the cluster）。
        val cluster = panels[1] as Map<*, *>
        assertEquals("b7e6c5a0-2222-4000-8000-000000000002-panel", cluster["uid"])
        assertEquals(listOf("showsInstruments"), cluster["properties"])
        assertNull("仪表屏不该挂 displayPlugins", cluster["displayPlugins"])
        // 仪表屏保留自己的视频流。
        val streams = cluster["initialVideoStreams"] as List<*>
        assertEquals(1, streams.size)
        assertEquals("b7e6c5a0-2222-4000-8000-000000000002", (streams[0] as Map<*, *>)["uuid"])
    }

    @Test
    fun `a virtual cluster panel carries the caf plugin so the phone knows where to draw`() {
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
        assertEquals(3, panels.size)

        // 虚拟 panel：无视频流、带 showsInstruments、带 displayPlugins。
        val virtual = panels[2] as Map<*, *>
        assertEquals("b7e6c5a0-3333-4000-8000-000000000003-panel", virtual["uid"])
        assertEquals(listOf("showsInstruments"), virtual["properties"])
        assertEquals(emptyList<Any?>(), virtual["initialVideoStreams"])
        val plugins = virtual["displayPlugins"] as List<*>
        assertEquals(1, plugins.size)
        val plugin = plugins[0] as Map<*, *>
        assertEquals("b7e6c5a0-3333-4000-8000-000000000003-plugin", plugin["uid"])
        assertEquals(emptyList<Any?>(), plugin["videoStreams"])
        // 尺寸跟仪表屏一致（虚拟 panel 就是仪表屏的那块虚拟镜像）。
        assertEquals(2408, virtual["widthPixels"])
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
