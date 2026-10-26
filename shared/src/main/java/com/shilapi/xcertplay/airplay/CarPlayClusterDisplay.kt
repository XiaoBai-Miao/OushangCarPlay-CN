package com.shilapi.xcertplay.airplay

/**
 * CarPlay's instrument-cluster screen (stream type 111).
 *
 * The iPhone lists the cluster content it offers in its /info request (`altScreenURLs`); without
 * an initial URL it streams a black frame. The cluster has no input. Apple Maps keeps the car
 * position inside the safe area while the map still fills the panel, so the safe area is set to
 * the part of the panel the cluster leaves uncovered.
 */
object CarPlayClusterDisplay {
    const val MAP_URL = "maps:/car/instrumentcluster/map"

    /**
     * What the dashboard shows: one of the cluster contents the iPhone lists in `altScreenURLs`.
     * On the tested car the iPhone glass turn card needed 0–5 kbit/s against 0.3–4 Mbit/s for the map.
     * MAP_WITH_CUSTOM_CARD asks for the map stream so DiPlay can draw a movable card on top.
     *
     * ★ v136: 原来的 `INSTRUMENTS`（`maps:/car/instrumentcluster`）已删除。
     *
     * 那是 **Apple 官方仪表** —— 要让 iPhone 自己画转速/功率/续航/胎压，
     * 必须走 CarPlay Ultra 的车辆状态协议（`vehicleStateProtocol` + RCS/CAF）。
     * 那条协议栈（`airplay/ultra/` + `airplay/rcs/`，共 19 个类）在 v136
     * 整体删除：它从来没有在真车上跑通过一次（iPhone 重新配对后不再提该
     * feature → 我们拒收它明确 SETUP 的 type=130 → 5 秒后 TEARDOWN →
     * 无限重连循环），而且它会独占 `type=100` 数据流与一条 RCS 通道，
     * 与 iAP2 隧道抢路。
     *
     * 现在仪表上的车辆信息**全部由我们自绘**（[com.shilapi.xcertplay.ClusterGaugeCardView]
     * + 车速表 + 转向/路口/车道/媒体/通话卡），不依赖 iPhone 端能力。
     */
    enum class Content(val url: String) {
        MAP(MAP_URL),
        TURN_CARD("maps:/car/instrumentcluster/instructioncard"),
        MAP_WITH_CUSTOM_CARD(MAP_URL),
    }

    /**
     * ★ v126: 设置页的仪表内容选项，**顺序与 [Content.entries] 严格一致**。
     *
     * 之前设置页手写了一个 4 项的文案数组，而 [Content.entries] 也是 4 项但
     * 顺序不同（枚举是 MAP / TURN_CARD / INSTRUMENTS / MAP_WITH_CUSTOM_CARD），
     * 于是按索引取值时整体错位一格：选「地图和官方转向卡」实际存成
     * INSTRUMENTS，选「地图和自定义转向卡」实际存成 MAP_WITH_CUSTOM_CARD。
     *
     * 现在标签与枚举项**成对**给出，索引不可能再错位。
     *
     * @param labels 每个枚举项的显示文案，**必须**覆盖全部枚举项。
     */
    data class ContentOption(val content: Content, val label: String)

    fun options(labels: Map<Content, String>): List<ContentOption> =
        Content.entries.map { content ->
            ContentOption(
                content,
                labels[content] ?: error("仪表内容选项缺少 ${content.name} 的文案"),
            )
        }

    fun usesCustomTurnCard(content: Content): Boolean = content == Content.MAP_WITH_CUSTOM_CARD

    fun usesOfficialTurnCard(content: Content): Boolean = content == Content.TURN_CARD

    /**
     * Where the car marker goes, as percent of the panel (left, top, right, bottom). Measured on a
     * DiLink 5.0 cluster with a calibration grid. In "Full screen navi" BYD draws a status row,
     * turn/ADAS icons, the ADAS lane view (from x 68 %), speed and power readouts and a bottom band
     * over the map; "Small screen navi" crops the panel to a centre window of about x 31–68 %,
     * y 16–89 %. This centre area is clear in both modes, so no per-mode setting is needed.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 16, bottom = 25, left = 35, right = 36)

    /** Balanced margins for virtual full-screen/16:9 streams (e.g. desktop cards/widgets). */
    val VIRTUAL_SAFE_AREA_PERCENT = AirPlayInsets(top = 8, bottom = 8, left = 8, right = 8)

