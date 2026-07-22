package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirestreamPlayerParserTest {
    private val mapper = jacksonObjectMapper()
    private val playerUrl = "https://firestream.to/e/fixture01"
    private val providerUrl = "https://tv.nontonfilm.red/scary-movie-2026/"
    private val blob = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
    private val playerHtml = """
        <script id="video-data" type="application/json">
          {"video":{"encodedPath":"fixture/video.mp4","encodingStatus":"completed"}}
        </script>
        <script id="token-blob" type="text/plain">$blob</script>
    """.trimIndent()

    @Test
    fun `builds a bounded same-origin token exchange request`() {
        val request = assertNotNull(FirestreamPlayerParser.resolveRequest(playerHtml, playerUrl))

        assertEquals("https://firestream.to/api/videos/fixture01/resolve", request.apiUrl)
        assertEquals(playerUrl, request.playerUrl)
        assertEquals("https://firestream.to", request.headers["Origin"])
        assertEquals(playerUrl, request.headers["Referer"])
        assertEquals(blob, mapper.readTree(request.body).path("blob").asText())
    }

    @Test
    fun `rejects lookalike hosts malformed paths and unsafe token blobs`() {
        assertFalse(FirestreamPlayerParser.supports("evilfirestream.to"))
        assertFalse(FirestreamPlayerParser.supports("firestream.to.evil.example"))
        assertNull(
            FirestreamPlayerParser.resolveRequest(
                playerHtml,
                "https://firestream.to/e/../admin"
            )
        )
        assertNull(
            FirestreamPlayerParser.resolveRequest(
                playerHtml.replace(blob, "not-base64!"),
                playerUrl
            )
        )
        assertNull(
            FirestreamPlayerParser.resolveRequest(
                playerHtml.replace(blob, "A".repeat(8_200)),
                playerUrl
            )
        )
    }

    @Test
    fun `accepts only signed Firestream mp4 responses`() {
        val media =
            "https://fr-cdn-0.firestream.to/encodings/fixture/video.mp4?md5=fixture&expires=4102444800"
        assertEquals(
            media,
            FirestreamPlayerParser.signedVideoUrl(
                mapper.writeValueAsString(mapOf("signedVideoUrl" to media))
            )
        )
        assertNull(
            FirestreamPlayerParser.signedVideoUrl(
                mapper.writeValueAsString(
                    mapOf("signedVideoUrl" to "https://evil.example/video.mp4?md5=x")
                )
            )
        )
        assertNull(FirestreamPlayerParser.signedVideoUrl("<html>blocked</html>"))
    }

    @Test
    fun `link session exchanges token blob and emits signed mp4 without generic fallback`() =
        runBlocking {
            val media =
                "https://fr-cdn-0.firestream.to/encodings/fixture/video.mp4?md5=fixture&expires=4102444800"
            val requests = mutableListOf<FirestreamResolveRequest>()
            val links = mutableListOf<ExtractorLink>()
            var genericCalled = false
            val session = LinkResolutionSession(
                api = LayarKacaProvider(),
                subtitleCallback = {},
                callback = links::add,
                pageFetcher = { url, _ ->
                    assertEquals(playerUrl, url)
                    playerHtml
                },
                firestreamApiFetcher = { request ->
                    requests += request
                    mapper.writeValueAsString(mapOf("signedVideoUrl" to media))
                },
                extractorLoader = { _, _, _, _ ->
                    genericCalled = true
                    false
                },
                mediaLinkProbe = { it }
            )

            assertTrue(session.resolve(playerUrl, providerUrl))
            assertEquals(1, requests.size)
            assertFalse(genericCalled)
            assertEquals(media, links.single().url)
            assertEquals(playerUrl, links.single().referer)
            assertEquals(ExtractorLinkType.VIDEO, links.single().type)
        }

    @Test
    fun `link session refreshes the player blob after a rejected token exchange`() = runBlocking {
        val refreshedBlob = Base64.getEncoder()
            .encodeToString(ByteArray(64) { (it + 1).toByte() })
        val media =
            "https://fr-cdn-0.firestream.to/encodings/fixture/retry.mp4?md5=retry&expires=4102444800"
        val requests = mutableListOf<FirestreamResolveRequest>()
        val links = mutableListOf<ExtractorLink>()
        var pageFetches = 0
        val session = LinkResolutionSession(
            api = LayarKacaProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                pageFetches++
                if (pageFetches == 1) playerHtml else playerHtml.replace(blob, refreshedBlob)
            },
            firestreamApiFetcher = { request ->
                requests += request
                if (requests.size == 1) {
                    mapper.writeValueAsString(mapOf("error" to "token rejected"))
                } else {
                    mapper.writeValueAsString(mapOf("signedVideoUrl" to media))
                }
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { it }
        )

        assertTrue(session.resolve(playerUrl, providerUrl))
        assertEquals(2, pageFetches)
        assertEquals(2, requests.size)
        assertEquals(blob, mapper.readTree(requests.first().body).path("blob").asText())
        assertEquals(refreshedBlob, mapper.readTree(requests.last().body).path("blob").asText())
        assertEquals(media, links.single().url)
    }
}
