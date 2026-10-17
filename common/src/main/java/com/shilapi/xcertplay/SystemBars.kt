package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 安卓标准状态栏配色：**白天白底、夜间黑底**，图标颜色随之反相。
 *
 * ## 为什么需要它
 *
 * 车机是沉浸式（隐藏系统栏）的，但设置页/主界面偶尔会把系统栏拉出来，
 * 而车机固件的状态栏默认是**深色底 + 深色图标**，在白底页面上图标看不见。
 * 这里显式把两者的关系定死：
 *
 * - 白天：`statusBarColor = 白`，图标**深色**（`isAppearanceLightStatusBars = true`）
 * - 夜间：`statusBarColor = 黑`，图标**浅色**（`isAppearanceLightStatusBars = false`）
 *
 * 只设颜色不够 —— Android 10 起 `statusBarColor` 单独设可能被系统忽略，
 * 必须同时设 `isAppearanceLightStatusBars` 才会真的反色。
 *
 * ## 为什么不做"跟随系统"？
 *
 * 车机固件报的 `uiMode` 常年是 `UI_MODE_NIGHT_UNDEFINED`（见 [nightModeOrNull]），
 * 跟随系统等于永远不切。所以这里**由调用方显式传 night**，用 CarPlay 自己的
 * 昼夜判定（`CarPlayNightModeController` + 车机光线传感器），那才是真正管用的信号。
 */
internal object SystemBars {

    /** 夜间状态栏底色：纯黑，与夜间页面底色一致，避免出现一条灰边。 */
    private const val NIGHT_BAR = 0xFF000000.toInt()

    /** 白天状态栏底色：纯白。 */
    private const val DAY_BAR = 0xFFFFFFFF.toInt()

    /**
     * 按昼夜给 [activity] 的状态栏上色，并同步导航栏。
     *
     * @param night true = 夜间（黑底浅色图标）
     */
    fun apply(activity: Activity, night: Boolean) {
        val window = activity.window ?: return
        val bar = if (night) NIGHT_BAR else DAY_BAR
        // targetSdk 37 上 `statusBarColor` 已弃用，但车机是 API 28，
        // 弃用路径仍是最可靠的做法（新 API 要靠 SystemBarStyle，API 35+ 才有）。
        @Suppress("DEPRECATION")
        window.statusBarColor = bar
        @Suppress("DEPRECATION")
        window.navigationBarColor = bar

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // 白底必须配深色图标，否则白底白字看不见。这是 API 23+ 的开关。
        controller.isAppearanceLightStatusBars = !night
        // 导航栏同理：深色图标 + 白底才看得清（API 26+ 才有该开关）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            controller.isAppearanceLightNavigationBars = !night
        }
    }
}
