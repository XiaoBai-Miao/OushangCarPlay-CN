package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay

/**
 * 仪表屏上的**媒体信息悬浮卡**（歌名 / 歌手 / 专辑 / 封面）。
 *
 * ## 与转向卡的关系
 *
 * 几何完全复用 [ClusterTurnCardOverlay]：仪表屏上要几张卡就用同一套
 * `xPercent / yPercent / sizePercent` 定位，谁在前面由挂载顺序决定。
 * 这样用户在设置里看到的「水平 / 垂直 / 大小」滑块对两种卡都生效，
 * 不需要为每种卡各做一套设置项。
 *
 * ## 三种排放方向（B561）
 *
 * | 方向 | 版式 | 卡片比例 |
 * |---|---|---|
 * | [ClusterTurnCardOverlay.CardOrientation.HORIZONTAL] | **封面在左**，三行文字在右**堆叠** | 2.64 : 1 横幅 |
 * | [ClusterTurnCardOverlay.CardOrientation.VERTICAL] | **封面在上**，三行文字在下**堆叠居中** | **1 : 1.35 竖版** |
 * | [ClusterTurnCardOverlay.CardOrientation.FREEFORM] | 宽高由两个独立百分比决定，**按实际宽高自动选上面两种版式** | 自由 |
 *
 * ## ★ v69:三行是「歌名 / 歌手 / 专辑」，不是把一个字段折成三行
 *
 * v67 之前把「歌名最多折三行」理解成 wrapText 的折行上限 ——
 * 结果长歌名会占满三行、歌手和专辑被挤出卡外。现在改成**每个字段各占一行**：
 *
 * ```
 * 歌名      ← titlePaint，超宽可滚动
 * 歌手      ← captionPaint，超宽可滚动
 * 专辑      ← captionPaint，超宽可滚动
 * ```
 *
 * 字段为空就少画那一行（iAP2 不保证每次都带专辑）。
 *
 * ## ★ v69:删掉播放态那个白点
 *
 * 原来封面右侧画了一个 [chipPaint] 小圆点表示"在播放"。仪表屏上它就是一个
 * 孤零零的白点，既不传达播放/暂停（颜色变了但没人盯着看），又跟封面白底糊在一起。
 * 现在整块删除 —— 播放态已经由「卡片是否出现」表达了（没歌名就不画卡）。
 *
 * ## 昼夜
 *
 * 夜间深色玻璃 + 浅色字；**白天浅色卡片 + 深色字**（原先白天也用深色卡片，
 * 压在 iPhone 白天浅色地图上像一块脏补丁）。
 *
 * ## 宁缺勿画
 *
 * 没有歌名就整块不画（[setMedia] 收到 null 会清空并让 [onDraw] 直接返回）——
 * 画一个空壳卡片比不画更糟：驾驶员会以为音乐 app 卡住了。
 */
internal class ClusterMediaCardView(context: Context) : android.view.View(context), ClusterTurnCardOverlayHost {

    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
    private var opacityPercent = ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT
    private var night = false

    private var title: String? = null
    private var artist: String? = null

    /** B561 v69: 专辑名 —— 媒体卡第三行（iAP2 MediaItemAttributes 字段 6）。 */
    private var album: String? = null
    private var playing = false
    private var artwork: Bitmap? = null
    /** B561: 是否画封面。默认 true；关闭后纯文字两行。 */
    private var artworkEnabled = true

    /**
     * B561: 文字大小百分比（100 = 卡片原始规格）。
     *
     * 原始规格把标题字号绑在卡片尺寸上，仪表屏上偏小——
     * 而卡片本身有「大小」滑块，但那缩的是整块卡片、字也跟着等比变小，
     * 所以还需要一个**只调文字**的独立档位。
     */
    private var textScalePercent = DEFAULT_TEXT_SCALE_PERCENT

