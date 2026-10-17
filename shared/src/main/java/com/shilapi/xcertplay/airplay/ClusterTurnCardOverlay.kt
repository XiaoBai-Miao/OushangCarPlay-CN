package com.shilapi.xcertplay.airplay

/**
 * Where DiPlay draws the instruction card on top of the dashboard map.
 *
 * Placement is a percent of the full panel so Left/Right can reach the cluster edges.
 *
 * ## v69:宽/高的百分比基数统一改成**屏幕高度**
 *
 * 原来 [card] / [junctionCard] / [mediaCard] 的 width 都按可见区**宽度**算，
 * height 按可见区**高度**算 —— 两种量纲混在一张卡上。仪表屏是 1920×720
 * 这种超宽比例，于是"大小 55%"算出1000 多 px 宽，一张卡横跨半个屏，
 * 位置滑块怎么调都躲不开转向卡。现在三种几何统一按 panelHeight 取基数，
 * 滑块值直接对应"占屏高的百分之几"，换任何屏尺寸都稳定。
 *
 * Size still follows the measured centre navi window so Large
 * does not cover half the cluster.
 */
object ClusterTurnCardOverlay {
    data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * B561: 位置滑条改为 **1% 步进**（原 2%，与上游 0.2.15 对齐）。
     *
     * 1920 px 面板上 2% ≈ 38 px 一档。反复调三张卡错开摆放时，
     * 常常就是"差那么一点没错开"，被迫在两个都不满意的位置之间取舍。
     */
    const val STEP_PERCENT = 1
    const val DEFAULT_X_PERCENT = 76
    const val DEFAULT_Y_PERCENT = 30
    /**
     * v109: 水平位置值域放宽到全屏 **0..100**（原 8..92）。
     *
     * 用户实测"卡片位置被限制"——想贴左/右边缘摆放做不到。位置是**中心坐标**
     * （[card]：`left = panelWidth * x / 100 - width / 2`），且 left/top 都有
     * `coerceIn(0, panel - size)` 钳制 —— 0 与 100 只是把卡片中心移到屏幕边缘、
     * 卡片本身仍完整留在屏内（ClusterTurnCardOverlayTest.percentStepsStayOnThePanel
     * 逐值域验证过该不变式）。
     */
    val xPercents = (0..100).toList()
    val smallWindowXPercents = (10..90 step 1).toList()
    val smallWindowYPercents = (15..70 step 1).toList()
    /** Opacity choices for the slider: 20..100 in steps of 5, default 85. */
    val opacityPercents = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)
    const val DEFAULT_OPACITY_PERCENT = 85

    /** 同 [xPercents]：v109 垂直值域一并放宽到 0..100（原 12..72）。 */
    val yPercents = (0..100).toList()

    /** Card width as percent of the **panel height**; one slider step = 5 %. */
    val sizePercents = listOf(30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95)
    const val DEFAULT_SIZE_PERCENT = 55

    /**
     * B561: 媒体卡文字大小（百分比）。
     *
     * 与 [sizePercents] 的区别：那个改**整张卡**（背景、封面一起缩放，字随之等比变小），
     * 所以用户想放大歌名时会被迫把整张卡也放大 —— 而仪表屏横向空间有限，
     * 卡一大就把地图挡了。这个只改字号，卡片尺寸与封面都不变。
     *
     * 值域 60..180，步进 10，100 = 上游原始规格。
     */
    val mediaTextScalePercents = listOf(60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160, 180)
    const val DEFAULT_MEDIA_TEXT_SCALE_PERCENT = 100

    fun card(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        sizePercent: Int,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight)
        val size = snap(sizePercent, sizePercents)
        // ★ v69: 基数改成**屏幕高度**，不再用可见区宽度。
        //   仪表屏是 1920×720 这种超宽比例，用宽度当基数时"大小 55%"
        //   算出 1000 多 px 宽 —— 一张卡横跨半个屏，位置滑块怎么调都躲不开。
        //   高度基数让"大小"直接对应"占屏高的百分之几"，跨屏幕尺寸也稳定。
        val width = (panelHeight * size / 100)
            .coerceAtLeast(150)
            .coerceAtMost(window.width)
        val height = (width / 2.64f).toInt()
            .coerceAtLeast(58)
            .coerceAtMost((window.height * 0.55f).toInt())
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    /** B561: 路口放大图的高宽比（高 : 宽）。 */
    private const val JUNCTION_ASPECT = 0.78f

    /**
     * B561: 媒体卡专用的自由尺寸几何（宽与高**各自独立**）。
     *
     * ## 为什么 [card] 不够用
     *
     * [card] 把宽高**绑死在 2.64:1 横幅比**上（`height = width / 2.64f`），
     * 所以「大小」滑块只能等比缩放。仪表屏上用户想要的是：
     * - 竖排放（封面在上、歌名在下）→ 需要**更高**
     * - 横排放（一行横幅）→ 现在的比例就够
     * 两者不能用一个 sizePercent 表达，所以给媒体卡单独一套宽/高。
     *
     * @param orientation [CardOrientation.HORIZONTAL] 沿用 2.64:1（封面在左、文字在右的横幅）；
     *   [CardOrientation.VERTICAL] 用 [VERTICAL_ASPECT]（1:1，封面在上、文字在下）。
     */
    fun mediaCard(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        widthPercent: Int,
        heightPercent: Int,
        orientation: CardOrientation,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight)
        // ★ B561 v67: 只有 FREEFORM 才按宽/高两个**独立**滑块取值。
        //   横幅与正方两种固定版式下，调用方传的是通用的 sizePercent ——
        //   若也拿去 snap 到 mediaWidthPercents/mediaHeightPercents，
        //   滑块值会被悄悄改掉（size 30 会被吸到 30，但 size 95 会变90），
        //   表现为"滑块拖到底没反应"。
        val wPct = if (orientation == CardOrientation.FREEFORM) snap(widthPercent, mediaWidthPercents) else widthPercent
        val hPct = if (orientation == CardOrientation.FREEFORM) snap(heightPercent, mediaHeightPercents) else heightPercent
        val width: Int
        val height: Int
        when (orientation) {
            // 自由比例：宽高各按自己的百分比，互不干涉。
            // ★ v69: 基数统一改成屏幕高度 —— 宽也按 height 算，
            //   否则 FREEFORM 的"宽"与另两个方向的"宽"不是同一个量纲，
            //   同一个 55% 在横幅下很宽、在自由下就变成了另一个尺寸。
            CardOrientation.FREEFORM -> {
                width = (panelHeight * wPct / 100).coerceAtLeast(120).coerceAtMost(window.width)
                height = (panelHeight * hPct / 100).coerceAtLeast(48).coerceAtMost(window.height)
            }
            // ★ v69: **1:1.35 竖版**（封面在上、文字在下堆叠）。
            //   宽度由高度百分比推出，高 = 宽 × [VERTICAL_ASPECT] ——
            //   用户实测反馈 1:1 正方太扁，封面上下都留不出地方，改回 1:1.35。
            //   上限取 min(可见区宽, 可见区高 /1.35)：竖版不能比可视区还高，
            //   否则仪表上下车辆数据叠层会被盖住。
            CardOrientation.VERTICAL -> {
                val base = (panelHeight * hPct / 100)
                    .coerceAtLeast(90)
                    //可见区高 ÷1.35 得到"卡片宽度上限"（高 =宽 ×1.35）。
                    .coerceAtMost((window.height / VERTICAL_ASPECT).toInt())
                    .coerceAtMost(window.width)
                width = base
                height = (base * VERTICAL_ASPECT).toInt()
            }
            // 横幅：宽由宽度百分比推，高按 2.64:1 横幅比。
            CardOrientation.HORIZONTAL -> {
                width = (panelHeight * wPct / 100).coerceAtLeast(150).coerceAtMost(window.width)
                height = (width / 2.64f).toInt()
                    .coerceAtLeast(58)
                    .coerceAtMost((window.height * 0.62f).toInt())
            }
        }
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    /**
     * B561: **路口放大图**（车道级导航图）的几何。
     *
     * ## 与 [card] 的区别：更方的版式
     *
     * 转向卡是 2.64:1 的横幅 —— 只放得下一个箭头 + 距离 + 路名。
     * 路口放大图要同时装下**上半的路口岔路**与**下半的车道带**，
     * 横幅比例根本放不下，所以给一份更方的高版（高 : 宽 = 0.78）。
     *
     * 高度另受 `window.height` 的 62% 约束 —— 仪表屏上下有车辆数据叠层，
     * 不能让它盖满。
     */
    fun junctionCard(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        sizePercent: Int,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight)
        val size = snap(sizePercent, sizePercents)
        // ★ v69: 基数同样是屏幕高度（见 card() 的说明）。
        val width = (panelHeight * size / 100).coerceAtLeast(180).coerceAtMost(window.width)
        val height = (width * JUNCTION_ASPECT).toInt()
            .coerceAtLeast(140)
            .coerceAtMost((window.height * 0.62f).toInt())
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    /** B561: 媒体卡排放方向。 */
    enum class CardOrientation {
        /**
         * **横幅**：封面在**左**，歌名/歌手在**右**堆叠（最多三行）。
         *
         * 沿用 2.64:1 横幅比 —— 仪表屏横向宽，横向空间留给文字更划算。
         */
        HORIZONTAL,

        /**
         * **竖版**：**1:1.35 竖版**卡片，封面在**上**，歌名/歌手/专辑在**下**堆叠。
         *
         * v67 曾改成 1:1 正方形，v69 按用户实测反馈改回 1:1.35 ——
         * 正方卡片封面上下都留不出空间，三行文字只能挤在半张卡里。
         */
        VERTICAL,

        /**
         * **自由**：宽高完全由两个独立百分比决定，版式按实际宽高**自动切换** ——
         * 宽 ≥ 高的 1.15 倍走横幅（封面在左），否则走竖版（封面在上）。
         */
        FREEFORM,
    }

    /**
     * 媒体卡的宽/高滑块值域（百分比，**步进 1** —— 与位置滑条一致）。
     *
     * ★ v69: 基数改为**屏幕高度**（见类注释），所以值域上限可以放得更宽 ——
     * 宽按屏高算，90% 在 1920×720 上约 650 px，仍在可见区宽度内。
     *
     * B561: 原为步进 5。封面卡要跟转向卡、通话卡挤在同一块仪表上，
     * 5% 一档几乎无法微调。
     */
    val mediaWidthPercents = (20..90).toList()
    val mediaHeightPercents = (15..80).toList()
    const val DEFAULT_MEDIA_WIDTH_PERCENT = 45
    const val DEFAULT_MEDIA_HEIGHT_PERCENT = 55

/**
     * B561 v69: 竖版卡片的高宽比（**高 : 宽** = 1.35）。
     *
     * 走 1.35 而不是 1:1：正方卡片里封面上下都留不出空间，
     * 歌名/歌手/专辑三行只能挤在半张卡里 —— 用户实测反馈后改回竖版。
     * 几何实现见 [mediaCard] 的 VERTICAL 分支。
     */
    private const val VERTICAL_ASPECT = 1.35f

    fun snap(value: Int, choices: List<Int>): Int =
        choices.minByOrNull { kotlin.math.abs(it - value) } ?: choices.first()

    internal fun visibleWindow(panelWidth: Int, panelHeight: Int): CardRect {
        val area = CarPlayClusterDisplay.SAFE_AREA_PERCENT
        val left = panelWidth * area.left / 100
        val top = panelHeight * area.top / 100
        val width = panelWidth * (100 - area.left - area.right) / 100
        val height = panelHeight * (100 - area.top - area.bottom) / 100
        return CardRect(left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}
