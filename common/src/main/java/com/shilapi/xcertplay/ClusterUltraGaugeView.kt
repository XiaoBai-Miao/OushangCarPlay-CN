package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.vehicle.B561UltraGauges
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画在仪表屏上的 **CarPlay Ultra 风格仪表盘**。
 *
 * ## 定位
 *
 * CarPlay Ultra（车辆状态协议）在 iPhone 侧能画一整套仪表（转速/油量/续航/里程/胎压），
 * 参考 LIVI 的 `GAUGE_KEYS`（`vehicle_state.rs:1519`）与 `set_gauges`。
 *
 * 真正的 Ultra 协议路线要补齐 RCS/CAF 底层才能让 **iPhone 自己画**，
 * 而我们的工程目前没有 `airplay/rcs/`。所以这里先做**自绘版**：
 * 我们自己在仪表屏上画同样的那套表，数据来自我们已经能读到的 VHAL / logcat。
 *
 * 好处：不依赖 iPhone 端是否支持 Ultra UI（VoyahPlay 的 README 就明说
 * 「iPhone 是否出现空调界面尚未确认」），当下就能实车验证。
 *
 * ## 显示策略：缺哪个表就少画哪个，绝不编造
 *
 * - 纯电车没有油量/发动机转速 → 不画那两块；
 * - 燃油车没有电池电量/续航 → 不画；
 * - TPMS 未连接 → 不画胎压。
 *
 * 一个数据都读不到时**整块隐藏** —— 画一圈空表盘会让驾驶员以为车坏了。
 *
 * ## 与 [ClusterSpeedometerView] 的关系
 *
 * 那个是**单个**车速表（v117 加的），这个是**整套** Ultra 仪表盘。
 * 两者独立开关、位置独立 —— 可以只开其中一个，也可以都开。
 */
internal class ClusterUltraGaugeView(context: Context) : View(context) {

    private var gauges: B561UltraGauges? = null
    private var night = false
    private var opacityPercent = 100

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

    fun setNightMode(night: Boolean) {
        if (this.night == night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    fun setOpacity(percent: Int) {
        val next = percent.coerceIn(10, 100)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    /**
     * 更新仪表数据。
     *
     * [gauges] 为 null 或全空 → 整块隐藏。
     */
    fun setGauges(gauges: B561UltraGauges?) {
        val next = gauges?.takeIf { !it.isEmpty }
        if (next == this.gauges) return
        this.gauges = next
        visibility = if (next == null) View.GONE else View.VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val data = gauges ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 玻璃底板。
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius(h), radius(h), glassPaint)
        rect.inset(1f, 1f)
        canvas.drawRoundRect(rect, radius(h), radius(h), strokePaint)

        // 转速表（若有）：左半区，一个半圆仪表。
        val rpm = data.primaryRpm
        if (rpm != null) {
            drawTachometer(canvas, 0f, 0f, w * 0.52f, h, rpm, data.gearLabel)
        }

        // 右侧信息栏：功率 / 续航(或油量) / 电量 / 里程 / 胎压。
        val rightX = w * 0.54f
        var y = h * 0.20f
        val rowH = h * 0.155f

        data.powerKw?.let {
            drawRow(canvas, rightX, y, "功率", "${it.roundToInt()} kW")
            y += rowH
        }
        // 燃油车优先显示油量，纯电车显示续航。
        data.fuelPercent?.let {
            drawRow(canvas, rightX, y, "油量", "$it %")
            y += rowH
        }
        data.rangeKm?.let {
            drawRow(canvas, rightX, y, "续航", "$it km")
            y += rowH
        }
        data.batteryPercent?.let {
            drawRow(canvas, rightX, y, "电量", "$it %")
            y += rowH
        }
        data.odometerKm?.let {
            drawRow(canvas, rightX, y, "里程", "${it.roundToInt()} km")
            y += rowH
        }
        data.tirePressureBar?.let { tires ->
            drawTirePressure(canvas, rightX, y, w, tires)
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
        val names = arrayOf("左前", "右前", "左后", "右后")
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

    private fun radius(h: Float): Float = h * 0.12f
}

/** 供外部算「有没有数据要画」的便捷函数。 */
internal fun B561UltraGauges.hasAnythingToShow(): Boolean = !isEmpty

/** 把百分比透明度夹到合理区间。 */
internal fun clusterGaugeOpacity(value: Int): Int = max(10, min(100, value))
