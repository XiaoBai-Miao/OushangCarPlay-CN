package com.shilapi.xcertplay.guidance

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dashboard song; [line] preserves title-only HUD text, [source] selects a note's music icon. */
internal data class ClusterSong(
    val text: String,
    val playing: Boolean,
    val line: String = text,
    val source: Int? = null,
    /**
     * B561: 歌手名，独立于拼好的 [text]。
     *
     * [text] 是 `"歌名 — 歌手"` 的单行拼接（比亚迪仪表只显示一行）；
     * 我们的悬浮媒体卡要**分三行**显示，所以需要原始的 [artist]、[title] 与 [album]。
     * 为空表示这次没带 —— 那就少画一行，不画空行。
     */
    val artist: String? = null,
    /** B561: 歌名（未拼接）。 */
    val title: String? = null,
    /**
     * B561 v69: 专辑名（iAP2 `MediaItemAttributes` 字段 6）。
     *
     * 媒体卡第三行。它是**独立字段**，不是从歌名/歌手里切出来的 ——
     * iAP2 的 MediaItemAttributes 只有 title(1) / album(6) / artist(12)
     * 三个文本字段，没有第二个歌手。
     */
    val album: String? = null,
)

/**
 * The CarPlay song for the dashboard, from iAP2 NowPlayingUpdate (0x5001): title (1), album (6) and
 * artist (12) in MediaItemAttributes, playback status in PlaybackAttributes. Updates carry only what
 * changed; omitted fields retain their previous values, while an explicitly cleared title forgets the item.
 */
internal class ClusterSongState {
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var playing = false
    private var last: ClusterSong? = null

    /** Updates the cached card; null can mean unchanged or cleared, so consumers compare [current]. */
    fun accept(frame: Iap2Frame): ClusterSong? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) {
                    artist = null
                    // ★ v69: 换歌时专辑也一起清 —— 否则上一首的专辑名会留在新歌下面。
                    album = null
                }
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
                // ★ v69: 专辑名（字段 6），媒体卡第三行。
                runCatching { item.optionalString(ALBUM) }.getOrNull()?.let { album = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = text(title, artist)?.let {
            ClusterSong(
                it, playing, title!!.trim(),
                artist = artist?.trim()?.takeIf { a -> a.isNotEmpty() },
                title = title?.trim(),
                album = album?.trim()?.takeIf { a -> a.isNotEmpty() },
            )
        }
        if (next == last) return null
        last = next
        return next
    }

    /** The card for the song known so far, if any. */
    fun current(): ClusterSong? = last

    /** The session ended: forget the song. */
    fun clear() {
        title = null
        artist = null
        album = null
        playing = false
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        /** B561 v69: 专辑名 —— 媒体卡第三行。 */
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4

        /** The dashboard takes at most 255 bytes of UTF-16LE. */
        const val MAX_TEXT_BYTES = 255

        /** "Title — Artist", shortened to what the dashboard takes; null without a title. */
        fun text(title: String?, artist: String?): String? {
            val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name — $it" } ?: name
            var end = full.length
            while (full.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_TEXT_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(full[end])) end--
            }
            return full.substring(0, end)
        }
    }
}
