package com.shilapi.xcertplay.media

import android.content.Context

/**
 * ★ v105 B561: 导航声道是否申请音频焦点。
 *
 * ## 为什么这是个开关
 *
 * 默认**不申请**（沿用上游行为）。`AndroidMediaSink.requestAudioFocus()` 里
 * `if (channel == AudioChannel.NAVIGATION) return` —— 导航语音刻意不抢焦点，
 * 这样它能**叠加**在媒体上而不打断（注释原话："overlays media without ducking it"）。
 *
 * 10-14 实测（`日志_10月14日19时49分23秒877.log`）：三路导航声音
 * （CarPlay + 原车地图 + 后装高德）同时响，后两者都能在主驾发声并跟随车机导航音量/声道，
 * 靠的就是「导航不抢焦点」+ `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`(=12) 与原车同通道。
 *
 * 但有些车机/场景需要反过来：
 *   - 车机音频策略要求「有导航播报时压低/暂停其他音频」→ 需要申请焦点
 *   - 导航语音被别的 App 抢走后听不见 → 申请焦点能夺回
 *
 * 所以做成开关，而不是写死。
 */
object NavigationAudioFocus {
    private const val PREFS = "diplay_navigation_audio_focus"
    private const val KEY_REQUESTS_FOCUS = "requests_focus"

    /**
     * 默认 **false**（不申请焦点）—— 与上游一致，保证「导航叠加在媒体上」这个既有行为不变。
     *
     * 读的是 SharedPreferences 而不是内存字段：焦点请求发生在
     * [AndroidMediaSink] 的 track 生命周期里，而 sink 可能早于设置页打开，
     * 每次请求时读盘最省事也最不会失同步（一次小文件读，可忽略）。
     */
    fun requestsFocus(context: Context?): Boolean {
        val app = context?.applicationContext ?: return false
        return runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_REQUESTS_FOCUS, false)
        }.getOrDefault(false)
    }

    fun saveRequestsFocus(context: Context, enabled: Boolean) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REQUESTS_FOCUS, enabled)
                .apply()
        }
    }
}