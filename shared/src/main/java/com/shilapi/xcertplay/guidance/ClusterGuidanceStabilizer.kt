package com.shilapi.xcertplay.guidance

/**
 * B561: 转向卡数据的滞回与两段式提交。
 *
 * ## 为什么需要
 *
 * iOS 每推进 1 米就发一次 `0x5201`，距离因此**每米都变**。
 * [BydNavigationOutputs.refreshTurnOverlay] 原本用 data class 相等比较，
 * 于是每米触发一次 `invalidate()` —— 仪表上表现为箭头持续微抖、
 * 距离数字跳动，而 1 Hz 的 tick 也会把"同一状态"重复发布一次。
 *
 * 设计借鉴甘风 CarPlay（`com.ganfandw.carplay`）的 `ClusterGuidanceStabilizer`，
 * 但按我们的数据结构重写（我们多了 `junctionAngles` 与路口类型）。
 *
 * ## 三级判定，从宽松到严格
 *
 * 1. **转向本身变了**（[StabilityKey] 任一字段不同）→ 立即接受。
 *    真转向必须马上换箭头，滞回在这里是有害的。
 * 2. **只是距离变了** → 走**相对死区**：`|new − cur| > max(|new|, |cur|) / 509`。
 *    固定阈值（如"小于 5 米不更新"）在两个尺度上都不可用：
 *    5 米对 1000 米的远距离太粗（卡片半天不动），
 *    对 20 米的近距离又太细（还是每米抖）。
 *    相对死区让**显示精度与距离成比例**：1000 m 时约 2 m 一档，
 *    100 m 时约 0.2 m 一档 —— 视觉上都是"大概 1/500 的相对变化"。
 * 3. **同一个新距离连续出现两次**（[pendingDistance] 两段式提交）→ 才发布。
 *    挡住 iOS 在两个值之间来回抖动（例如 GPS 精度抖动导致 99↔100 反复）。
 *
 * ## 线程安全
 *
 * 全部状态都在 [BydNavigationOutputs] 的 `overlayLock` 内访问，
 * 本类**不做内部同步** —— 与调用方的锁约定保持一致。
 */
internal class ClusterGuidanceStabilizer {
    companion object {
        /**
         * 相对死区分母。
         *
         * 509 而不是 512：让死区略大于"距离的 1/512"，
         * 也就是**只在相对变化超过约 0.2% 时**才更新 —— 恰好落在
         * "人眼能察觉变化"与"不会被 GPS 抖动触发"之间。
         */
        private const val RELATIVE_DEADBODY_DIVISOR = 509

        /** 两段式提交需要的连续命中次数。 */
        private const val PENDING_HITS_REQUIRED = 2
    }

    /**
     * 判定"是不是同一次转向"的指纹。
     *
     * 只取**语义上换了转向就会变**的字段：[icon] 是 Apple 的 maneuver 类型、
     * [roundaboutExit] 是环岛出口号、[road] 是转向后的路名 ——
     * 任何一个变了都意味着换了一次转向，箭头必须立刻重画。
     *
     * 路口几何（`junctionType` / `junctionAngles`）不在这里：它属于
     * `ClusterLaneGuidance`，走单独的 [BydNavigationOutputs.refreshLaneOverlay]
     * 通道。转向卡的箭头形状已经由 [icon] 完整表达了。
     *
     * 也不把 `arrivalEpochSeconds` / `remainingSeconds` 算进来 ——
     * 剩余时间是**每帧都在变**的连续量（ETA 每秒刷新），算进指纹会让
     * 指纹每秒变一次，滞回完全失效。
     */
    private data class StabilityKey(
        val icon: Int,
        val roundaboutExit: Int,
        val road: String,
    )

    private var accepted: ClusterTurnGuidance? = null
    private var acceptedKey: StabilityKey? = null

    /** 两段式提交：待确认的新距离与它的命中次数。 */
    private var pendingDistance: Int? = null
    private var pendingHits = 0

    /**
     * 喂入一份**非空**的转向数据，返回**应该发布**的值（或 `null` 表示这一帧不用发布）。
     *
     * ★ "导航结束"（没有数据）**不走这里** —— 调用方必须自己处理，
     *   因为"发布 null 收卡"和"不发布"都用 `null` 表示返回值，
     *   但前者必须真的通知监听者移除卡片。收卡逻辑见
     *   `BydNavigationOutputs.refreshTurnOverlay`。
     */
    fun accept(next: ClusterTurnGuidance): ClusterTurnGuidance? {
        val current = accepted
        if (current == null) {
            commit(next)
            return next
        }

        // 1) 转向变了 —— 立即接受，不做任何死区判定。
        val key = keyOf(next)
        if (key != acceptedKey) {
            commit(next)
            return next
        }

        // 2) 只有距离变了 —— 相对死区。
        if (!withinDeadZone(current.distanceMeters, next.distanceMeters)) {
            commit(next)
            return next
        }

        // 3) 死区内：同一个新距离要连续出现两次才发布。
        if (next.distanceMeters == current.distanceMeters) {
            // 与当前值相同 —— 不是新状态，清掉待确认项（它在抖回旧值）。
            pendingDistance = null
            pendingHits = 0
            return null
        }
        if (pendingDistance == next.distanceMeters) {
            pendingHits++
        } else {
            pendingDistance = next.distanceMeters
            pendingHits = 1
        }
        if (pendingHits < PENDING_HITS_REQUIRED) return null
        commit(next)
        return next
    }

    /** 导航结束或换路线：清空全部状态。 */
    fun reset() {
        accepted = null
        acceptedKey = null
        pendingDistance = null
        pendingHits = 0
    }

    /**
     * 相对死区：`|new − cur| > max(|new|, |cur|) / 509`。
     *
     * 用 `max` 而不是 `cur`：靠近路口时距离快速趋零，`cur` 会让分母塌缩到 0，
     * 死区跟着消失、退化成每米都更新 —— 恰好在最需要稳定的阶段失去稳定。
     *
     * ## 为什么下限是 1 而不是"至少 1"
     *
     * `scale / 509` 向下取整，scale < 509 时结果是 0。若再套一层 `max(0, 1)`
     * 把下限抬到 1 m，那么 100 m → 99 m 这种**明显的近距变化**会被吃掉
     * （1 > 100/509 = 0）—— 而这正是最该立刻更新的场景。
     *
     * 所以这里**不做下限兜底**：分母为 0 时死区就是 0，语义是"距离已经贴近 0，
     * 每一米都是真变化，立即显示"。真正需要防抖的近距离区间（如 20 m → 19 m，
     * 死区 0.039 m 仍然有效）由比例本身覆盖。
     */
    private fun withinDeadZone(current: Int, next: Int): Boolean {
        val delta = kotlin.math.abs(next - current).toLong()
        val scale = maxOf(kotlin.math.abs(next.toLong()), kotlin.math.abs(current.toLong()))
        return delta <= scale / RELATIVE_DEADBODY_DIVISOR
    }

    private fun commit(next: ClusterTurnGuidance) {
        accepted = next
        acceptedKey = keyOf(next)
        pendingDistance = null
        pendingHits = 0
    }

    private fun keyOf(guidance: ClusterTurnGuidance) = StabilityKey(
        icon = guidance.icon,
        roundaboutExit = guidance.roundaboutExit,
        road = guidance.road,
    )
}
