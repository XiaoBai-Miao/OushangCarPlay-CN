package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.media.CarPlayCallCard

/**
 * 方向盘按键在 CarPlay 通话中的行为（v110 移植自 v64 车验版，v113 起键位可配）。
 *
 * 没有 CarPlay 通话时一律 PASS —— 按键原样留给车机，车机自己的蓝牙电话不受影响。
 *
 * ## 键位
 *
 * 接听键与挂断键**由用户在设置里指定**（方控菜单 → 通话按键），默认：
 * - 接听：[KeyEvent.KEYCODE_MEDIA_NEXT]（**下一首**）
 * - 挂断：[KeyEvent.KEYCODE_MEDIA_PREVIOUS]（**上一首**）
 *
 * 这与上游 DiLink 3 的语义一致（响铃时下一曲接听、上一曲拒接）。之所以不用
 * `KEYCODE_CALL`：车机方控并不一定发得出标准通话键码，而下一首/上一首是
 * 方向盘上一定存在的两个键。
 *
 * ## 耳机钩子
 *
 * [KeyEvent.KEYCODE_HEADSETHOOK] 是标准语义：**响铃时接听、通话中挂断**。
 * 它只有在**没有被用户占用**（既不是接听键也不是挂断键）时才按这个语义生效 ——
 * 否则同一个键会既接听又挂断。
 */
internal object CarPlayCallKeyPolicy {
    const val KEYCODE_BYD_DIAL_ANSWER = 313
    const val KEYCODE_BYD_HANG_UP = 314
    const val KEYCODE_BYD_MULTIPLEXING = 309

    /** 默认接听键：下一首。 */
    const val DEFAULT_ANSWER_KEY = KeyEvent.KEYCODE_MEDIA_NEXT

    /** 默认挂断键：上一首。 */
    const val DEFAULT_END_KEY = KeyEvent.KEYCODE_MEDIA_PREVIOUS

    enum class Action { PASS, CONSUME, ANSWER, END }

    /**
     * 可选的预设键位。
     *
     * 除 Android 标准键码外也收了比亚迪的一套（B561 方控实测码未知，两套都给，
     * 命中哪个算哪个）。`CUSTOM` 不在枚举里 —— 自定义键位在持久化里就是一个
     * 裸 keyCode。
     */
    enum class Choice(val keyCode: Int) {
        NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
        PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        CALL(KeyEvent.KEYCODE_CALL),
        ENDCALL(KeyEvent.KEYCODE_ENDCALL),
        HEADSET_HOOK(KeyEvent.KEYCODE_HEADSETHOOK),
        PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        BYD_ANSWER(KEYCODE_BYD_DIAL_ANSWER),
        BYD_HANG_UP(KEYCODE_BYD_HANG_UP),
    }

    /** 已保存的 keyCode 属于哪个预设；不是任何预设就是用户自定义的。 */
    fun choiceOf(keyCode: Int): Choice? = Choice.entries.firstOrNull { it.keyCode == keyCode }

    /**
     * CarPlay 会话中（[session]）且 iPhone 有通话（[call]）时的一个按键事件。
     *
     * [answerKey] / [endKey] 是用户配置的接听/挂断键位。
     * 两半次按键都接住，动作在抬起时执行（与车机方控的 down/up 成对语义一致）。
     */
    fun onKey(
        keyCode: Int,
        down: Boolean,
        call: CarPlayCallCard?,
        session: Boolean,
        answerKey: Int = DEFAULT_ANSWER_KEY,
        endKey: Int = DEFAULT_END_KEY,
    ): Action {
        if (!session || call == null) return Action.PASS
        if (keyCode == answerKey) {
            return when {
                down -> Action.CONSUME
                // 通话中按接听键：消费掉（不换歌、不开车机电话应用盖在 CarPlay 上），
                // 但不挂断 —— 挂断只认挂断键。
                call.phase == CarPlayCallCard.Phase.RINGING -> Action.ANSWER
                else -> Action.CONSUME
            }
        }
        if (keyCode == endKey) {
            // 挂断键：响铃时是拒接，通话中是挂断。
            return if (down) Action.CONSUME else Action.END
        }
        // 耳机钩子没被占用时按标准语义走：响铃接听、通话挂断。
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (down) return Action.CONSUME
            return if (call.phase == CarPlayCallCard.Phase.RINGING) Action.ANSWER else Action.END
        }
        return Action.PASS
    }
}
