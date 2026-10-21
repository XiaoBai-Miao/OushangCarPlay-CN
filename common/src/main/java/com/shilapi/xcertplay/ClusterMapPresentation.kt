package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.guidance.ClusterTurnGuidance

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice (DiLink 5) or com.xdja.containerservice (DiLink 4). The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val theme: ClusterLayout.Theme = ClusterLayout.theme(context),
    private val onSurface: (Surface?) -> Unit,
    /**
     * B561: 建 presentation 时的深色状态。
     *
     * 默认值仍是比亚迪那套 [ClusterLayout.dark]，但**比亚迪以外的车机
     * 永远走不到它** —— 那个判据是"对比度设置 == DARK 或主题 == SIMPLE"，
     * B561 上既没这个设置、主题恒为 SCENARIO，于是 `dark` 永远是 false，
     * 等待页就永远是浅底深字（实测：深色模式下仪表上一片惨白的"等待 CarPlay"）。
     *
     * 所以调用方（CarPlayHostActivity）必须把**它自己的** darkMode 传进来。
     */
    initialNight: Boolean = ClusterLayout.dark(context, theme),
) : Presentation(context, display) {
    private var waitingLabel: TextView? = null
    private var turnCardView: ClusterTurnCardView? = null

    /** B561: 媒体信息卡 / 通话卡。默认 GONE，有数据才显示（见各自 setXxx）。 */
    private var mediaCardView: ClusterMediaCardView? = null

    /** B561: 通话卡（来电方 / 通话时长）。默认 GONE，见 [setCallCard]。 */
    private var callCardView: ClusterCallCardView? = null

    /** B561: 路口放大图（车道级导航图）。默认 GONE，见 [setJunction]。 */
    private var junctionCardView: ClusterJunctionCardView? = null

    /** v118 B561: 车道信息卡片（只画车道带）。默认 GONE，见 [setLaneCard]。 */
    private var laneCardView: ClusterLaneCardView? = null

    /** v117: 车速表。默认 GONE，读到 VHAL 车速才显示，见 [setSpeedometerReading]。 */
    private var speedometerView: ClusterSpeedometerView? = null
    private var videoView: View? = null
    private var streamActive = false

    /** 深色状态。等待页、底衬都要跟着变，所以不能只在 onCreate 里算一次。 */
    private var night = initialNight
    private var rootView: FrameLayout? = null
    private var contrastView: InstrumentContrastView? = null
    /** 底衬色随夜色变；单独记一份，供运行时切换重画。 */
    private var backdropColor = if (initialNight) DARK_BACKDROP else LIGHT_BACKDROP
    /** 浅色主题下的那块纯色底（无 plan 的旧固件路径用）。 */
    private var flatBackground = if (initialNight) DARK_FLAT_BACKGROUND else LIGHT_FLAT_BACKGROUND
    /**
     * 等待页要不要自带一块不透明底色。
     *
     * 只有**没有 plan** 的旧固件路径需要 —— 那条路走 SurfaceView，
     * 首个 surface 到达前它是一块黑面，必须用不透明底色盖住；
     * 有 plan 时（TextureView + 透明窗口）底色由 root/对比层负责。
     */
    private var flatPlaceholder = false

    var outputSurface: Surface? = null
        private set
    var mapVisible = true
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = sizeOf(display)
        val adaptive = ClusterLayout.supported()
        val plan = if (adaptive) ClusterLayout.plan(size.x, size.y, theme) else null
        val dark = night
        backdropColor = if (dark) DARK_BACKDROP else LIGHT_BACKDROP
        flatBackground = if (dark) DARK_FLAT_BACKGROUND else LIGHT_FLAT_BACKGROUND
        val root = FrameLayout(context).apply {
            setBackgroundColor(flatOrBackdrop(plan))
        }
        rootView = root
        activePlan = plan
        flatPlaceholder = plan == null
        if (plan != null) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val videoParams = if (plan == null) FrameLayout.LayoutParams(-1, -1) else
            FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = plan.left; topMargin = plan.top
            }
        if (plan != null) {
            val textureView = TextureView(context)
            fun transform() {
                textureView.setTransform(Matrix().apply {
                    setScale(ClusterLayout.STREAM_WIDTH.toFloat() / plan.width,
                        ClusterLayout.STREAM_HEIGHT.toFloat() / plan.height)
                    postTranslate(-plan.sourceLeft.toFloat(), -plan.sourceTop.toFloat())
                })
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(ClusterLayout.STREAM_WIDTH, ClusterLayout.STREAM_HEIGHT)
                    transform()
                    outputSurface = Surface(texture).also(onSurface)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onSurface(null)
                    outputSurface?.release()
                    outputSurface = null
                    return true
                }
            }
            root.addView(textureView, videoParams)
            videoView = textureView
            if (plan.fullMap) {
                val contrast = InstrumentContrastView(context, plan, backdropColor)
                contrastView = contrast
                root.addView(contrast, FrameLayout.LayoutParams(-1, -1))
            }
            Log.i(TAG, "layout=$theme viewport=$plan source=${plan.sourceLeft},${plan.sourceTop} dark=$dark")
        } else {
            // Preserve the original PR renderer for DiLink 5 and unverified firmware.
            val surfaceView = SurfaceView(context)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "cluster surface created")
                    outputSurface = holder.surface
                    onSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.i(TAG, "cluster surface ${width}x$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "cluster surface destroyed")
                    onSurface(null)
                    // SurfaceHolder owns this surface; do not release it ourselves.
                    outputSurface = null
                }
            })
            root.addView(surfaceView, videoParams)
            videoView = surfaceView
        }
        // ★ 先建对象、赋给字段，**再**上色。
        //   `waitingLabel = TextView(...).apply { applyWaitingPalette() }` 里
        //   字段此刻还是 null（赋值要等 apply 结束），于是配色被静默跳过 ——
        //   表现就是"改了深色模式，等待页仍是白底"。
        //   这个坑踩过一次：单测直接断言 currentTextColor 才暴露出来。
        waitingLabel = TextView(context).apply {
            text = context.getString(R.string.cluster_waiting_for_map)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        applyWaitingPalette(dark)
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        turnCardView = ClusterTurnCardView(context).apply { visibility = View.GONE }
        root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
        // ★ 媒体卡与通话卡（B561）。挂载方式与转向卡一致：整屏MATCH_PARENT + GONE，
        //   几何由各自 View 内部的 ClusterTurnCardOverlay.card() 算 ——
        //   所以设置里的「水平 / 垂直 / 大小 / 透明度」滑块对三种卡统一生效，
        //   不需要为每种卡各做一套设置项。
        //   addView 顺序即叠放次序：后加的盖在上面。
        mediaCardView = ClusterMediaCardView(context).apply { visibility = View.GONE }
        root.addView(mediaCardView, FrameLayout.LayoutParams(-1, -1))
        // ★ B561 通话卡。挂在媒体卡**之上**：通话优先级高于音乐，来电时应当压住歌名卡。
        //   几何与转向/媒体卡共用同一套 x/y/size（见 [setCardGeometry]）。
        callCardView = ClusterCallCardView(context).apply { visibility = View.GONE }
        root.addView(callCardView, FrameLayout.LayoutParams(-1, -1))
        // ★ B561 路口放大图（车道级导航图）。iOS 不发成品图，只发 0x5202 的路口
        //   几何与 0x5204 的车道数据，由这里自己渲染 —— 详见
        //   [ClusterJunctionCardView] 顶部的说明。挂在三张卡**之上**：
        //   它就是转向信息的展开态，出现时应该在最上层。
        junctionCardView = ClusterJunctionCardView(context).apply { visibility = View.GONE }
        root.addView(junctionCardView, FrameLayout.LayoutParams(-1, -1))
        // ★ v118 车道信息卡片。挂在路口卡**之下**：同时出现时路口图是
        //   临近路口的展开态，优先级更高；车道卡是常驻条，被压住也合理。
        laneCardView = ClusterLaneCardView(context).apply { visibility = View.GONE }
        root.addView(laneCardView, FrameLayout.LayoutParams(-1, -1))
        // ★ v117 车速表。数据来自 B561 VHAL（与原厂仪表同源），
        //   读不到时整块 GONE —— 报假的 0 速比空白更糟。
        //   挂在最上层：它是驾驶员最需要随时看到的信息。
        speedometerView = ClusterSpeedometerView(context).apply { visibility = View.GONE }
        root.addView(speedometerView, FrameLayout.LayoutParams(-1, -1))
        // Start with the placeholder visible; SurfaceView would otherwise cover the light
        // backdrop with an empty black surface before the first stream notification.
        // SurfaceView ignores fractional alpha before API 34. Keep its decoder surface
        // visible and fade an opaque ordinary View over it instead.
        if (videoView !is SurfaceView) videoView?.alpha = if (streamActive) 1f else 0f
        waitingLabel?.apply {
            alpha = if (streamActive) 0f else 1f
            visibility = if (streamActive) View.GONE else View.VISIBLE
        }
        setContentView(root)
    }

    /** root 底色：没有 plan 的旧固件用整屏纯色，有 plan 且全屏地图时用带渐变的底衬。 */
    private fun flatOrBackdrop(plan: ClusterLayout.Plan?): Int =
        if (plan == null) flatBackground else if (plan.fullMap) backdropColor else Color.TRANSPARENT

    /**
     * B561: 等待页配色跟随夜色。
     *
     * 深色 → 黑底白字；浅色 → 保持上游那套浅底深字。
     * 单独抽出来是因为它既要用于建 presentation，也用于运行时切夜色。
     */
    private fun applyWaitingPalette(dark: Boolean) {
        val label = waitingLabel ?: return
        label.setTextColor(if (dark) Color.WHITE else Color.DKGRAY)
        if (flatPlaceholder) label.setBackgroundColor(flatBackground)
    }

    /**
     * B561: 运行时切深浅色。等待页与底衬都要跟着重画 —— 否则车机夜间模式下
     * 仪表上会一直留着那块浅底"等待 CarPlay"（实测反馈）。
     *
     * 幂等：同值直接返回，避免反复 invalidate。
     */
    fun setNightMode(dark: Boolean) {
        if (dark == night) return
        night = dark
        backdropColor = if (dark) DARK_BACKDROP else LIGHT_BACKDROP
        flatBackground = if (dark) DARK_FLAT_BACKGROUND else LIGHT_FLAT_BACKGROUND
        applyWaitingPalette(dark)
        contrastView?.setBackdropColor(backdropColor)
        // plan 只在 onCreate 里算过；这里按当前底色规则重设 root，
        // 保证"全屏地图 + 深色"和"旧固件纯色底"两条路都换到新底色。
        rootView?.setBackgroundColor(flatOrBackdrop(activePlan))
    }

    /** onCreate 算出的几何；null = 旧固件纯色底路径。 */
    private var activePlan: ClusterLayout.Plan? = null

    /**
     * Cross-fades the waiting placeholder over the live map. TextureView can also fade the
     * video; legacy SurfaceView stays visible under the placeholder to preserve its surface.
     */
    fun setStreamActive(active: Boolean) {
        if (streamActive == active) return
        // Update before cancelling: a cancelled fade must not hide the newly restored label.
        streamActive = active
        val video = videoView
        val label = waitingLabel
        video?.animate()?.cancel()
        label?.animate()?.cancel()
        if (video == null || label == null) return
        label.visibility = View.VISIBLE
        // Animate from the current opacity so a stream reversal does not jump or restart.
        if (video !is SurfaceView) video.animate().alpha(if (active) 1f else 0f).setDuration(300).start()
        label.animate().alpha(if (active) 0f else 1f).setDuration(300).withEndAction {
            if (streamActive == active) label.visibility = if (active) View.GONE else View.VISIBLE
        }.start()
    }

    /** Window alpha hides the pixels without destroying the TextureView/decoder surface. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        window?.let { window ->
            val alpha = if (visible) 1f else 0f
            if (window.attributes.alpha != alpha) window.attributes = window.attributes.apply { this.alpha = alpha }
        }
    }

    fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int) {
        turnCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setTurnCardNightMode(night: Boolean) {
        turnCardView?.setNightMode(night)
    }

    fun setTurnCardOpacity(percent: Int) {
        turnCardView?.setOpacity(percent)
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        turnCardView?.setGuidance(guidance)
    }

    /**
     * B561: 媒体信息卡。[artwork] 为 null 就画纯文字卡。
     *
     * 位置/大小/透明度**独立于转向卡**（见 [setCardGeometry]）—— 原先三张卡共用
     * 转向卡那一份滑块，结果媒体卡与转向卡永远重叠、无法分开摆。
     *
     * ★ v69: [album] 是第三行（专辑名，iAP2 MediaItemAttributes 字段 6）。
     */
    fun setMediaCard(
        title: String?,
        artist: String?,
        album: String?,
        playing: Boolean,
        artwork: android.graphics.Bitmap? = null,
    ) {
        mediaCardView?.apply {
            setMedia(title, artist, album, playing)
            setArtwork(artwork)
        }
    }

    /** B561: 媒体卡是否画封面。关闭则只显示歌名/歌手两行。 */
    fun setMediaCardArtworkEnabled(enabled: Boolean) {
        mediaCardView?.setArtworkEnabled(enabled)
    }

    /** B561: 只调媒体卡文字大小（60..180，100 = 原始规格）。 */
    fun setMediaCardTextScalePercent(percent: Int) {
        mediaCardView?.setTextScalePercent(percent)
    }

    /** B561: 媒体卡歌名/歌手超宽时循环滚动。 */
    fun setMediaCardScrollText(enabled: Boolean) {
        mediaCardView?.setScrollText(enabled)
    }

    /**
     * B561: 媒体卡的宽/高与排放方向。
     *
     * 与 `setCardGeometry` 分开 —— 那套是三卡共用的 x/y/size（等比缩放），
     * 这里只影响媒体卡的形状。
     */
    fun setMediaCardShape(
        widthPercent: Int,
        heightPercent: Int,
        orientation: com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.CardOrientation,
    ) {
        mediaCardView?.setMediaShape(widthPercent, heightPercent, orientation)
    }

    /**
     * 三张卡**各自独立**的几何。
     *
     * ★ 不要再合并成"一次性下发到三种卡" —— 那样它们会永远重叠在同一位置，
     *   用户没法把媒体卡摆在转向卡旁边。这是踩过的坑。
     */
    fun setCardGeometry(
        turn: Triple<Int, Int, Int>,
        media: Triple<Int, Int, Int>,
        call: Triple<Int, Int, Int>,
    ) {
        turnCardView?.setLayout(turn.first, turn.second, turn.third)
        mediaCardView?.setLayout(media.first, media.second, media.third)
        callCardView?.setLayout(call.first, call.second, call.third)
    }

    /**
     * B561: 通话卡内容。[elapsedSeconds] 由调用方按秒推进（仅 ACTIVE 相位有意义）。
     * null = 无通话，整卡隐藏。
     */
    fun setCallCard(card: com.shilapi.xcertplay.media.CarPlayCallCard?, elapsedSeconds: Long) {
        callCardView?.setCall(card, elapsedSeconds)
    }

    /**
     * B561: 路口放大图的位置/大小/透明度 —— 独立于转向卡。
     *
     * 用 [ClusterTurnCardOverlay.junctionCard] 那份更方的几何，所以**不能**
     * 复用 [setCardGeometry] 的横幅比例。
     */
    fun setJunctionGeometry(xPercent: Int, yPercent: Int, sizePercent: Int) {
        junctionCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setJunctionOpacity(percent: Int) {
        junctionCardView?.setOpacity(percent)
    }

    /**
     * 旧入口：只推车道带（没有路口几何）。
     *
     * v118 起 [ClusterJunctionCardView] 只吃 [setJunction] 一种数据
     * （见该类顶部"两半不能互相绑架"的说明），所以这里把车道带包一份
     * 只有 lanes 的 [com.shilapi.xcertplay.guidance.ClusterJunctionGuidance]
     * 转进去：`maneuverType = 0` 时 `branches` 为空、不会凭空画岔路，
     * 卡片表现与旧的"只画车道带"一致。车道不可画时整块隐藏。
     */
    fun setJunctionLanes(lanes: com.shilapi.xcertplay.guidance.ClusterLaneGuidance?) {
        val usable = lanes?.takeIf { it.drawable && it.sane }
        junctionCardView?.setJunction(
            usable?.let {
                com.shilapi.xcertplay.guidance.ClusterJunctionGuidance(
                    maneuverType = 0,
                    drivingSide = 0,
                    distanceMeters = 0,
                    lanes = it,
                )
            },
        )
    }

    /**
     * v118: 推路口放大图数据。
     *
     * 与 [setJunctionLanes] 是**两条路**：本方法推的是
     * [com.shilapi.xcertplay.guidance.ClusterJunctionGuidance]（`0x5202` 路口几何
     * + 可选车道带），车道带解不出来时路口图照样画 —— 这就是"路口放大图
     * 画不出来"的修复点。[setJunctionLanes] 是它的车道带专用包装。
     */
    fun setJunction(guidance: com.shilapi.xcertplay.guidance.ClusterJunctionGuidance?) {
        junctionCardView?.setJunction(guidance)
    }

    /**
     * v118: 推车道信息卡片数据。null（`0x5204` 解不出来）时整块隐藏。
     *
     * ★ 不用 maneuver 类型编一组车道充数 —— 那是路口卡的权限
     *   （见 [ClusterLaneCardView] 顶部说明）。猜错车道比不画危险得多。
     */
    fun setLaneCard(lanes: com.shilapi.xcertplay.guidance.ClusterLaneGuidance?) {
        laneCardView?.setLanes(lanes)
    }

    /** v118: 车道信息卡片的位置/大小/透明度 —— 独立于其它卡。 */
    fun setLaneCardGeometry(xPercent: Int, yPercent: Int, sizePercent: Int) {
        laneCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setLaneCardOpacity(percent: Int) {
        laneCardView?.setOpacity(percent)
    }

    /** v118: 车道信息卡片的深浅色。 */
    fun setLaneCardNightMode(night: Boolean) {
        laneCardView?.setNightMode(night)
    }

    /**
     * v117: 推 VHAL 车速读数。null（读不到）时整块隐藏。
     *
     * 走 VHAL 而不是抓 logcat —— 数据与原厂仪表同源，单位明确是 km/h。
     */
    fun setSpeedometerReading(reading: com.shilapi.xcertplay.vehicle.CarCabinVehicleStatus.CabinReading?) {
        speedometerView?.setReading(reading)
    }

    /** v117: 车速表的位置/大小/透明度。 */
    fun setSpeedometerLayout(xPercent: Int, yPercent: Int, sizePercent: Int, opacityPercent: Int) {
        speedometerView?.setLayout(xPercent, yPercent, sizePercent)
        speedometerView?.setOpacity(opacityPercent)
    }

    /** v117: 车速表的深浅色。 */
    fun setSpeedometerNightMode(night: Boolean) {
        speedometerView?.setNightMode(night)
    }

    fun setCardOpacity(turnPercent: Int, mediaPercent: Int, callPercent: Int) {
        turnCardView?.setOpacity(turnPercent)
        mediaCardView?.setOpacity(mediaPercent)
        callCardView?.setOpacity(callPercent)
    }

    fun setClusterCardNightMode(night: Boolean) {
        turnCardView?.setNightMode(night)
        mediaCardView?.setNightMode(night)
        callCardView?.setNightMode(night)
        junctionCardView?.setNightMode(night)
    }

    companion object {
        const val TAG = "DiPlay-Cluster"

        /** 深色底衬（全屏地图路径的渐变底色）。 */
        private val DARK_BACKDROP = Color.rgb(15, 22, 30)

        /** 浅色底衬 —— 与上游 0.2.14 一致，别改。 */
        private val LIGHT_BACKDROP = Color.rgb(207, 218, 229)

        /**
         * 无 plan（旧固件 / B561）时等待页与 root 共用的不透明底色。
         *
         * 深色版取近黑（不是纯 0，避免与仪表自身黑底边界糊在一起）。
         */
        private val DARK_FLAT_BACKGROUND = Color.rgb(10, 12, 16)

        /** 上游原始浅色底色，`ClusterMapPresentationTest` 断言的就是它。 */
        private val LIGHT_FLAT_BACKGROUND = Color.rgb(233, 238, 246)

        /**
         * 找出仪表屏。
         *
         * ## B561 优先用用户选定的屏（0.2.14 缺的那一环）
         *
         * 原实现只认比亚迪：`DISPLAY_CATEGORY_PRESENTATION` 取屏 →
         * `ClusterLayout.displayName()` 按屏名/指纹 → fallback 到
         * `DiLink4ClusterDisplay.accepts()`（要求 `name == "DiLink4"` 且 1920×720）。
         *
         * B561 的仪表是 **HDMI 外接副屏**，`DISPLAY_CATEGORY_PRESENTATION`
         * 恰好**不包含**这类屏；而屏名/尺寸也匹配不上任何比亚迪指纹 ——
         * 于是 `findDisplay()` 恒返回 null，`ClusterMapPresentation` 从不创建，
         * 表现为「连接成功但仪表无画面」，且**日志里连异常都没有**
         * （调用点写的是 `clusterPresentation?.setStreamActive(...)`，null 静默跳过）。
         *
         * 所以顺序改成：**先问用户在设置页选定的屏**（`ClusterDisplayCatalog`
         * 会枚举除主屏外的所有可用屏并记住用户选择），没有再走比亚迪指纹。
         * 这也让设置页里的选屏真正生效 —— 之前它只影响 UI，对投屏链路不起作用。
         */
        fun findDisplay(context: Context, theme: ClusterLayout.Theme = ClusterLayout.theme(context)): Display? {
            // 1) B561 的副屏投图。判据要**无歧义**才认：
            //    - 用户显式选过（selectedId != -1）→ 用他选的那块；
            //    - 只枚举到**一块**副屏 → 没得选，直接用（车机常态：仪表是唯一外接屏）；
            //    - 有多块却没选 → 落到下面的比亚迪指纹，绝不"挑最大的那块"硬上。
            //
            //    ★ 为什么不能无条件 `ClusterDisplayCatalog.resolve()`：
            //    autoFollow 默认 true，它会回落到"第一个候选（分辨率最大）"，
            //    于是 "Passenger display" 960×360、乃至 1280×720 的屏都会被当成
            //    仪表屏（ClusterMapPresentationTest 的 unrelatedPresentationDisplay /
            //    dilink4WrongGeometry / dilink4RequiresPresentationFlag 三条都因此红）。
            //    认错屏会把导航画到 passenger display 上，比不画更糟 —— 宁缺勿画。
            val catalog = com.shilapi.xcertplay.cluster.ClusterDisplayCatalog
            val chosen = catalog.selectedId(context)
            val candidates = catalog.candidates(context)
            // ★ 只对**非比亚迪**的副屏走这条 —— 屏名属于 DiLink/DiPlay 体系时
            //   一律交回下面的指纹逻辑。那几条契约（错尺寸要拒绝、缺
            //   FLAG_PRESENTATION 要拒绝、5.1 要优先于 4.0）不能被B561 分支绕过，
            //   否则就是在比亚迪车上乱认屏。
            val bydNamed = candidates.filter { looksLikeByd(it.name) }
            val foreign = candidates.filterNot { looksLikeByd(it.name) }
            if (chosen != -1) {
                catalog.resolve(context)?.let { return it }
            } else if (foreign.size == 1 && bydNamed.isEmpty()) {
                // 唯一一块**非比亚迪**副屏 = 无歧义（B561 常态：仪表是唯一外接屏）。
                // 仍要过 STATE_OFF 与 name 双重校验 —— 枚举与取用之间屏可能已拔掉。
                val picked = foreign.first()
                context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(picked.displayId)
                    ?.takeIf { it.name == picked.name && it.state != android.view.Display.STATE_OFF }
                    ?.let { return it }
            }

            // 2) 比亚迪原逻辑保留为fallback。
            val displays = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            val name = ClusterLayout.displayName(
                displays.map { it.name }, android.os.Build.FINGERPRINT, theme,
            )
            if (name == null) {
                // Never replace a missing 5.1 side layer with a full-screen display.
                if (ClusterLayout.supported()) return null
                return displays.firstOrNull { display ->
                    val size = sizeOf(display)
                    false
                }
            }
            return displays.firstOrNull { it.name == name }?.takeIf {
                if (!ClusterLayout.supported()) true else {
                    val size = sizeOf(it)
                    ClusterLayout.plan(size.x, size.y, theme) != null
                }
            }
        }

        /**
         * 屏名看起来是否属于比亚迪 / DiLink 体系。
         *
         * 命中的一律交回上游的指纹逻辑处理，B561 的"单块外接屏即采用"不介入 ——
         * 上游那几条契约（错尺寸拒绝、缺 FLAG_PRESENTATION 拒绝、5.1 优先）
         * 正是为了避免在比亚迪车上认错屏，不能被绕过。
         */
        private fun looksLikeByd(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("dilink") || lower.contains("byd") ||
                false
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString {
                    val size = sizeOf(it)
                    "${it.displayId}:${it.name} ${size.x}x${size.y} flags=${it.flags} valid=${it.isValid}"
                }.orEmpty()

        fun diagnosticReport(context: Context): String = buildString {
            appendLine("clusterEnabled=${AirPlayPersistence.loadClusterMapEnabled(context)}")
            appendLine("navigationReceiverAvailable=false")
            appendLine("allDisplays=${describeDisplays(context)}")
            val presentations = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty()
            appendLine("presentationDisplayIds=${presentations.joinToString { it.displayId.toString() }}")
            val selected = findDisplay(context)
            append("selectedCluster=${selected?.let { "${it.displayId}:${it.name}" } ?: "none"}")
        }

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}

/** Shading belongs to DiPlay's map only; no stock cluster window is changed or covered outside the side card. */
private class InstrumentContrastView(
    context: Context,
    private val plan: ClusterLayout.Plan,
    private var background: Int,
) : View(context) {
    private val paint = Paint()

    /**
     * B561: 运行时切深浅色要重画渐变，底色是构造时给的，不能是 val。
     *
     * 刻意不叫 `setBackground` —— 那会与 `View.setBackground(Drawable)` 撞名。
     */
    fun setBackdropColor(color: Int) {
        if (background == color) return
        background = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plan.left.toFloat(); val top = plan.top.toFloat()
        val right = left + plan.width; val bottom = top + plan.height
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float, start: Int, end: Int,
                 l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        val clear = background and 0x00ffffff
        val soft = (background and 0x00ffffff) or (210 shl 24)
        if (plan.fullMap) {
            fade(left, top, left, top + 64, background, clear, left, top, right, top + 64)
            fade(left, bottom - 48, left, bottom, clear, background, left, bottom - 48, right, bottom)
            // The trip/menu readouts occupy the outer panels; keep the route and marker in the clear center.
            fade(left, top, left + 460, top, soft, clear, left, top, left + 460, bottom)
            fade(right - 460, top, right, top, clear, soft, right - 460, top, right, bottom)
        }
        paint.shader = null
    }
}
