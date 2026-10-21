package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.guidance.ClusterLaneGuidance
import kotlin.math.cos
import kotlin.math.sin

/**
 * B561 v118: **车道信息卡片** —— 只画车道带的那一张卡。
 *
 * ## 与 [ClusterJunctionCardView] 的分工
 *
 * | | 路口放大图 | 车道信息卡片（本类） |
 * |---|---|---|
 * | 数据来源 | `0x5202` 路口几何（+ 可选 `0x5204`） | `0x5204` 车道数据 |
 * | 画什么 | 路口岔路 + 出口编号 + 车道带 | **只有**车道带 |
 * | 解不出来时 | 画路口岔路（ maneuver 推导） | 整块隐藏 |
 * | 几何 | [ClusterTurnCardOverlay.junctionCard]（偏方） | [ClusterTurnCardOverlay.card]（横幅） |
 *
 * 用户要的是"新增车道信息卡片"，而路口放大图那边**已经**带了一条车道带。
 * 单独做一张的理由是两条，都不是重复造轮子：
 *
 * 1. **几何不同**。路口卡的版式是"上岔路下车道"的方块，车道卡片走横幅比例，
 *    可以压在仪表最底边当常驻 HUD 用 —— 那是完全不同的摆放诉求。
 * 2. **生命周期不同**。路口图只在临近路口时出现；车道卡可以一直在，
 *    iOS 发 `0x5201` 的 `0x12 = 0` 收起来时它也该消失。
 *
 * ## 宁缺勿画
 *
 * 车道数据是 `0x5204` 解出来的，而那套字节布局**没有实车抓包定论**
 * （见 [ClusterLaneGuidance.parseLanes]）。解错一位就会把箭头画到隔壁车道上，
 * 比不画危险得多。所以本类与路口卡一样：数据不可画就整块 `GONE`，
 * 绝不用 maneuver 类型**编**一组车道充数 —— 那是路口卡的权限，不是车道卡的。
 */
internal class ClusterLaneCardView(context: Context) : android.view.View(context), ClusterTurnCardOverlayHost {

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
    private val laneCellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 推荐车道：蓝底白箭头。 */
    private val laneActiveCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
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
     * 推一份车道数据。
     *
     * null / 不可画 → 整块隐藏。[visibility] 与 [bringToFront] 必须在这里做
     * （卡片初始是 GONE，只设数据不切 visibility 等于白设）。
     */
    fun setLanes(next: ClusterLaneGuidance?) {
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
     * 昼夜配色与路口卡一致：白天浅卡深线，夜间深卡亮线。
     *
     * 推荐车道两种模式都是蓝底白箭头 —— 蓝色在浅底上对比更好，
     * 这是路口卡 v67 起就定下的调子，车道卡跟随，不要另起一套。
     */
    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)
        if (night) {
            backgroundPaint.color = Color.argb(alpha, 10, 13, 18)
            strokePaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(10), 255, 255, 255)
            val road = Color.rgb(150, 158, 172)
            arrowPaint.color = road
            laneCellPaint.color = Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
        } else {
            backgroundPaint.color = Color.argb(alpha, 250, 251, 253)
            strokePaint.color = Color.argb((alpha * 0.28f).toInt().coerceAtLeast(16), 16, 22, 32)
            val road = Color.rgb(126, 134, 148)
            arrowPaint.color = road
            laneCellPaint.color = Color.argb((alpha * 0.10f).toInt().coerceAtLeast(8), 16, 22, 32)
        }
        arrowActivePaint.color = Color.WHITE
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
        // 横幅几何：与转向/媒体/通话卡同一套 [ClusterTurnCardOverlay.card]，
        // 所以设置里那四个滑块对它直接生效，不需要另做一套。
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val w = card.width.toFloat()
        val h = card.height.toFloat()
        val left = card.left.toFloat()
        val top = card.top.toFloat()

        val radius = h * 0.16f
        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // 车道带独占整张卡 —— 这张卡存在的意义就是它。
        drawLanes(canvas, data, left, top, w, h)
    }

    /**
     * 整条车道带：每条车道一个等宽格子，推荐车道蓝底白箭头。
     *
     * 与 [ClusterJunctionCardView] 里那段是同一套画法，但**不共用代码**：
     * 那边的格子高度被路口岔路压掉一半，这边是满高。共用一个函数就得带一堆
     * "这次是半高还是满高"的参数，反而更难读懂。两份各自可读更重要。
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
        val inset = height * 0.08f

        // 有位置就按位置从左到右排；位置缺失/重复时按收到的顺序（与路口卡一致）。
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
