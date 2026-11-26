package com.e8.navhud

import android.content.Intent

/**
 * Immutable snapshot of media playback metadata broadcast by WeCarFlow to NavHUD.
 */
data class HudMediaPayload(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val coverUri: String = "",
    val coverBytes: ByteArray? = null,
    val isPlaying: Boolean = false
) {
    val hasTrack: Boolean
        get() = title.isNotBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HudMediaPayload) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            coverUri == other.coverUri &&
            isPlaying == other.isPlaying &&
            ((coverBytes == null && other.coverBytes == null) ||
                (coverBytes != null && other.coverBytes != null && coverBytes.contentEquals(other.coverBytes)))
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + coverUri.hashCode()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        result = 31 * result + isPlaying.hashCode()
        return result
    }

    companion object {
        const val ACTION_GEELY_NAVHUD_UPDATE = "com.geely.navhud.metadata_update"
        const val ACTION_NAVHUD_REQUEST_METADATA = "com.geely.navhud.request_metadata"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_COVER = "cover"
        const val EXTRA_COVER_BYTES = "cover_bytes"
        const val EXTRA_PLAYING = "playing"

        fun fromIntent(intent: Intent): HudMediaPayload {
            val rawBytes = intent.getByteArrayExtra(EXTRA_COVER_BYTES)?.takeIf { it.isNotEmpty() }
            return HudMediaPayload(
                title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty(),
                artist = intent.getStringExtra(EXTRA_ARTIST)?.trim().orEmpty(),
                album = intent.getStringExtra(EXTRA_ALBUM)?.trim().orEmpty(),
                coverUri = intent.getStringExtra(EXTRA_COVER)?.trim().orEmpty(),
                coverBytes = rawBytes,
                isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false)
            )
        }
    }
}

/**
 * UI target sink for displaying track metadata and album artwork on the HUD overlay.
 */
interface HudMusicSink<T> {
    fun showTrackText(title: String, artist: String)
    fun applyArt(bitmap: T?)
    fun clearMusicDisplay()
}

/**
 * Manages track state and race-safe artwork updates strictly from WeCarFlow broadcasts.
 */
class HudMediaController<T>(
    private val sink: HudMusicSink<T>,
    private val decodeBytes: (ByteArray) -> T?,
    private val loadUriAsync: (uri: String, onResult: (T?) -> Unit) -> Unit
) {
    private var currentArtToken = 0
    private var lastTrackKey = ""
    private var lastArtKey = ""
    private var hasAppliedArt = false
    private var lastPayload: HudMediaPayload? = null

    fun onMediaUpdate(payload: HudMediaPayload) {
        if (!payload.hasTrack) {
            lastPayload = null
            clearState()
            return
        }

        lastPayload = payload
        val trackKey = "${payload.title}|${payload.artist}"
        val trackChanged = trackKey != lastTrackKey
        lastTrackKey = trackKey

        sink.showTrackText(payload.title, payload.artist)

        val artKey = computeArtKey(payload)
        if (artKey == "none") {
            currentArtToken++
            lastArtKey = "none"
            hasAppliedArt = false
            sink.applyArt(null)
            return
        }

        if (artKey == lastArtKey && hasAppliedArt) {
            return
        }

        val token = ++currentArtToken

        val bytes = payload.coverBytes
        if (bytes != null && bytes.isNotEmpty()) {
            val decoded = decodeBytes(bytes)
            if (decoded != null) {
                lastArtKey = "bytes:${bytes.contentHashCode()}"
                hasAppliedArt = true
                sink.applyArt(decoded)
                return
            }
        }

        val uri = payload.coverUri
        if (uri.isNotBlank()) {
            if (trackChanged && hasAppliedArt) {
                hasAppliedArt = false
                sink.applyArt(null)
            }
            loadUriAsync(uri) { loaded ->
                if (token != currentArtToken) {
                    return@loadUriAsync
                }
                if (loaded != null) {
                    lastArtKey = "uri:$uri"
                    hasAppliedArt = true
                    sink.applyArt(loaded)
                } else {
                    lastArtKey = "none"
                    hasAppliedArt = false
                    sink.applyArt(null)
                }
            }
            return
        }

        lastArtKey = "none"
        hasAppliedArt = false
        sink.applyArt(null)
    }

    fun replayLastState() {
        val cached = lastPayload
        if (cached == null || !cached.hasTrack) {
            sink.clearMusicDisplay()
            return
        }
        lastArtKey = ""
        hasAppliedArt = false
        onMediaUpdate(cached)
    }

    fun clear() {
        lastPayload = null
        clearState()
    }

    private fun clearState() {
        currentArtToken++
        lastTrackKey = ""
        lastArtKey = ""
        hasAppliedArt = false
        sink.clearMusicDisplay()
    }

    private fun computeArtKey(payload: HudMediaPayload): String {
        val bytes = payload.coverBytes
        if (bytes != null && bytes.isNotEmpty()) {
            return "bytes:${bytes.contentHashCode()}"
        }
        if (payload.coverUri.isNotBlank()) {
            return "uri:${payload.coverUri}"
        }
        return "none"
    }
}