    /**
     * B561: 宽/高**各自独立**（百分比）。
     *
     * 与 [sizePercent] 的区别：那个走 [ClusterTurnCardOverlay.card]，宽高被
     * 绑在 2.64:1 上只能等比缩放；这里走 [ClusterTurnCardOverlay.mediaCard]，
     * 宽和高各按自己的百分比 —— 仪表屏上想要"宽而扁"或"窄而高"都能做到。
     * 仅 [orientation] 为 [CardOrientation.FREEFORM] 时生效。
     */
    private var widthPercent = ClusterTurnCardOverlay.DEFAULT_MEDIA_WIDTH_PERCENT
    private var heightPercent = ClusterTurnCardOverlay.DEFAULT_MEDIA_HEIGHT_PERCENT

    /** B561: 排放方向（横幅 / 竖版 / 自由）。 */
    private var orientation = ClusterTurnCardOverlay.CardOrientation.FREEFORM

    /**
     * B561: 歌名/歌手是否滚动显示。
     *
     * 仪表屏很窄，长歌名必然放不下。开启后**单行仍超宽**才循环滚动，
     * 能放下的行保持静态文本。
     */
    private var scrollText = false

    /**
     * 走马灯的**逐行累积位移**（px），key = 文本内容。
     *
     * ## 为什么不用「相位 = 经过时间 × 速度」
     *
     * 最初那版是 `phase = (elapsed * speed) % span`，看着数学等价，实际**一卡一卡**：
     * 重绘被节流到每 33ms 一次，而 phase 按**真实时间**算，于是相邻两帧的
     * 位移恒等于 `speed * 0.033` —— 一帧跨一大步，视觉上就是顿挫。
     *
     * 改成**按帧累加** `offset += speed * dt`，每帧走同样距离 → 匀速。
     */
    private val scrollOffsets = HashMap<String, Float>()

    /** 上一帧的绘制时刻，用来算 dt。0 表示还没开始滚。 */
    private var lastScrollDrawUptime = 0L

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * B561: 浅色模式下描边 —— 白天卡片是白底，没有描边在浅色地图上会"化掉"。
     */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val artworkDst = RectF()

    init {
        applyPalette()
    }

    private companion object {
        /** 文字大小百分比默认值 = 原始规格。 */
        const val DEFAULT_TEXT_SCALE_PERCENT = 100

        /**
         * 字号可调范围。
         *
         * 上限 180：仪表屏横向空间有限，再大就一个字都放不下了。
         * 下限 60：低于这个歌名几乎无法辨认，不如直接关卡片。
         */
        const val MIN_TEXT_SCALE_PERCENT = 60
        const val MAX_TEXT_SCALE_PERCENT = 180

        /**
         * B561 v69: 媒体卡上固定的**三行**行数上限。
         *
         * 不是「折行上限」—— 现在歌名 / 歌手 / 专辑**各占一行**，
         * 所以这个常量就是版式的行数（少于此的字段为空时不画那一行）。
         */
        const val MAX_TEXT_LINES = 3

        /**
         * B561 v69: 自由模式下判定"宽卡片"的宽高阈值。
         *
         * 1.15 而不是 1.0：竖版（1:1.35）附近如果按横幅画，封面会变成
         * 一个很小的方块、文字挤成三条横线；反过来 1.15 以上的扁卡用竖版，
         * 封面顶到天上去。所以给一个 1.15 的死区。
         */
        const val FREEFORM_ROW_RATIO = 1.15f

        /**
         * 走马灯最小重绘间隔（ms）。
         *
         * ★ 曾经用33ms（30fps）节流，结果滚动看起来"一卡一卡"。
         *   原因不是帧率本身，而是 **相位按真实时间算**：
         *   每 33ms 才重绘一次，phase 却按 `elapsed * speed` 取模，
         *   两次绘制之间的相位跳跃 = speed * 0.033，视觉上就是顿挫。
         *
         * 现在改用 `postInvalidateOnAnimation()`（vsync 驱动，实际 60fps）
         * 并且**按帧间隔累加位移**，每帧走同样的距离 → 匀速无顿挫。
         * 这个值只作为"帧间隔异常大"的兜底，正常 vsync 不触发它。
         */
        const val SCROLL_FRAME_INTERVAL_MS = 16L
    }

