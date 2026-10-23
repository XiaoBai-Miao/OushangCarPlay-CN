package com.shilapi.xcertplay.vehicle

/**
 * 长安欧尚 B561 的 VHAL 车辆属性定义与换算。
 *
 * ## 属性 ID 的来源
 *
 * 全部来自**实车 logcat**（`/d/CAR/日志/` 下 21 份日志），不是猜的：
 * 原厂仪表 `Mointerservice` 与原厂桌面 `NewEnergyDeskView2CtrManager`
 * 都在读同一批属性，属性 ID 与取值都印在 `CarCabinManager: getProperty` /
 * `getIntProperty` / `Property.service: property:... on change` 三类行里。
 *
 * ## ★ 三个被历史实现搞错的属性（v117 修正）
 *
 * | 属性 | 历史实现 | 实车事实 |
 * |---|---|---|
 * | 车速 | `0x2140f4a9` int，单位 0.01 km/h | **`0x11600207` Float，单位 1 km/h** |
 * | 挡位 | `0x2140f477`，映射 21..27 → P/R/N/D | `0x2140f477` **不是挡位**：静止恒 27、55 km/h 时 19，**与车速反相关**；真实挡位见 [GEAR_SELECTION] |
 * | 电量 | `0x2140f47b/0x2140f480/0x2140f4a0/0x2140f486` 候选 | 真实电量是 **`0x2140f636`**（miao-uni-z 观测到，实测 34..80 %） |
 *
 * `0x2140f4a9` 在实车日志里全程 `data=0` —— 历史实现读它永远得到 0 速，
 * 所以「仪表车速」在 v46-v75 期间从来没真正工作过。
 *
 * ## 单位
 *
 * - 车速：km/h（原始 Float 已是 km/h，无需缩放）。
 *   佐证：日志里同一属性值 `0.5625` 与原厂仪表打印的 `speed Value() = 0.5625`
 *   逐条相等，而原厂另有「车速大于20km/h，D挡」的中文判定行。
 * - 电量：% 0..100（Int）。
 * - 电压：V（Float），实测 372.5 —— 高压电池组电压量级。
 * - 电流：A（Float），实测 0.0..1.4。
 * - 功率：kW = V × A / 1000。
 */
object B561VehicleProperty {

    /** 车速，`PERF_VEHICLE_SPEED`，Float，单位 km/h。 */
    const val SPEED = 0x11600207

    /**
     * 电池电量百分比，Int 0..100。
     *
     * 来自 miao-uni-z 的观测（它 grep logcat 里的 `0x2140f636`），
     * 实车日志坐实：`getIntProperty 0x2140f636 data=80`，
     * 且同刻原厂桌面打印「电量=80」。
     */
    const val BATTERY_PERCENT = 0x2140f636

    /** 高压电池组电压，Float，单位 V。 */
    const val VOLTAGE = 0x2160f503

    /** 电流，Float，单位 A。 */
    const val CURRENT = 0x2160f502

    /**
     * **驱动电机转速**，Float，单位 rpm。
     *
     * ★ 厂商自定义属性（AOSP 无对应 `ELECTRIC_MOTOR_RPM`；
     * AOSP 的 `ENGINE_RPM = 0x11600305` 是发动机转速，电动车用不上，
     * 且实车日志里 `0x11600305` **一次都没出现**）。
     *
     * ## 证据链（实车 logcat，`日志_10月22日11时40分14秒718.log`）
     *
     * ```
     * 11:41:31.903 CmdSmdManager: ----commFloatData----prop=0x2160f315,value=500.0
     * 11:41:31.925 ... value=1000.0
     * 11:41:32.024 ... value=1200.0
     * 11:41:32.187 ... value=1300.0
     * 11:41:33.147 ... value=1400.0
     * 11:41:33.392 ... value=1500.0
     * 11:41:33.697 ... value=1600.0
     * 11:41:34.495 ... value=1700.0
     * 11:41:35.391 ... value=1800.0
     * 11:41:36.132 ... value=1900.0   ← 峰值
     * 11:41:38.917 ... value=1800.0
     * 11:41:39.008 ... value=1600.0
     * 11:41:39.107 ... value=1300.0
     * 11:41:39.251 ... value=900.0
     * 11:41:39.308 ... value=600.0
     * 11:41:39.410 ... value=300.0
     * 11:41:39.603 ... value=200.0
     * 11:41:40.113 ... value=0.0      ← 停转
     * ```
     *
     * **踏板踩下 → 转速 8 秒内爬到 1900 峰值 → 松开 → 6 秒内回落到 0**，
     * 是电机控制器扭矩爬升/回落的教科书形态；量程 0..1900 rpm 也是电机转速的典型值。
     * 同期车速 `0x11600207` 是 134→135.5 km/h（高速巡航），
     * 说明这是**电机实际转速**而非其它量。
     *
     * ★ **它不走 `Property.service`**，只由 `CmdSmdManager`（pid 417，MCU 原始报文）
     * 打点，所以 [B561VehicleLogParser] 必须额外解析 `commFloatData` 行、
     * 且 logcat 订阅里必须加上 `CmdSmdManager` 这个 tag，否则抓不到。
     */
    const val MOTOR_RPM = 0x2160f315

