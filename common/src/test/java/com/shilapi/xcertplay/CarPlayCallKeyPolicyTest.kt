package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.media.CarPlayCallCard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v110 B561：CarPlay 通话中的方控按键行为。核心约束是**没有 CarPlay 通话时按键一律
 * PASS** —— 车机自己的蓝牙电话必须照常拿到接听/挂断键。
 */
class CarPlayCallKeyPolicyTest {
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "张三")
    private val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "张三", 1_000L)
    private val dialing = CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "张三")

    private fun onKey(keyCode: Int, down: Boolean, call: CarPlayCallCard?, session: Boolean = true) =
        CarPlayCallKeyPolicy.onKey(keyCode, down, call, session)

    @Test fun noCallLetsEveryKeyThrough() {
        for (key in CALL_KEYS) {
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, true, null))
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, false, null))
        }
    }

    @Test fun noSessionLetsEveryKeyThrough() {
        for (key in CALL_KEYS) {
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, true, ringing, session = false))
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, false, ringing, session = false))
        }
    }

    @Test fun unrelatedKeysAlwaysPass() {
        for (key in intArrayOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 305, 289, 291)) {
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, true, ringing))
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, false, active))
        }
    }

    @Test fun answerKeyAnswersARingingCall() {
        for (key in ANSWER_KEYS) {
            assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(key, true, ringing))
            assertEquals(CarPlayCallKeyPolicy.Action.ANSWER, onKey(key, false, ringing))
        }
    }

    @Test fun answerKeyDoesNotHangUpAnActiveCall() {
        for (key in ANSWER_KEYS) {
            assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(key, true, active))
            // 通话中按接听键既不接听也不挂断，只吃掉，不开车机电话应用盖在 CarPlay 上。
            assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(key, false, active))
        }
    }

    @Test fun answerKeyDoesNotAnswerAnOutgoingCall() {
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(KeyEvent.KEYCODE_CALL, true, dialing))
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(KeyEvent.KEYCODE_CALL, false, dialing))
    }

    @Test fun endKeyEndsAnyCall() {
        for (key in END_KEYS) {
            for (call in listOf(ringing, active, dialing)) {
                assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(key, true, call))
                assertEquals(CarPlayCallKeyPolicy.Action.END, onKey(key, false, call))
            }
        }
    }

    @Test fun headsetHookFollowsTheCallPhase() {
        val hook = KeyEvent.KEYCODE_HEADSETHOOK
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(hook, true, ringing))
        assertEquals(CarPlayCallKeyPolicy.Action.ANSWER, onKey(hook, false, ringing))
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(hook, true, active))
        assertEquals(CarPlayCallKeyPolicy.Action.END, onKey(hook, false, active))
    }

    @Test fun keyClassification() {
        for (key in ANSWER_KEYS) assertEquals(true, CarPlayCallKeyPolicy.isAnswerKey(key))
        for (key in END_KEYS) assertEquals(true, CarPlayCallKeyPolicy.isEndKey(key))
        // 耳机钩子两义，既接听也挂断，所以两个分类都不收它。
        assertEquals(false, CarPlayCallKeyPolicy.isAnswerKey(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(false, CarPlayCallKeyPolicy.isEndKey(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    private companion object {
        val ANSWER_KEYS = intArrayOf(KeyEvent.KEYCODE_CALL, CarPlayCallKeyPolicy.KEYCODE_BYD_DIAL_ANSWER)
        val END_KEYS = intArrayOf(
            KeyEvent.KEYCODE_ENDCALL,
            CarPlayCallKeyPolicy.KEYCODE_BYD_HANG_UP,
            CarPlayCallKeyPolicy.KEYCODE_BYD_MULTIPLEXING,
        )
        val CALL_KEYS = ANSWER_KEYS + END_KEYS + intArrayOf(KeyEvent.KEYCODE_HEADSETHOOK)
    }
}