package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.media.CarPlayCallCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v113：通话按键策略。
 *
 * 默认接听键 = **下一首**（KEYCODE_MEDIA_NEXT 87）、挂断键 = **上一首**
 * （KEYCODE_MEDIA_PREVIOUS 88），与上游 DiLink 3 的语义一致。
 * 键位可由用户在「方控」菜单里改成任意 keyCode。
 */
class CarPlayCallKeyPolicyTest {
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "张三")
    private val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "张三", 1_000L)
    private val dialing = CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "张三")

    private val next = KeyEvent.KEYCODE_MEDIA_NEXT
    private val previous = KeyEvent.KEYCODE_MEDIA_PREVIOUS

    private fun onKey(
        keyCode: Int,
        down: Boolean,
        call: CarPlayCallCard?,
        session: Boolean = true,
        answerKey: Int = next,
        endKey: Int = previous,
    ) = CarPlayCallKeyPolicy.onKey(keyCode, down, call, session, answerKey, endKey)

    @Test fun defaultsAreNextAndPrevious() {
        assertEquals(next, CarPlayCallKeyPolicy.DEFAULT_ANSWER_KEY)
        assertEquals(previous, CarPlayCallKeyPolicy.DEFAULT_END_KEY)
    }

    @Test fun noCallLetsEveryKeyThrough() {
        for (key in intArrayOf(next, previous, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HEADSETHOOK)) {
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, true, null))
            assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(key, false, null))
        }
    }

    @Test fun noSessionLetsEveryKeyThrough() {
        for (key in intArrayOf(next, previous, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HEADSETHOOK)) {
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
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(next, true, ringing))
        assertEquals(CarPlayCallKeyPolicy.Action.ANSWER, onKey(next, false, ringing))
    }

    @Test fun answerKeyDoesNotHangUpAnActiveCall() {
        // 通话中按接听键：消费掉（不换歌、不开车机电话应用盖在 CarPlay 上），但不挂断。
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(next, true, active))
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(next, false, active))
    }

    @Test fun answerKeyDoesNotAnswerAnOutgoingCall() {
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(next, true, dialing))
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(next, false, dialing))
    }

    @Test fun endKeyEndsAnyCall() {
        for (call in listOf(ringing, active, dialing)) {
            assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(previous, true, call))
            assertEquals(CarPlayCallKeyPolicy.Action.END, onKey(previous, false, call))
        }
    }

    @Test fun customKeysAreHonoured() {
        // 用户把接听绑到 313（比亚迪接听键）、挂断绑到 314。
        assertEquals(
            CarPlayCallKeyPolicy.Action.CONSUME,
            onKey(313, true, ringing, answerKey = 313, endKey = 314),
        )
        assertEquals(
            CarPlayCallKeyPolicy.Action.ANSWER,
            onKey(313, false, ringing, answerKey = 313, endKey = 314),
        )
        assertEquals(
            CarPlayCallKeyPolicy.Action.CONSUME,
            onKey(314, true, ringing, answerKey = 313, endKey = 314),
        )
        assertEquals(
            CarPlayCallKeyPolicy.Action.END,
            onKey(314, false, ringing, answerKey = 313, endKey = 314),
        )
        // 换了键位之后，原来的下一首/上一首就只是普通键了。
        assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(next, true, ringing, answerKey = 313, endKey = 314))
        assertEquals(CarPlayCallKeyPolicy.Action.PASS, onKey(previous, true, ringing, answerKey = 313, endKey = 314))
    }

    @Test fun headsetHookFollowsTheCallPhaseWhenUnclaimed() {
        val hook = KeyEvent.KEYCODE_HEADSETHOOK
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(hook, true, ringing))
        assertEquals(CarPlayCallKeyPolicy.Action.ANSWER, onKey(hook, false, ringing))
        assertEquals(CarPlayCallKeyPolicy.Action.CONSUME, onKey(hook, true, active))
        assertEquals(CarPlayCallKeyPolicy.Action.END, onKey(hook, false, active))
    }

    @Test fun headsetHookYieldsToAnExplicitMapping() {
        // 用户把耳机钩子指定成挂断键：它就只挂断，不再兼做接听。
        val hook = KeyEvent.KEYCODE_HEADSETHOOK
        assertEquals(
            CarPlayCallKeyPolicy.Action.CONSUME,
            onKey(hook, true, ringing, answerKey = next, endKey = hook),
        )
        assertEquals(
            CarPlayCallKeyPolicy.Action.END,
            onKey(hook, false, ringing, answerKey = next, endKey = hook),
        )
    }

    @Test fun choiceLookup() {
        assertEquals(CarPlayCallKeyPolicy.Choice.NEXT, CarPlayCallKeyPolicy.choiceOf(next))
        assertEquals(CarPlayCallKeyPolicy.Choice.PREVIOUS, CarPlayCallKeyPolicy.choiceOf(previous))
        assertEquals(
            CarPlayCallKeyPolicy.Choice.HEADSET_HOOK,
            CarPlayCallKeyPolicy.choiceOf(KeyEvent.KEYCODE_HEADSETHOOK),
        )
        // 比亚迪那套也是预设（313/314），真正自定义的键不在任何预设里。
        assertEquals(CarPlayCallKeyPolicy.Choice.BYD_ANSWER, CarPlayCallKeyPolicy.choiceOf(313))
        assertEquals(CarPlayCallKeyPolicy.Choice.BYD_HANG_UP, CarPlayCallKeyPolicy.choiceOf(314))
        assertNull(CarPlayCallKeyPolicy.choiceOf(999))
    }
}
