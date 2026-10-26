package com.shilapi.xcertplay.guidance

import kotlin.math.abs

/**
 * B561: 「路口放大图」的渲染模型。
 *
 * ## 为什么要把路口几何从车道数据里拆出来
 *
 * 改之前，仪表上那张"路口放大图"只有**一个**数据入口：
 * [ClusterGuidanceFeed] 发布 [ClusterLaneGuidance]（来自 `0x5204`），
 * 路口渲染器收到 null 就整块隐藏。
 *
 * 于是出现一个纯粹的人为故障：`0x5202` 的 `0x09/0x0A/0x0B`
 * （路口类型 / 各岔路方位角 / 出口偏角）**一直解析得好好的**，
 * 却因为 `0x5204` 那边解析不出来（它的字节布局是推出来的，没有实车抓包，
 * 见 [ClusterLaneGuidance.parseLanes] 的说明），整张卡永远不出现。
 * 驾驶员看到的就是"路口放大图画不出来"。
 *
 * 事实上这是两个**独立**的数据源：
 *
 * | 图上的部分 | 来源 | 可靠度 |
 * |---|---|---|
 * | 路口岔路 / 出口编号 | `0x5202` 的 `0x09/0x0A/0x0B` | 高（与 0x5201 同构的 TLV，一直在解） |
 * | 车道带（该走哪条道） | `0x5204` | 低（字节布局是推的，没有实车抓包定论） |
 *
 * 把它们绑在一起，等于让**可靠的那一半**被**不可靠的那一半**绑架。
 * 本类就是把路口几何单独取出来后的模型。
 *
 * ## 角度约定
 *
 * `0°` = 正前方，`+90°` = 右，`-90°` = 左（以车头前进方向为基准）——
 * 与 [ClusterLaneGuidance] 顶部注释一致，渲染层不用管数据来自哪。
 */
data class ClusterJunctionGuidance(
    /** Apple maneuver 类型（0x5202 的 0x03）。 */
    val maneuverType: Int,
    /** 0x5202 的 0x08 DrivingSide；1 = 左舵/左行。 */
    val drivingSide: Int,
    /** 距下一个 maneuver 的距离（米），给"还有多远到路口"用。 */
    val distanceMeters: Int,
    /** 路口类型（0x5202 的 0x09）。0 = 普通路口。 */
    val junctionType: Int = 0,
    /** `0x5202` 的 `0x0A`：路口各岔路方位角。可能为空（普通路口 iOS 常不发）。 */
    val junctionAngles: List<Int> = emptyList(),
    /** `0x5202` 的 `0x0B`：出口/匝道额外偏角。 */
    val junctionExitAngle: Int? = null,
    /** `0x5204` 车道数据；解析失败时为 null —— 此时只画路口，不画车道带。 */
    val lanes: ClusterLaneGuidance? = null,
) {
    /** 归一化后的 maneuver 码（[ManeuverCodes] 的词表）。 */
    val gaode: Int
        get() = ManeuverCodes.gaode(maneuverType, drivingSide)

    /**
     * 有没有可画的东西。
     *
     * 路口几何或车道数据任一存在即可画 —— 这正是改之前缺的那一条：
     * 只有 `0x5204` 解出来才画，`0x5202` 解出来了也不算。
     */
    val drawable: Boolean
        get() = branches.isNotEmpty() || lanes?.drawable == true

    /** 角度必须是有限度数，否则画出来是乱线。 */
    val sane: Boolean
        get() = junctionAngles.all { it in -180..180 } && (lanes == null || lanes.sane)

    /**
     * 路口岔路的方位角。
     *
     * 优先用 iOS 明确给的 [junctionAngles]（`0x5202` 的 `0x0A`）；
     * 一个都没有时，由 maneuver 类型**推导**一条 —— 即将左转的路口，
     * 推荐岔路当然是 -90°。这是 maneuver 本身蕴含的方向，不是编造的车道信息。
     *
     * 推导不出来的 maneuver（环岛、目的地）返回空列表，整块不画（宁缺勿画）。
     */
    val branches: List<Int>
        get() = junctionAngles.filter { it in -180..180 }
            .ifEmpty { listOfNotNull(angleForManeuver(gaode)) }

    /** 推荐岔路：离正前方最近的那条（驾驶员看的永远是即将拐的那条）。 */
    val recommended: Int
        get() = branches.minByOrNull { abs(it) } ?: 0

    /** 路口类型的可读标签。 */
    val junctionKind: ClusterLaneGuidance.JunctionKind
        get() = ClusterLaneGuidance.JunctionKind.of(junctionType)

    /** iOS 是否真的发了路口几何（区别于"由 maneuver 推导"的那条）。 */
    val hasRealJunctionGeometry: Boolean
        get() = junctionAngles.isNotEmpty()

    companion object {
        /**
         * maneuver → 推荐岔路方位角。
         *
         * 只用**确定**的映射：左/右/小左/小右/急左/急右/掉头/直行。
         * 环岛（进出口方向取决于第几个出口）、目的地这种没有单一方向的，
         * 一律返回 null —— 画一条猜的线比不画更糟。
         *
         * 输入是 [ManeuverCodes.gaode] 归一化后的码，不是 Apple 原始类型。
         */
        fun angleForManeuver(gaode: Int): Int? = when (gaode) {
            ManeuverCodes.LEFT -> -90
            ManeuverCodes.SHARP_LEFT -> -135
            ManeuverCodes.SLIGHT_LEFT -> -45
            ManeuverCodes.RIGHT -> 90
            ManeuverCodes.SHARP_RIGHT -> 135
            ManeuverCodes.SLIGHT_RIGHT -> 45
            ManeuverCodes.U_TURN_LEFT -> -180
            ManeuverCodes.U_TURN_RIGHT -> 180
            ManeuverCodes.STRAIGHT -> 0
            else -> null
        }
    }
}
