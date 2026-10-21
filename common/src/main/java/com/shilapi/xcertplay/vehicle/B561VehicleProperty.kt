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
     */
    val BATTERY_PERCENT_CANDIDATES = intArrayOf(
        BATTERY_PERCENT,
        0x2140f47b,
        0x2140f480,
        0x2140f4a0,
        0x2140f486,
    )

    /**
     * 续航（km）候选列表。
     *
     * 实车日志里没有观测到直接的续航属性 —— 原厂桌面的「续航」是算出来的
     * （电量百分比 × 满电续航）。所以这里只保留历史候选，
     * **读不到就报 null**，绝不拿电量反推续航充数。
     */
    val RANGE_KM_CANDIDATES = intArrayOf(
        0x2140f47c,
        0x2140f481,
        0x2140f4a1,
        0x2140f487,
    )

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
}
