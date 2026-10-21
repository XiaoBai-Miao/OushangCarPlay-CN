package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v116: SuperUni 飞屏(CAST_GATE 仪表通道)开关的持久化。
 *
 * 默认必须是**开** —— v115 起我们就是先向 SuperUni 申请物理仪表通道再出图,
 * 默认关会让升级上来的车机行为突变(退回老路),而老路正是通道被占时出不去的原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SuperUniFlyScreenPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
    }

    @Test fun theChannelRequestIsOnByDefault() {
        assertTrue(AirPlayPersistence.loadSuperUniFlyScreen(context))
    }

    @Test fun theSwitchRoundTrips() {
        AirPlayPersistence.saveSuperUniFlyScreen(context, false)
        assertFalse(AirPlayPersistence.loadSuperUniFlyScreen(context))
        AirPlayPersistence.saveSuperUniFlyScreen(context, true)
        assertTrue(AirPlayPersistence.loadSuperUniFlyScreen(context))
    }

    @Test fun theSwitchIsIndependentOfTheClusterMapSwitch() {
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        AirPlayPersistence.saveSuperUniFlyScreen(context, true)
        assertTrue(AirPlayPersistence.loadSuperUniFlyScreen(context))
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        AirPlayPersistence.saveSuperUniFlyScreen(context, false)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
        assertFalse(AirPlayPersistence.loadSuperUniFlyScreen(context))
    }
}
