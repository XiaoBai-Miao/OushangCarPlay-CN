package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the car-tested v64 tests (hud/CarPlayCallStateTest), minus the BYD name-bytes case. */
class CarPlayCallStateTest {
    private var now = 1_000L
    private val state = CarPlayCallState { now }

    private fun update(uuid: String, status: Int, name: String? = null, remote: String? = null) =
        Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            remote?.let { string(0, it) }
            name?.let { string(1, it) }
            u8(2, status)
            u8(3, 1)
            string(4, uuid)
        }

    @Test
    fun followsAnIncomingCallFromRingingToEnded() {
        assertTrue(state.accept(update("a", status = 2, name = "Mum")))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum"), state.current())

        now = 5_000L
        assertTrue(state.accept(update("a", status = 4, name = "Mum")))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 5_000L), state.current())

        // A repeated update keeps the time the call connected and changes nothing on the card.
        now = 9_000L
        assertFalse(state.accept(update("a", status = 4, name = "Mum")))
        assertTrue(state.accept(update("a", status = 0)))
        assertNull(state.current())
    }

    @Test
    fun anOutgoingCallDialsThenConnects() {
        state.accept(update("b", status = 1, remote = "+971500000000"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "+971500000000"), state.current())
        state.accept(update("b", status = 3, name = "Office"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "Office"), state.current())
        state.accept(update("b", status = 4))
        // The name sticks when a later update leaves it out.
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Office", 1_000L), state.current())
    }

    @Test
    fun aWaitingCallRingsOverTheActiveOneAndTheActiveOneReturns() {
        state.accept(update("a", status = 4, name = "Mum"))
        state.accept(update("b", status = 2, name = "Boss"))
        assertEquals(CarPlayCallCard.Phase.RINGING, state.current()?.phase)
        assertEquals("Boss", state.current()?.name)

        state.accept(update("b", status = 6))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1_000L), state.current())
    }

    @Test
    fun otherMessagesAndClearLeaveNoCall() {
        assertFalse(state.accept(Iap2Messages.buildRaw(0x5001) { u8(2, 2) }))
        assertNull(state.current())
        state.accept(update("a", status = 2, name = "Mum"))
        state.clear()
        assertNull(state.current())
    }
}
