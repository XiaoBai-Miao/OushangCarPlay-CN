package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.vehicle.B561GaugeReadings
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画在仪表屏上的**自绘仪表卡片**（转速 / 功率 / 续航 / 里程 / 胎压）。
 *
 * ## ★ v136：它取代了「CarPlay Ultra 官方仪表」
 *
 * 原来这里有两套并存的东西：
 *
 * | | 原 v123 自绘表 | 原 v125 真 Ultra |
 * |---|---|---|
 * | 谁画 | **我们**（这个类） | iPhone 自己画 |
 * | 依赖 | 只要车机能读 VHAL | 要 CarPlay Ultra 车辆状态协议（RCS/CAF）|
 * | 实车 | 一直可用 | **从未跑通**，且会让 iPhone 反复 TEARDOWN 重连 |
 *
 * v136 把真 Ultra 协议栈整体删除（`airplay/ultra/` + `airplay/rcs/`，19 个类），
 * 于是「官方仪表」这个内容项（`maps:/car/instrumentcluster`）也一并没了 ——
 * 留着它就是设置页里一个"选了也没用"的死选项。
 *
 * 现在**仪表上的车辆信息全部由我们自绘**：这一张仪表卡片 + 车速表卡片 +
 * 转向/路口/车道/媒体/通话卡，统统走 VHAL / logcat 的真实读数，
 * 不依赖 iPhone 端是否支持任何 Ultra UI。
 *
 * ## 与其它卡片的关系
 *
 * 它现在和其它卡（转向/媒体/路口/车道/通话）**共用同一套几何**
 * （[ClusterTurnCardOverlay.card]）与同一套滑块（水平/垂直/大小/透明度），
 * 也进设置页的「统一卡片预览」—— 用户可以像摆转向卡一样摆它。
 *
 * 之前它是**整屏铺满**（`FrameLayout.LayoutParams(-1, -1)`）、没有位置滑块，
 * 也没有预览 —— 想调只能靠改代码。
 *
 * ## 显示策略：缺哪个表就少画哪个，绝不编造
 *
 * - 纯电车没有油量/发动机转速 → 不画那两块；
 * - 燃油车没有电池电量/续航 → 不画；
 * - TPMS 未连接 → 不画胎压。
 *
 * 一个数据都读不到时**整块隐藏** —— 画一圈空表盘会让驾驶员以为车坏了。
 */
internal class ClusterGaugeCardView(context: Context) : View(context), ClusterTurnCardOverlayHost {

