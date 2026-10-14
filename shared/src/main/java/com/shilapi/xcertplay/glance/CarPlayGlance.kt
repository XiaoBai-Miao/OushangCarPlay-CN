package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.guidance.RouteGuidanceState
import com.shilapi.xcertplay.guidance.ClusterSongState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) and the song from NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session.
 */
object CarPlayGlance {
    /** One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route. */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        val song: String? = null,
        val playing: Boolean = false,
        /** v100 B561: 未拼接的歌名/歌手/专辑(仪表媒体卡三行显示用)。 */
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
    )

    private val route = RouteGuidanceState()
    private val song = ClusterSongState()
    private var connected = false
    private var last = Snapshot()

    /** Called with each new snapshot, on the thread that changed it. */
    @Volatile var listener: ((Snapshot) -> Unit)? = null

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let { listener?.invoke(it) }
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        val changed = synchronized(this) {
            when (frame.messageId) {
                RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE ->
                    runCatching { route.accept(frame.messageId, frame.payload) }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                else -> return
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (!next) {
                route.clear()
                song.clear()
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    private fun publishLocked(): Snapshot? {
        val maneuver = route.currentApple()
        val current = song.current()
        val next = Snapshot(
            connected = connected,
            maneuverType = maneuver?.type,
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            song = current?.text,
            playing = current?.playing ?: false,
            title = current?.title,
            artist = current?.artist,
            album = current?.album,
        )
        if (next == last) return null
        last = next
        return next
    }
}
