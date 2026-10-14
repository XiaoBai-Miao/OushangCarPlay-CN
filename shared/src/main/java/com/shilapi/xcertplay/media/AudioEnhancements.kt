package com.shilapi.xcertplay.media

import android.content.Context

/**
 * ★ v106 B561: 音频增强开关组（三项独立，默认全关）。
 *
 * ## 为什么做成开关而不是直接改
 *
 * 14 日晚逆向 `CarService.apk`（`com.android.car.CarAudioService`）拿到了 B561 的
 * **完整音量分组表**（`res/xml/car_volume_groups.xml` + `USAGE_TO_CONTEXT`）：
 *
 * | usage | context | group |
 * |---|---|---|
 * | 1(USAGE_MEDIA，我们的音乐) | MUSIC | **1** |
 * | 12(NAVIGATION_GUIDANCE，我们的导航) | NAVIGATION | **0** |
 *
 * 这与 20:15 实测日志 `usage=1→groupId1` / `usage=12→groupId0` 完全一致 ——
 * **我们一直用对了 usage，分组从一开始就是对的。**
 *
 * 实测「播音乐后导航音量失去跟随」的根因不是分组错误，而是车机自己切到了
 * `MixType: 3` 并判定 `the same source = 13`，把后续`adjustVolume` 全导向
 * source 13 混音总线（对应 `CarAudioService` 里的 `HANDLE_MEDIA_FADE_IN`）。
 *
 * 这些都是**车机侧策略**，改之前必须能一键退回 v105 的行为，所以全部做成开关。
 */
object AudioEnhancements {
    private const val PREFS = "diplay_audio_enhancements"

    private const val KEY_ENFORCED_AUDIBILITY = "enforced_audibility"
    private const val KEY_NAV_VOLUME_FOLLOW = "nav_volume_follow"
    private const val KEY_GROUP_VOLUME_VERBOSE = "group_volume_verbose"

    /**
     * 强制有声（`AudioFocusRequest.FLAG_AUDIBILITY_ENFORCED`）。
     *
     * 依据：`dumpsys audio` 实车焦点事件日志里，原厂音乐
     * `com.chinatsp.music` 请求焦点时 `flags=0x1`，而我们是 `flags=0x0`
     * —— 我们`AudioFocusRequestCompat.Builder` 一个 flag 都没设。
     *
     * 该 flag 声明「我的音频必须出声，别把我静音/duck 掉」。加它可能让我们
     * 的音乐与播报不被车机静音策略压制。**默认关**，先观察。
     */
    fun enforcedAudibility(context: Context?): Boolean = read(context, KEY_ENFORCED_AUDIBILITY)

    /**
     * 导航音量跟随纠正（依赖 CarAudioManager）。
     *
     * 开启后：当音乐流启动（车机很可能切MixType）时，读取导航组当前音量并
     * 写回，抵消车机把音量导向 source 13 的动作。
     *
     * **默认关** —— 写车机音量有副作用（详见 [NavVolumeFollower] 的说明），
     * 必须由用户确认车机音量表现正常后再开。
     */
    fun navVolumeFollow(context: Context?): Boolean = read(context, KEY_NAV_VOLUME_FOLLOW)

    /** 打印 CarAudio 分组表与音量细节（诊断用）。默认关。 */
    fun groupVolumeVerbose(context: Context?): Boolean = read(context, KEY_GROUP_VOLUME_VERBOSE)

    fun saveEnforcedAudibility(context: Context, enabled: Boolean) = write(context, KEY_ENFORCED_AUDIBILITY, enabled)
    fun saveNavVolumeFollow(context: Context, enabled: Boolean) = write(context, KEY_NAV_VOLUME_FOLLOW, enabled)
    fun saveGroupVolumeVerbose(context: Context, enabled: Boolean) = write(context, KEY_GROUP_VOLUME_VERBOSE, enabled)

    private fun read(context: Context?, key: String): Boolean {
        val app = context?.applicationContext ?: return false
        return runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
        }.getOrDefault(false)
    }

    private fun write(context: Context, key: String, enabled: Boolean) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, enabled).apply()
        }
    }
}