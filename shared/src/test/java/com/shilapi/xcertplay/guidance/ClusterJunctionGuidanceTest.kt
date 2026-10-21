package com.shilapi.xcertplay.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B561 v118: 路口放大图模型（[ClusterJunctionGuidance]）单测。
 *
 * 这个类是"路口放大图画不出来"的修复核心：把路口几何（`0x5202`，可靠）
 * 与车道带（`0x5204`，字节布局是推的）解绑。以下用例全部围绕这条分工：
 *
 * - 只有路口几何 → 必须画（改之前被车道数据挡死）
 * - 只有车道数据 → 必须画
 * - 两个都没有 → 不画（宁缺勿画）
 * - maneuver 推导只覆盖方向确定的转向，环岛/目的地不猜
 */
class ClusterJunctionGuidanceTest {

    // ---- 构造助手 ----------------------------------------------------------

    private fun junction(
        maneuverType: Int,
        drivingSide: Int = 0,
        junctionAngles: List<Int> = emptyList(),
        lanes: ClusterLaneGuidance? = null,
        junctionType: Int = 0,
    ) = ClusterJunctionGuidance(
        maneuverType = maneuverType,
        drivingSide = drivingSide,
        distanceMeters = 180,
        junctionType = junctionType,
        junctionAngles = junctionAngles,
        lanes = lanes,
    )

    private fun lanes(angles: List<List<Int>> = listOf(listOf(0), listOf(-90))) =
        ClusterLaneGuidance(
            eventIndex = 7,
            laneCount = angles.size,
            angles = angles,
            positions = angles.indices.map { it + 1 },
            primaryLane = 0,
        )

    // ---- branches: 优先 iOS 明确给的几何 ------------------------------------

    @Test
    fun branchesPreferRealJunctionGeometry() {
        val data = junction(maneuverType = 1, junctionAngles = listOf(0, -45, -90, 90))
        assertEquals(listOf(0, -45, -90, 90), data.branches)
        assertTrue(data.hasRealJunctionGeometry)
    }

    @Test
    fun branchesDeriveFromManeuverWhenIosSendsNoGeometry() {
        // 普通路口 iOS 常不发 0x0A —— 此时按 maneuver 方向推导一条。
        val data = junction(maneuverType = 2) // Apple RIGHT
        assertEquals(listOf(90), data.branches)
        assertFalse(data.hasRealJunctionGeometry)
        assertTrue(data.drawable)
    }

    @Test
    fun branchesDeriveStraightForStraightManeuver() {
        val data = junction(maneuverType = 3) // Apple STRAIGHT
        assertEquals(listOf(0), data.branches)
    }

    @Test
    fun branchesFlipUTurnWithDrivingSide() {
        // 右舵（drivingSide != 1）掉头朝左；左舵朝右。
        assertEquals(
            listOf(-180),
            junction(maneuverType = 4, drivingSide = 0).branches,
        )
        assertEquals(
            listOf(180),
            junction(maneuverType = 4, drivingSide = 1).branches,
        )
    }

    @Test
    fun branchesEmptyForGlyphlessManeuvers() {
        // 环岛 / 目的地没有单一方向 —— 推导不出来就整个不画。
        assertTrue(junction(maneuverType = 6).branches.isEmpty()) // 环岛
        assertTrue(junction(maneuverType = 24).branches.isEmpty()) // 目的地
        assertFalse(junction(maneuverType = 6).drawable)
        assertFalse(junction(maneuverType = 24).drawable)
    }

