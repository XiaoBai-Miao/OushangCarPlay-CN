package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.guidance.ClusterLaneGuidance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * B561: **路口放大图**（车道级导航图）。
 *
 * ## 为什么必须自己画，而不是"取图"
 *
 * CarPlay 的导航平面里**没有任何图片字段**。iOS 只发三类 TLV 消息：
 * `0x5201`（路线级）、`0x5202`（单条转向 + 路口几何）、`0x5204`（车道级）。
 * 原厂仪表上那张漂亮的路口图，是车机拿这些**几何数据自己渲染**出来的 ——
 * 这也是 Apple 的设计意图（车机决定样式）。
 *
 * 所以准确的说法是：**拿不到成品图，但拿得到画它所需的全部几何**，
 * 本类就是那份几何的渲染器。数据源见 [ClusterLaneGuidance]。
 *
 * ## 画的是什么
 *
 * 三部分，从上到下：
 * 1. **路口岔路**（上半）：以本车行驶方向为基准，按 `junctionAngles` 画
 *    几条辐射线 + 推荐方向那一根加粗。普通路口没给角度时只画一个箭头。
 * 2. **车道带**（下半）：每条车道一个格子，格子里画该车道的可行驶方向箭头，
 *    推荐车道整格高亮（蓝底）。这就是"该走哪条道"的答案。
 * 3. **出口编号**（若有）：右上角一枚小徽章，写 `junctionExitAngle` 对应的
 *    出口序号 —— 高速出口场景最有用。
 *
 * ## 宁缺勿画
 *
 * [ClusterLaneGuidance.drawable] 为 false 时整块隐藏（[setLane] 收到 null）。
 * 只画一个空壳框比不画更糟：驾驶员会以为导航坏了。
 */
internal class ClusterJunctionCardView(context: Context) : android.view.View(context), ClusterTurnCardOverlayHost {

    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
    private var opacityPercent = ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT
    private var night = false
    private var lanes: ClusterLaneGuidance? = null

    private val accent = Color.rgb(10, 132, 255)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    /** 未推荐车道：暗色，弱化。 */
    private val dimRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 推荐车道：亮色 + 实心高亮。 */
    private val activeRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val laneCellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val laneActiveCellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    init {
        applyPalette()
    }

    override fun setLayout(x: Int, y: Int, size: Int) {
        xPercent = x
        yPercent = y
        sizePercent = size
        invalidate()
    }

    override fun setOpacity(percent: Int) {
        val next = percent.coerceIn(20, 100)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    override fun setNightMode(night: Boolean) {
        if (night == this.night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    /**
     * B561: 推一份车道数据。
     *
     * null / 不可画 → 整块隐藏。[visibility] 与 [bringToFront] 必须在这里做
     * （卡片初始是 GONE，只设数据不切 visibility 等于白设）。
     */
    fun setLane(next: ClusterLaneGuidance?) {
        val usable = next?.takeIf { it.drawable && it.sane }
        if (usable == lanes) {
            visibility = if (usable == null) GONE else VISIBLE
            return
        }
        lanes = usable
        visibility = if (usable == null) GONE else VISIBLE
        if (usable != null) bringToFront()
        invalidate()
    }

    /** 是否有内容可画。给设置页预览用。 */
    fun hasContent(): Boolean = lanes != null

    /**
     * B561 v67: 白天改**浅色卡片 + 深色车道线**（原先白天也是深底）。
     *
     * 路口图白天压在 iPhone 浅色地图上，深底卡片像一块脏补丁；
     * 而推荐车道是蓝底白箭头，那部分两种模式一致 —— 蓝色在浅底上对比更好。
     */
    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)
        if (night) {
            backgroundPaint.color = Color.argb(alpha, 10, 13, 18)
            strokePaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(10), 255, 255, 255)
            val road = Color.rgb(150, 158, 172)
            dimRoadPaint.color = road
            arrowPaint.color = road
            activeRoadPaint.color = Color.rgb(235, 240, 248)
            arrowActivePaint.color = Color.WHITE
            laneCellPaint.color = Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
        } else {
            backgroundPaint.color = Color.argb(alpha, 250, 251, 253)
            strokePaint.color = Color.argb((alpha * 0.28f).toInt().coerceAtLeast(16), 16, 22, 32)
            val road = Color.rgb(126, 134, 148)
            dimRoadPaint.color = road
            arrowPaint.color = road
            activeRoadPaint.color = Color.rgb(28, 32, 40)
            arrowActivePaint.color = Color.WHITE
            laneCellPaint.color = Color.argb((alpha * 0.10f).toInt().coerceAtLeast(8), 16, 22, 32)
        }
        laneActiveCellPaint.color = accent
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = lanes ?: return
        val card = ClusterTurnCardOverlay.junctionCard(width, height, xPercent, yPercent, sizePercent)
        val w = card.width.toFloat()
        val h = card.height.toFloat()
        val left = card.left.toFloat()
        val top = card.top.toFloat()

        val radius = h * 0.10f
        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val pad = h * 0.06f
        // 车道带占下半，路口岔路占上半 —— 车道是驾驶员真正要看的，放大一点。
        val laneBandTop = top + h * 0.52f
        drawJunction(canvas, data, left + pad, top + pad * 0.6f, w - pad * 2, laneBandTop - (top + pad * 0.6f))
        drawLanes(canvas, data, left + pad, laneBandTop, w - pad * 2, top + h - pad - laneBandTop)
    }

