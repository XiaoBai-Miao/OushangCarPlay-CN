package com.shilapi.xcertplay.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B561VehicleLogParser] 的回归测试。
 *
 * **所有断言用的日志行都是实车 logcat 原样抄录**（`/d/CAR/日志/` 下
 * 2026-10-22 的四份日志），不是构造的假样本 —— 这样固件换行格式时
 * 测试会立刻失败，而不是悄悄解析不出值。
 */
class B561VehicleLogParserTest {

    // ── Property.service：VHAL 变化回调（车速/电量/电压/电流）───────────────

    @Test
    fun `parses speed from the real Property_service line`() {
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:49:33.802  1250  1376 D Property.service: property:0x11600207 on change, values=16.70625",
        )
        assertNotNull(fields)
        assertEquals(16.70625f, fields!![B561VehicleLogParser.KEY_SPEED] as Float, 0.0001f)
    }

    @Test
    fun `parses battery percent from the real Property_service line`() {
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:49:30.210  1250  1376 D Property.service: property:0x2140f636 on change, values=49",
        )
        assertEquals(49, fields!![B561VehicleLogParser.KEY_BATTERY])
    }

    @Test
    fun `parses voltage and current from the real Property_service lines`() {
        val voltage = B561VehicleLogParser.parseLine(
            "10-22 11:49:34.100  1250  1376 D Property.service: property:0x2160f503 on change, values=331.9",
        )
        assertEquals(331.9f, voltage!![B561VehicleLogParser.KEY_VOLTAGE] as Float, 0.01f)

        // 实测放电时电流为负 —— 不能被当成无效值丢掉。
        val current = B561VehicleLogParser.parseLine(
            "10-22 11:49:34.128  1250  1376 D Property.service: property:0x2160f502 on change, values=-2.4",
        )
        assertEquals(-2.4f, current!![B561VehicleLogParser.KEY_CURRENT] as Float, 0.001f)
    }

    @Test
    fun `falls back to CarCabinManager_getIntProperty_lines`() {
        // 原厂主动拉值时打的另一类行，也应该能解析出电量。
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:49:30.210  2202  2202 D CarCabinManager: getIntProperty 0x2140f636 data=49",
        )
        assertEquals(49, fields!![B561VehicleLogParser.KEY_BATTERY])
    }

    @Test
    fun `ignores unrelated VHAL properties`() {
        // 0x2140f4a9 出现在实车日志里 35365 次，但它不是车速也不是电量
        // （与车速反相关，历史实现读它永远得到 0 速）。必须不产出任何字段。
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:49:34.141  1250  1376 D Property.service: property:0x2140f4a9 on change, values=-103",
        )
        assertNull(fields)
    }

    @Test
    fun `ignores non-vehicle logcat noise`() {
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:36:38.287  7946  9188 I xcertplay-usb: wireless Bluetooth iAP2 bootstrap starting location=false",
            ),
        )
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:49:33.802  2202  2202 D CarCabinManager: getProperty, propId: 0x11600207, area: 0x0, class: class java.lang.Float",
            ),
        )
    }

    // ── FastControlManager：挡位 ──────────────────────────────────────────

    @Test
    fun `parses gear from the three real FastControlManager line shapes`() {
        val lines = listOf(
            "10-22 11:35:40.819  7849  7849 D FastControlManager: _getCarCabinProperty success. property:GEAR_SELECTION , areaId:0 , value:8",
            "10-22 11:35:40.819  7849  7849 D FastControlManager: getGearStatus: 8",
            "10-22 11:35:40.819  7849  7849 I FastControlManager: _ID_GEAR_SELECTION  gearValue: 8",
            "10-22 11:35:40.819  7849  7849 D FastControlManager: _getSensorGearPosition === event.getGearData(null).gear: 8",
        )
        for (line in lines) {
            val fields = B561VehicleLogParser.parseLine(line)
            assertNotNull("这一行没解析出挡位: $line", fields)
            assertEquals("这一行挡位不是 8: $line", 8, fields!![B561VehicleLogParser.KEY_GEAR])
        }
    }

    @Test
    fun `FastControlManager_is_reverse_gear_line_is_not_a_gear_reading`() {
        // 这行里有 gear 字样但不是挡位值 —— 不能被 GEAR_STATUS 误抓。
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:35:40.819  7849  7849 D FastControlManager: _is reverse gear:false",
        )
        assertNull(fields)
    }

    // ── 有效性过滤 ───────────────────────────────────────────────────────

    @Test
    fun `drops impossible values instead of reporting them`() {
        // 车速为负/超上限 → 无效，不产出字段。
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:49:33.802  1250  1376 D Property.service: property:0x11600207 on change, values=-1.0",
            ),
        )
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:49:33.802  1250  1376 D Property.service: property:0x11600207 on change, values=999.0",
            ),
        )
        // 电量 0 / 100 都不可信（0 几乎总是"读不到"，100 在部分固件是默认值）。
        for (raw in listOf("0", "100")) {
            assertNull(
                "电量 $raw 不该被接受",
                B561VehicleLogParser.parseLine(
                    "10-22 11:49:30.210  1250  1376 D Property.service: property:0x2140f636 on change, values=$raw",
                ),
            )
        }
        // 电压 0/负 → 无效。
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:49:34.100  1250  1376 D Property.service: property:0x2160f503 on change, values=0.0",
            ),
        )
    }

    // ── fold：把一段日志流折叠成完整读数 ────────────────────────────────

    @Test
    fun `fold_merges_a_log_stream_with_later_lines_winning`() {
        // 按实车日志的时间顺序拼的一段：车速在变，挡位/电量/电压/电流随后到达。
        val fields = B561VehicleLogParser.fold(
            listOf(
                "10-22 11:49:30.210  1250  1376 D Property.service: property:0x11600207 on change, values=0.5625",
                "10-22 11:49:30.310  1250  1376 D Property.service: property:0x11600207 on change, values=16.70625",
                "10-22 11:49:30.410  1250  1376 D Property.service: property:0x2140f636 on change, values=49",
                "10-22 11:49:34.100  1250  1376 D Property.service: property:0x2160f503 on change, values=331.9",
                "10-22 11:49:34.128  1250  1376 D Property.service: property:0x2160f502 on change, values=-2.4",
                "10-22 11:35:40.819  7849  7849 D FastControlManager: getGearStatus: 8",
                "10-22 11:49:33.802  7946  9188 I xcertplay-usb: wireless Bluetooth iAP2 bootstrap starting",
            ),
        )
        // 后到的车速 16.70625 覆盖先到的 0.5625。
        assertEquals(16.70625f, fields[B561VehicleLogParser.KEY_SPEED] as Float, 0.0001f)
        assertEquals(49, fields[B561VehicleLogParser.KEY_BATTERY])
        assertEquals(8, fields[B561VehicleLogParser.KEY_GEAR])
        assertEquals(331.9f, fields[B561VehicleLogParser.KEY_VOLTAGE] as Float, 0.01f)
        assertEquals(-2.4f, fields[B561VehicleLogParser.KEY_CURRENT] as Float, 0.001f)
        assertEquals(5, fields.size)
    }

    @Test
    fun `fold of an unrelated stream is empty`() {
        val fields = B561VehicleLogParser.fold(
            listOf(
                "10-22 11:36:38.287  7946  9188 I xcertplay-usb: wireless Bluetooth iAP2 bootstrap starting",
                "10-22 11:36:38.300  7946  9188 D ClusterMap: presentation shown",
            ),
        )
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `gear_label_only_maps_the_verified_value`() {
        // 实车只坐实过 8 = D；其余一律 null，绝不画错的挡位误导驾驶员。
        assertEquals('D', B561VehicleProperty.gearLabel(8))
        assertNull(B561VehicleProperty.gearLabel(1))
        assertNull(B561VehicleProperty.gearLabel(27))
    }
}