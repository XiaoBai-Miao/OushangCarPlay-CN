package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay

/**
 * 三种仪表卡片共用的对外动作。
 *
 * 抽这个接口不是为了优雅，而是为了能一次性把「位置 / 大小 / 透明度 / 昼夜」
 * 下发给三张卡 —— 它们没有共同父类，`listOf(turnCard, mediaCard, junctionCard)`
 * 会被推成 `List<View>`，而这些方法不在 `View` 上，编译不过。
 */
internal interface ClusterTurnCardOverlayHost {
    fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int)
    fun setOpacity(percent: Int)
    fun setNightMode(night: Boolean)
}

/**
 * 设置页里的**仪表卡片位置预览**。
 *
 * ## 为什么需要
 *
 * 卡片位置/大小是靠四个滑块（水平 / 垂直 / 大小 / 透明度）调的，而仪表屏是
 * **HDMI 外接的另一块屏** —— 未连接 iPhone 时那上面什么都没有。于是调滑块时
 * 完全没有反馈：得连上手机、等 iPhone 推流、跑到车机另一块屏上看，才知道改成了
 * 什么样子。实测这就是「调了不知道有没有生效」。
 *
 * 这个 View 把仪表屏按比例缩成一个缩略图，把三张卡按**当前滑块值**画在上面，
 * 拖滑块时立刻重绘 —— 未连接也能看到位置。
 *
 * ## 为什么直接复用卡片 View
 *
 * 预览和实车用的是同一套 `ClusterTurnCardOverlay.card()` 几何，
 * 所以预览里看到的位置/大小与仪表上**逐像素一致**。若预览另画一套算法，
 * 就会出现"预览挺好、上车不对"—— 那种不一致比没有预览更糟。
 */