    /**
     * 电机转速上限（rpm），用于剔除无效值。
     *
     * 实车观测峰值 1900；留 20% 余量取 2400。
     * 超过上限的多半是别的量或脏数据，宁可丢弃也不要画错的转速。
     */
    const val MAX_MOTOR_RPM = 2400f

    /**
     * 挡位，`ID_GEAR_SELECTION`，Int。
     *
     * ★ 证据链（同一毫秒内闭合）：
     * ```
     * 19:01:49.694  CarCabinManager: getProperty, propId: 0x11400400, area: 0x0, class: Integer
     * 19:01:49.696  CarCabinManager: getIntProperty 0x11400400 data=8
     * 19:01:49.696  BindCarService: 车速大于20km/h ，D挡
     * ```
     * 另有独立佐证：`DvrCanModel: ID_GEAR_SELECTION signal:289408000, value:8`
     * —— 而 `289408000 == 0x11400400`，与 CarCabinManager 读的是同一个属性。
     * 值为 8 时原厂明确判定为 **D 挡**。
     */
    const val GEAR_SELECTION = 0x11400400

    /**
     * 电量候选列表。
     *
     * `0x2140f636` 是实车实测值；其余三个是历史实现留下的候选，
     * 在别的固件变体上可能是主属性。逐个试，取第一个有值的。
     *
     * ★ v120 新增首项之外的 [BATTERY_PERCENT_AOSP]：
     * AOSP 权威定义 `android.car.VehiclePropertyIds.EV_BATTERY_LEVEL = 0x11600309`
     * （291504905），是**跨厂商标准**的电池电量百分比属性。
     * 厂商扩展 `0x2140f636` 只在这台车上有效，标准属性则可能在别的固件/车型上
     * 才是主属性 —— 两者互为备份，逐个试取第一个有值的即可。
     */
    val BATTERY_PERCENT_CANDIDATES = intArrayOf(
        BATTERY_PERCENT,
        BATTERY_PERCENT_AOSP,
        0x2140f47b,
        0x2140f480,
        0x2140f4a0,
        0x2140f486,
    )

    /**
     * AOSP 标准电池电量百分比，`android.car.VehiclePropertyIds.EV_BATTERY_LEVEL`。
     *
     * 值 = 291504905 = `0x11600309`，取自
     * `android.car.jar` → `android/car/VehiclePropertyIds.class`（javap 实测）。
     *
     * ★ 注意与反编译 APK 里的 `TIRE_PRESSURE = 0x11610309` **不是同一个值**
     * （多一位，厂商自定义区，AOSP 无定义），别被那个命名误导。
     */
    const val BATTERY_PERCENT_AOSP = 0x11600309

