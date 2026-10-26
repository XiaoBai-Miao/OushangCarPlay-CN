package com.shilapi.xcertplay

/**
 * v136: 仪表面板的常量与默认安全区。
 *
 * ## 历史
 *
 * v100 之前这里是一整套**比亚迪 DiLink 集群**的布局替身：按 `Build.FINGERPRINT`
 * 门控、算出仪表屏上的地图矩形（[plan]）、按屏名/指纹挑屏（[displayName]）。
 * 比亚迪集成在 v100 已被移除，这些函数全部退化成**恒 false / 恒 null** 的占位：
 *
 * ```
 * supported()   → false
 * plan()        → null
 * displayName() → null
 * automatic()   → false
 * theme()       → SCENARIO
 * ```
 *
 * 于是 [ClusterMapPresentation] 里那一大段"有 plan / 无 plan"的分支、
 * `InstrumentContrastView` 渐变层、以及选屏末尾的比亚迪 fallback
 * 全部是**不可达的死代码**。v136 把它们删掉，`ClusterLayout` 只留下
 * 真正还在用的东西：
 *
 * - [STREAM_WIDTH] / [STREAM_HEIGHT] —— 集群视频画布的逻辑尺寸（1920×720）；
 * - [defaultSafeAreaRect] —— 安全区映射编辑器的默认框。
 *
 * 选屏现在完全走 [com.shilapi.xcertplay.cluster.ClusterDisplayCatalog]
 * （枚举除主屏外的所有屏 + 用户手动选择）。
 */
internal object ClusterLayout {
    /** 集群视频画布的逻辑宽度（B561 实测 1920×720，比例 8:3）。 */
    const val STREAM_WIDTH = 1920

    /** 集群视频画布的逻辑高度。 */
    const val STREAM_HEIGHT = 720

    /**
     * 默认安全区(= 整块流)。
     *
     * 原 `DiLink4ClusterDisplay.defaultSafeAreaRect` 会去查 DiLink 4 的仪表布局表来算
     * 安全区 inset；B561 没有那套布局数据,默认给全屏,让用户自己在
     * 「安全区映射」编辑器里按实际仪表屏调。
     */
    fun defaultSafeAreaRect(
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
    ): com.shilapi.xcertplay.airplay.SafeAreaRect =
        com.shilapi.xcertplay.airplay.SafeAreaRect(0, 0, STREAM_WIDTH, STREAM_HEIGHT)
}