internal class ClusterCardPreview(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** 仪表屏逻辑尺寸；B561 实测 1920×720，比例 8:3。 */
    private val panelWidth = 1920
    private val panelHeight = 720

    private var night = false

    /** 要预览哪几张卡。未连接的设置页里媒体卡通常没数据，用占位内容示意版式。 */
    private var showTurnCard = true
    private var showMediaCard = true

    /** B561: 路口放大图。默认不画（与实车一致：没车道数据就不显示）。 */
    private var showJunctionCard = false

    /** v110 B561: 通话卡。默认不画（与实车一致：没来电就不显示）。 */
    private var showCallCard = false

    /**
     * v108: 当前选中的卡片 —— 预览框下方的位置滑块调的就是它。
     *
     * 三张卡（转向 / 媒体 / 路口）共用这一个预览框，选中项决定：
     * ① 哪张卡画高亮描边；② 下方滑块读写哪一套持久化键。
     */
    private var selectedCard = "turn"

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = Color.argb(90, 255, 255, 255)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** v108: 选中卡的描边。颜色随昼夜在 [update] 里切换。 */
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(51, 139, 255)
    }

    private val turnCard = ClusterTurnCardView(context)
    private val mediaCard = ClusterMediaCardView(context)
    private val junctionCard = ClusterJunctionCardView(context)
    private val callCard = ClusterCallCardView(context)

    init {
        // 预览里的卡片不吃触摸，也不需要真实数据 ——
        // 给一点固定的示意内容，用户才能看出卡片里会放什么。
        turnCard.setGuidance(sampleGuidance())
        mediaCard.setMedia("示例歌曲", "示例歌手", "示例专辑", true)
        // B561: 路口放大图给一组示意车道（3 车道、中间那条推荐），
        //   这样设置页里能看出它长什么样、以及该摆在哪。
        junctionCard.setLane(sampleLanes())
        // v110: 通话卡给一通"来电中"的示意 —— 暖色竖条 + 两行文字。
        callCard.setCall(
            com.shilapi.xcertplay.media.CarPlayCallCard(
                com.shilapi.xcertplay.media.CarPlayCallCard.Phase.RINGING, "示例来电",
            ), 0L,
        )
        cardPaint.color = Color.argb(255, 18, 22, 30)
        borderPaint.color = Color.argb(120, 255, 255, 255)
    }

    /**
     * 由设置页的滑块实时回调驱动。**每张卡传自己的几何** ——
     * 三张卡位置不同，预览与实车必须一致，否则预览就白给了。
     */
    fun update(
        geometry: Map<String, Triple<Int, Int, Int>>,
        opacity: Map<String, Int>,
        night: Boolean,
    ) {
        this.night = night
        val defaults = Triple(
            ClusterTurnCardOverlay.DEFAULT_X_PERCENT,
            ClusterTurnCardOverlay.DEFAULT_Y_PERCENT,
            ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT,
        )
        fun place(card: ClusterTurnCardOverlayHost, key: String) {
            val (x, y, size) = geometry[key] ?: defaults
            card.setLayout(x, y, size)
            card.setOpacity(opacity[key] ?: ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)
            card.setNightMode(night)
        }
        place(turnCard, "turn")
        place(mediaCard, "media")
        place(junctionCard, "junction")
        place(callCard, "call")
        panelPaint.color = if (night) Color.rgb(18, 20, 26) else Color.rgb(226, 232, 240)
        hintPaint.color = if (night) Color.argb(110, 255, 255, 255) else Color.argb(150, 0, 0, 0)
        // 夜间面板底色接近卡片底色，亮蓝在两种底色上都清晰；白天用更饱和的蓝。
        selectionPaint.color = if (night) Color.rgb(96, 165, 250) else Color.rgb(37, 99, 235)
        invalidate()
    }

    fun setVisibleCards(turn: Boolean, media: Boolean) {
        showTurnCard = turn
        showMediaCard = media
        invalidate()
    }

    /**
     * B561: 路口放大图是否在预览里显示。
     *
     * 与 [setVisibleCards] 分开 —— 它已经有三个位置参数了，再加第四个
     * 只会让调用处出现一堆 `true, true, false, ???`。
     */
    fun setJunctionVisible(visible: Boolean) {
        showJunctionCard = visible
        invalidate()
    }

    /** v110 B561: 通话卡是否在预览里显示。 */
    fun setCallVisible(visible: Boolean) {
        showCallCard = visible
        invalidate()
    }

    /**
     * v108: 选中一张卡（"turn" / "media" / "junction" / "call"）。
     *
     * 高亮只画描边不改卡片本身 —— 未选中的卡照常显示，
     * 用户能看到三张卡的相对位置（这正是这个预览框相对
     * 每卡一个预览框的核心价值），同时明确知道滑块在调哪张。
     */
    fun setSelectedCard(key: String) {
        selectedCard = key
        invalidate()
    }

    /**
     * B561: 把媒体卡的「文字大小」「滚动」同步到预览里的那张卡。
     *
     * 与 [update] 分开是因为这两项**只属于媒体卡**，而几何/透明度是三张卡共有的 ——
     * 走同一个 update 就得把这两项也塞进 Map 里传，对转向卡/通话卡毫无意义。
     */
    fun setMediaTextOptions(textScalePercent: Int, scrollText: Boolean) {
        (mediaCard as? ClusterMediaCardView)?.let {
            it.setTextScalePercent(textScalePercent)
            it.setScrollText(scrollText)
        }
        invalidate()
    }

    /**
     * B561: 媒体卡的宽/高与排放方向同步到预览。
     *
     * 不做这个，预览里还是旧的等比尺寸 —— 用户按预览摆好位置，实车却不一样。
     */
    fun setMediaShape(
        widthPercent: Int,
        heightPercent: Int,
        orientation: com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.CardOrientation,
    ) {
        (mediaCard as? ClusterMediaCardView)?.setMediaShape(widthPercent, heightPercent, orientation)
        invalidate()
    }

    /**
     * 缩略图里按屏幕比例显示仪表。
     *
     * 高度铺满可用空间、宽度按 8:3 推；宽度不够时反过来以宽度为准 ——
     * 车机是横屏 1920×720，设置页右侧空间通常偏宽，所以以高度为准更常见。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val byHeight = (available * panelWidth.toFloat() / panelHeight).toInt()
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val width = minOf(availableWidth, byHeight)
        val height = (width * panelHeight.toFloat() / panelWidth).toInt()
        setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, panelPaint)
        canvas.drawRect(0f, 0f, w, h, borderPaint)

        val scale = w / panelWidth
        val cardHeight = h.toInt()
        val cardWidth = w.toInt()
        // 卡片 View 用"整块面板当父容器"的几何（与实车Presentation 同构），
        // 缩放由画布的 scale 承担，所以传原始像素尺寸即可。
        canvas.save()
        canvas.scale(scale, scale)
        drawCard(canvas, turnCard, cardWidth, cardHeight, showTurnCard, "turn")
        drawCard(canvas, mediaCard, cardWidth, cardHeight, showMediaCard, "media")
        drawCard(canvas, junctionCard, cardWidth, cardHeight, showJunctionCard, "junction")
        drawCard(canvas, callCard, cardWidth, cardHeight, showCallCard, "call")
        canvas.restore()

        canvas.drawText(
            "$panelWidth×$panelHeight", dp(6).toFloat(), h - dp(4).toFloat(), hintPaint,
        )
    }

    private fun drawCard(
        canvas: Canvas,
        card: View,
        cardWidth: Int,
        cardHeight: Int,
        visible: Boolean,
        key: String,
    ) {
        if (!visible) return
        // 没有数据的卡片不画 —— 与实车"宁缺勿画"一致：
        // 预览里画个空壳会让用户以为仪表上也会出现空卡。
        if (card is ClusterTurnCardView && !card.hasContent()) return
        if (card is ClusterJunctionCardView && !card.hasContent()) return
        card.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY),
        )
        card.layout(0, 0, cardWidth, cardHeight)
        card.draw(canvas)
        // v108: 选中卡的描边画在卡片之上（卡片自身背景不透明，垫在下面会被盖住）。
        // 圆角与卡片圆角一致，看起来就是卡片自己的边框被点亮。
        if (key == selectedCard) {
            val rect = Rect(
                card.left + dp(3), card.top + dp(3),
                card.right - dp(3), card.bottom - dp(3),
            )
            selectionPaint.style = Paint.Style.STROKE
            selectionPaint.strokeWidth = 3f * density
            canvas.drawRoundRect(RectF(rect), 10f * density, 10f * density, selectionPaint)
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * 预览用的示意导航信息。
     *
     * 字段对齐 [com.shilapi.xcertplay.guidance.ClusterTurnGuidance]：`icon` 用
     * `R.drawable.ic_dp_dashboard` 之类不必真图标，`drawGlyph` 对未知 icon 会退回
     * 默认字形，这里只需要一个非零值让卡片有内容可画。
     */
    private fun sampleGuidance() = com.shilapi.xcertplay.guidance.ClusterTurnGuidance(
        icon = 1,
        roundaboutExit = 0,
        distanceMeters = 320,
        road = "示例道路",
    )

    /**
     * B561: 预览用的示意车道数据。
     *
     * 3 条车道、左右各左转/直行、中间直行且被推荐 —— 这是最常见的
     * "前方路口保持直行"场景，一眼就能看出推荐车道高亮是否对。
     */
    private fun sampleLanes() = com.shilapi.xcertplay.guidance.ClusterLaneGuidance(
        eventIndex = 1,
        laneCount = 3,
        angles = listOf(listOf(-90), listOf(0), listOf(90)),
        positions = listOf(0, 1, 2),
        primaryLane = 1,
        showing = true,
    )
}