    /**
     * **四轮胎压**，Float 数组（长度 4），单位 **bar**。
     *
     * 顺序：**左前、右前、左后、右后**（原厂 `BindCarService.handleCarCallBack`
     * 的打印顺序：`leftFrontPressure, rightFrontPressure, leftBackPressure,
     * rightBackPressure`，逐条与下面的实测值对应）。
     *
     * ★ 决定性证据（实车 logcat）：
     * ```
     * 19:01:54.232 D Property.service: property:0x11610309 on change,
     *      values=[2.59497, 2.52632, 2.56751, 2.49886]
     * 19:01:54.234 D BindCarService: ID_TIRE_PRESSURE[2.59497, 2.52632, 2.56751, 2.49886]
     * 19:01:54.234 D BindCarService: handleCarCallBack:leftFrontPressure:2.59497
     *      rightFrontPressure:2.52632 leftBackPressure:2.56751 rightBackPressure:2.49886
     * ```
     * 原厂自己把这个属性的名字就叫 `ID_TIRE_PRESSURE` / `mPropertyId=TIRE_PRESSURE`，
     * 而且**同时**给出了一个语义完全冲突的 `mPropertyId=TIRE_PRESSURE_SIGNAL`
     * （值恒为 0，是"胎压告警信号"不是胎压值）。别搞混。
     *
     * ★ v121 我把它误判成"厂商自定义、无定义"——AOSP 里确实没有
     * `0x11610309` 这个值（多一位，在厂商区），但**它有明确的厂商语义**，
     * 就是四轮胎压。反编译 APK 把它命名成 `TIRE_PRESSURE` 其实是**对的**。
     *
     * 走 `commFloatVectData`（size=4）上报，所以 [B561VehicleLogParser]
     * 需要额外解析向量形态。
     */
    const val TIRE_PRESSURE = 0x11610309

    /** 胎压数组长度（四轮）。 */
    const val TIRE_PRESSURE_COUNT = 4

    /**
     * 胎压有效下限（bar）。低于此值视为无效/未上报。
     *
     * 实测值域 2.38..2.60 bar（约 238..260 kPa，正常胎压），
     * 另有 0（未连接 TPMS 时）。下限取 1.0 bar —— 低于此基本是传感器缺失。
     */
    const val MIN_TIRE_PRESSURE_BAR = 1.0f

    /**
     * 胎压有效上限（bar）。高于此值视为脏数据。
     *
     * 量产车胎压上限约 3.5 bar（≈350 kPa），留余量取 5.0。
     */
    const val MAX_TIRE_PRESSURE_BAR = 5.0f

    /**
     * 总里程（km），Float。
     *
     * 实车日志里取值 53818 → 54268 **单调递增、每次恰好 +1**，
     * 且是唯一超过 10000 的连续量 —— 量级与单位都指向总里程。
     *
     * AOSP 标准是 `PERF_ODOMETER = 0x11600204`，但这台车用的是厂商扩展
     * `0x21600309`。**单位待实车确认**（也可能是 0.1 km 精度）。
     */
    const val ODOMETER = 0x21600309

    /**
     * 油量百分比，Int 0..100。
     *
     * B561/UNI-Z 有燃油与混动两种动力形式，燃油版需要油量。
     * 候选 `0x2140f629`（原厂桌面主动读，实测恒为 0 —— 纯电车上没油），
     * `0x2140f633` 是反编译坐实的燃油续航（km），`0x2140f667` 是燃油百分比。
     * **读不到就报 null**，绝不拿电量冒充油量。
     */
    val FUEL_PERCENT_CANDIDATES = intArrayOf(
        0x2140f667,
        0x2140f629,
    )

    /**
     * 续航（km）候选列表。
     *
     * ★ v120 新增首选 [RANGE_ELECTRIC_KM]（`0x2140f635`）：这个值不是猜的，
     * 来自反编译 `VehicleHvacControl-282.apk`（UNIZ车机控制）的硬编码常量：
     * ```smali
     * .field private static final VEHICLE_ELECTRIC_RANGE:I = 0x2140f635
     * .field private static final VEHICLE_FUEL_RANGE:I = 0x2140f633
     * ```
     * 那个APK 是实车在用的第三方车机控制，属性 ID 与仪表同源，可信度高。
     * 之前这里只有历史候选，是因为**实车 logcat 里没观测到 0x2140f635 上报**
     * —— 纯电续航可能只在仪表/桌面刷新时才被拉取；那是 logcat 源的固有盲区。
     *
     * 其余四个是历史实现留下的候选，在别的固件变体上可能是主属性。
     * **读不到就报 null**，绝不拿电量反推续航充数
     * —— iAP2 的 `vehicleStatusEnabled` 需要电量+续航双条件，
     * 报假续航会让Apple 地图给出错误的充电站推荐。
     */
    val RANGE_KM_CANDIDATES = intArrayOf(
        RANGE_ELECTRIC_KM,
        RANGE_FUEL_KM,
        0x2140f47c,
        0x2140f481,
        0x2140f4a1,
        0x2140f487,
    )

