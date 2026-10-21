package com.shilapi.xcertplay.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B561 v118: 「路口放大图画不出来」修复的回归测试。
 *
 * 走完整条 iAP2 链路（`0x5201` 路线更新 + `0x5202` maneuver + `0x5204` 车道），
 * 覆盖三条曾经被绑在一起的通道如今各自独立：
 *
 * 1. 只有 `0x5202` 路口几何（没有 `0x5204`）→ 路口图必须出
 * 2. `0x5204` 解析失败 → 路口图仍然出（不连坐）
 * 3. `0x12 showing` 从 false 翻成 0 → 只收车道带，路口图照出
 *
 * 另外固定 v118 放宽的 `showing` 门禁：从没见过 `showing == 1` 之前，
 * `0x5201` 的 `0x12` 一律当未知，不能把解析成功的车道数据挡死。
 *
 * 需要 Robolectric：[RouteGuidanceState.parseLaneInfo] 走 `android.util.Log`
 * （实车定论 `0x5204` 布局靠的就是那些日志）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class RouteGuidanceStateJunctionTest {

    // ---- TLV 构造助手 --------------------------------------------------------

    private fun tlv(type: Int, value: ByteArray): ByteArray {
        val length = value.size + 4
        return u16(length) + u16(type) + value
    }

    private fun u16(value: Int): ByteArray = byteArrayOf(
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun i16(value: Int): ByteArray = u16(value and 0xffff)

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    /** `0x5201` 路线级更新。 */
    private fun routeUpdate(
        state: Int = 1,
        distanceMeters: Int = 180,
        firstManeuver: Int? = 0,
        laneIndex: Int? = null,
        laneShowing: Int? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(tlv(0x01, byteArrayOf(state.toByte())).toList())
        out.addAll(tlv(0x0a, u32(distanceMeters.toLong())).toList())
        if (firstManeuver != null) out.addAll(tlv(0x0d, u16(firstManeuver)).toList())
        if (laneIndex != null) out.addAll(tlv(0x10, u16(laneIndex)).toList())
        if (laneShowing != null) out.addAll(tlv(0x12, byteArrayOf(laneShowing.toByte())).toList())
        return out.toByteArray()
    }

    /** `0x5202` 单条 maneuver + 路口几何。 */
    private fun maneuver(
        index: Int = 0,
        type: Int,
        drivingSide: Int = 0,
        junctionType: Int = 0,
        junctionAngles: List<Int> = emptyList(),
        exitAngle: Int? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(tlv(0x01, u16(index)).toList())
        out.addAll(tlv(0x03, byteArrayOf(type.toByte())).toList())
        out.addAll(tlv(0x08, byteArrayOf(drivingSide.toByte())).toList())
        out.addAll(tlv(0x09, byteArrayOf(junctionType.toByte())).toList())
        if (junctionAngles.isNotEmpty()) {
            val items = ArrayList<Byte>()
            junctionAngles.forEach { angle ->
                items.addAll(u16(6).toList()) // 子 TLV 长：4 头 + 2 角度
                items.addAll(u16(0x01).toList()) // 子 TLV 型：JunctionElementAngle
                items.addAll(i16(angle).toList())
            }
            out.addAll(tlv(0x0a, items.toByteArray()).toList())
        }
        if (exitAngle != null) out.addAll(tlv(0x0b, i16(exitAngle)).toList())
        return out.toByteArray()
    }

    /**
     * `0x5204` 严格布局载荷：`0x01` u16 事件序号 + `0x02` 车道记录容器。
     * 记录为 `u16 position | u8 status | u8 angleCount | i16 primary | i16[count] angles`。
     */
    private fun strictLaneFrame(
        eventIndex: Int,
        position: Int = 1,
        status: Int = 2, // LANE_STATUS_RECOMMENDED
        primary: Int = 0,
        angles: List<Int> = listOf(0),
    ): ByteArray {
        val record = ArrayList<Byte>()
        record.addAll(u16(position).toList())
        record.add(status.toByte())
        record.add(angles.size.toByte())
        record.addAll(i16(primary).toList())
        angles.forEach { record.addAll(i16(it).toList()) }
        return tlv(0x01, u16(eventIndex)) + tlv(0x02, record.toByteArray())
    }

    private fun feed(state: RouteGuidanceState, vararg frames: Pair<Int, ByteArray>) {
        frames.forEach { (id, payload) -> state.accept(id, payload) }
    }

    // ---- 核心回归：路口几何不再被车道数据绑架 --------------------------------

    @Test
    fun junctionShowsWithJunctionGeometryOnly() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(distanceMeters = 180),
            MANEUVER_UPDATE to maneuver(type = 1, junctionAngles = listOf(0, -90)),
        )

        val junction = state.currentJunction()
        assertNotNull("只有 0x5202 路口几何时路口图也必须出", junction)
        assertEquals(listOf(0, -90), junction!!.branches)
        assertNull("没有 0x5204 就不该有车道带", junction.lanes)
        assertTrue(junction.hasRealJunctionGeometry)
        assertEquals(180, junction.distanceMeters)
        assertEquals(ManeuverCodes.LEFT, junction.gaode)
        // 车道通道独立地保持空。
        assertNull(state.currentLaneGuidance())
    }

    @Test
    fun junctionSurvivesAnUnparsableLaneFrame() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(),
            MANEUVER_UPDATE to maneuver(type = 2, junctionAngles = listOf(0, 90)),
            // 0x5204 字节布局是推的（无实车抓包）—— 解不出来时路口图不能连坐。
            ROUTE_GUIDANCE_LANE_INFO to byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )

        val junction = state.currentJunction()
        assertNotNull("0x5204 解不出来不该拖垮已经解出来的路口几何", junction)
        assertNull(junction!!.lanes)
        assertEquals(listOf(0, 90), junction.branches)
    }

    @Test
    fun junctionCarriesLanesWhenTheLaneFrameParses() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(laneIndex = 7),
            MANEUVER_UPDATE to maneuver(type = 2, junctionAngles = listOf(0, 90)),
            ROUTE_GUIDANCE_LANE_INFO to strictLaneFrame(eventIndex = 7),
        )

        val junction = state.currentJunction()
        assertNotNull(junction)
        val lanes = junction!!.lanes
        assertNotNull("解析成功时路口图要带上车道带", lanes)
        assertEquals(1, lanes!!.laneCount)
        assertEquals(0, lanes.primaryLane)
        assertEquals(listOf(0), lanes.angles[0])
        assertEquals(ClusterLaneGuidance.LaneSource.STRICT, lanes.source)
        // 独立车道通道也拿得到同一份数据。
        assertEquals(lanes, state.currentLaneGuidance())
    }

    @Test
    fun laneFrameAloneBeforeRouteUpdateIsPickedUpAfterwards() {
        // iOS 有时先发 0x5204 再发 0x5201 指认它 —— 事件要缓存住。
        val state = RouteGuidanceState()
        state.accept(ROUTE_GUIDANCE_LANE_INFO, strictLaneFrame(eventIndex = 3))
        assertNull(state.currentLaneGuidance()) // 还没有 maneuver

        state.accept(ROUTE_GUIDANCE_UPDATE, routeUpdate())
        state.accept(MANEUVER_UPDATE, maneuver(type = 1, junctionAngles = listOf(-90)))

        assertNotNull(state.currentLaneGuidance())
        assertNotNull(state.currentJunction())
        assertEquals(3, state.currentLaneGuidance()!!.eventIndex)
    }

    // ---- showing 门禁：未见过 true 前当未知 ----------------------------------

    @Test
    fun laneGateStaysOpenUntilShowingHasBeenSeenTrue() {
        // ★ 0x5201 从不发 0x12（或一直发 0）时，不能把解析成功的车道挡死。
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(), // 没有 0x12
            MANEUVER_UPDATE to maneuver(type = 1, junctionAngles = listOf(-90)),
            ROUTE_GUIDANCE_LANE_INFO to strictLaneFrame(eventIndex = 1),
        )
        assertNotNull("没见过 showing==1 时 0x12 是未知，不是 false", state.currentLaneGuidance())
    }

    @Test
    fun showingFalseAfterTrueHidesLanesButKeepsTheJunction() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(laneShowing = 1, laneIndex = 5),
            MANEUVER_UPDATE to maneuver(type = 1, junctionAngles = listOf(0, -90)),
            ROUTE_GUIDANCE_LANE_INFO to strictLaneFrame(eventIndex = 5),
        )
        assertNotNull(state.currentLaneGuidance())

        // iOS 明说该收了 —— 车道带消失，但路口形状属于 maneuver，照出。
        state.accept(ROUTE_GUIDANCE_UPDATE, routeUpdate(laneShowing = 0, laneIndex = 5))
        assertNull(state.currentLaneGuidance())
        val junction = state.currentJunction()
        assertNotNull("车道带收了不代表路口也没了", junction)
        assertNull(junction!!.lanes)
        assertEquals(listOf(0, -90), junction.branches)
    }

    // ---- maneuver 推导与清理 -------------------------------------------------

    @Test
    fun junctionDerivesItsBranchFromTheManeuverWhenIosSendsNoAngles() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(),
            MANEUVER_UPDATE to maneuver(type = 2), // 右转，不带 0x0A
        )
        val junction = state.currentJunction()
        assertNotNull(junction)
        assertEquals(listOf(90), junction!!.branches)
        assertFalse(junction.hasRealJunctionGeometry)
    }

    @Test
    fun junctionDisappearsAfterRouteEnds() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(),
            MANEUVER_UPDATE to maneuver(type = 1, junctionAngles = listOf(-90)),
            ROUTE_GUIDANCE_LANE_INFO to strictLaneFrame(eventIndex = 1),
        )
        assertNotNull(state.currentJunction())

        state.accept(ROUTE_GUIDANCE_UPDATE, routeUpdate(state = 0)) // NoRouteSet
        assertNull(state.currentJunction())
        assertNull(state.currentLaneGuidance())
    }

    @Test
    fun staleRouteRetiresTheJunctionToo() {
        var now = 0L
        val state = RouteGuidanceState(nanoTime = { now })
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(),
            MANEUVER_UPDATE to maneuver(type = 1, junctionAngles = listOf(-90)),
        )
        assertNotNull(state.currentJunction())

        now = 31_000_000_000L // 超过 30s stale 窗口
        assertNull(state.currentJunction())
    }

    @Test
    fun outOfRangeJunctionAnglesAreDroppedAtParse() {
        val state = RouteGuidanceState()
        feed(
            state,
            ROUTE_GUIDANCE_UPDATE to routeUpdate(),
            // 0x0A 里的越界角度在 RouteGuidanceState 层就被丢掉，
            // 剩下空几何 + 左转 maneuver → 推导出 -90。
            MANEUVER_UPDATE to maneuver(type = 1, junctionAngles = listOf(400, 0)),
        )
        val junction = state.currentJunction()
        assertNotNull(junction)
        assertEquals(listOf(0), junction!!.branches)
    }

    private companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val MANEUVER_UPDATE = 0x5202
        const val ROUTE_GUIDANCE_LANE_INFO = 0x5204
    }
}
