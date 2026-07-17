package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BysePlayerParserTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `derives same-origin API URL from first embed path segment`() {
        assertEquals(
            "https://bysebuho.com/api/videos/mjhbffdhls01",
            BysePlayerParser.apiUrl(
                "https://bysebuho.com/e/mjhbffdhls01/lk21-nonton-avatar-fire-and-ash-2025?autoplay=1"
            )
        )
        assertEquals(
            "https://mirror.example:8443/api/videos/current_01",
            BysePlayerParser.apiUrl("https://mirror.example:8443/player/e/current_01/title")
        )
        assertNull(BysePlayerParser.apiUrl("https://bysebuho.com/e/%2e%2e/title"))
        assertNull(BysePlayerParser.apiUrl("javascript:alert(1)"))
    }

    @Test
    fun `decrypts URL-safe AES-GCM envelope and preserves HLS metadata`() {
        val plaintext = mapper.writeValueAsBytes(
            mapOf(
                "sources" to listOf(
                    mapOf(
                        "url" to "https://media.example/current/master.m3u8",
                        "mime_type" to "application/vnd.apple.mpegurl",
                        "label" to "Full HD",
                        "quality" to "1080p",
                        "height" to 1080
                    ),
                    mapOf("url" to "ftp://media.example/rejected.mp4", "height" to 720)
                ),
                "tracks" to listOf(
                    mapOf(
                        "file" to "https://media.example/subtitles/id.vtt",
                        "kind" to "captions",
                        "label" to "Indonesia",
                        "srclang" to "id"
                    )
                )
            )
        )
        val parsed = BysePlayerParser.playback(envelope(plaintext, version = 6))

        assertEquals(
            listOf(
                ByseMediaSource(
                    url = "https://media.example/current/master.m3u8",
                    mimeType = "application/vnd.apple.mpegurl",
                    label = "Full HD",
                    quality = "1080p",
                    height = 1080
                )
            ),
            parsed?.sources
        )
        assertTrue(parsed!!.sources.single().isHls)
        assertEquals(
            listOf(
                ByseTrack(
                    url = "https://media.example/subtitles/id.vtt",
                    kind = "captions",
                    label = "Indonesia",
                    language = "id"
                )
            ),
            parsed.tracks
        )
    }

    @Test
    fun `fails closed for tampering invalid versions and oversized source arrays`() {
        val valid = envelope("{\"sources\":[],\"tracks\":[]}".toByteArray(), version = 17)
        val tamperedRoot = mapper.readTree(valid)
        val payload = tamperedRoot.path("playback").path("payload").asText()
        val replacement = if (payload.last() == 'A') 'B' else 'A'
        (tamperedRoot.path("playback") as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("payload", payload.dropLast(1) + replacement)
        assertNull(BysePlayerParser.playback(tamperedRoot.toString()))

        val invalidVersion = mapper.readTree(valid)
        (invalidVersion.path("playback") as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("version", "21")
        assertNull(BysePlayerParser.playback(invalidVersion.toString()))

        val tooManySources = mapper.writeValueAsBytes(
            mapOf("sources" to List(101) { mapOf("url" to "https://media.example/$it.mp4") })
        )
        assertNull(BysePlayerParser.playback(envelope(tooManySources, version = 1)))
        assertFalse(BysePlayerParser.sources("{}").isNotEmpty())
    }

    @Test
    fun `link resolution session turns a Byse embed into HLS`() = runBlocking {
        val playerUrl = "https://bysebuho.com/e/current01/movie-title"
        val apiUrl = "https://bysebuho.com/api/videos/current01"
        val apiJson = envelope(
            mapper.writeValueAsBytes(
                mapOf(
                    "sources" to listOf(
                        mapOf(
                            "url" to "https://media.example/current/master.m3u8",
                            "mime_type" to "application/vnd.apple.mpegurl",
                            "label" to "720p",
                            "height" to 720
                        )
                    ),
                    "tracks" to listOf(
                        mapOf(
                            "url" to "https://media.example/current/id.vtt",
                            "kind" to "captions",
                            "label" to "",
                            "language" to "id"
                        ),
                        mapOf(
                            "url" to "https://media.example/current/chapters.vtt",
                            "kind" to "chapters",
                            "label" to "Chapters"
                        ),
                        mapOf(
                            "url" to "http://127.0.0.1/private.vtt",
                            "kind" to "captions",
                            "label" to "Private"
                        )
                    )
                )
            ),
            version = 17
        )
        val requests = mutableListOf<Pair<String, String?>>()
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val subtitles = mutableListOf<com.lagradost.cloudstream3.SubtitleFile>()
        val session = LinkResolutionSession(
            api = IndoxxiProvider(),
            subtitleCallback = subtitles::add,
            callback = links::add,
            pageFetcher = { url, referer ->
                error("Generic page fetcher should not run: $url from $referer")
            },
            byseApiFetcher = { url, referer ->
                requests += url to referer
                if (url == apiUrl) apiJson else error("Unexpected request: $url")
            },
            extractorLoader = { _, _, _, _ -> error("Generic extractor should not run") }
        )

        assertTrue(session.resolve(playerUrl, "https://provider.example/movie"))
        assertEquals(listOf<Pair<String, String?>>(apiUrl to playerUrl), requests)
        assertEquals(1, subtitles.size)
        assertEquals("id", subtitles.single().lang)
        assertEquals(1, links.size)
        assertEquals("https://media.example/current/master.m3u8", links.single().url)
        assertEquals(playerUrl, links.single().referer)
        assertEquals(720, links.single().quality)
        assertEquals("Byse • 720p", links.single().name)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
        assertEquals(playerUrl, links.single().headers["Referer"])
    }

    private fun envelope(plaintext: ByteArray, version: Int): String {
        val parts = List(30) { index ->
            ByteArray(16) { offset -> ((index + 1) * 7 + offset).toByte() }
        }
        val key = parts[version - 1] + parts[31 - version - 1]
        val iv = ByteArray(12) { (it * 11 + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return mapper.writeValueAsString(
            mapOf(
                "playback" to mapOf(
                    "algorithm" to "AES-256-GCM",
                    "iv" to encoder.encodeToString(iv),
                    "payload" to encoder.encodeToString(cipher.doFinal(plaintext)),
                    "key_parts" to parts.map(encoder::encodeToString),
                    "version" to version.toString()
                )
            )
        )
    }
}
