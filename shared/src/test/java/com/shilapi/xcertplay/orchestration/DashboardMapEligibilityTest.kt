package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMapEligibilityTest {
    private fun eligible(url: String? = CarPlayClusterDisplay.MAP_URL, physical: Boolean = true,
        stream: Int = 1, shown: Boolean = true, closed: Boolean = false): Boolean =
        DashboardMapEligibility.permits(url, physical, stream, shown, closed)

    @Test fun requiresPhysicalOutputAndUnpausedStream() {
        assertTrue(eligible())
        assertFalse(eligible(physical = false)) // Virtual centre map alone never owns wheel volume.
        assertFalse(eligible(stream = 0))
        assertFalse(eligible(shown = false)) // stopUI retains a negotiated cluster stream.
        assertFalse(eligible(closed = true))
    }

    @Test fun supportsMapButNotOnlyATurnCard() {
        // ★ v136: 原来还放行 INSTRUMENTS（Apple 官方仪表）——
        //   那一项依赖已下线的 CarPlay Ultra 车辆状态协议，随之删除。
        assertTrue(eligible(url = CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD.url))
        assertTrue(eligible(url = CarPlayClusterDisplay.Content.MAP.url))
        assertFalse(eligible(url = CarPlayClusterDisplay.Content.TURN_CARD.url))
        assertFalse(eligible(url = null))
        assertFalse(eligible(url = "unrecognized:/content"))
    }

    @Test fun theRemovedInstrumentsContentIsNoLongerEligible() {
        // v136: "maps:/car/instrumentcluster"（官方仪表）必须不再被放行 ——
        //   那个内容项已经不存在，留着会让"已下线的 Ultra"继续占着仪表音量。
        assertFalse(eligible(url = "maps:/car/instrumentcluster"))
    }
}
