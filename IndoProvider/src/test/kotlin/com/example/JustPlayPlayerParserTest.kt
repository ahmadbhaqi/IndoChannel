package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class JustPlayPlayerParserTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `derives modern embed endpoints and browser-equivalent parent headers`() {
        val playerUrl = "https://justplay.cam/e/fa49irj7tucw"
        val referer = "https://www.tv.nontonfilm.red/ip-man-kung-fu-legend-2026/"
        val context = JustPlayPlayerParser.context(playerUrl, referer)

        assertEquals(
            "https://justplay.cam/api/videos/fa49irj7tucw/embed/settings",
            context?.settingsUrl
        )
        assertEquals(
            "https://justplay.cam/api/videos/fa49irj7tucw/embed/captcha",
            context?.captchaUrl
        )
        assertEquals(
            "https://justplay.cam/api/videos/fa49irj7tucw/embed/captcha/verify",
            context?.captchaVerifyUrl
        )
        assertEquals(
            "https://justplay.cam/api/videos/fa49irj7tucw/embed/playback",
            context?.playbackUrl
        )
        assertEquals(
            "https://justplay.cam/api/videos/access/challenge",
            context?.accessChallengeUrl
        )
        assertEquals(
            "https://justplay.cam/api/videos/access/attest",
            context?.accessAttestUrl
        )
        assertEquals(
            mapOf(
                "X-Embed-Origin" to "tv.nontonfilm.red",
                "X-Embed-Referer" to referer,
                "X-Embed-Parent" to playerUrl
            ),
            context?.headers
        )

        assertNull(JustPlayPlayerParser.context("javascript:alert(1)", referer))
        assertNull(JustPlayPlayerParser.context("https://justplay.cam/e/%2e%2e/title", referer))
        assertNull(JustPlayPlayerParser.context("https://justplay.cam/watch/fa49irj7tucw", referer))
    }

    @Test
    fun `memory-hard proof solver is deterministic and bounded`() = runBlocking {
        val solution = JustPlayPlayerParser.solvePow(
            nonce = "fixture-nonce",
            difficulty = 10,
            maxAttempts = 20_000,
            maxMillis = 5_000
        )

        assertEquals("1398", solution)
        assertTrue(JustPlayPlayerParser.leadingZeroBits("fixture-nonce", solution!!) >= 10)
        assertNull(
            JustPlayPlayerParser.solvePow(
                nonce = "fixture-nonce",
                difficulty = 20,
                maxAttempts = 1,
                maxMillis = 5_000
            )
        )
        assertNull(JustPlayPlayerParser.solvePow("fixture-nonce", difficulty = 21))
        assertNull(JustPlayPlayerParser.solvePow("x".repeat(1_025), difficulty = 1))
    }

    @Test
    fun `modern captcha flow verifies proof then decrypts bounded playback`() = runBlocking {
        val playerUrl = "https://justplay.cam/e/current01"
        val referer = "https://tv.nontonfilm.red/current-movie/"
        val playbackJson = envelope(
            mapper.writeValueAsBytes(
                mapOf(
                    "sources" to listOf(
                        mapOf(
                            "url" to "https://media.example/current/master.m3u8",
                            "mime_type" to "application/vnd.apple.mpegurl",
                            "label" to "1080p",
                            "height" to 1080
                        )
                    ),
                    "tracks" to listOf(
                        mapOf(
                            "url" to "https://media.example/current/id.vtt",
                            "kind" to "captions",
                            "label" to "Indonesia",
                            "language" to "id"
                        )
                    )
                )
            ),
            version = 6
        )
        val requests = mutableListOf<JustPlayHttpRequest>()
        val playback = JustPlayPlayerParser.resolve(playerUrl, referer) { request ->
            requests += request
            when {
                request.url.endsWith("/access/challenge") ->
                    """{"challenge_id":"challenge-id","nonce":"attestation-nonce"}"""
                request.url.endsWith("/access/attest") -> attestationResponse()
                request.url.endsWith("/settings") -> "{\"captcha_required\":true}"
                request.url.endsWith("/captcha") -> """{
                    "pow_nonce":"fixture-nonce",
                    "pow_difficulty":10,
                    "pow_token":"pow-token",
                    "expires_in":1800,
                    "algorithm":"sha256-leading-zero-bits"
                }"""
                request.url.endsWith("/captcha/verify") ->
                    "{\"status\":\"ok\",\"token\":\"captcha-token\",\"expires_in\":1800}"
                request.url.endsWith("/playback") -> playbackJson
                else -> error("Unexpected request: $request")
            }
        }

        assertEquals(6, requests.size)
        assertEquals(JustPlayHttpMethod.GET, requests[0].method)
        assertNull(requests[0].body)
        assertEquals(JustPlayHttpMethod.POST, requests[1].method)
        assertEquals(JustPlayHttpMethod.POST, requests[2].method)
        val attestBody = mapper.readTree(requests[2].body)
        assertEquals("challenge-id", attestBody.path("challenge_id").asText())
        assertEquals("attestation-nonce", attestBody.path("nonce").asText())
        assertEquals("EC", attestBody.path("public_key").path("kty").asText())
        assertEquals("P-256", attestBody.path("public_key").path("crv").asText())
        assertEquals(
            64,
            Base64.getUrlDecoder().decode(attestBody.path("signature").asText()).size
        )
        val fingerprint = fingerprintFixture()
        assertEquals(
            mapOf("fingerprint" to fingerprint),
            mapper.readValue(requests[3].body!!, Map::class.java)
        )
        assertEquals(
            mapOf(
                "pow_token" to "pow-token",
                "solution" to "1398",
                "fingerprint" to fingerprint
            ),
            mapper.readValue(requests[4].body!!, Map::class.java)
        )
        assertEquals(JustPlayHttpMethod.POST, requests[5].method)
        assertEquals(
            mapOf("fingerprint" to fingerprint),
            mapper.readValue(requests[5].body!!, Map::class.java)
        )
        assertEquals("captcha-token", requests[5].headers["X-Captcha-Token"])
        requests.forEach { request ->
            assertEquals("tv.nontonfilm.red", request.headers["X-Embed-Origin"])
            assertEquals(referer, request.headers["X-Embed-Referer"])
            assertEquals(playerUrl, request.headers["X-Embed-Parent"])
        }
        assertEquals("https://media.example/current/master.m3u8", playback?.sources?.single()?.url)
        assertEquals("https://media.example/current/id.vtt", playback?.tracks?.single()?.url)
    }

    @Test
    fun `modern flow fails closed for hostile challenge and oversized playback`() = runBlocking {
        val playerUrl = "https://justplay.cam/e/current01"
        val referer = "https://tv.nontonfilm.red/current-movie/"
        var calls = 0
        val hostile = JustPlayPlayerParser.resolve(playerUrl, referer) { request ->
            calls += 1
            when {
                request.url.endsWith("/settings") -> "{\"captcha_required\":true}"
                request.url.endsWith("/access/challenge") ->
                    """{"challenge_id":"challenge-id","nonce":"attestation-nonce"}"""
                request.url.endsWith("/access/attest") -> attestationResponse()
                else -> "{\"pow_nonce\":\"nonce\",\"pow_difficulty\":30,\"pow_token\":\"token\"}"
            }
        }
        assertNull(hostile)
        assertEquals(4, calls)

        calls = 0
        val oversized = JustPlayPlayerParser.resolve(playerUrl, referer) { request ->
            calls += 1
            when {
                request.url.endsWith("/settings") -> "{\"captcha_required\":false}"
                request.url.endsWith("/access/challenge") ->
                    """{"challenge_id":"challenge-id","nonce":"attestation-nonce"}"""
                request.url.endsWith("/access/attest") -> attestationResponse()
                else -> "x".repeat(2_000_001)
            }
        }
        assertNull(oversized)
        assertEquals(4, calls)
    }

    @Test
    fun `link session resolves justplay sources and subtitle without generic fallback`() = runBlocking {
        val playerUrl = "https://justplay.cam/e/current01"
        val referer = "https://tv.nontonfilm.red/current-movie/"
        val playbackJson = envelope(
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
                            "language" to "id"
                        )
                    )
                )
            ),
            version = 17
        )
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val session = LinkResolutionSession(
            api = LayarKacaProvider(),
            subtitleCallback = subtitles::add,
            callback = links::add,
            pageFetcher = { url, from -> error("Generic fetch should not run: $url from $from") },
            byseApiFetcher = { url, from -> error("Legacy Byse should not run: $url from $from") },
            justPlayApiFetcher = { request ->
                when {
                    request.url.endsWith("/settings") -> "{\"captcha_required\":false}"
                    request.url.endsWith("/access/challenge") ->
                        """{"challenge_id":"challenge-id","nonce":"attestation-nonce"}"""
                    request.url.endsWith("/access/attest") -> attestationResponse()
                    request.url.endsWith("/playback") -> playbackJson
                    else -> error("Unexpected JustPlay request: $request")
                }
            },
            extractorLoader = { _, _, _, _ -> error("Generic extractor should not run") }
        )

        assertTrue(session.resolve(playerUrl, referer))
        assertTrue(session.loaded)
        assertEquals("https://media.example/current/master.m3u8", links.single().url)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
        assertEquals(720, links.single().quality)
        assertEquals(playerUrl, links.single().referer)
        assertEquals(playerUrl, links.single().headers["Referer"])
        assertEquals("https://justplay.cam", links.single().headers["Origin"])
        assertEquals("id", subtitles.single().lang)
    }

    private fun fingerprintFixture(): Map<String, Any> = linkedMapOf(
        "token" to "fingerprint-token",
        "viewer_id" to "viewer-id",
        "device_id" to "device-id",
        "confidence" to 0.35
    )

    private fun attestationResponse(): String = mapper.writeValueAsString(fingerprintFixture())

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
