package com.shilapi.xcertplay.vehicle

import org.junit.Assert.*
import org.junit.Test

/**
 * v129: logcat 订阅 tag 守护 + v128 真车日志行回放。
 *
 * 背景：2026-10-24 真车 B561 日志（`D:/CAR/日志/日志_10月24日07时24分06秒546.log`）
 * 暴露 `VEHICLE rpm=null power=24.3 battery=null`——功率有值（电压×电流都读到了），
 * 唯独电量是 null。根因：SOC `0x2140f636` 在真车上**只**以
 * `CarCabinManager: getIntProperty ... data=68` 形态出现（原厂桌面轮询时打），
 * 而 [LogcatVehicleStatus] 的 logcat 子进程没订阅这个 tag——
 * parser 的 CAR_CABIN_GET 正则从 v123 起就在，却从未收到过输入行。
 *
 * 漏 tag 是**静默失败**（正则等一个永远不来的输入），所以用测试逐项守住。
 */
class LogcatVehicleStatusTagTest {
    @Test
    fun `subscribes every tag the parser depends on`() {
        val tags = LogcatVehicleStatus.LOG_TAGS
        assertTrue(
            "必须订阅 Property.service（VHAL 变化回调，车速/电压/电流）",
            tags.contains("Property.service:D"),
        )
        assertTrue(
            "必须订阅 FastControlManager（挡位）",
            tags.contains("FastControlManager:D"),
        )
        assertTrue(
            "必须订阅 CmdSmdManager（MCU 原始报文，电机转速/胎压）",
            tags.contains("CmdSmdManager:D"),
        )
        assertTrue(
            "必须订阅 CarCabinManager（SOC 只以 getIntProperty 形态出现在这个 tag）",
            tags.contains("CarCabinManager:D"),
        )
    }

    /**
     * 用 2026-10-24 07:24 真车日志的**原始行**回放，验证每条路径都解析得出值。
     *
     * 这比构造样本更硬：行格式（threadtime、pid/tid、D/I 级别、多余列）
     * 全是实车原样，任何正则漂移都会在这里暴露。
     */
    @Test
    fun `replays the 2026-10-24 real car log lines`() {
        val parser = B561VehicleLogParser
        val merged = LinkedHashMap<String, Any>()

        // 07:24:06.587 —— 车速 19.5 km/h
        merged.merge(parser.parseLine(
            "10-24 07:24:06.587 25891 26013 D Property.service: property:0x11600207 on change, values=19.51875",
        )!!)
        // 07:24:06.618 / .607 —— 电流 55 A（Property.service 与 CmdSmdManager 双路）
        merged.merge(parser.parseLine(
            "10-24 07:24:06.618 25891 26013 D Property.service: property:0x2160f502 on change, values=55.0",
        )!!)
        merged.merge(parser.parseLine(
            "10-24 07:24:06.607   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f502,value=55.000000",
        )!!)
        // 07:24:06.677 —— 电压 357.6 V
        merged.merge(parser.parseLine(
            "10-24 07:24:06.677 25891 26013 D Property.service: property:0x2160f503 on change, values=357.6",
        )!!)
        // 07:24:06.636 —— SOC 68%（CarCabinManager，v129 修复的那条路）
        merged.merge(parser.parseLine(
            "10-24 07:24:06.636 26764 26764 D CarCabinManager: getIntProperty 0x2140f636 data=68",
        )!!)
        // 07:24:14.531 —— 四轮胎压 bar
        merged.merge(parser.parseLine(
            "10-24 07:24:14.531   417   479 D CmdSmdManager: ----commFloatVectData----prop=0x11610309,size=4,vec=[2.4714,2.38902,2.41648,2.40275]",
        )!!)
        // 07:24:06.590 —— D 挡（gearValue 8）
        merged.merge(parser.parseLine(
            "10-24 07:24:06.590 13140 13140 I FastControlManager: _getSensorGearPosition === event.getGearData(null).gear: 8",
        )!!)

        assertEquals(19.51875f, (merged[B561VehicleLogParser.KEY_SPEED] as Number).toFloat(), 1e-4f)
        assertEquals(55.0f, (merged[B561VehicleLogParser.KEY_CURRENT] as Number).toFloat(), 1e-3f)
        assertEquals(357.6f, (merged[B561VehicleLogParser.KEY_VOLTAGE] as Number).toFloat(), 1e-3f)
        assertEquals(68, (merged[B561VehicleLogParser.KEY_BATTERY] as Number).toInt())
        assertEquals(8, (merged[B561VehicleLogParser.KEY_GEAR] as Number).toInt())
        val tires = merged[B561VehicleLogParser.KEY_TIRE_PRESSURE] as FloatArray
        assertArrayEquals(floatArrayOf(2.4714f, 2.38902f, 2.41648f, 2.40275f), tires, 1e-4f)

        // 真车功率 = 357.6 V × 55 A ≈ 19.7 kW，与日志里 VEHICLE power=24.3/11.6 同量级 ✓
        val powerKw = B561VehicleProperty.powerKw(357.6f, 55.0f)
        assertNotNull("电压电流都有效时功率不应为 null", powerKw)
        assertTrue("功率应在 15~30 kW（实测 $powerKw）", powerKw!! in 15f..30f)
    }

    /** 非车辆数据行（CarCabinManager 的高频轮询行）不得产出字段。 */
    @Test
    fun `ignores CarCabinManager polling lines without values`() {
        val parser = B561VehicleLogParser
        // 原厂桌面每 2 秒轮询整个车舱属性，这类行没有 data=，必须落空。
        assertNull(parser.parseLine(
            "10-24 07:24:06.627 26764 26764 D CarCabinManager: getProperty, propId: 0x2160f479, area: 0x0, class: class java.lang.Float",
        ))
        assertNull(parser.parseLine(
            "10-24 07:24:06.628 26764 26764 D CarCabinManager: value",
        ))
        assertNull(parser.parseLine(
            "10-24 07:24:06.629 26764 26764 D CarCabinManager: setProperty, propId: 0x2140f6c4, area: 0x0",
        ))
    }

    private fun MutableMap<String, Any>.merge(fields: Map<String, Any>) {
        putAll(fields)
    }
}