    /**
     * 上半：路口岔路。
     *
     * 以卡片底部中点为"本车"，往上画 [ClusterLaneGuidance.junctionAngles]
     * 里的每条岔路。没有角度数据（普通路口常常没有）就退回一个居中直行箭头 ——
     * 那仍然比空框有用。
     */
    private fun drawJunction(
        canvas: Canvas,
        data: ClusterLaneGuidance,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        if (height <= 0f || width <= 0f) return
        val originX = left + width / 2f
        val originY = top + height
        val length = height * 0.92f
        val halfWidth = width * 0.42f

        val angles = data.junctionAngles.filter { it in -180..180 }
        if (angles.isEmpty()) {
            // 无几何数据：只画本车前方那一根。
            drawBranch(canvas, originX, originY, 0f, length, halfWidth, primary = true)
            return
        }
        // 推荐方向：优先用转向类型推出的角度（由调用方塞进 junctionAngles 的第一项），
        // 这里按"离正前方最近"判定 —— 驾驶员看的永远是即将拐的那条。
        val recommended = pickRecommendedAngle(angles)
        for (angle in angles) {
            drawBranch(canvas, originX, originY, angle.toFloat(), length, halfWidth, angle == recommended)
        }
    }

    /**
     * 挑"推荐岔路"：绝对值最小的那个（也就是最接近直行的那条）。
     *
     * 高速出口场景下最小绝对值是 ±30° 之类的缓弯，正好对应"继续走主路"，
     * 所以这个规则同时覆盖直行、优先路口、环岛入口。
     */
    private fun pickRecommendedAngle(angles: List<Int>): Int =
        angles.minByOrNull { abs(it) } ?: 0

    /**
     * 一条岔路：从原点沿 [angle] 度方向画一条渐宽的road，两端各留一点圆角。
     *
     * `angle` 约定：`0` = 正前方（朝上），`+90` = 右，`-90` = 左 ——
     * 与 [ClusterLaneGuidance] 顶部注释一致。
     */
    private fun drawBranch(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        angle: Float,
        length: Float,
        halfWidth: Float,
        primary: Boolean,
    ) {
        val radians = Math.toRadians(angle.toDouble())
        val endX = originX + (sin(radians) * length).toFloat()
        val endY = originY - (cos(radians) * length).toFloat()
        val farHalf = if (primary) halfWidth * 0.34f else halfWidth * 0.20f
        path.reset()
        path.moveTo(originX - halfWidth, originY)
        path.lineTo(originX + halfWidth, originY)
        path.lineTo(endX + farHalf, endY)
        path.lineTo(endX - farHalf, endY)
        path.close()
        canvas.drawPath(path, if (primary) activeRoadPaint else dimRoadPaint)
        // 端头一个圆角矩形，视觉上就是"这条路通向那边"。
        val capRadius = farHalf
        rect.set(endX - farHalf, endY - farHalf * 0.6f, endX + farHalf, endY + farHalf * 0.6f)
        canvas.drawRoundRect(rect, capRadius, capRadius, if (primary) activeRoadPaint else dimRoadPaint)
    }

