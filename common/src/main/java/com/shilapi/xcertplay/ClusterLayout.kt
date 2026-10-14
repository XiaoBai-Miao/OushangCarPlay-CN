package com.shilapi.xcertplay

import android.content.Context
import android.os.Build

/**
 * v100: B561 仪表布局替身 —— 原 `DiLink51ClusterLayout` 只服务比亚迪 DiLink 集群
 * (按 `Build.FINGERPRINT` 门控)，B561 上 `supported()` 恒 false，布局永远走
 * "纯色全屏"路径([ClusterMapPresentation] 里 `plan == null` 分支)。
 *
 * 保留 API 外形是为了让集群呈现与卡片的几何/主题代码原样工作；
 * 计划几何([plan])在 B561 上不产出，显示屏选择走 [com.shilapi.xcertplay.cluster.ClusterDisplayCatalog]。
 */
internal object ClusterLayout {
    enum class Theme(val label: String) {
        SCENARIO("scenario"),
        MAP("map"),
        SIMPLE("simple"),
    }

    data class Plan(val left: Int, val top: Int, val width: Int, val height: Int, val fullMap: Boolean) {
        val sourceLeft: Int get() = (STREAM_WIDTH - width) / 2
        val sourceTop: Int get() = STREAM_HEIGHT - height
    }

    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    /** B561 不是 DiLink 车机 —— 恒 false。 */
    fun supported(fingerprint: String = Build.FINGERPRINT): Boolean = false

    fun theme(context: Context): Theme = Theme.SCENARIO

    fun automatic(context: Context): Boolean = false

    fun dark(context: Context, theme: Theme = theme(context)): Boolean = false

    fun plan(width: Int, height: Int, theme: Theme): Plan? = null

    /** BYD 屏名匹配在 B561 上不适用 —— 恒 null(显示屏选择走 ClusterDisplayCatalog)。 */
    fun displayName(names: List<String>, fingerprint: String, theme: Theme): String? = null

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
