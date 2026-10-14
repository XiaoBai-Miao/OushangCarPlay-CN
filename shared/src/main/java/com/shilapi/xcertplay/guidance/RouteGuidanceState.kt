package com.shilapi.xcertplay.guidance

internal data class RouteGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class AppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
)

internal enum class RouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/** Decodes the iAP2 route-guidance subset needed by the BYD windshield HUD and cluster. */
internal class RouteGuidanceState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    /**
     * The dashboard overlay keeps the last instruction across a wireless session drop: the iPhone
     * often sends NoRouteSet (0) while the tunnel is tearing down, which is not a real arrival.
     * Arrived (2) still ends the route. The overlay's own stale window retires a truly ended one.
     */
    private val keepAcrossNoRoute: Boolean = false,
) {
    private data class Maneuver(
        val type: Int,
        val drivingSide: Int,
        val afterRoad: String,
        /** B561: 0x5202 的 0x09/0x0A/0x0B —— 画"路口放大图"要用的路口几何。 */
        val junctionType: Int = 0,
        val junctionAngles: List<Int> = emptyList(),
        val junctionExitAngle: Int? = null,
    )

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null

    /**
     * B561: 最近一次 0x5204 车道数据。
     *
     * ★ 它是**独立事件**，不挂在某个 maneuver 槽位上 —— 一次转向过程中
     *   车道组可以换好几次（先 3 车道 → 临近路口变 5 车道）。
     *   所以按 iOS 给的 `eventIndex` 缓存，显示时取 `laneGuidanceCurrentIndex`
     *   指定的那一组。
     */
    private val laneEvents = mutableMapOf<Int, ClusterLaneGuidance>()

    /** 0x5201 的 0x10/0x11/0x12：当前车道事件序号 / 总数 / 是否该显示。 */
    private var laneGuidanceIndex = -1
    private var laneGuidanceShowing = false

    /**
     * B561: 上一次成功解析用的布局，用于在**变化时**才打日志。
     *
     * 只在切换时打一次（而不是每帧），否则一秒一次的 1Hz tick 会把日志刷爆。
     */
    private var lastLaneSource: ClusterLaneGuidance.LaneSource? = null

    /** 已打印的解析失败转储次数，封顶避免刷日志。 */
    private var laneInfoDumpsLogged = 0

    fun accept(messageId: Int, payload: ByteArray): RouteChange {
        // 0x5204 的容器 TLV 与 0x5201/0x5202 同构（4 字节头），但它内部
        // 还要再嵌一层，所以**不能**走下面那条 validTlvs —— 交给专用解析器。
        if (messageId == ClusterLaneGuidance.ROUTE_GUIDANCE_LANE_INFO) {
            return parseLaneInfo(payload)
        }
        if (!validTlvs(payload)) return RouteChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            else -> RouteChange.NONE
        }
    }

    fun current(): RouteGuidance? {
        val maneuver = activeManeuver() ?: return null
        val gaode = ManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return RouteGuidance(
            distanceMeters = distanceMeters,
            maneuver = ManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = roadFor(maneuver),
            arrivalEpochSeconds = arrivalEpochSeconds,
        )
    }

    /** Next maneuver as Apple sent it, for outputs with a richer icon set than the HUD. */
    fun currentApple(): AppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return AppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
        )
    }

    /**
     * B561: 当前该显示的车道指引，没有就返回 null。
     *
     * 只有 iOS 明说 `laneGuidanceShowing == 1` 才给数据 —— 它会在不该显示时
     * 主动发 0，仪表上不该留着上一组车道箭头。
     *
     * 路口几何（[ClusterLaneGuidance.junctionAngles] 等）从**当前 maneuver**
     * 取过来：车道是独立事件，但路口形状属于那一次转向。
     */
    fun currentLaneGuidance(): ClusterLaneGuidance? {
        val maneuver = activeManeuver() ?: return null
        if (!laneGuidanceShowing || laneGuidanceIndex < 0) return null
        val event = laneEvents[laneGuidanceIndex] ?: return null
        if (!event.drawable || !event.sane) return null
        // 路口几何（0x5202 的 0x09/0x0A/0x0B）只挂在 maneuver 上，0x5204 里没有，
        // 所以在这里补上；`showing` 也在这里定 —— 它来自 0x5201 的 0x12。
        return event.copy(
            showing = true,
            junctionType = maneuver.junctionType,
            junctionAngles = maneuver.junctionAngles,
            junctionExitAngle = maneuver.junctionExitAngle,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        maneuvers.clear()
        // ★ 换路线时车道缓存必须一起清 —— 否则新路线复用了旧的
        //   eventIndex 时会继承上一条路线的车道箭头。
        laneEvents.clear()
        laneGuidanceIndex = -1
        laneGuidanceShowing = false
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    private fun parseRouteUpdate(data: ByteArray): RouteChange {
        // A teardown NoRouteSet is not fresh guidance. Do not let repeated teardown frames
        // extend the retained instruction's lifetime or replace its road/arrival metadata.
        if (keepAcrossNoRoute) {
            var noRoute = false
            forEachTlv(data) { type, value, valueLength ->
                if (type == 0x01 && valueLength >= 1) noRoute = data[value] == 0.toByte()
            }
            if (noRoute) return RouteChange.NONE
        }
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) firstManeuver = u16(data, value)
                }
                // B561: 车道组的指针与显示意图（0x10/0x11/0x12）。
                type == 0x10 && valueLength >= 2 -> laneGuidanceIndex = u16(data, value)
                type == 0x12 && valueLength >= 1 -> {
                    laneGuidanceShowing = (data[value].toInt() and 0xff) == 1
                }
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route. A wireless handoff often sends
        // NoRouteSet while the session is still coming back — keep the overlay instruction then.
        if (state == 0 || state == 2) {
            return if (clear()) RouteChange.CLEAR else RouteChange.NONE
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the bridges' 1 s tick clears the outputs once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return RouteChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (state != null) routeActive = true
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        return if (current() != null) RouteChange.GUIDANCE else RouteChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): RouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        // B561: 路口几何。0x0a 是**子TLV 列表**（每条岔路一个），
        //   不是定长数组 —— 按 TLV 逐个走，别按长度硬切。
        var junctionType = 0
        var junctionAngles: List<Int> = emptyList()
        var junctionExitAngle: Int? = null
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
                id == 0x09 && valueLength >= 1 -> junctionType = data[value].toInt() and 0xff
                id == 0x0a -> junctionAngles = parseJunctionAngles(data, value, valueLength)
                id == 0x0b && valueLength >= 2 -> junctionExitAngle = i16(data, value)
            }
        }
        if (index != null && type != null) {
            maneuvers[index!!] = Maneuver(
                type!!, drivingSide, afterRoad, junctionType, junctionAngles, junctionExitAngle,
            )
        }
        return if (current() != null) RouteChange.GUIDANCE else RouteChange.NONE
    }

    /**
     * B561: 解析 0x0a JunctionElementAngle（路口各岔路的方位角）。
     *
     * 里面还是一层 TLV：每项 `u16 angle`。角度按**有符号度数**取，
     * 超出 ±180 的丢弃 —— 画成乱线比不画更糟。
     */
    private fun parseJunctionAngles(data: ByteArray, start: Int, length: Int): List<Int> {
        val angles = ArrayList<Int>()
        var offset = start
        val end = start + length
        while (offset + 4 <= end) {
            val itemLength = u16(data, offset)
            val itemType = u16(data, offset + 2)
            if (itemLength < 4 || offset + itemLength > end) break
            if (itemType == 0x01 && itemLength >= 6) {
                val angle = i16(data, offset + 4)
                if (angle in -180..180) angles.add(angle)
            }
            offset += itemLength
        }
        return angles
    }

    private fun i16(data: ByteArray, offset: Int): Int {
        val raw = u16(data, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    /**
     * B561: 收一条 0x5204 车道指引。
     *
     * 解析失败（[ClusterLaneGuidance.parse] 返回 null）时**保留旧数据**：
     * 车道事件本来就只在路口前成组发一次，中途丢一条不该让整条箭头消失。
     * 真正的清理由 [clear] 与 0x5201 的 `laneGuidanceShowing` 负责。
     *
     * ★ 失败时打原始字节 —— 车道记录的字节布局是推出来的（见
     *   [ClusterLaneGuidance.parseLanes]），第一次实车验证需要靠它钉死偏移。
     *   只打前若干条，避免一条坏帧刷爆日志。
     */
    private fun parseLaneInfo(payload: ByteArray): RouteChange {
        val parsed = ClusterLaneGuidance.parse(payload)
        if (parsed == null) {
            if (laneInfoDumpsLogged < MAX_LANE_INFO_DUMPS) {
                laneInfoDumpsLogged++
                android.util.Log.w(
                    TAG,
                    "lane guidance 0x5204 unparsed (#$laneInfoDumpsLogged): ${ClusterLaneGuidance.dump(payload)}",
                )
            }
            return RouteChange.NONE
        }
        // ★ B561: 记录是用哪套布局解出来的。
        //   严格与扁平两套解读互斥且都没有实车抓包定论（见 [ClusterLaneGuidance.LaneSource]），
        //   所以实车跑一次看这条日志就能定论：出现 flat 就说明严格布局猜错了。
        if (parsed.source != lastLaneSource) {
            lastLaneSource = parsed.source
            android.util.Log.i(
                TAG,
                "lane guidance 0x5204 source=${parsed.source} lanes=${parsed.laneCount} " +
                    "primary=${parsed.primaryLane} angles=${parsed.angles}",
            )
        }
        if (laneEvents.size >= MAX_LANE_EVENTS) {
            // 正常一轮导航只有个位数事件；到上限说明 eventIndex 没被复用
            // （换路线却没发 clear），清掉重建而不是无限增长。
            laneEvents.clear()
        }
        laneEvents[parsed.eventIndex] = parsed
        // iOS 有时先发 0x5204 再发 0x5201 指认它；这里顺手兜住"没有指认"的情况。
        if (laneGuidanceIndex < 0) laneGuidanceIndex = parsed.eventIndex
        return if (currentLaneGuidance() != null) RouteChange.GUIDANCE else RouteChange.NONE
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        private const val TAG = "DiPlay-LaneGuidance"

        /** 车道事件缓存上限；一轮导航实际只有个位数。 */
        private const val MAX_LANE_EVENTS = 16

        /** 解析失败转储的打印次数上限。 */
        private const val MAX_LANE_INFO_DUMPS = 5
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
    }
}