    override fun setLayout(x: Int, y: Int, size: Int) {
        xPercent = x
        yPercent = y
        sizePercent = size
        invalidate()
    }

    /** Percent 20..100 of the card's background alpha; text stays readable at any value. */
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
     * 设置媒体信息。[title] 为 null 视为"没有媒体信息"——整块隐藏。
     *
     * 不画假的占位：没歌名时画一个空壳卡片，驾驶员会以为音乐 app 卡住了。
     *
     * ★ v69: [album] 是第三行（专辑名）。为 null/空就少画那一行，
     *   iAP2 不保证每次NowPlayingUpdate 都带专辑。
     */
    fun setMedia(title: String?, artist: String?, album: String?, playing: Boolean) {
        val nextTitle = title?.takeIf { it.isNotBlank() }
        val nextArtist = artist?.takeIf { it.isNotBlank() }
        val nextAlbum = album?.takeIf { it.isNotBlank() }
        if (nextTitle == this.title && nextArtist == this.artist &&
            nextAlbum == this.album && playing == this.playing
        ) {
            return
        }
        this.title = nextTitle
        this.artist = nextArtist
        this.album = nextAlbum
        this.playing = playing
        // ★ 切歌时重置走马灯：偏移以文本内容为 key，
        //   不清的话新歌若与旧歌同名会接着上次的位置起跑。
        scrollOffsets.clear()
        lastScrollDrawUptime = 0L
        // ★ visibility 与 bringToFront 必须在这里做（照 ClusterTurnCardView.setGuidance 的做法）：
        //   Presentation 里这些卡片初始是 GONE，只设数据不切visibility 等于白设；
        //   bringToFront 保证后收到数据的卡盖在上面。
        visibility = if (nextTitle == null) GONE else VISIBLE
        if (nextTitle != null) bringToFront()
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap === artwork) return
        artwork = bitmap
        invalidate()
    }

    /**
     * 是否显示封面。
     *
     * 关闭后只画歌名/歌手 —— 有人就是想看纯文字卡片（仪表屏窄，
     * 封面占掉的那块留给字更好）。
     */
    fun setArtworkEnabled(enabled: Boolean) {
        if (artworkEnabled == enabled) return
        artworkEnabled = enabled
        invalidate()
    }

    /**
     * B561: 只调文字大小，不动卡片尺寸。
     *
     * 与「大小」滑块的区别：那个改的是整张卡（含背景与封面），字跟着等比缩放；
     * 这个只改 [textScalePercent]，背景与封面尺寸不变 —— 仪表屏上想放大歌名时用这个。
     */
    fun setTextScalePercent(percent: Int) {
        val next = percent.coerceIn(MIN_TEXT_SCALE_PERCENT, MAX_TEXT_SCALE_PERCENT)
        if (next == textScalePercent) return
        textScalePercent = next
        invalidate()
    }

    /**
     * B561: 设置宽/高与排放方向。
     *
     * 传 null 的宽/高表示"沿用 [ClusterTurnCardOverlay.card] 的等比尺寸"，
     * 即回到旧行为 —— 这样 [setLayout]（三卡通用的 x/y/size）仍能工作。
     */
    fun setMediaShape(
        widthPercent: Int?,
        heightPercent: Int?,
        orientation: ClusterTurnCardOverlay.CardOrientation,
    ) {
        val nextW = widthPercent ?: ClusterTurnCardOverlay.DEFAULT_MEDIA_WIDTH_PERCENT
        val nextH = heightPercent ?: ClusterTurnCardOverlay.DEFAULT_MEDIA_HEIGHT_PERCENT
        if (nextW == this.widthPercent && nextH == this.heightPercent &&
            orientation == this.orientation
        ) {
            return
        }
        this.widthPercent = nextW
        this.heightPercent = nextH
        this.orientation = orientation
        invalidate()
    }

    /**
     * B561 v67: 三种排放方向**都**走 [ClusterTurnCardOverlay.mediaCard]。
     *
     * ## 为什么不再退回 [ClusterTurnCardOverlay.card]
     *
     * 旧逻辑是"只有 FREEFORM 且宽高与 size 不同时才走 mediaCard，否则退回 card"，
     * 而 `card()` 把宽高**绑死在 2.64:1 横幅比**上 —— 于是选「正方」时
     * 拿到的仍是横幅，版式和选中的方向对不上（实测反馈：选了竖版还是横幅）。
     *
     * 现在按方向分流：
     * - FREEFORM → 用两个独立百分比（宽、高各走各的）
     * - HORIZONTAL / VERTICAL → 用通用 [sizePercent]，比例由 mediaCard 按方向定
     */
    private fun cardRect(panelWidth: Int, panelHeight: Int): ClusterTurnCardOverlay.CardRect =
        if (orientation == ClusterTurnCardOverlay.CardOrientation.FREEFORM) {
            ClusterTurnCardOverlay.mediaCard(
                panelWidth, panelHeight, xPercent, yPercent, widthPercent, heightPercent, orientation,
            )
        } else {
            ClusterTurnCardOverlay.mediaCard(
                panelWidth, panelHeight, xPercent, yPercent, sizePercent, sizePercent, orientation,
            )
        }

    /**
     * B561: 实际用哪种版式。
     *
     * `null` = 没有歌名，整块不画（"宁缺勿画"）。
     */
    private enum class MediaLayout {
        /** 封面在左，文字在右。 */
        ROW,
        /** 封面在上，文字在下。 */
        COLUMN,
    }

    /**
     * 按排放方向与实际宽高**决定版式**。
     *
     * FREEFORM 是"根据宽高自动变换"的落点：宽 ≥ 高的 1.15 倍用 [MediaLayout.ROW]，
     * 否则 [MediaLayout.COLUMN]。
     */
    private fun layoutFor(card: ClusterTurnCardOverlay.CardRect): MediaLayout =
        when (orientation) {
            ClusterTurnCardOverlay.CardOrientation.HORIZONTAL -> MediaLayout.ROW
            ClusterTurnCardOverlay.CardOrientation.VERTICAL -> MediaLayout.COLUMN
            ClusterTurnCardOverlay.CardOrientation.FREEFORM ->
                if (card.width >= (card.height * FREEFORM_ROW_RATIO).toInt()) {
                    MediaLayout.ROW
                } else {
                    MediaLayout.COLUMN
                }
        }

    /**
     * B561: 歌名/歌手/专辑超宽时循环滚动。
     *
     * 只在**确实放不下**时才滚（宽度足够就是静态文本），所以开着的代价只是
     * 长文本的滚动动画，短文本完全不变。
     */
    fun setScrollText(enabled: Boolean) {
        if (scrollText == enabled) return
        scrollText = enabled
        scrollOffsets.clear()
        lastScrollDrawUptime = 0L
        invalidate()
    }

    /**
     * 画一行文本：够宽就正常画，不够宽且开了滚动就画走马灯。
     *
     * 走马灯用 `canvas.save()/clipRect()/translate()/restore()` 把超宽部分裁掉，
     * 循环时先画到"末尾 + 一个字的间隔"，再回起点画一遍，实现无缝衔接。
     *
     * @param centerX 非null 时**居中**绘制（竖版用），此时 [left] 被忽略。
     * @return 该行是否需要继续下一帧重绘（也就是是否在滚）
     */
    private fun drawScrollingText(
        canvas: Canvas,
        text: String,
        left: Float,
        baseline: Float,
        maxWidth: Float,
        paint: Paint,
        speedPxPerSecond: Float,
        centerX: Float? = null,
    ): Boolean {
        val textWidth = paint.measureText(text)
        if (maxWidth <= 0f || textWidth <= maxWidth) {
            // 放得下 —— 若之前在滚（刚换布局），清掉偏移避免下次从中间起。
            scrollOffsets.remove(text)
            if (centerX != null) {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, centerX, baseline, paint)
                paint.textAlign = Paint.Align.LEFT
            } else {
                canvas.drawText(text, left, baseline, paint)
            }
            return false
        }
        val gap = paint.textSize          // 一个字的宽度当间隔
        val span = textWidth + gap
        // 安排下一帧：vsync 驱动（实际 60fps），比定时器平滑。
        val now = android.os.SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
        // ★ 按帧累加位移，每帧走同样距离 → 匀速不顿挫。
        //   dt 取上一帧到现在的真实间隔并 clamp 到 [4,50]ms：
        //   上限很重要 —— 卡顿后（刚切歌、主线程忙）dt 可能几百毫秒，
        //   不 clamp 的话一帧跨过半屏，看着像瞬移。
        val dt = if (lastScrollDrawUptime == 0L) {
            SCROLL_FRAME_INTERVAL_MS
        } else {
            (now - lastScrollDrawUptime).coerceIn(4L, 50L)
        }
        lastScrollDrawUptime = now
        val phase = ((scrollOffsets[text] ?: 0f) + speedPxPerSecond * dt / 1000f) % span
        scrollOffsets[text] = phase

        val clipLeft: Float
        val drawAt: Float
        if (centerX != null) {
            //居中滚动：以可见区中线为锚，超宽部分左右对称地裁。
            clipLeft = centerX - maxWidth / 2f
            drawAt = centerX
        } else {
            clipLeft = left
            drawAt = left
        }
        canvas.save()
        canvas.clipRect(
            clipLeft, baseline - paint.textSize,
            clipLeft + maxWidth, baseline + paint.textSize * 0.4f,
        )
        if (centerX != null) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, drawAt - phase, baseline, paint)
            canvas.drawText(text, drawAt - phase + span, baseline, paint)
            paint.textAlign = Paint.Align.LEFT
        } else {
            // 两遍：正常位置 + 右边一整段（衔接回左端）
            canvas.drawText(text, drawAt - phase, baseline, paint)
            canvas.drawText(text, drawAt - phase + span, baseline, paint)
        }
        canvas.restore()
        return true
    }

    /**
     * B561 v69: 媒体卡的**一行文字**。
     *
     * @param text 内容；null/空表示这个字段这次没带来
     * @param paint 画这一行的笔（歌名用 titlePaint，歌手/专辑用 captionPaint）
     * @param maxWidth 这一行可用的宽度
     * @param speedPxPerSecond 走马灯速度（px/s）
     * @param centerX 非null 时**居中**绘制（竖版用），此时 [left] 被忽略
     * @return 是否在滚（决定要不要继续下一帧）
     */
    private class MediaLine(
        val text: String,
        val paint: Paint,
        val left: Float,
        val baseline: Float,
        val maxWidth: Float,
        val speedPxPerSecond: Float,
        val centerX: Float?,
    )

    /**
     * B561 v69: 组装要画的三行 —— **歌名 / 歌手 / 专辑，各占一行**。
     *
     * ## 为什么不再是"折行"
     *
     * v67 用折行把一个长歌名拆成最多三行，结果长歌名独占三行、
     * 歌手和专辑被挤出卡外 —— 而 iAP2 的 `MediaItemAttributes`
     * 明明带了 album（字段 6）却一直没人用。现在改成三个字段各一行，
     * 每行独立判断"放得下就静态、放不下就滚动"。
     *
     * 整块文字在 [areaTop]..[areaBottom] 之间**垂直居中**；
     * 歌名先排，歌手、专辑依次往后 —— 卡片太矮时从末行开始丢，
     * 保证歌名一定能看到。
     */
    private fun buildLines(
        song: String,
        areaTop: Float,
        areaBottom: Float,
        titleAdvance: Float,
        captionAdvance: Float,
        left: Float,
        maxWidth: Float,
        centerX: Float?,
    ): List<MediaLine> {
        val captions = listOfNotNull(artist, album)
        val maxLines = MAX_TEXT_LINES
        // 卡片只放得下 n 行时，保留前 n 行（歌名优先）。
        val keptCaptions = captions.take(maxLines - 1)
        val totalHeight = titleAdvance + keptCaptions.size * captionAdvance
        val firstBaseline = (areaTop + areaBottom) / 2f - totalHeight / 2f + titlePaint.textSize

        val lines = mutableListOf<MediaLine>()
        var baseline = firstBaseline
        lines += MediaLine(
            song, titlePaint, left, baseline, maxWidth,
            titlePaint.textSize * 0.6f, centerX,
        )
        baseline += titleAdvance
        for (caption in keptCaptions) {
            lines += MediaLine(
                caption, captionPaint, left, baseline, maxWidth,
                captionPaint.textSize * 0.6f, centerX,
            )
            baseline += captionAdvance
        }
        return lines
    }

    /**
     * B561 v69: 画一组行，返回是否有任意一行在滚动。
     *
     * @param onDraw 未开启滚动时的绘制回调（横幅左对齐 / 竖版居中，
     *   走马灯路径自带裁剪，两边画法不同所以交给调用方）
     */
    private fun drawLines(
        canvas: Canvas,
        lines: List<MediaLine>,
        onDraw: (MediaLine, Canvas) -> Unit,
    ): Boolean {
        var scrolling = false
        for (line in lines) {
            if (scrollText) {
                if (drawScrollingText(
                        canvas, line.text, line.left, line.baseline,
                        line.maxWidth, line.paint, line.speedPxPerSecond,
                        centerX = line.centerX,
                    )
                ) {
                    scrolling = true
                }
            } else {
                onDraw(line, canvas)
            }
        }
        return scrolling
    }

    private fun applyPalette() {
        val alpha = (opacityPercent / 100f * 255f).toInt().coerceIn(0, 255)
        if (night) {
            backgroundPaint.color = Color.argb(alpha, 12, 16, 24)
            accentPaint.color = Color.rgb(126, 214, 255)
            titlePaint.color = Color.WHITE
            captionPaint.color = Color.rgb(190, 200, 214)
            strokePaint.color = Color.argb((alpha * 0.22f).toInt().coerceAtLeast(12), 255, 255, 255)
        } else {
            // ★ B561 白天改浅色：原先白天也用深色卡片（18,22,30），
            //   压在 iPhone 白天浅色地图上像一块脏补丁，白天看不清歌名。
            backgroundPaint.color = Color.argb(alpha, 250, 251, 253)
            accentPaint.color = Color.rgb(10, 132, 255)
            titlePaint.color = Color.rgb(20, 24, 32)
            captionPaint.color = Color.rgb(92, 102, 118)
            strokePaint.color = Color.argb((alpha * 0.30f).toInt().coerceAtLeast(16), 16, 22, 32)
        }
        titlePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        captionPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val song = title ?: return
        val card = cardRect(width, height)
        when (layoutFor(card)) {
            MediaLayout.ROW -> drawRowLayout(canvas, card, song)
            MediaLayout.COLUMN -> drawColumnLayout(canvas, card, song)
        }
    }

    private fun drawCardBackground(canvas: Canvas, card: ClusterTurnCardOverlay.CardRect, radius: Float) {
        val left = card.left.toFloat()
        val top = card.top.toFloat()
        val right = (card.left + card.width).toFloat()
        val bottom = (card.top + card.height).toFloat()
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, backgroundPaint)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, strokePaint)
    }

    /**
     * B561 横幅版式：**封面在左，歌名/歌手/专辑在右堆叠**（各一行）。
     *
     * ★ v69: 封面右侧那个播放态小圆点已删除（用户反馈"白点"），
     * 所以文字直接从封面右边开始排。
     */
    private fun drawRowLayout(canvas: Canvas, card: ClusterTurnCardOverlay.CardRect, song: String) {
        val h = card.height.toFloat()
        val w = card.width.toFloat()
        drawCardBackground(canvas, card, h * 0.18f)

        val art = if (artworkEnabled) artwork else null
        val pad = h * 0.14f
        var textLeft = card.left + pad
        val textRight = card.left + w - pad
        if (art != null) {
            // 封面取高度的 0.72 倍 —— 2.64:1 横幅里能放下的最大正方形。
            val side = (h * 0.72f).toInt().coerceAtLeast(1)
            val left = (card.left + (h - side) / 2f).toInt()
            val top = card.top + (h - side) / 2
            artworkDst.set(
                left.toFloat(), top.toFloat(),
                (left + side).toFloat(), (top + side).toFloat(),
            )
            canvas.drawBitmap(art, null, artworkDst, artworkPaint)
            textLeft = left + side + h * 0.09f
        }
        val textWidth = textRight - textLeft
        if (textWidth <= 0f) return

        val scale = textScalePercent / 100f
        titlePaint.textSize = h * 0.21f * scale
        captionPaint.textSize = h * 0.16f * scale
        val lines = buildLines(
            song = song,
            areaTop = card.top.toFloat(),
            areaBottom = card.top + h,
            titleAdvance = titlePaint.textSize * 1.24f,
            captionAdvance = captionPaint.textSize * 1.30f,
            left = textLeft,
            maxWidth = textWidth,
            centerX = null,
        )
        val scrolling = drawLines(canvas, lines) { line, c ->
            c.drawText(clipToWidth(line.text, line.maxWidth, line.paint), line.left, line.baseline, line.paint)
        }
        if (!scrolling) lastScrollDrawUptime = 0L
    }

    /**
     * B561 竖版式：**1:1.35 卡片，封面在上，歌名/歌手/专辑在下堆叠居中**（各一行）。
     *
     * 封面取宽度的 0.62 倍，文字块在"封面（或卡顶）到卡底"之间居中。
     */
    private fun drawColumnLayout(canvas: Canvas, card: ClusterTurnCardOverlay.CardRect, song: String) {
        val w = card.width.toFloat()
        val h = card.height.toFloat()
        drawCardBackground(canvas, card, w * 0.08f)

        val art = if (artworkEnabled) artwork else null
        var cursor = card.top + h * 0.05f
        val artBottom: Float
        if (art != null) {
            // 高度大时封面上限 0.42 —— 1:1.35 的卡里要给三行文字留够位置。
            val side = (w * 0.72f).toInt()
                .coerceAtMost((h * 0.42f).toInt())
                .coerceAtLeast(1)
            val left = card.left + (w - side) / 2f
            artworkDst.set(left, cursor, left + side, cursor + side)
            canvas.drawBitmap(art, null, artworkDst, artworkPaint)
            artBottom = cursor + side
            cursor = artBottom + h * 0.045f
        } else {
            artBottom = card.top.toFloat()
        }
        val centerX = card.left + w / 2f
        val textWidth = w * 0.88f
        if (textWidth <= 0f) return

        val scale = textScalePercent / 100f
        titlePaint.textSize = w * 0.135f * scale
        captionPaint.textSize = w * 0.105f * scale
        val lines = buildLines(
            song = song,
            areaTop = maxOf(cursor, artBottom + h * 0.03f),
            areaBottom = card.top + h - h * 0.04f,
            titleAdvance = titlePaint.textSize * 1.26f,
            captionAdvance = captionPaint.textSize * 1.30f,
            left = centerX,
            maxWidth = textWidth,
            centerX = centerX,
        )
        val scrolling = drawLines(canvas, lines) { line, c ->
            drawCentered(c, clipToWidth(line.text, line.maxWidth, line.paint), line.centerX!!, line.baseline, line.maxWidth, line.paint)
        }
        if (!scrolling) lastScrollDrawUptime = 0L
    }

    /**
     * B561 v69: 静态绘制时把超宽文本**硬裁到边界**（不画省略号）。
     *
     * 走马灯关闭时不能任由文字画出卡外，所以这里裁一刀。
     * 开了滚动的行走[drawScrollingText]，自带裁剪，不经过这里。
     */
    private fun clipToWidth(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end)) > maxWidth) end--
        return text.substring(0, end).trimEnd()
    }

    /** 居中画一行（调用方已裁好宽度）。 */
    private fun drawCentered(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baseline: Float,
        maxWidth: Float,
        paint: Paint,
    ) {
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, centerX, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}