    @Test
    fun angleForManeuverCoversEveryDirectionalCode() {
        assertEquals(-90, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.LEFT))
        assertEquals(-135, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.SHARP_LEFT))
        assertEquals(-45, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.SLIGHT_LEFT))
        assertEquals(90, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.RIGHT))
        assertEquals(135, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.SHARP_RIGHT))
        assertEquals(45, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.SLIGHT_RIGHT))
        assertEquals(-180, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.U_TURN_LEFT))
        assertEquals(180, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.U_TURN_RIGHT))
        assertEquals(0, ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.STRAIGHT))
        // 没有单一方向的码一律不猜。
        assertNull(ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.ROUNDABOUT_ENTER))
        assertNull(ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.ROUNDABOUT_EXIT))
        assertNull(ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.DESTINATION))
        assertNull(ClusterJunctionGuidance.angleForManeuver(ManeuverCodes.NONE))
    }

    // ---- drawable: 三条互斥路径，这是本次修复的契约 --------------------------

    @Test
    fun drawableWithJunctionGeometryOnly() {
        // ★ 改之前这种情形整块不画 —— 0x5204 解不出来把 0x5202 一起挡死了。
        val data = junction(maneuverType = 1, junctionAngles = listOf(0, -90))
        assertNull(data.lanes)
        assertTrue(data.drawable)
        assertTrue(data.sane)
    }

    @Test
    fun drawableWithLanesOnly() {
        val data = junction(maneuverType = 6, lanes = lanes()) // 环岛不给几何，但有车道带
        assertTrue(data.drawable)
        assertEquals(emptyList<Int>(), data.branches)
    }

    @Test
    fun notDrawableWhenNeitherSourceHasContent() {
        val data = junction(maneuverType = 6)
        assertFalse(data.drawable)
    }

    @Test
    fun notDrawableWhenLanesAreEmpty() {
        val data = junction(maneuverType = 6, lanes = lanes(angles = listOf(emptyList(), emptyList())))
        assertFalse(data.drawable)
    }

    // ---- recommended / sane -------------------------------------------------

    @Test
    fun recommendedIsTheBranchClosestToStraightAhead() {
        val data = junction(maneuverType = 1, junctionAngles = listOf(-90, 20, -45, 90))
        assertEquals(20, data.recommended)
    }

    @Test
    fun recommendedFallsBackToZeroWhenBranchesEmpty() {
        val data = junction(maneuverType = 6)
        assertEquals(0, data.recommended)
    }

    @Test
    fun saneRejectsOutOfRangeJunctionAngles() {
        val data = junction(maneuverType = 1, junctionAngles = listOf(0, 200))
        assertFalse(data.sane)
    }

    @Test
    fun saneRejectsInsaneLaneAngles() {
        val bad = lanes(angles = listOf(listOf(400)))
        val data = junction(maneuverType = 1, junctionAngles = listOf(0), lanes = bad)
        assertFalse(data.sane)
        // 几何本身没越界，是车道那半边的责任。
        assertTrue(data.junctionAngles.all { it in -180..180 })
    }

    @Test
    fun saneAcceptsBoundaryAngles() {
        val data = junction(maneuverType = 1, junctionAngles = listOf(-180, 0, 180))
        assertTrue(data.sane)
    }

    // ---- 元数据 --------------------------------------------------------------

    @Test
    fun junctionKindMapsRawType() {
        assertEquals(
            ClusterLaneGuidance.JunctionKind.ROUNDABOUT,
            junction(maneuverType = 1, junctionType = 1).junctionKind,
        )
        assertEquals(
            ClusterLaneGuidance.JunctionKind.EXIT,
            junction(maneuverType = 1, junctionType = 2).junctionKind,
        )
        assertEquals(
            ClusterLaneGuidance.JunctionKind.FORK,
            junction(maneuverType = 1, junctionType = 3).junctionKind,
        )
        // 未登记的值回落普通路口，不抛异常。
        assertEquals(
            ClusterLaneGuidance.JunctionKind.NONE,
            junction(maneuverType = 1, junctionType = 99).junctionKind,
        )
    }

    @Test
    fun gaodeNormalizationForRightAndLeftHandTraffic() {
        assertEquals(ManeuverCodes.RIGHT, junction(maneuverType = 2, drivingSide = 0).gaode)
        assertEquals(ManeuverCodes.RIGHT, junction(maneuverType = 21, drivingSide = 1).gaode)
        assertEquals(ManeuverCodes.U_TURN_LEFT, junction(maneuverType = 4, drivingSide = 0).gaode)
        assertEquals(ManeuverCodes.U_TURN_RIGHT, junction(maneuverType = 4, drivingSide = 1).gaode)
    }
}