    /** The driver can move the marker from that centre, in steps of 10 % of the panel. */
    const val MARKER_STEP_PERCENT = 10
    val horizontalSteps = -4..4 // negative = left
    val verticalSteps = -3..3 // negative = up

    /** Marker placement on a 1 % grid, used by the full-screen and small-window positions. */
    val markerXPercents = (5..95 step 1).toList()
    val markerYPercents = (5..95 step 1).toList()

    /** Where the car marker lands, in percent of the panel (x from the left, y from the top). */
    fun markerPercent(
        horizontalStep: Int,
        verticalStep: Int,
        area: AirPlayInsets = SAFE_AREA_PERCENT,
    ): Pair<Double, Double> {
        val x = (area.left + 100 - area.right) / 2.0 + horizontalStep.coerceIn(horizontalSteps) * MARKER_STEP_PERCENT
        val y = (area.top + 100 - area.bottom) / 2.0 + verticalStep.coerceIn(verticalSteps) * MARKER_STEP_PERCENT
        return x.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN) to y.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN)
    }

    private const val MARKER_MARGIN = 5.0

    /**
     * Stream size in percent of the panel. The cluster scales the stream up, so a smaller stream
     * gives a larger map: 83 % is clearly larger and still sharp.
     *
     * Values above 100 ask for a larger stream than the panel: the iPhone renders more map
     * area and the cluster scales it down, so features are smaller and finer — "smaller"
     * in the settings means more content, not a magnified view.
     */
    const val STREAM_SCALE_PERCENT = 83
    val scalePresets = listOf(100, STREAM_SCALE_PERCENT, 67, 125)

    private const val WIDTH_PHYSICAL_MM = 292 // a 12.3-inch 8:3 cluster panel; Apple Maps ignores it here
    private const val FPS = 30

    fun config(
        widthPixels: Int,
        heightPixels: Int,
        scalePercent: Int = STREAM_SCALE_PERCENT,
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
        content: Content = Content.MAP,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
        baseSafeArea: AirPlayInsets = SAFE_AREA_PERCENT,
    ): AirPlayDisplayConfig {
        // Height rounds to a multiple of 8 and width follows it, so the panel's aspect is kept
        // (83 % of 1920x720 gives exactly 1600x600). The cluster scales the stream to the panel.
        val scale = scalePercent.coerceIn(25, 150)
        val height = roundTo8(heightPixels * scale / 100.0)
        val width = roundTo8(height * widthPixels.toDouble() / heightPixels)
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = WIDTH_PHYSICAL_MM,
            heightPhysicalMm = Math.round(WIDTH_PHYSICAL_MM * height.toDouble() / width).toInt(),
            fps = FPS,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = content.url,
            safeArea = safeArea(width, height, horizontalStep, verticalStep, markerXPercent, markerYPercent, baseSafeArea),
            safeAreaDrawOutside = true,
        )
    }

    // The safe area keeps its measured size around the marker and shrinks only where the marker
    // comes close to a panel edge, so the marker always sits at its centre.
    private fun safeArea(
        width: Int,
        height: Int,
        horizontalStep: Int,
        verticalStep: Int,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
        baseSafeArea: AirPlayInsets = SAFE_AREA_PERCENT,
    ): AirPlayInsets {
        val area = baseSafeArea
        val (x, y) = if (markerXPercent != null && markerYPercent != null) {
            markerXPercent.coerceIn(5, 95).toDouble() to markerYPercent.coerceIn(5, 95).toDouble()
        } else {
            markerPercent(horizontalStep, verticalStep, area)
        }
        val halfWidth = minOf((100 - area.left - area.right) / 2.0, x, 100 - x)
        val halfHeight = minOf((100 - area.top - area.bottom) / 2.0, y, 100 - y)
        return AirPlayInsets(
            top = (height * (y - halfHeight) / 100).toInt(),
            bottom = (height * (100 - y - halfHeight) / 100).toInt(),
            left = (width * (x - halfWidth) / 100).toInt(),
            right = (width * (100 - x - halfWidth) / 100).toInt(),
        )
    }

    private fun roundTo8(value: Double): Int = (Math.round(value / 8) * 8).toInt().coerceAtLeast(8)
}
