package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * 「导航播报压低媒体声音」的集成级测试。
 *
 * 验证的是 [AudioFocusCoordinator] 的真实行为（用 mock AudioTrack 记录音量写入），
 * 而不只是 [NavigationDucking] 的纯逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [25, 28, 33],
    manifest = Config.NONE,
    shadows = [NavigationMediaDuckingTest.VolumeTrackingAudioTrack::class],
)
class NavigationMediaDuckingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val tracks = mutableListOf<AudioTrack>()
    private val coordinators = mutableListOf<AudioFocusCoordinator>()

    @Before fun reset() {
        VolumeTrackingAudioTrack.volumes.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun cleanup() {
        coordinators.forEach { coordinator -> tracks.forEach(coordinator::release) }
        tracks.forEach(AudioTrack::release)
        VolumeTrackingAudioTrack.volumes.clear()
    }

    @Test fun navigationPromptLowersMediaVolume() {
        val coordinator = coordinator(ducking = true)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        // 还没播报时媒体是满音量 —— 注意首次 acquire **不会**写 setStereoVolume
        // （Entry.appliedVolume 初值就是 FULL_VOLUME，AudioTrack 默认即满音量）。
        assertTrue(volumes(media).isEmpty())

        // 导航开始播报。
        coordinator.onNavigationVoiceFlowing(1_000L)
        assertTrue(coordinator.isMediaDuckedForNavigation())
        // 只写一次 —— 从满音量压到 DUCKED_VOLUME。
        // （首次 acquire 不写 1.0：Entry.appliedVolume 初值即 FULL_VOLUME，
        //   AudioTrack 默认就是满音量。）
        assertEquals(listOf(NavigationDucking.DUCKED_VOLUME), volumes(media))
    }

    @Test fun mediaVolumeIsRestoredAfterTheHoldWindow() {
        val coordinator = coordinator(ducking = true)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.onNavigationVoiceFlowing(1_000L)
        assertEquals(NavigationDucking.DUCKED_VOLUME, volumes(media).last())

        // 保持时间内：仍是压低的。
        coordinator.refreshDuckState(1_000L + NavigationDucking.HOLD_MILLIS - 1)
        assertTrue(coordinator.isMediaDuckedForNavigation())
        assertEquals(NavigationDucking.DUCKED_VOLUME, volumes(media).last())

        // 保持时间到期：恢复。
        coordinator.refreshDuckState(1_000L + NavigationDucking.HOLD_MILLIS)
        assertFalse(coordinator.isMediaDuckedForNavigation())
        assertEquals(1f, volumes(media).last())
        // 序列：压低 → 恢复
        assertEquals(
            listOf(NavigationDucking.DUCKED_VOLUME, 1f),
            volumes(media),
        )
    }

    @Test fun explicitStopRestoresMediaImmediately() {
        val coordinator = coordinator(ducking = true)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.onNavigationVoiceFlowing(1_000L)
        assertTrue(coordinator.isMediaDuckedForNavigation())

        // 导航渲染器明确结束 → 立刻恢复，不等保持时间。
        coordinator.onNavigationAudioStopped()
        assertFalse(coordinator.isMediaDuckedForNavigation())
        assertEquals(1f, volumes(media).last())
        assertEquals(listOf(NavigationDucking.DUCKED_VOLUME, 1f), volumes(media))
    }

    @Test fun duckingStaysOffWhenTheSettingIsOff() {
        val coordinator = coordinator(ducking = false)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.onNavigationVoiceFlowing(1_000L)
        assertFalse(coordinator.isMediaDuckedForNavigation())
        // 音量一次都没被改过。
        assertTrue(volumes(media).isEmpty())
    }

    @Test fun onlyMediaIsLowered() {
        val coordinator = coordinator(ducking = true)
        val media = track(); val phone = track(); val assistant = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        coordinator.acquire(assistant, AudioChannel.ASSISTANT, attributes)
        coordinator.onNavigationVoiceFlowing(1_000L)
        // 只有媒体被压低；通话与助理语音必须始终可听。
        assertTrue(volumes(phone).isEmpty())
        assertTrue(volumes(assistant).isEmpty())
        assertEquals(NavigationDucking.DUCKED_VOLUME, volumes(media).last())
    }

    @Test fun mediaTracksAcquiredDuringDuckingStartLowered() {
        val coordinator = coordinator(ducking = true)
        val first = track()
        coordinator.acquire(first, AudioChannel.MEDIA, attributes)
        coordinator.onNavigationVoiceFlowing(1_000L)

        // 播报期间新起的媒体轨道也要是压低的 —— 否则会出现「老音乐小了、新音乐还是大」。
        val second = track()
        coordinator.acquire(second, AudioChannel.MEDIA, attributes)
        assertEquals(NavigationDucking.DUCKED_VOLUME, volumes(second).last())
    }

    @Test fun userStreamVolumeIsNeverChanged() {
        val coordinator = coordinator(ducking = true)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val userVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        coordinator.onNavigationVoiceFlowing(1_000L)
        // 只改渲染器的相对增益，不动 Android 的用户流音量。
        assertEquals(userVolume, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    private fun coordinator(ducking: Boolean) =
        AudioFocusCoordinator(context, true, true).also { coordinator ->
            coordinator.navigationDuckingEnabled = ducking
            coordinators.add(coordinator)
        }

    private fun track(): AudioTrack = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also(tracks::add)

    private fun volumes(track: AudioTrack): List<Float> =
        VolumeTrackingAudioTrack.volumes[track].orEmpty()

    @Implements(AudioTrack::class)
    class VolumeTrackingAudioTrack {
        @RealObject private lateinit var track: AudioTrack

        @Implementation fun setStereoVolume(left: Float, right: Float): Int {
            volumes.getOrPut(track) { mutableListOf() }.add(left)
            return AudioTrack.SUCCESS
        }

        companion object { val volumes = linkedMapOf<AudioTrack, MutableList<Float>>() }
    }
}
