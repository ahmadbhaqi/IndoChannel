package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class IdlixProviderTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `dedicated series catalog hint survives missing contentType field`() {
        val node = mapper.readTree(
            """
            {
              "title": "The East Palace",
              "slug": "the-east-palace-2026",
              "posterPath": "/east-palace.jpg"
            }
            """.trimIndent()
        )

        val result = IdlixParser.searchResult(IdlixProvider(), node, "series")

        assertEquals(
            "https://z2.idlixku.com/series/the-east-palace-2026",
            result?.url
        )
    }

    @Test
    fun `playback data is scoped to the current IDLIX content page`() {
        val id = "f7b4b404-606f-4380-b0d8-51030729942b"
        val page = "https://z2.idlixku.com/movie/ghost-board-2026"
        val encoded = IdlixParser.encodePlayback("movie", id, page)

        assertEquals(
            IdlixParser.PlaybackRequest("movie", id, page),
            IdlixParser.decodePlayback(encoded, "https://z2.idlixku.com")
        )
        assertNull(
            IdlixParser.decodePlayback(
                IdlixParser.encodePlayback("movie", id, "https://evil.example/movie/ghost-board-2026"),
                "https://z2.idlixku.com"
            )
        )
        assertNull(IdlixParser.decodePlayback("https://z2.idlixku.com/movie/ghost-board-2026", "https://z2.idlixku.com"))
    }

    @Test
    fun `master parser carries the signed token to video and audio playlists`() {
        val masterUrl = "https://e2e.majorplay.net/v/z2/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val manifest = IdlixParser.masterManifest(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="/v/z2/video-id/p/key/audio.json",GROUP-ID="audio"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
            /v/z2/video-id/p/key/video-1080.json
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="audio"
            /v/z2/video-id/p/key/video-720.json
            """.trimIndent(),
            masterUrl,
            maxHeight = 720
        )

        assertEquals(1, manifest?.streams?.size)
        assertEquals(720, manifest?.streams?.single()?.height)
        assertEquals(
            "https://e2e.majorplay.net/v/z2/video-id/p/key/video-720.json" +
                "?t=signed-token&pm=browser",
            manifest?.streams?.single()?.url
        )
        assertEquals(
            listOf(
                "https://e2e.majorplay.net/v/z2/video-id/p/key/audio.json" +
                    "?t=signed-token&pm=browser"
            ),
            manifest?.audioUrls
        )
    }

    @Test
    fun `player keeps the signed parent master so native HLS can select audio`() = runBlocking {
        val masterUrl = "https://e2e.majorplay.net/v/z5/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val manifest = IdlixParser.masterManifest(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            /v/z5/video-id/p/key/video-720.json
            """.trimIndent(),
            masterUrl,
            maxHeight = 1080
        )!!

        val link = newIdlixMasterLink(
            source = "IDLIX",
            masterUrl = masterUrl,
            pageUrl = "https://z2.idlixku.com/movie/example",
            quality = manifest.streams.maxOf { it.height },
            headers = mapOf("User-Agent" to "fixture")
        )

        assertEquals(masterUrl, link.url)
        assertEquals(ExtractorLinkType.M3U8, link.type)
        assertTrue(link.audioTracksCompat().isEmpty())
    }

    @Test
    fun `playback request inherits master tokens only inside the trusted video path`() {
        val masterUrl = "https://e2e.majorplay.net/v/z5/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"

        assertEquals(
            "https://g2.akademivo.website/v/z5/video-id/p/key/audio.json" +
                "?t=signed-token&pm=browser",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://g2.akademivo.website/v/z5/video-id/p/key/audio.json"
            )
        )
        assertEquals(
            "https://g2.akademivo.website/v/z5/other-id/p/key/audio.json",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://g2.akademivo.website/v/z5/other-id/p/key/audio.json"
            )
        )
        assertEquals(
            "https://evil.example/v/z5/video-id/p/key/audio.json",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://evil.example/v/z5/video-id/p/key/audio.json"
            )
        )
    }

    @Test
    fun `master parser rejects HTML and foreign playback hosts`() {
        assertNull(
            IdlixParser.masterManifest(
                "<html><title>Just a moment...</title></html>",
                "https://e2e.majorplay.net/v/z2/id/config-1.json?t=token",
                720
            )
        )
        assertNull(
            IdlixParser.masterManifest(
                "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nhttps://evil.example/video.m3u8",
                "https://e2e.majorplay.net/v/z2/id/config-1.json?t=token",
                720
            )
        )
        assertFalse(IdlixParser.isTrustedRedeemUrl("https://evil.example/api/play"))
        assertTrue(IdlixParser.isTrustedRedeemUrl("https://e2e.majorplay.net/api/play"))
    }

    @Test
    fun `series parser emits only published episodes with video`() {
        val season = mapper.readTree(
            """
            {
              "episodes": [
                {
                  "id": "c1d62dd4-1cfd-4a5f-b053-e9c11ac9f3cb",
                  "episodeNumber": 1,
                  "name": "Episode 1",
                  "overview": "Ready",
                  "stillPath": "/still-one.jpg",
                  "isPublished": true,
                  "hasVideo": true
                },
                {
                  "id": "28b823da-356c-4019-8e55-3e4c6c55baef",
                  "episodeNumber": 2,
                  "name": "Episode 2",
                  "isPublished": true,
                  "hasVideo": false
                }
              ]
            }
            """.trimIndent()
        )

        val episodes = IdlixParser.episodes(
            IdlixProvider(),
            season,
            1,
            "https://z2.idlixku.com/series/the-east-palace-2026"
        )

        assertEquals(1, episodes.size)
        assertEquals(1, episodes.single().episode)
        assertEquals(1, episodes.single().season)
        assertEquals("Ready", episodes.single().description)
        assertEquals(
            IdlixParser.PlaybackRequest(
                "episode",
                "c1d62dd4-1cfd-4a5f-b053-e9c11ac9f3cb",
                "https://z2.idlixku.com/series/the-east-palace-2026"
            ),
            IdlixParser.decodePlayback(episodes.single().data, "https://z2.idlixku.com")
        )
    }
}