    private var gauges: B561GaugeReadings? = null
    private var night = false
    private var opacityPercent = ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT
    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    init {
        applyPalette()
    }

    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)
        if (night) {
            glassPaint.color = Color.argb(alpha, 12, 14, 18)
            strokePaint.color = Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
            valuePaint.color = Color.argb(255, 240, 242, 246)
            labelPaint.color = Color.argb(179, 176, 182, 192)
            smallPaint.color = Color.argb(160, 176, 182, 192)
        } else {
            glassPaint.color = Color.argb(alpha, 250, 251, 253)
            strokePaint.color = Color.argb((alpha * 0.22f).toInt().coerceAtLeast(12), 16, 22, 32)
            valuePaint.color = Color.argb(255, 18, 22, 30)
            labelPaint.color = Color.argb(190, 88, 98, 114)
            smallPaint.color = Color.argb(170, 88, 98, 114)
        }
    }

    override fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        invalidate()
    }

    override fun setOpacity(percent: Int) {
        val next = percent.coerceIn(10, 100)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    override fun setNightMode(night: Boolean) {
        if (this.night == night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    /**
     * 更新仪表数据。
     *
     * [gauges] 为 null 或全空 → 整块隐藏。
     */
    fun setGauges(gauges: B561GaugeReadings?) {
        val next = gauges?.takeIf { !it.isEmpty }
        if (next == this.gauges) return
        this.gauges = next
        visibility = if (next == null) View.GONE else View.VISIBLE
        invalidate()
    }

    /** 是否有内容可画；给设置页预览用。 */
    fun hasContent(): Boolean = gauges != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = gauges ?: return
        // ★ v136: 用自己的偏方几何（1.9:1），不是转向卡那种 2.64:1 横幅 ——
        //   左边要放半圆转速表，右边要竖排 4~5 行读数。
        val card = ClusterTurnCardOverlay.gaugeCard(width, height, xPercent, yPercent, sizePercent)
        val h = card.height.toFloat()
        val w = card.width.toFloat()
        if (w <= 0f || h <= 0f) return

        // 玻璃底板。
        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        val radius = h * 0.16f
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        rect.inset(1f, 1f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // 转速表（若有）：左半区，一个半圆仪表。
        val rpm = data.primaryRpm
        if (rpm != null) {
            drawTachometer(canvas, card.left.toFloat(), card.top.toFloat(), w * 0.52f, h, rpm, data.gearLabel)
        }

        // 右侧信息栏：功率 / 续航(或油量) / 电量 / 里程 / 胎压。
        val rightX = card.left + w * 0.54f
        var y = card.top + h * 0.20f
        val rowH = h * 0.155f

        data.powerKw?.let {
            drawRow(canvas, rightX, y, context.getString(R.string.cluster_gauge_power), "${it.roundToInt()} kW")
            y += rowH
        }
        // 燃油车优先显示油量，纯电车显示续航。
        data.fuelPercent?.let {
            drawRow(canvas, rightX, y, context.getString(R.string.cluster_gauge_fuel), "$it %")
            y += rowH
        }
        data.rangeKm?.let {
            drawRow(canvas, rightX, y, context.getString(R.string.cluster_gauge_range), "$it km")
            y += rowH
        }
        data.batteryPercent?.let {
            drawRow(canvas, rightX, y, context.getString(R.string.cluster_gauge_battery), "$it %")
            y += rowH
        }
        data.odometerKm?.let {
            drawRow(canvas, rightX, y, context.getString(R.string.cluster_gauge_odometer), "${it.roundToInt()} km")
            y += rowH
        }
        data.tirePressureBar?.let { tires ->
            drawTirePressure(canvas, rightX, y, card.left + w, tires)
        }
    }

    /**
     * 半圆转速表。
     *
     * 刻度按 `0..maxRpm` 画，红区从 80% 起（与常规仪表一致）。
     */
    private fun drawTachometer(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        rpm: Float,
        gear: Char?,
    ) {
        val cx = left + width / 2f
        val cy = top + height * 0.72f
        val radius = min(width, height) * 0.42f
        val startAngle = 180f
        val sweep = 180f
        val maxRpm = 2400f

        // 底槽。
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        arcPaint.strokeWidth = radius * 0.16f
        arcPaint.color = strokePaint.color
        canvas.drawArc(rect, startAngle, sweep, false, arcPaint)

        // 实际值。
        val fraction = (rpm / maxRpm).coerceIn(0f, 1f)
        arcPaint.color = if (fraction > 0.8f) {
            Color.argb(255, 214, 58, 48)
        } else {
            Color.argb(255, 66, 132, 244)
        }
        canvas.drawArc(rect, startAngle, sweep * fraction, false, arcPaint)

        // 数字。
        valuePaint.textSize = radius * 0.52f
        canvas.drawText("${rpm.roundToInt()}", cx, cy - radius * 0.06f, valuePaint)
        labelPaint.textSize = radius * 0.24f
        canvas.drawText("rpm", cx, cy + radius * 0.24f, labelPaint)

        // 挡位。
        gear?.let {
            valuePaint.textSize = radius * 0.34f
            canvas.drawText(it.toString(), cx, cy - radius * 0.44f, valuePaint)
        }
    }

    /** 右侧一行「标签 + 数值」。 */
    private fun drawRow(canvas: Canvas, x: Float, y: Float, label: String, value: String) {
        labelPaint.textSize = hUnit()
        canvas.drawText(label, x, y, labelPaint)
        valuePaint.textSize = hUnit() * 1.15f
        canvas.drawText(value, x, y + hUnit() * 1.05f, valuePaint)
    }

    /** 四轮胎压：横排四个小方块，各自标 bar。 */
    private fun drawTirePressure(
        canvas: Canvas,
        x: Float,
        y: Float,
        right: Float,
        tires: FloatArray,
    ) {
        val names = arrayOf(
            context.getString(R.string.cluster_gauge_tire_fl),
            context.getString(R.string.cluster_gauge_tire_fr),
            context.getString(R.string.cluster_gauge_tire_rl),
            context.getString(R.string.cluster_gauge_tire_rr),
        )
        labelPaint.textSize = hUnit() * 0.86f
        valuePaint.textSize = hUnit() * 0.95f
        val cell = (right - x) / 4f
        for (i in tires.indices) {
            val cx = x + cell * (i + 0.5f)
            canvas.drawText(names[i], cx, y, labelPaint)
            canvas.drawText(String.format("%.2f", tires[i]), cx, y + hUnit() * 0.95f, valuePaint)
        }
    }

    /** 行高基准：按 View 高度推，保证不同仪表分辨率下比例一致。 */
    private fun hUnit(): Float = height * 0.085f
}
