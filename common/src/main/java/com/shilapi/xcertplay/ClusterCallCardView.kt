package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.media.CarPlayCallCard

/**
 * 仪表屏上的**通话卡**（来电方 / 通话时长）。
 *
 * 移植自 v64 车验版（BYD 时代同一份 View，只把出口从「写 SOME/IP 让原厂
 * CarPlay 应用显示」换成「画到 B561 副屏 Surface」）。解析模型
 * [CarPlayCallCard] 与车机无关，是通用 iAP2 协议。
 *
 * ## 复用转向卡的几何
 *
 * 与 [ClusterMediaCardView] 一样走 `ClusterTurnCardOverlay.card(w,h,x,y,size)`，
 * 所以设置里的位置/大小滑块对三种卡（转向 / 媒体 / 通话）统一生效。
 *
 * ## 优先级
 *
 * 有通话时整卡置顶（bringToFront）—— 通话信息应当压住歌名卡。
 */
internal class ClusterCallCardView(context: Context) : android.view.View(context), ClusterTurnCardOverlayHost {

    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
    private var opacityPercent = ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT
    private var night = false
    private var call: CarPlayCallCard? = null
    private var elapsedSeconds = 0L

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        if (this.night == night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    /**
     * [elapsedSeconds] 由调用方按秒推进 —— View 自己不跑定时器，
     * 免得为一个卡片在每个 Presentation 上各挂一个、还漏掉取消。
     */
    fun setCall(next: CarPlayCallCard?, elapsedSeconds: Long) {
        val changed = next != call
        call = next
        this.elapsedSeconds = elapsedSeconds
        // ★ 同 ClusterTurnCardView：visibility 自己切，有通话才显示并置顶。
        //   通话优先级高于媒体 —— 正在通话时卡应当压住歌名卡。
        visibility = if (next == null) GONE else VISIBLE
        if (next != null) bringToFront()
        if (changed || next != null) invalidate()
    }

    private fun applyPalette() {
        val alpha = (opacityPercent / 100f * 255f).toInt().coerceIn(0, 255)
        backgroundPaint.color = Color.argb(alpha, 12, 16, 24)
        // 通话态用暖色，与导航卡的冷色区分开：一眼能看出这是来电/通话而非导航。
        accentPaint.color = Color.rgb(255, 176, 64)
        namePaint.color = Color.WHITE
        statusPaint.color = if (night) Color.rgb(210, 216, 226) else Color.rgb(226, 232, 240)
        namePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        statusPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = call ?: return
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val radius = card.height * 0.18f
        canvas.drawRoundRect(
            card.left.toFloat(), card.top.toFloat(),
            (card.left + card.width).toFloat(), (card.top + card.height).toFloat(),
            radius, radius, backgroundPaint,
        )
        // 左侧竖条：通话中恒亮，用颜色表达"这通电话还活着"。
        val barWidth = card.height * 0.07f
        canvas.drawRoundRect(
            card.left.toFloat(), card.top + card.height * 0.16f,
            card.left + barWidth, card.top + card.height * 0.84f,
            barWidth / 2f, barWidth / 2f, accentPaint,
        )
        val textLeft = card.left + card.height * 0.26f
        val textWidth = (card.left + card.width) - textLeft - card.height * 0.22f
        namePaint.textSize = card.height * 0.32f
        statusPaint.textSize = card.height * 0.22f
        val baseline = card.top + card.height * 0.44f
        // 来电方为空时写"未知来电"而不是留空 —— 留空会让人以为是渲染故障。
        canvas.drawText(
            ellipsize(current.name.ifBlank { "未知来电" }, textWidth, namePaint),
            textLeft, baseline, namePaint,
        )
        val status = when (current.phase) {
            CarPlayCallCard.Phase.RINGING -> "来电"
            CarPlayCallCard.Phase.DIALING -> "呼叫中"
            CarPlayCallCard.Phase.ACTIVE -> formatDuration(elapsedSeconds)
        }
        canvas.drawText(status, textLeft, baseline + card.height * 0.28f, statusPaint)
    }

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val rest = safe % 60
        return "%d:%02d".format(minutes, rest)
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        val suffix = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end).trimEnd() + suffix
    }
}
