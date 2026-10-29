package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeScreenMonitorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun knownCarLaunchersContainDiYouDesktop() {
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.smg.dydesktop"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.smg.dydesktop.pro"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.dy.launcher"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.king.dyzm"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.dudu.android.launcher"))
    }

    @Test
    fun queryHomePackagesIncludesTheResolvedDefaultHome() {
        val packages = HomeScreenMonitor.queryHomePackages(context)
        // The static BYD/third-party launcher set is always part of the result.
        assertTrue(packages.containsAll(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS))
        // The environment's actual default home (if any) is merged in.
        val default = HomeScreenMonitor.defaultHome(context)
        if (default != null) {
            assertTrue(packages.contains(default))
        }
    }

    @Test
    fun defaultHomeResolvesToALauncherWhenPresent() {
        // Robolectric may not configure a default home resolver; it must not crash and may return null.
        val home = HomeScreenMonitor.defaultHome(context)
        if (home != null) {
            assertTrue(home.isNotBlank())
        }
    }

    @Test
    fun unrelatedAccessibilityServiceDoesNotClaimForegroundAccess() {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                context.packageName, android.app.AppOpsManager.MODE_IGNORED)
        android.provider.Settings.Secure.putString(context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/UnrelatedService")
        assertFalse(HomeScreenMonitor.hasAccess(context))
    }
}