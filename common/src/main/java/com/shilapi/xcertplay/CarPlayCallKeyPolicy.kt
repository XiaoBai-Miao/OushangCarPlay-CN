package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.media.CarPlayCallCard

/**
 * 方向盘的通话按键在 CarPlay 通话中的行为（移植自 v64 车验版，原 hud/CarPlayCallKeyPolicy.kt）。
 *
 * 没有 CarPlay 通话时一律 PASS —— 按键原样留给车机，车机自己的蓝牙电话不受影响。
 *
 * 支持的按键（B561 未知车机方控具体码，按通用 + 比亚迪两套收口，命中即生效）：
 * - 接听：[KeyEvent.KEYCODE_CALL] / 比亚迪 313 / 耳机钩子 [KeyEvent.KEYCODE_HEADSETHOOK]
 * - 挂断：[KeyEvent.KEYCODE_ENDCALL] / 比亚迪 314 / 多功能键 309
 *
 * 耳机钩子是标准语义：**响铃时接听、通话中挂断**；接听键在通话中被消费
 * （不开车机电话应用盖在 CarPlay 上，也不误挂断）；挂断键消费按下、动作在抬起。
 */
internal object CarPlayCallKeyPolicy {
    const val KEYCODE_BYD_DIAL_ANSWER = 313
    const val KEYCODE_BYD_HANG_UP = 314
    const val KEYCODE_BYD_MULTIPLEXING = 309

    enum class Action { PASS, CONSUME, ANSWER, END }

    fun isAnswerKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_DIAL_ANSWER || keyCode == KeyEvent.KEYCODE_CALL

    fun isEndKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENDCALL ||
            keyCode == KEYCODE_BYD_HANG_UP || keyCode == KEYCODE_BYD_MULTIPLEXING

    /**
     * CarPlay 会话中（[session]）且 iPhone 有通话（[call]）时的一个按键事件。
     * 两半次按键都接住，动作在抬起时执行（与车机方控的 down/up 成对语义一致）。
     */
    fun onKey(keyCode: Int, down: Boolean, call: CarPlayCallCard?, session: Boolean): Action {
        if (!session || call == null) return Action.PASS
        // 耳机钩子：响铃接听、通话挂断。
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (down) return Action.CONSUME
            return if (call.phase == CarPlayCallCard.Phase.RINGING) Action.ANSWER else Action.END
        }
        return when {
            isAnswerKey(keyCode) -> when {
                down -> Action.CONSUME
                call.phase == CarPlayCallCard.Phase.RINGING -> Action.ANSWER
                else -> Action.CONSUME
            }
            isEndKey(keyCode) -> if (down) Action.CONSUME else Action.END
            else -> Action.PASS
        }
    }
}
