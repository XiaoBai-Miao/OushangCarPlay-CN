package com.shilapi.xcertplay.compat

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * One audio focus request that works on every supported Android version.
 * API 26+ uses [AudioFocusRequest]. Android 7.x uses the stream-based calls, whose callbacks are
 * moved to [handler] so that both paths deliver them on the same thread.
 */
class AudioFocusRequestCompat(
    val gain: Int,
    val attributes: AudioAttributes,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    private val handler: Handler,
    /**
     * ★ v106 B561: 是否加`FLAG_AUDIBILITY_ENFORCED`（强制有声）。
     *
     * 默认 **false** —— 不传任何 flag，行为与 v105 及更早完全一致。
     *
     * 依据：实车 `dumpsys audio` 焦点事件日志里原厂音乐 `com.chinatsp.music`
     * 请求焦点时 `flags=0x1`，而我们一直是 `flags=0x0`（Builder 一个 flag 都没设）。
     * 该 flag 声明「我的音频必须出声，别静音/duck 掉」。
     */
    private val enforcedAudibility: Boolean = false,
) {
    private val platformRequest: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
            // ★ v106: flag 必须在 build() 之后设 —— Builder 没有 requestFlags 通道，
            // AOSP 是在 AudioFocusRequest 上加的 setter。
            .also { if (enforcedAudibility) applyAudibilityEnforced(it) }
    } else {
        null
    }

    private val legacyListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (Looper.myLooper() == handler.looper) listener.onAudioFocusChange(change)
        else handler.post { listener.onAudioFocusChange(change) }
    }

    /**
     * 尽力给已build 的 [request] 加上 audibility-enforced 标记，失败静默。
     *
     * ## 为什么用反射
     *
     * `AudioManager.FLAG_AUDIBILITY_ENFORCED`(0x1) 与
     * `AudioFocusRequest.setRequestFlags()` 在 AOSP 里都是 **API 31 引入**，
     * 而 B561 是 **API 28** ⇒ 编译期根本不存在这些符号。
     *
     * 但车机的 audioserver 是厂商**反向移植**过的（实证：20:15 日志里
     * Android 9 的 `dumpsys audio` 打出了 Android 12 才有的
     * `AUDIO_USAGE_ASSISTANT` / `NOTIFICATION_COMMUNICATION_*` 常量名）。
     * 所以运行时**试一次**平台是否被移植过；没有就静默跳过。
     *
     * 开关打开而平台不支持时，本方法什么都不做 —— 最多「维持现状」，不会崩。
     */
    private fun applyAudibilityEnforced(request: Any) {
        if (request !is AudioFocusRequest) return
        runCatching {
            // 路径 ①：若平台已移植 FLAG_AUDIBILITY_ENFORCED 常量则直接用
            val field = AudioManager::class.java.getField("FLAG_AUDIBILITY_ENFORCED")
            val flag = field.getInt(null)
            // 路径 ②：API 31+ 的 Builder.setForceDucking 之外的 requestFlags setter
            val setter = AudioFocusRequest::class.java.methods.firstOrNull {
                it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.name.startsWith("set") &&
                    it.name.contains("Flag", ignoreCase = true)
            }
            if (setter != null) {
                setter.invoke(request, flag)
                android.util.Log.i("AudioFocusCompat", "audibility enforced set via setter, flag=$flag")
            } else {
                android.util.Log.i(
                    "AudioFocusCompat",
                    "audibility enforced requested but platform has no setter (API ${android.os.Build.VERSION.SDK_INT}), skipped",
                )
            }
        }.onFailure {
            android.util.Log.w("AudioFocusCompat", "applyAudibilityEnforced failed: ${it.message}")
        }
    }

    fun request(manager: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestAudioFocus(platformRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(legacyListener, legacyStreamType(attributes.usage), gain)
        }

    fun abandon(manager: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.abandonAudioFocusRequest(platformRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(legacyListener)
        }

    internal companion object {
        /** The stream Android 7.x derives from these usages for its own focus bookkeeping. */
        fun legacyStreamType(usage: Int): Int = when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
            AudioAttributes.USAGE_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
    }
}
