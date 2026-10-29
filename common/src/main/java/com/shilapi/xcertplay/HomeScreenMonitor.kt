package com.shilapi.xcertplay

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Whether a home screen is in front (the default launcher, such as a third-party car launcher),
 * from the newest resumed activity in the owner's Usage Access events.
 *
 * v142: the centre-screen dashboard map and launcher map sharing were removed, so the live
 * foreground monitoring (start/stop/poll) no longer has a consumer. Only the Usage-Access
 * detection and home-package queries are still used, by [UsbPermissionSetup] and the
 * permissions/setup pages.
 */
internal object HomeScreenMonitor {

    // BYD's home list (Launcher3 HomeHelper): MyCar, the normal home, and the map home.
    val HOME_PACKAGES = setOf("com.android.launcher3")

    // Known third-party car launchers
    val KNOWN_CAR_LAUNCHERS = setOf(
        "com.smg.dydesktop",          // 迪友桌面 (常见主流包名)
        "com.smg.dydesktop.pro",      // 迪友桌面 Pro
        "com.dy.launcher",            // 迪友桌面 (部分渠道)
        "com.king.dyzm",              // 迪友桌面
        "com.king.diyou",
        "com.byd.diyou",
        "com.dudu.android.launcher",  // 嘟嘟桌面
        "com.dudu.android.launcher.mini",
        "com.tencent.autolauncher",   // 腾讯车联
        "com.mx.launcher",            // 喵驾桌面
    )

    // Accessibility alone is not a foreground source: this PR has no service dispatching events.
    fun hasAccess(context: Context): Boolean = runCatching {
        // v100: 通用 usage-access 探测(替代原 BYD Usage Access 监控):有事件数据即视为已授权。
        val usm = context.getSystemService(UsageStatsManager::class.java)
        run {
            // UsageEvents 是一次性游标:有事件即视为已授权。
            val events = usm?.queryEvents(0, System.currentTimeMillis())
            // UsageEvents 是只读游标:有事件即视为已授权(hasNextEvent 不消费它)。
            events?.let { runCatching { it.hasNextEvent() }.getOrDefault(false) } == true
        }
    }.getOrDefault(false)

    /** Query all launcher packages declared on the system. */
    fun queryHomePackages(context: Context): Set<String> {
        val set = (HOME_PACKAGES + KNOWN_CAR_LAUNCHERS).toMutableSet()
        defaultHome(context)?.let { set.add(it) }
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            for (info in list) {
                val pkg = info.activityInfo?.packageName
                if (!pkg.isNullOrEmpty() && pkg != "android" && pkg != context.packageName) {
                    set.add(pkg)
                }
            }
        }
        return set
    }

    /** The launcher Android uses as home now, unless that is the chooser or DiPlay itself. */
    fun defaultHome(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" && it != context.packageName }
}