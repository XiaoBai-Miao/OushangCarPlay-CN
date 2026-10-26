package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterSafeAreaPersistenceTest {
    @Test fun clusterBoxDoesNotOverwriteTheMainMappingAtTheSameSize() {
        val context = RuntimeEnvironment.getApplication()
        val main = SafeAreaRect(20, 30, 1900, 700)
        val cluster = SafeAreaRect(300, 100, 1500, 620)
        AirPlayPersistence.saveSafeAreaRect(context, 1920, 720, main)
        AirPlayPersistence.saveClusterSafeAreaRect(context, cluster)
        assertEquals(main, AirPlayPersistence.loadSafeAreaRect(context, 1920, 720))
        assertEquals(cluster, AirPlayPersistence.loadClusterSafeAreaRect(context))
        AirPlayPersistence.clearClusterSafeAreaRect(context)
        assertNull(AirPlayPersistence.loadClusterSafeAreaRect(context))
        assertEquals(main, AirPlayPersistence.loadSafeAreaRect(context, 1920, 720))
    }

    @Test fun mapWithBuiltInTurnCardDefaultsButSavedChoicesRemain() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        // v136: 默认是「地图 + 自绘卡片」——
        //   原来的默认 INSTRUMENTS（Apple 官方仪表）依赖 CarPlay Ultra 车辆状态协议，
        //   那套协议栈已下线，所以默认落到我们自绘卡片的那一项。
        assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD,
            AirPlayPersistence.loadClusterContent(context))
        AirPlayPersistence.saveClusterContent(context, com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP)
        assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP,
            AirPlayPersistence.loadClusterContent(context))
    }

    @Test fun theRemovedUltraContentFallsBackToTheSelfDrawnDefault() {
        val context = RuntimeEnvironment.getApplication()
        // v136: 存量用户 prefs 里存着 "INSTRUMENTS" —— 那个枚举项已不存在，
        //   必须优雅回落到默认值，不能抛异常也不能返回 null。
        //   通过公开 API 写入一个已失效的名字来模拟这个状态。
        AirPlayPersistence.saveClusterContentByName(context, "INSTRUMENTS")
        assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD,
            AirPlayPersistence.loadClusterContent(context))
    }

}
