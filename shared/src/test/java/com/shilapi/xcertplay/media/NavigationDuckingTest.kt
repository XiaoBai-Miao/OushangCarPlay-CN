package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavigationDucking] 的回归测试。
 *
 * 核心场景：导航播报是**分段**的（播一段 → 停几秒 → 再播），
 * 媒体音量必须在这段空隙里保持压低，而不是忽大忽小。
 */
class NavigationDuckingTest {

    @Test
    fun `ducking applies while navigation audio is flowing`() {
        assertTrue(NavigationDucking.shouldDuck(true, lastNavigationAtMillis = 1_000L, nowMillis = 1_000L))
    }

    @Test
    fun `no ducking before any navigation audio has been seen`() {
        // lastNavigationAtMillis = 0 表示从没收到过导航音频。
        assertFalse(NavigationDucking.shouldDuck(false, lastNavigationAtMillis = 0L, nowMillis = 999_999L))
    }

    @Test
    fun `ducking holds for the configured window after the last prompt`() {
        // 最后一次播报在 10_000，播报结束后 HOLD_MILLIS 内仍然压低。
        assertTrue(
            NavigationDucking.shouldDuck(false, lastNavigationAtMillis = 10_000L, nowMillis = 10_000L),
        )
        assertTrue(
            NavigationDucking.shouldDuck(false, lastNavigationAtMillis = 10_000L, nowMillis = 10_000L + 2_999L),
        )
    }

    @Test
    fun `ducking releases once the hold window expires`() {
        assertFalse(
            NavigationDucking.shouldDuck(
                false,
                lastNavigationAtMillis = 10_000L,
                nowMillis = 10_000L + NavigationDucking.HOLD_MILLIS,
            ),
        )
        assertFalse(
            NavigationDucking.shouldDuck(
                false,
                lastNavigationAtMillis = 10_000L,
                nowMillis = 10_000L + NavigationDucking.HOLD_MILLIS + 5_000L,
            ),
        )
    }

    @Test
    fun `media volume is scaled down only while ducking`() {
        assertEquals(1.0f, NavigationDucking.mediaVolume(ducked = false, fullVolume = 1.0f), 0.0001f)
        assertEquals(
            NavigationDucking.DUCKED_VOLUME,
            NavigationDucking.mediaVolume(ducked = true, fullVolume = 1.0f),
            0.0001f,
        )
    }

    @Test
    fun `ducking scales relative to the existing media volume`() {
        // 用户本来就把音乐调到了 0.5 —— 压低时是 0.5 * 0.35，
        // 绝不能反向放大到 1.0。
        val ducked = NavigationDucking.mediaVolume(ducked = true, fullVolume = 0.5f)
        assertEquals(0.5f * NavigationDucking.DUCKED_VOLUME, ducked, 0.0001f)
        assertTrue(ducked < 0.5f)
    }

    // ── 静音 vs 有声 ──────────────────────────────────────────────────

    @Test fun `silent pcm is not treated as a navigation prompt`() {
        // 导航音频流是长驻的，播报间歇期发的是静音包。
        // 全 0 的 PCM 不能被当成「在播报」。
        val silence = ByteArray(1764)
        assertFalse(NavigationDucking.hasVoice(silence, 0, silence.size))
    }

    @Test fun `pcm with any non-zero sample counts as voice`() {
        val pcm = ByteArray(1764)
        pcm[900] = 0x01
        assertTrue(NavigationDucking.hasVoice(pcm, 0, pcm.size))
    }

    @Test fun `voice detection respects the offset and length`() {
        // 真实场景：writePcm 传的是 (data, offset, length) 的一段。
        val pcm = ByteArray(4096)
        pcm[3000] = 0x7F
        // 只看 [0, 2048) —— 声音在区间外，不该被检出。
        assertFalse(NavigationDucking.hasVoice(pcm, 0, 2048))
        // 看 [2048, 4096) —— 声音在内，应检出。
        assertTrue(NavigationDucking.hasVoice(pcm, 2048, 2048))
    }

    @Test fun `empty buffer is never voice`() {
        assertFalse(NavigationDucking.hasVoice(ByteArray(0), 0, 0))
    }
}
