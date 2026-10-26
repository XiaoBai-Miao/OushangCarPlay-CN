package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
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
    private val onSurface: (Surface?) -> Unit,
    /**
     * B561: 建 presentation 时的深色状态。
     *
     * 默认浅色 —— 与上游一致。**B561 永远走不到浅色**：调用方
     * （CarPlayHostActivity）总是把**它自己的** darkMode 传进来。
     */
    initialNight: Boolean = false,
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

    /** v123: 自绘仪表卡片（转速/功率/续航/里程/胎压）。 */
    private var gaugeCardView: ClusterGaugeCardView? = null
    private var videoView: View? = null
    private var streamActive = false

    /** 深色状态。等待页、底衬都要跟着变，所以不能只在 onCreate 里算一次。 */
    private var night = initialNight
    private var rootView: FrameLayout? = null
    /** 底色随夜色变；单独记一份，供运行时切换重画。 */
    private var flatBackground = if (initialNight) DARK_FLAT_BACKGROUND else LIGHT_FLAT_BACKGROUND

    var outputSurface: Surface? = null
        private set
    var mapVisible = true
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = sizeOf(display)
        val dark = night
        flatBackground = if (dark) DARK_FLAT_BACKGROUND else LIGHT_FLAT_BACKGROUND
        val root = FrameLayout(context).apply {
            setBackgroundColor(flatBackground)
        }
        rootView = root
        // ★ v136 B561: 视频层统一用 TextureView。
        //
        //   实证（10-26，RMX2202 + 开发者选项「模拟副屏」720×480）：
        //   同一个 presentation 里自绘的车速表（普通 View）显示正常，
        //   而视频那块是纯黑；同时 111 流协商成功（600x400）、解码器
        //   "first frame rendered"、stats 里 shown=10.6fps —— 帧确实渲染了，
        //   只是 SurfaceView 走独立合成层，虚拟屏没有独立的硬件合成通道，
        //   帧出不来。
        //
        //   TextureView 画在视图树里，任何屏都能出图。代价是多一次 GPU 纹理拷贝，
        //   但仪表只有一路 600x400 流，可忽略。
        //
        //   ★ v136: 原来这里有两路（有 plan 的比亚迪 DiLink 5 / 无 plan 的 B561），
        //   但 `ClusterLayout.plan()` 恒返回 null（比亚迪集成已在 v100 移除），
        //   那一路是不可达的死代码 —— 合并成一路。
        val videoParams = FrameLayout.LayoutParams(-1, -1)
        val textureView = TextureView(context)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "cluster surface created")
                outputSurface = Surface(texture).also(onSurface)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "cluster surface ${width}x$height")
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                Log.i(TAG, "cluster surface destroyed")
                onSurface(null)
                outputSurface?.release()
                outputSurface = null
                return true
            }
        }
        root.addView(textureView, videoParams)
        videoView = textureView
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
        // ★ v136: 自绘仪表卡片（转速/功率/续航/里程/胎压）。
        //   它取代了原来的「CarPlay Ultra 官方仪表」—— 真 Ultra 协议栈
        //   （airplay/ultra/ + airplay/rcs/）在 v136 已整体删除，
        //   仪表上的车辆信息现在全部由我们自绘。
        //   挂在最上层（同车速表）—— 仪表信息是驾驶员随时要看的。
        //   ★ v136: 它和其它卡一样是**整屏 MATCH_PARENT + 自己算几何**，
        //   位置/大小/透明度由 setGaugeCardGeometry 下发（之前没有滑块、整屏铺满）。
        gaugeCardView = ClusterGaugeCardView(context).apply { visibility = View.GONE }
        root.addView(gaugeCardView, FrameLayout.LayoutParams(-1, -1))
        // Start with the placeholder visible; the video is faded in over it.
        videoView?.alpha = if (streamActive) 1f else 0f
        waitingLabel?.apply {
            alpha = if (streamActive) 0f else 1f
            visibility = if (streamActive) View.GONE else View.VISIBLE
        }
        setContentView(root)
    }

    /**
     * B561: 等待页配色跟随夜色。
     *
     * 深色 → 黑底白字；浅色 → 保持上游那套浅底深字。
     * 单独抽出来是因为它既要用于建 presentation，也用于运行时切夜色。
     */
    private fun applyWaitingPalette(dark: Boolean) {
        val label = waitingLabel ?: return
        label.setTextColor(if (dark) Color.WHITE else Color.DKGRAY)
        label.setBackgroundColor(flatBackground)
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
        flatBackground = if (dark) DARK_FLAT_BACKGROUND else LIGHT_FLAT_BACKGROUND
        applyWaitingPalette(dark)
        rootView?.setBackgroundColor(flatBackground)
    }

    /**
     * Cross-fades the waiting placeholder over the live map.
     *
     * 视频层是 TextureView，可以直接做 alpha 淡入淡出；
     * 等待页是普通 View，叠在视频之上。
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
        video.animate().alpha(if (active) 1f else 0f).setDuration(300).start()
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

    /**
     * v136: 推自绘仪表卡片数据。
     *
     * [gauges] 为 null 或全空 → 整块隐藏。**绝不画空的表盘** ——
     * 驾驶员会以为车坏了。
     */
    fun setGaugeCard(readings: com.shilapi.xcertplay.vehicle.B561GaugeReadings?) {
        gaugeCardView?.setGauges(readings)
    }

    /** v136: 仪表卡片的位置/大小/透明度 —— 与其它卡共用同一套滑块。 */
    fun setGaugeCardGeometry(xPercent: Int, yPercent: Int, sizePercent: Int, opacityPercent: Int) {
        gaugeCardView?.setLayout(xPercent, yPercent, sizePercent)
        gaugeCardView?.setOpacity(opacityPercent)
    }

    /** v136: 仪表卡片的深浅色。 */
    fun setGaugeCardNightMode(night: Boolean) {
        gaugeCardView?.setNightMode(night)
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
         * 会枚举除主屏外的所有可用屏并记住用户选择），没有再走"唯一一块副屏"。
         * 这也让设置页里的选屏真正生效 —— 之前它只影响 UI，对投屏链路不起作用。
         *
         * ★ v136: 末尾那段"比亚迪原逻辑"已删除。
         *   `ClusterLayout.displayName()` 恒返回 null（比亚迪集成在 v100 移除），
         *   于是 `name == null` → `supported()` 也是 false → 那个分支
         *   **恒返回 null**（`firstOrNull { false }`）。整块是不可达的死代码，
         *   而且它把 BYD 屏的指纹契约写在一个永远走不到的地方 —— 删掉更诚实。
         */
        fun findDisplay(context: Context): Display? {
            // 判据要**无歧义**才认：
            // - 用户显式选过（selectedId != -1）→ 用他选的那块；
            // - 只枚举到**一块**副屏 → 没得选，直接用（车机常态：仪表是唯一外接屏）；
            // - 有多块却没选 → **不认**。绝不"挑最大的那块"硬上 ——
            //   认错屏会把导航画到 passenger display 上，比不画更糟 —— 宁缺勿画。
            //
            // ★ 为什么不能无条件 `ClusterDisplayCatalog.resolve()`：
            //   autoFollow 默认 true，它会回落到"第一个候选（分辨率最大）"，
            //   于是 "Passenger display" 960×360、乃至 1280×720 的屏都会被当成
            //   仪表屏。认错屏比不画更糟。
            val catalog = com.shilapi.xcertplay.cluster.ClusterDisplayCatalog
            val chosen = catalog.selectedId(context)
            val candidates = catalog.candidates(context)
            if (chosen != -1) {
                catalog.resolve(context)?.let { return it }
            } else if (candidates.size == 1) {
                // 唯一一块副屏 = 无歧义（车机常态：仪表是唯一外接屏）。
                // 仍要过 STATE_OFF 与 name 双重校验 —— 枚举与取用之间屏可能已拔掉。
                val picked = candidates.first()
                context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(picked.displayId)
                    ?.takeIf { it.name == picked.name && it.state != android.view.Display.STATE_OFF }
                    ?.let { return it }
            }
            return null
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
