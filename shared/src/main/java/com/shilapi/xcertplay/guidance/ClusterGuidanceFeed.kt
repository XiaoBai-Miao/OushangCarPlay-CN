package com.shilapi.xcertplay.guidance

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * v100 B561: 仪表卡片(转向卡/路口放大图)的导航数据门面。
 *
 * 替代原比亚迪版 `BydNavigationOutputs` 的**监听职责** —— 只保留 iAP2 解析与监听推送,
 * 不碰任何比亚迪输出(SOME/IP、ADB、原厂应用)。媒体卡数据走 [com.shilapi.xcertplay.glance.CarPlayGlance]。
 */
object ClusterGuidanceFeed {
    private val overlayLock = Any()
    private val laneLock = Any()

    /** 路由引导/车道解析器(0x5201/0x5202/0x5204)。 */
    private val overlayRoute = RouteGuidanceState()

    /** 转向卡滞回器:iOS 每米发一次 0x5201,原始 data class 相等比较会让箭头每米抖一次。 */
    private val stabilizer = ClusterGuidanceStabilizer()

    private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private var laneListener: ((ClusterLaneGuidance?) -> Unit)? = null

    /**
     * v118: 路口放大图监听。
     *
     * 与 [laneListener] 是**两条独立的通道**：路口几何来自 `0x5202`（挂在 maneuver 上），
     * 车道带来自 `0x5204`（独立事件）。改之前只有后者，于是 `0x5204` 解不出来时
     * 连已经解出来的路口几何也一起被隐藏 —— 那就是"路口放大图画不出来"的根因。
     */
    private var junctionListener: ((ClusterJunctionGuidance?) -> Unit)? = null
    private var publishedOverlay: ClusterTurnGuidance? = null
    private var publishedLanes: ClusterLaneGuidance? = null
    private var publishedJunction: ClusterJunctionGuidance? = null

    /** iAP2 帧入口(控制线程调用)。 */
    fun onFrame(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        // v118: 车道事件（0x5204）不改变转向卡状态，但会改变路口图里的车道带 ——
        // 改之前这里只在 RouteChange != NONE 时刷新，0x5204 单独到达时
        // 车道带要等下一次 0x5201 才更新（最长一秒），现在立刻刷。
        if (change != RouteChange.NONE || frame.messageId == ClusterLaneGuidance.ROUTE_GUIDANCE_LANE_INFO) {
            refreshTurnOverlay()
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = synchronized(overlayLock) {
            // 换监听者时重置稳定器,并让新监听者**立即**拿到当前状态 ——
            // presentation 重建时走的就是这条路径,不重置的话新卡片要等两次 tick 才出现。
            stabilizer.reset()
            val current = rawOverlay()
            if (current != null) {
                stabilizer.accept(current)
                publishedOverlay = current
            } else {
                publishedOverlay = null
            }
            current
        }
        listener?.invoke(next)
    }

    /** 车道指引监听(画"路口放大图"用)。注册时立即推一次当前值。 */
    fun setLaneGuidanceListener(listener: ((ClusterLaneGuidance?) -> Unit)?) {
        laneListener = listener
        val next = overlayRoute.currentLaneGuidance()
        synchronized(laneLock) { publishedLanes = next }
        listener?.invoke(next)
    }

    /**
     * v118: 路口放大图监听。注册时立即推一次当前值。
     *
     * 推的是 [ClusterJunctionGuidance]（路口几何 + 可选车道带），
     * 与 [setLaneGuidanceListener] 分开 —— 见 [junctionListener] 的说明。
     */
    fun setJunctionListener(listener: ((ClusterJunctionGuidance?) -> Unit)?) {
        junctionListener = listener
        val next = overlayRoute.currentJunction()
        synchronized(laneLock) { publishedJunction = next }
        listener?.invoke(next)
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val raw = rawOverlay()
            if (raw == null) {
                // 收卡路径必须**绕过**滞回:滞回返回 null 表示"这一帧不用动",
                // 而这里 raw == null 表示"要把卡收掉",两者都 null 但含义相反。
                if (publishedOverlay == null) return
                stabilizer.reset()
                publishedOverlay = null
                null
            } else {
                val stable = stabilizer.accept(raw) ?: return
                if (stable == publishedOverlay) return
                publishedOverlay = stable
                stable
            }
        }
        overlayListener?.invoke(next)
        // 车道数据的过期与转向同一个 stale 窗口,每 tick 一起收 ——
        // 只靠 0x5201/0x5204 新帧的话,"导航结束但没发新帧"时会留着旧箭头。
        refreshLaneOverlay()
        refreshJunctionOverlay()
    }

    private fun refreshLaneOverlay() {
        val next = synchronized(laneLock) {
            val current = overlayRoute.currentLaneGuidance()
            if (current == publishedLanes) return
            publishedLanes = current
            current
        }
        laneListener?.invoke(next)
    }

    /**
     * v118: 刷路口放大图。
     *
     * 与 [refreshLaneOverlay] 分开推：路口图的显隐由 `0x5202` 的几何决定，
     * 车道带只是它的一部分。两条监听都可能为 null，语义不同
     * （路口图没数据 vs 车道带没数据），不能合并成一个回调让调用方猜。
     */
    private fun refreshJunctionOverlay() {
        val next = synchronized(laneLock) {
            val current = overlayRoute.currentJunction()
            if (current == publishedJunction) return
            publishedJunction = current
            current
        }
        junctionListener?.invoke(next)
    }

    private fun rawOverlay(): ClusterTurnGuidance? = overlayRoute.currentApple()?.let { apple ->
        ClusterTurnGuidance.from(RouteGuidanceFrame.from(apple)).copy(
            arrivalEpochSeconds = apple.arrivalEpochSeconds,
            remainingSeconds = apple.remainingSeconds,
            remainingMeters = apple.remainingMeters,
        )
    }
}