    /**
     * 下半：车道带。
     *
     * 每条车道一个等宽格子；推荐车道整格蓝底 + 白色箭头，其余暗格 + 暗箭头。
     * 一格最多画 [ClusterLaneGuidance.MAX_DIRECTIONS_PER_LANE] 个方向。
     */
    private fun drawLanes(
        canvas: Canvas,
        data: ClusterLaneGuidance,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val count = data.angles.size
        if (count == 0 || height <= 0f || width <= 0f) return
        val gap = width * 0.012f
        val cellWidth = (width - gap * (count - 1)) / count
        if (cellWidth <= 0f) return
        val radius = cellWidth * 0.16f
        val inset = height * 0.06f

        // 有位置就按位置从左到右排；位置缺失/重复时按收到的顺序（与 HUD 侧一致）。
        val order = laneOrder(data)

        for ((slot, laneIndex) in order.withIndex()) {
            val directions = data.angles[laneIndex]
            if (directions.isEmpty()) continue
            val active = laneIndex == data.primaryLane
            val cellLeft = left + slot * (cellWidth + gap)
            rect.set(cellLeft, top + inset, cellLeft + cellWidth, top + height - inset)
            canvas.drawRoundRect(rect, radius, radius, if (active) laneActiveCellPaint else laneCellPaint)
            drawCellArrows(canvas, directions, cellLeft, top + inset, cellWidth, height - inset * 2, active)
        }
    }

    /**
     * 车道在卡面上的排列顺序。
     *
     * 只有**每个位置都有值且互不相同**时才按位置排 —— 否则退回收到顺序。
     * iOS 偶尔会给重复位置，强行排序会让两条车道叠在一起。
     */
    private fun laneOrder(data: ClusterLaneGuidance): List<Int> {
        val positions = data.positions
        if (positions.size != data.angles.size) return data.angles.indices.toList()
        if (positions.any { it == null }) return data.angles.indices.toList()
        val distinct = positions.filterNotNull().toSet()
        if (distinct.size != positions.size) return data.angles.indices.toList()
        return positions.indices.sortedBy { positions[it] ?: 0 }
    }

    /** 一个车道格里的方向箭头。 */
    private fun drawCellArrows(
        canvas: Canvas,
        directions: List<Int>,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        active: Boolean,
    ) {
        val paint = if (active) arrowActivePaint else arrowPaint
        val shown = directions.take(ClusterLaneGuidance.MAX_DIRECTIONS_PER_LANE)
        if (shown.isEmpty()) return
        // 一格内的多条方向：并排（多于两条时压成两行网格，最多 3 条 = 2+1）。
        val columns = if (shown.size == 1) 1 else 2
        val rows = (shown.size + columns - 1) / columns
        val slotWidth = width / columns
        val slotHeight = height / rows
        val arrowLength = minOf(slotWidth, slotHeight) * 0.52f
        val stroke = arrowLength * 0.30f
        paint.strokeWidth = stroke

        shown.forEachIndexed { index, angle ->
            val column = index % columns
            val row = index / columns
            val cx = left + slotWidth * (column + 0.5f)
            val cy = top + slotHeight * (row + 0.5f)
            drawArrow(canvas, paint, cx, cy, angle, arrowLength)
        }
    }

    /**
     * 一个方向箭头：从格心沿 [angle] 画一条带两个勾角的折线。
     *
     * `0` = 朝上，`+90` = 朝右 —— 与 [ClusterLaneGuidance] 的角度约定一致，
     * 所以左转/右转箭头不用单独画一套资源。
     */
    private fun drawArrow(canvas: Canvas, paint: Paint, cx: Float, cy: Float, angle: Int, length: Float) {
        val radians = Math.toRadians(angle.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val half = length / 2f
        val tipX = cx + dx * half
        val tipY = cy + dy * half
        val tailX = cx - dx * half
        val tailY = cy - dy * half
        path.reset()
        path.moveTo(tailX, tailY)
        path.lineTo(tipX, tipY)
        canvas.drawPath(path, paint)
        // 箭头两个勾角，长度取笔画的 1.6 倍，视觉上刚好饱满。
        val barb = paint.strokeWidth * 1.6f
        val back = Math.toRadians((angle - 140).toDouble())
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(tipX + sin(back).toFloat() * barb, tipY - cos(back).toFloat() * barb)
        val back2 = Math.toRadians((angle + 140).toDouble())
        path.moveTo(tipX, tipY)
        path.lineTo(tipX + sin(back2).toFloat() * barb, tipY - cos(back2).toFloat() * barb)
        canvas.drawPath(path, paint)
    }
}
