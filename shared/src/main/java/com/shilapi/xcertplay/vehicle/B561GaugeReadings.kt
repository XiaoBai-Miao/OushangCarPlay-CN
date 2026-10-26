package com.shilapi.xcertplay.vehicle

/**
 * B561 的**仪表卡片数据快照** —— 转速表/功率表/续航/里程/胎压的数据源。
 *
 * ## 为什么要有这个
 *
 * 仪表上的车辆信息**全部由我们自绘**（本类 + [com.shilapi.xcertplay.ClusterGaugeCardView]），
 * 数据来自 VHAL / logcat，**不依赖 iPhone 端是否支持任何 Ultra UI**。
 *
 * ★ v136：原来的「真 CarPlay Ultra 协议」（`vehicleStateProtocol` + RCS/CAF，
 * 让 iPhone 自己画官方仪表）已随 `airplay/ultra/` + `airplay/rcs/` 整体下线 ——
 * 那条路从未在真车上跑通过一次（iPhone 重新配对后不再提该 feature，
 * 我们拒收它明确 SETUP 的 type=130 → 5 秒后 TEARDOWN → 无限重连循环）。
 * 于是本类成了仪表车辆信息的**唯一**来源。
 *
 * ## 字段对照（LIVI 的 `GAUGE_KEYS`，`vehicle_state.rs:1519`）
 *
 * | LIVI | 本类 | 说明 |
 * |---|---|---|
 * | `rpm` | [motorRpm] / [engineRpm] | 转速。**纯电无发动机转速，混动/燃油才有** |
 * | `fuelPct` | [fuelPercent] | 油量 %（燃油版才有） |
 * | `rangeKm` | [rangeKm] | 续航 km |
 * | `odometerKm` | [odometerKm] | 总里程 km |
 * | `tirePressure` | [tirePressureBar] | 四轮胎压 bar |
 * | — | [batteryPercent] | 电量 %（纯电） |
 * | — | [powerKw] | 功率 kW（LIVI 的 `electric_engine.power`） |
 *
 * ## ★ 铁律：读不到就报 null，绝不编造
 *
 * 每个字段都可能缺失（纯电车没有油量、燃油车没有电量、TPMS 未连接没有胎压）。
 * **仪表上宁可留空，也不能画一个假的数字** —— 驾驶员会当真的。
 * 所以 [isEmpty] 为真时整个表都不画，而不是画一圈空表盘。
 */
data class B561GaugeReadings(
    /** 车速 km/h。 */
    val speedKph: Float? = null,
    /** 挡位原始值（8 = D）。 */
    val gear: Int? = null,
    /** 电机转速 rpm（纯电/混动）。 */
    val motorRpm: Float? = null,
    /** 发动机转速 rpm（燃油/混动）。 */
    val engineRpm: Float? = null,
    /** 功率 kW。电压 × 电流 / 1000。 */
    val powerKw: Float? = null,
    /** 电池电量 %（纯电）。 */
    val batteryPercent: Int? = null,
    /** 油量 %（燃油）。 */
    val fuelPercent: Int? = null,
    /** 续航 km。 */
    val rangeKm: Int? = null,
    /** 总里程 km。 */
    val odometerKm: Float? = null,
    /** 四轮胎压 bar，顺序：左前、右前、左后、右后。 */
    val tirePressureBar: FloatArray? = null,
) {

    /**
     * 是否有任何可画的数据。
     *
     * 只有车速也算 —— 仪表至少该显示速度。
     */
    val isEmpty: Boolean
        get() = speedKph == null && motorRpm == null && engineRpm == null &&
            powerKw == null && batteryPercent == null && fuelPercent == null &&
            rangeKm == null && odometerKm == null && tirePressureBar == null

    /**
     * 有没有转速可画。
     *
     * 燃油车只有发动机转速、纯电车只有电机转速、混动两个都有。
     */
    val hasRpm: Boolean
        get() = motorRpm != null || engineRpm != null

    /**
     * 转速表要画的那个值。
     *
     * 优先级：**发动机 > 电机**。
     *
     * 为什么发动机优先：混动车上仪表的转速表（ tach）传统上显示的是**发动机**转速，
     * 电机转速一般不单独显示。纯电车没有发动机，自然落到电机转速。
     */
    val primaryRpm: Float?
        get() = engineRpm ?: motorRpm

    /**
     * 挡位字母；未坐实的挡位值返回 null（不画错的字母）。
     */
    val gearLabel: Char? get() = B561VehicleProperty.gearLabel(gear)

    /**
     * 从 [B561VehicleLogParser.fold] 的结果构造。
     *
     * @param fields 解析器折叠出的字段表。
     * @param engineRpm 发动机转速（若已从其它源取得）。
     */
    constructor(fields: Map<String, Any>, engineRpm: Float? = null) : this(
        speedKph = (fields[B561VehicleLogParser.KEY_SPEED] as? Number)?.toFloat(),
        gear = (fields[B561VehicleLogParser.KEY_GEAR] as? Number)?.toInt(),
        motorRpm = (fields[B561VehicleLogParser.KEY_MOTOR_RPM] as? Number)?.toFloat(),
        engineRpm = engineRpm,
        powerKw = B561VehicleProperty.powerKw(
            (fields[B561VehicleLogParser.KEY_VOLTAGE] as? Number)?.toFloat(),
            (fields[B561VehicleLogParser.KEY_CURRENT] as? Number)?.toFloat(),
        ),
        batteryPercent = (fields[B561VehicleLogParser.KEY_BATTERY] as? Number)?.toInt(),
        fuelPercent = (fields[B561VehicleLogParser.KEY_FUEL_PERCENT] as? Number)?.toInt(),
        rangeKm = (fields[B561VehicleLogParser.KEY_RANGE] as? Number)?.toInt(),
        odometerKm = (fields[B561VehicleLogParser.KEY_ODOMETER] as? Number)?.toFloat(),
        // 胎压是 FloatArray（不是 Number），单独取。
        tirePressureBar = (fields[B561VehicleLogParser.KEY_TIRE_PRESSURE] as? FloatArray)?.copyOf(),
    )

    // data class 的 equals/hashCode 对数组用引用比较，必须手动改。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is B561GaugeReadings) return false
        return speedKph == other.speedKph &&
            gear == other.gear &&
            motorRpm == other.motorRpm &&
            engineRpm == other.engineRpm &&
            powerKw == other.powerKw &&
            batteryPercent == other.batteryPercent &&
            fuelPercent == other.fuelPercent &&
            rangeKm == other.rangeKm &&
            odometerKm == other.odometerKm &&
            tirePressureBar?.toList() == other.tirePressureBar?.toList()
    }

    override fun hashCode(): Int {
        var result = speedKph?.hashCode() ?: 0
        result = 31 * result + (gear ?: 0)
        result = 31 * result + (motorRpm?.hashCode() ?: 0)
        result = 31 * result + (engineRpm?.hashCode() ?: 0)
        result = 31 * result + (powerKw?.hashCode() ?: 0)
        result = 31 * result + (batteryPercent ?: 0)
        result = 31 * result + (fuelPercent ?: 0)
        result = 31 * result + (rangeKm ?: 0)
        result = 31 * result + (odometerKm?.hashCode() ?: 0)
        result = 31 * result + (tirePressureBar?.toList()?.hashCode() ?: 0)
        return result
    }
}
