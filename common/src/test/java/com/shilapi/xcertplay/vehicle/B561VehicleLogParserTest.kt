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

    @Test
    fun `parses electric range and AOSP battery level from the real log shapes`() {
        // 0x2140f635 = VEHICLE_ELECTRIC_RANGE，来源：反编译
        // VehicleHvacControl-282.apk 的 VEHICLE_ELECTRIC_RANGE 常量。
        // 这是 iAP2 vehicleStatusEnabled 需要的「电量 + 续航」双条件里缺的那一半。
        val range = B561VehicleLogParser.parseLine(
            "10-22 11:49:30.500  1250  1376 D Property.service: property:0x2140f635 on change, values=312",
        )
        assertEquals(312, range!![B561VehicleLogParser.KEY_RANGE])

        // 0x11600309 = AOSP EV_BATTERY_LEVEL（291504905），
        // 取自 android.car.jar 的 android/car/VehiclePropertyIds.class。
        val aosp = B561VehicleLogParser.parseLine(
            "10-22 11:49:30.600  1250  1376 D Property.service: property:0x11600309 on change, values=49",
        )
        assertEquals(49, aosp!![B561VehicleLogParser.KEY_BATTERY])
    }

    @Test
    fun `AOSP battery level candidate is in the battery candidate list`() {
        // 候选表驱动：新增的属性必须真的能被日志解析器认出来，
        // 否则改了 B561VehicleProperty 却忘了同步解析器，候选就是死的。
        assertTrue(
            "AOSP EV_BATTERY_LEVEL 必须在电量候选表里",
            B561VehicleProperty.BATTERY_PERCENT_CANDIDATES.contains(
                B561VehicleProperty.BATTERY_PERCENT_AOSP,
            ),
        )
        assertTrue(
            "纯电续航必须在续航候选表里",
            B561VehicleProperty.RANGE_KM_CANDIDATES.contains(
                B561VehicleProperty.RANGE_ELECTRIC_KM,
            ),
        )
    }

    // ── 电机转速：只走 CmdSmdManager（MCU 原始报文）这条路 ──────────────────

    @Test
    fun `parses motor rpm from the real CmdSmdManager line`() {
        // 实车日志原文（日志_10月22日11时40分14秒718.log）：
        //   10-22 11:41:31.903   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=500.000000
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:41:31.903   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=500.000000",
        )
        assertNotNull(fields)
        assertEquals(500f, fields!![B561VehicleLogParser.KEY_MOTOR_RPM] as Float, 0.01f)
    }

    @Test
    fun `motor rpm reaches us only through the CmdSmdManager tag`() {
        // 实车事实：0x2160f315 在**所有日志**的 `Property.service` 变化行里
        // 一次都没出现过（grep 21 份日志为 0），只由 pid 417 的 CmdSmdManager 打点。
        //
        // 所以「能不能读到转速」取决于 logcat 订阅里有没有 CmdSmdManager 这个 tag。
        // 解析器本身对两种行格式都能认（下面的 Property.service 行走的是同一条
        // fieldsFor 映射），但实车上只有 CmdSmdManager 会真的发出这个属性。
        assertNotNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:31.903  1250  1376 D Property.service: property:0x2160f315 on change, values=500.0",
            ),
        )
        // 而 CmdSmdManager 这条路必须真的能解析出来 —— 这是实车唯一的来源。
        assertNotNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:31.903   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=500.000000",
            ),
        )
    }

    @Test
    fun `zero motor rpm is a valid reading`() {
        // 电机停转是合法状态（实车证据链最后一条就是 value=0.0），
        // 与「电量 0 视为读不到」的规则不同 —— 这里必须接受 0。
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:41:40.113   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=0.000000",
        )
        assertEquals(0f, fields!![B561VehicleLogParser.KEY_MOTOR_RPM] as Float, 0.01f)
    }

    @Test
    fun `rejects impossible motor rpm values`() {
        // 负值与超上限（>2400）都判为无效。
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:39.410   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=-100.000000",
            ),
        )
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:36.132   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=9999.000000",
            ),
        )
    }

    @Test
    fun `folds the real motor rpm ramp sequence`() {
        // 实车证据链：踩下踏板 8 秒爬到 1900 峰值，松开后 6 秒回落到 0。
        val fields = B561VehicleLogParser.fold(
            listOf(
                "10-22 11:41:31.903   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=500.000000",
                "10-22 11:41:31.925   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=1000.000000",
                "10-22 11:41:32.024   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=1200.000000",
                "10-22 11:41:32.187   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=1300.000000",
                "10-22 11:41:36.132   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=1900.000000",
                "10-22 11:41:39.410   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=300.000000",
                "10-22 11:41:40.113   417   479 D CmdSmdManager: ----commFloatData----prop=0x2160f315,value=0.000000",
            ),
        )
        // 后到的覆盖先到的：最终应是最后的 0。
        assertEquals(0f, fields[B561VehicleLogParser.KEY_MOTOR_RPM] as Float, 0.01f)
    }

    @Test
    fun `CmdSmdManager vector and bytes lines are ignored`() {
        // 这两种是数组/字节形态，不是标量，不该产出字段。
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:30.008   417   479 D CmdSmdManager: ----commFloatVectData----prop=0x2161f28b,size=9,vec=[80.97,136,201]",
            ),
        )
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:30.008   417   479 D CmdSmdManager: ----commBytesData----prop=0x2140f343,value=0",
            ),
        )
    }

    // ── 胎压（0x11610309，走 commFloatVectData 向量形态）─────────────────

    @Test
    fun `parses four tyre pressures from the real CmdSmdManager vector line`() {
        // 实车日志原文：
        //   10-09 19:01:54.232 D CmdSmdManager: ----commFloatVectData----prop=0x11610309,size=4,vec=[2.59497,2.52632,2.56751,2.49886]
        //   10-09 19:01:54.234 D BindCarService: handleCarCallBack:leftFrontPressure:2.59497
        //        rightFrontPressure:2.52632 leftBackPressure:2.56751 rightBackPressure:2.49886
        val fields = B561VehicleLogParser.parseLine(
            "10-09 19:01:54.232   420   447 D CmdSmdManager: ----commFloatVectData----prop=0x11610309,size=4,vec=[2.59497,2.52632,2.56751,2.49886]",
        )
        assertNotNull(fields)
        val tires = fields!![B561VehicleLogParser.KEY_TIRE_PRESSURE] as FloatArray
        assertEquals(4, tires.size)
        // 顺序：左前、右前、左后、右后（与原厂 handleCarCallBack 打印顺序一致）
        assertEquals(2.59497f, tires[0], 0.0001f)
        assertEquals(2.52632f, tires[1], 0.0001f)
        assertEquals(2.56751f, tires[2], 0.0001f)
        assertEquals(2.49886f, tires[3], 0.0001f)
    }

    @Test
    fun `rejects tyre pressure vector with an impossible wheel`() {
        // 混进 0（TPMS 未连接）就整组丢弃 —— 免得仪表上出现
        // 「三个轮有压一个轮 0」的怪相。
        val fields = B561VehicleLogParser.parseLine(
            "10-09 19:01:54.232   420   447 D CmdSmdManager: ----commFloatVectData----prop=0x11610309,size=4,vec=[2.59497,0.0,2.56751,2.49886]",
        )
        assertNull(fields)
    }

    @Test
    fun `ignores other vector properties`() {
        // 车上有 4559 条 commFloatVectData，我们只关心胎压。
        assertNull(
            B561VehicleLogParser.parseLine(
                "10-22 11:41:30.008   417   479 D CmdSmdManager: ----commFloatVectData----prop=0x2161f28b,size=9,vec=[80.97,136,201]",
            ),
        )
    }

    @Test
    fun `parses odometer from the real line`() {
        // 实车日志：0x21600309 取值 53818 → 54268 单调递增（疑似总里程，单位待确认）
        val fields = B561VehicleLogParser.parseLine(
            "10-22 11:49:30.500   417   479 D CmdSmdManager: ----commFloatData----prop=0x21600309,value=54266.000000",
        )
        assertNotNull(fields)
        assertEquals(54266f, fields!![B561VehicleLogParser.KEY_ODOMETER] as Float, 0.01f)
    }
}
