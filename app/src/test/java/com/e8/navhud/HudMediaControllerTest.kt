package com.e8.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudMediaControllerTest {

    private class FakeSink : HudMusicSink<String> {
        var title: String = ""
        var artist: String = ""
        var art: String? = null
        var clearCount: Int = 0
        val artHistory = mutableListOf<String?>()

        override fun showTrackText(title: String, artist: String) {
            this.title = title
            this.artist = artist
        }

        override fun applyArt(bitmap: String?) {
            this.art = bitmap
            artHistory.add(bitmap)
        }

        override fun clearMusicDisplay() {
            title = ""
            artist = ""
            art = null
            clearCount++
            artHistory.add(null)
        }
    }

    @Test
    fun clearsPreviousArtWhenNextTrackHasNoCover() {
        val sink = FakeSink()
        val controller = HudMediaController(
            sink = sink,
            decodeBytes = { bytes -> "bytes_${bytes.size}" },
            loadUriAsync = { uri, onResult -> onResult("uri_$uri") }
        )

        // Track 1 (Spotify) had art.
        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Believer",
                artist = "Imagine Dragons",
                coverUri = "https://img/believer.jpg",
                isPlaying = true
            )
        )
        assertEquals("uri_https://img/believer.jpg", sink.art)

        // Switched to BT track without cover URL or cover bytes: old cover MUST vanish.
        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Smells Like Teen Spirit",
                artist = "Nirvana",
                coverUri = "",
                coverBytes = null,
                isPlaying = true
            )
        )
        assertEquals("Smells Like Teen Spirit", sink.title)
        assertNull(sink.art)
    }

    @Test
    fun clearsPreviousArtWhenSameTrackLosesCover() {
        val sink = FakeSink()
        val controller = HudMediaController(
            sink = sink,
            decodeBytes = { "bytes_art" },
            loadUriAsync = { _, onResult -> onResult(null) }
        )

        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Track A",
                artist = "Artist A",
                coverBytes = byteArrayOf(1, 2, 3),
                isPlaying = true
            )
        )
        assertEquals("bytes_art", sink.art)

        // Same title/artist updated with cleared art must not retain stale bitmap.
        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Track A",
                artist = "Artist A",
                coverUri = "",
                coverBytes = null,
                isPlaying = false
            )
        )
        assertNull(sink.art)
    }

    @Test
    fun emptyTitleClearsMusicDisplayAndCancelsInFlightUriLoad() {
        val sink = FakeSink()
        val pendingCallbacks = mutableListOf<(String?) -> Unit>()
        val controller = HudMediaController(
            sink = sink,
            decodeBytes = { "bytes_art" },
            loadUriAsync = { _, cb -> pendingCallbacks.add(cb) }
        )

        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Slow Cover Track",
                artist = "Artist",
                coverUri = "https://slow.example/cover.jpg",
                isPlaying = true
            )
        )

        // Track stops / disconnected before slow cover finishes loading.
        controller.onMediaUpdate(HudMediaPayload(title = "", isPlaying = false))
        assertEquals(1, sink.clearCount)
        assertNull(sink.art)

        // Late async callback for stopped track must be ignored.
        pendingCallbacks.single().invoke("late_bmp")
        assertNull(sink.art)
        assertEquals("", sink.title)
    }

    @Test
    fun prioritizesCoverBytesAndFallsBackToUriWhenDecodeFails() {
        val sink = FakeSink()
        var shouldDecodeFail = false
        val controller = HudMediaController(
            sink = sink,
            decodeBytes = { if (shouldDecodeFail) null else "decoded_jpeg_${it.size}" },
            loadUriAsync = { uri, cb -> cb("fallback_$uri") }
        )

        controller.onMediaUpdate(
            HudMediaPayload(
                title = "USB Song",
                artist = "Local Artist",
                coverUri = "file:///storage/emulated/0/Music/.wecarflow_covers/cover_1.jpg",
                coverBytes = byteArrayOf(1, 2, 3, 4),
                isPlaying = true
            )
        )
        assertEquals("decoded_jpeg_4", sink.art)

        shouldDecodeFail = true
        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Radio Track",
                artist = "FM",
                coverUri = "https://cdn.radio/cover.jpg",
                coverBytes = byteArrayOf(9, 9),
                isPlaying = true
            )
        )
        assertEquals("fallback_https://cdn.radio/cover.jpg", sink.art)
    }

    @Test
    fun cancelsStaleTrackAsyncCoverWhenNextTrackArrives() {
        val sink = FakeSink()
        val pendingByUri = mutableMapOf<String, (String?) -> Unit>()
        val controller = HudMediaController(
            sink = sink,
            decodeBytes = { null },
            loadUriAsync = { uri, cb -> pendingByUri[uri] = cb }
        )

        controller.onMediaUpdate(
            HudMediaPayload(title = "Track 1", artist = "A", coverUri = "https://1.jpg", isPlaying = true)
        )
        controller.onMediaUpdate(
            HudMediaPayload(title = "Track 2", artist = "B", coverUri = "https://2.jpg", isPlaying = true)
        )

        // Late Track 1 art must be dropped.
        pendingByUri.getValue("https://1.jpg").invoke("bmp_1")
        assertNull(sink.art)

        // Track 2 art applies cleanly.
        pendingByUri.getValue("https://2.jpg").invoke("bmp_2")
        assertEquals("bmp_2", sink.art)
    }

    @Test
    fun replayLastStateRestoresMetadataAndArtAfterScreenOnOverlayRecreate() {
        val sink = FakeSink()
        val controller = HudMediaController(
            sink = sink,
            decodeBytes = { "replayed_bytes" },
            loadUriAsync = { _, cb -> cb(null) }
        )

        controller.onMediaUpdate(
            HudMediaPayload(
                title = "Houdini",
                artist = "Dua Lipa",
                coverBytes = byteArrayOf(7, 8, 9),
                isPlaying = false
            )
        )
        sink.title = ""
        sink.art = null

        controller.replayLastState()
        assertEquals("Houdini", sink.title)
        assertEquals("Art should reload on overlay recreate", "replayed_bytes", sink.art)
    }
}
