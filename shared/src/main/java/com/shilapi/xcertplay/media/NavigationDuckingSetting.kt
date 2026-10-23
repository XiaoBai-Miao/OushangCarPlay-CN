package com.shilapi.xcertplay.media

import android.content.Context

/**
 * ★ v124 B561: 「导航播报压低媒体声音」开关。
 *
 * ## 与 [NavigationAudioFocus] 的区别（两个开关，别搞混）
 *
 * | | [NavigationAudioFocus] | 本开关 |
 * |---|---|---|
 * | 名字 | 导航播报**是否申请音频焦点** | 导航播报时**压低媒体** |
 * | 谁决定压低 | **车机音频策略**（我们申请焦点，它决定 duck 还是 pause） | **我们自己**（直接调 `AudioTrack.setStereoVolume`） |
 * | 可控性 | 不可控 —— 不同 ROM 行为差别很大 | **可控** —— 所有车机行为一致 |
 * | 默认 | 关（不申请焦点，导航叠加不打断） | 关（保持现有行为不变） |
 *
 * 两者**可以同时开**：申请焦点让车机也 duck，我们再压一层。
 * 但通常只开一个就够了 —— 单开本开关就能达到「导航说话时音乐变小」的效果。
 *
 * ## 为什么读盘而不是内存字段
 *
 * 焦点与压低都发生在 sink 的音频线程里，而 sink 可能早于设置页打开。
 * 每次判定时读一次 SharedPreferences（小文件读，可忽略）最省事，
 * 也不会出现「设置改了但没生效」的失同步。
 */
object NavigationDuckingSetting {
    private const val PREFS = "diplay_navigation_ducking"
    private const val KEY_ENABLED = "enabled"

    /** 默认 **false** —— 现有行为（导航叠加在媒体上不打断）保持不变。 */
    fun isEnabled(context: Context?): Boolean {
        val app = context?.applicationContext ?: return false
        return runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }.getOrDefault(false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }
    }
}