    /**
     * 纯电续航（km），`VEHICLE_ELECTRIC_RANGE`。
     *
     * 来源：反编译 `VehicleHvacControl-282.apk` 的
     * `HvacController.VEHICLE_ELECTRIC_RANGE = 0x2140f635`。
     */
    const val RANGE_ELECTRIC_KM = 0x2140f635

    /**
     * 燃油续航（km），`VEHICLE_FUEL_RANGE`。
     *
     * 来源：同一常量表。B561 是纯电，但保留作为对照——
     * 若实车上 `0x2140f635` 恒为 0 而它有值，说明这台车的续航走的不是纯电属性。
     */
    const val RANGE_FUEL_KM = 0x2140f633

    /** 车速上限（km/h），用于剔除 VHAL 的无效值（熄火/倒车时可能给 −1）。 */
    const val MAX_SPEED_KPH = 300f

    /**
     * 挡位原始值 → 字母。
     *
     * AOSP `android.car.VehicleGear` 的语义：
     * UNKNOWN=0, NEUTRAL=1, REVERSE=2, FORWARD=3, PARK=4,
     * DRIVE=5, MANUAL=6, SELECTOR=7, SEMI_AUTO=8。
     *
     * ★ B561 实车观测到的 `ID_GEAR_SELECTION=8` 配「D挡」，与 AOSP 的
     * SEMI_AUTO=8 并不一致 —— 说明 B561 用的是**厂商自己的挡位编码**，
     * 不是 AOSP 枚举。历史实现写的 21..27 → P/R/N/D 已被实车数据否证
     * （`0x2140f477` 静止时恒为 27，55 km/h 时为 19，与挡位无关）。
     *
     * 所以这里**只认实车坐实过的 8 = D**，其余值一律 null ——
     * 宁可少画一个字母，也不要画一个错的挡位误导驾驶员。
     * 待实车补齐 P/R/N 的观测值后再逐个添加。
     */
    fun gearLabel(raw: Int?): Char? = when (raw) {
        8 -> 'D'
        else -> null
    }

    /**
     * 电压电流 → 功率（kW）。
     *
     * V × A = W，除以 1000 得 kW。任一缺失或非正数时返回 null。
     */
    fun powerKw(voltage: Float?, current: Float?): Float? {
        if (voltage == null || current == null) return null
        if (voltage <= 0f || current < 0f) return null
        return voltage * current / 1000f
    }

    /**
     * 原始车速 → 有效车速（km/h）。
     *
     * 负值与超上限的值都判为无效（返回 null），理由同 [gearLabel]：
     * 报一个假的 0 会让驾驶员以为车速传感器坏了，比空着更糟。
     */
    fun speedKph(raw: Float?): Float? {
        if (raw == null) return null
        if (raw < 0f || raw > MAX_SPEED_KPH) return null
        return raw
    }

    /**
     * 原始电量 → 有效电量百分比。
     *
     * 只接受 1..99 —— 0 几乎总是「读不到」而不是「真的没电」，
     * 而 100 在部分固件上是默认值，同样不可信。
     */
    fun batteryPercent(raw: Int?): Int? {
        if (raw == null) return null
        if (raw !in 1..99) return null
        return raw
    }

    /**
     * 原始电机转速 → 有效转速（rpm）。
     *
     * 负值与超上限的值都判为无效（返回 null），理由同 [speedKph]：
     * 报一个假的 0 会让驾驶员以为电机坏了，比空白更糟。
     *
     * 0 转速是**合法值**（电机停转，见证据链末尾那条 `value=0.0`），
     * 所以这里**允许 0** —— 与电量那个「0 视为读不到」的规则不同。
     */
    fun motorRpm(raw: Float?): Float? {
        if (raw == null) return null
        if (raw < 0f || raw > MAX_MOTOR_RPM) return null
        return raw
    }
}
