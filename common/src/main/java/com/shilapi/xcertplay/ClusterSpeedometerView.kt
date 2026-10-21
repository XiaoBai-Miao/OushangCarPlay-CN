package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay
import com.shilapi.xcertplay.vehicle.CarCabinVehicleStatus

/**
 * 画在仪表盘上的车速表。
 *
 * ## 为什么需要它
 *
 * 设置页里一直有「仪表车速表」开关（`cluster_speedometer_enabled`）与
 * 位置/大小/透明度四个滑条，布局计算 [ClusterSpeedometerOverlay] 也早就写好了 ——
 * 但**全项目没有任何地方调用它**，所以这个功能从 v100 重做起就一直是空壳。
 *
 * v117 补上最后一环：数据。车速来自 B561 的 VHAL
 * （`android.car.hardware.cabin.CarCabinManager`，与原厂仪表同源），
 * 读数见 [CarCabinVehicleStatus]。
 *
 * ## 显示策略
 *
 * **读不到车速就整块隐藏** —— 与车辆状态上报的原则一致。
 * 报一个假的 0 会让驾驶员误以为车速传感器坏了，比空白更糟。
 *
 * ## 与 miao-uni-z 的差异
 *
 * `miao-uni-z` 也在仪表上显示车速，但它是**抓 logcat 文本**再字符串匹配，
 * 而且把 m/s 当 km/h 用（它的 `speed Value()` 解析没做单位判断）。
 * 本项目走 VHAL 拿结构化数据，单位明确是 km/h，不需要猜。
 */
internal class ClusterSpeedometerView(context: Context) : View(context) {
    private var speedKph: Float? = null
    private var gearLabel: Char? = null
    private var night = false
    private var xPercent = ClusterSpeedometerOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterSpeedometerOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterSpeedometerOverlay.DEFAULT_SIZE_PERCENT
    private var opacityPercent = ClusterSpeedometerOverlay.DEFAULT_OPACITY_PERCENT

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            speedPaint.color = Color.argb(255, 240, 242, 246)
            unitPaint.color = Color.argb(179, 176, 182, 192)
            gearPaint.color = Color.argb(224, 226, 228, 236)
        } else {
            glassPaint.color = Color.argb(alpha, 250, 251, 253)
            strokePaint.color = Color.argb((alpha * 0.22f).toInt().coerceAtLeast(12), 16, 22, 32)
            speedPaint.color = Color.argb(255, 18, 22, 30)
            unitPaint.color = Color.argb(190, 88, 98, 114)
            gearPaint.color = Color.argb(224, 52, 60, 74)
        }
    }

    fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        invalidate()
    }

    fun setOpacity(percent: Int) {
        val next = ClusterSpeedometerOverlay.snap(percent, ClusterSpeedometerOverlay.opacityPercents)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (this.night == night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    /**
     * 更新读数。
     *
     * [reading] 为 null 表示读不到 —— 整块隐藏。
     */
    fun setReading(reading: CarCabinVehicleStatus.CabinReading?) {
        val speed = reading?.speedKph
        val gear = reading?.gearLabel
        if (speed == null) {
            if (speedKph == null && gearLabel == null) return
            speedKph = null
            gearLabel = null
            visibility = GONE
            invalidate()
            return
        }
        val changed = speedKph != speed || gearLabel != gear
        speedKph = speed
        gearLabel = gear
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            bringToFront()
        }
        if (changed) invalidate()
    }

    /** 是否有内容可画；给设置页预览用。 */
    fun hasContent(): Boolean = speedKph != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val speed = speedKph ?: return
        val card = ClusterSpeedometerOverlay.rect(width, height, xPercent, yPercent, sizePercent)
        val h = card.height.toFloat()
        val w = card.width.toFloat()
        val radius = h * 0.22f

        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // 车速大字:整数部分大,小数部分小一号跟在后面。
        val whole = speed.toInt().coerceIn(0, 599)
        val tenths = ((speed - whole) * 10).toInt().coerceIn(0, 9)
        speedPaint.textSize = h * 0.46f
        unitPaint.textSize = h * 0.16f
        gearPaint.textSize = h * 0.22f

        val centerX = card.left + w / 2f
        val baseline = card.top + h * 0.62f
        canvas.drawText(whole.toString(), centerX, baseline, speedPaint)

        val unitY = card.top + h * 0.82f
        canvas.drawText("km/h", centerX, unitY, unitPaint)

        gearLabel?.let { gear ->
            // 挡位放右上角的小标签里,不抢车速的主视觉。
            val badge = h * 0.26f
            val left = card.left + w - badge - h * 0.10f
            val top = card.top + h * 0.10f
            rect.set(left, top, left + badge, top + badge)
            canvas.drawRoundRect(rect, badge * 0.28f, badge * 0.28f, glassPaint)
            canvas.drawRoundRect(rect, badge * 0.28f, badge * 0.28f, strokePaint)
            gearPaint.textSize = badge * 0.62f
            canvas.drawText(
                gear.toString(),
                left + badge / 2f,
                top + badge * 0.72f,
                gearPaint,
            )
        }
    }
}
