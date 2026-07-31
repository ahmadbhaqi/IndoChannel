package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class AbyssPlayerParserTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `Abyss decoder emits every source with a concrete URL and path`() {
        val media = """
            {
              "mp4": {
                "sources": [
                  {
                    "label": "480p",
                    "size": 100,
                    "partSize": 0,
                    "url": "https://video.example",
                    "path": "full/movie.mp4"
                  },
                  {
                    "label": "1080p",
                    "size": 1000,
                    "partSize": 500,
                    "url": "https://video.example",
                    "path": "partial/movie.mp4"
                  },
                  {
                    "label": "720p",
                    "size": 700,
                    "sub": "browser-only"
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                AbyssMediaSource(
                    label = "480p",
                    url = "https://video.example/full/movie.mp4",
                    quality = 480
                ),
                AbyssMediaSource(
                    label = "1080p",
                    url = "https://video.example/partial/movie.mp4",
                    quality = 1080
                )
            ),
            AbyssPlayerParser.sources(abyssPage(media))
        )
    }

    @Test
    fun `Abyss decoder accepts URL-safe Base64 on Android compatible path`() {
        assertContentEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), decodeBase64Compat("-_8"))
        val fixture = byteArrayOf(0, 1, 2, 0xfb.toByte(), 0xff.toByte())
        val encoded = encodeBase64UrlNoPadding(fixture)
        assertEquals("AAEC-_8", encoded)
        assertContentEquals(fixture, decodeBase64Compat(encoded))

        val media = """
            {"mp4":{"sources":[{"label":"720p","size":10,"partSize":10,"url":"https://video.example","path":"full/movie.mp4"}]}}
        """.trimIndent()
        assertEquals(
            listOf(AbyssMediaSource("720p", "https://video.example/full/movie.mp4", 720)),
            AbyssPlayerParser.sources(abyssPage(media, urlSafe = true))
        )
    }

    @Test
    fun `Abyss decoder rejects current encrypted chunk backing storage`() {
        val media = """
            {
              "mp4": {
                "sources": [{
                  "label": "720p",
                  "res_id": 4,
                  "size": 406038866,
                  "partSize": 0,
                  "sub": "current-subdomain",
                  "url": "https://storage.example",
                  "path": "3/f/b/encrypted.4"
                }],
                "domains": ["current-subdomain.storage.example"]
              }
            }
        """.trimIndent()

        assertEquals(emptyList(), AbyssPlayerParser.sources(abyssPage(media)))
    }

    @Test
    fun `Abyss decoder derives a full seekable Sora URL from current metadata`() {
        val media = """
            {
              "mp4": {
                "sources": [{
                  "res_id": 2,
                  "size": 369670425,
                  "sub": "o32l05c213",
                  "url": "https://encrypted-storage.example",
                  "path": "encrypted.2"
                }],
                "domains": ["o32l05c213.sssrr.org"]
              }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                AbyssMediaSource(
                    label = "Abyss 360p",
                    url = "https://o32l05c213.sssrr.org/sora/369670425/" +
                        "dDN0TjNSYkE0eTBaZHNQWEF3TUs2cmYvbjJxVlNVSmJhMjBtR3V4STBTeWs2dVU5WkE",
                    quality = 360,
                    headers = mapOf("User-Agent" to Embed4mePlayerParser.USER_AGENT)
                )
            ),
            AbyssPlayerParser.sources(
                abyssPage(
                    media = media,
                    slug = "Ofj7sXf0s",
                    md5Id = "22002463"
                )
            )
        )
    }

    @Test
    fun `Abyss decoder keeps live 1080p res5 and skips explicitly disabled sources`() {
        val media = """
            {
              "mp4": {
                "sources": [
                  {
                    "res_id": 2,
                    "size": 369670425,
                    "sub": "o32l05c213",
                    "status": false
                  },
                  {
                    "label": "1080p",
                    "res_id": 5,
                    "size": 1939119501,
                    "sub": "htm4jbxon18",
                    "status": true
                  }
                ],
                "domains": [
                  "o32l05c213.sssrr.org",
                  "htm4jbxon18.sssrr.org"
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                AbyssMediaSource(
                    label = "Abyss 1080p",
                    url = "https://htm4jbxon18.sssrr.org/sora/1939119501/" +
                        "NjVCdFlJR3dDUEo5UGRRMmtkUXJOSlgvMFEwakt0UWJub3dWVHBRTndDTXFCSjM4QjM4",
                    quality = 1080,
                    headers = mapOf("User-Agent" to Embed4mePlayerParser.USER_AGENT)
                )
            ),
            AbyssPlayerParser.sources(
                abyssPage(
                    media = media,
                    slug = "Ofj7sXf0s",
                    md5Id = "22002463"
                )
            )
        )
    }

    @Test
    fun `Sora probe requires a full file content range total`() {
        val url = "https://htm4jbxon18.sssrr.org/sora/1939119501/token"

        assertTrue(
            expectedSoraRangeIsValid(
                url,
                206,
                "bytes 0-65535/1939119501"
            ) == true
        )
        assertFalse(expectedSoraRangeIsValid(url, 200, null) == true)
        assertFalse(
            expectedSoraRangeIsValid(
                url,
                206,
                "bytes 0-65535/2097152"
            ) == true
        )
        assertNull(
            expectedSoraRangeIsValid(
                "https://cdn.example/video.mp4",
                200,
                null
            )
        )
        assertNull(
            expectedSoraRangeIsValid(
                "https://legacy.sssrr.org/video/current.mp4",
                200,
                null
            )
        )
    }

    @Test
    fun `resolver emits a healthy lower Abyss quality without waiting for stalled 1080p`() = runBlocking {
        val media = """
            {"mp4":{"sources":[
              {"label":"1080p","size":100,"partSize":100,"url":"https://video.example","path":"full-1080.mp4"},
              {"label":"720p","size":100,"partSize":100,"url":"https://video.example","path":"full-720.mp4"}
            ]}}
        """.trimIndent()
        val links = mutableListOf<ExtractorLink>()
        val probes = Collections.synchronizedList(mutableListOf<String>())
        val playerUrl = "https://abyssplayer.com/current-video"
        // Build and decode the encrypted fixture before starting the deliberately
        // tiny candidate timeout. This test is about concurrent media probes;
        // cipher/Jackson cold-start time must not consume that probe budget.
        val playerPage = abyssPage(media)
        assertEquals(2, AbyssPlayerParser.sources(playerPage).size)
        val session = LinkResolutionSession(
            api = KitanontonProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                playerPage
            },
            extractorLoader = { _, _, _, _ -> error("Abyss adapter must be terminal") },
            mediaLinkProbe = { link ->
                probes += link.url
                if (link.url.endsWith("full-1080.mp4")) {
                    // Deliberately outlive the whole candidate budget. A
                    // sequential resolver would never reach the healthy 720p
                    // source, while the concurrent resolver can emit it first.
                    delay(5_000)
                    null
                } else {
                    link
                }
            },
            candidateTimeoutMs = 2_000
        )

        assertTrue(session.resolve(playerUrl, "https://kitanonton2.surf/movie/current/"))
        assertTrue("https://video.example/full-1080.mp4" in probes)
        assertTrue("https://video.example/full-720.mp4" in probes)
        assertEquals(listOf("https://video.example/full-720.mp4"), links.map { it.url })
    }

    @Test
    fun `parallel Abyss probes stop emitting after callback failure`() = runBlocking {
        val media = """
            {"mp4":{"sources":[
              {"label":"1080p","size":100,"partSize":100,"url":"https://video.example","path":"full-1080.mp4"},
              {"label":"720p","size":100,"partSize":100,"url":"https://video.example","path":"full-720.mp4"}
            ]}}
        """.trimIndent()
        val playerUrl = "https://abyssplayer.com/current-video"
        val playerPage = abyssPage(media)
        var callbackCalls = 0
        val session = LinkResolutionSession(
            api = KitanontonProvider(),
            subtitleCallback = {},
            callback = {
                callbackCalls++
                throw AssertionError("consumer rejected link")
            },
            pageFetcher = { _, _ -> playerPage },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { it }
        )

        assertFailsWith<AssertionError> {
            session.resolve(playerUrl, "https://kitanonton2.surf/movie/current/")
        }
        assertEquals(1, callbackCalls)
        assertFalse(session.loaded)
    }

    @Test
    fun `resolver treats the current abysscdn host as an Abyss player`() = runBlocking {
        val media = """
            {"mp4":{
              "sources":[{
                "label":"720p",
                "res_id":4,
                "size":406038866,
                "sub":"current-subdomain",
                "status":true
              }],
              "domains":["current-subdomain.sssrr.org"]
            }}
        """.trimIndent()
        val playerUrl = "https://abysscdn.com/?v=current-movie"
        val playerPage = abyssPage(media)
        val links = mutableListOf<ExtractorLink>()
        var genericAttempts = 0
        val session = LinkResolutionSession(
            api = KeBioskopProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                playerPage
            },
            extractorLoader = { _, _, _, _ ->
                genericAttempts++
                false
            },
            mediaLinkProbe = { link -> link }
        )

        assertTrue(
            session.resolve(
                playerUrl,
                "https://streaming.kebioskop21.pro/apidrive.php?token=current"
            )
        )
        assertEquals(0, genericAttempts, "The first-party Abyss adapter must handle abysscdn directly")
        assertEquals(1, links.size)
        assertTrue(links.single().url.startsWith("https://current-subdomain.sssrr.org/sora/406038866/"))
        assertEquals(720, links.single().quality)
    }

    @Test
    fun `resolver does not trust abysscdn lookalike hosts`() = runBlocking {
        val lookalikes = listOf(
            "https://abysscdn.com.evil.example/?v=current-movie",
            "https://evilabysscdn.com/?v=current-movie",
            "https://subdomain.abysscdn.com/?v=current-movie"
        )
        val genericAttempts = mutableListOf<String>()
        val session = LinkResolutionSession(
            api = KeBioskopProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { _, _ -> error("Lookalike hosts must not enter the trusted Abyss adapter") },
            extractorLoader = { url, _, _, _ ->
                genericAttempts += url
                false
            },
            mediaLinkProbe = { link -> link }
        )

        lookalikes.forEach { url ->
            assertFalse(session.resolve(url, "https://kebioskop21.cfd/movie/current/"))
        }
        assertEquals(lookalikes, genericAttempts)
    }

    private fun abyssPage(
        media: String,
        urlSafe: Boolean = false,
        slug: String = "current-video",
        md5Id: String = "30893980"
    ): String {
        val userId = "394115"
        val key = md5Hex("$userId:$slug:$md5Id").toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(key.copyOfRange(0, 16))
        )
        val encrypted = cipher.doFinal(media.toByteArray(Charsets.UTF_8))
        val outerJson = mapper.writeValueAsString(
            mapOf(
                "slug" to slug,
                "user_id" to userId,
                "md5_id" to md5Id,
                "media" to String(encrypted, Charsets.ISO_8859_1)
            )
        )
        val encoder = if (urlSafe) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder()
        val payload = encoder.encodeToString(outerJson.toByteArray(Charsets.ISO_8859_1))
        return """<script>const datas = "$payload";</script>"""
    }

    private fun md5Hex(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
