package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Embed4mePlayerParserTest {
    private val playerUrl = "https://dm21.embed4me.vip/#x8yws"
    private val providerUrl = "https://austincomputerworks.org/lunok-2026/"

    @Test
    fun `hash player builds bounded same-origin api requests`() {
        val request = Embed4mePlayerParser.apiRequest(playerUrl, providerUrl)!!

        assertEquals("https://dm21.embed4me.vip", request.origin)
        assertEquals(
            "https://dm21.embed4me.vip/api/v1/video?id=x8yws&w=1920&h=1080&r=austincomputerworks.org",
            request.videoApiUrl
        )
        assertEquals(Embed4mePlayerParser.USER_AGENT, request.headers["User-Agent"])
        assertEquals(
            "https://ichinime.4meplayer.pro",
            Embed4mePlayerParser.apiRequest(
                "https://ichinime.4meplayer.pro/#e8bn6",
                "https://akses7.indomax21.xyz/masters-of-the-universe-2026/"
            )?.origin
        )
        assertNull(Embed4mePlayerParser.apiRequest("https://untrusted.example/#x8yws", providerUrl))
        assertNull(Embed4mePlayerParser.apiRequest("https://dm21.embed4me.vip/#../bad", providerUrl))
    }

    @Test
    fun `AES api payload prefers same-origin hls proxy`() {
        val proxy = "https://dm21.embed4me.vip/v4/pl/current/master.m3u8?k=signed"
        val source = "https://185.237.107.150/v4/current/master.m3u8?v=signed"
        val ciphertext = encryptHex(
            """{"cfNative":"$proxy","source":"$source","title":"Current"}"""
        )

        assertEquals(
            listOf(
                Embed4meMediaSource("Embed4me HLS Proxy", proxy, ExtractorLinkType.M3U8),
                Embed4meMediaSource("Embed4me HLS", source, ExtractorLinkType.M3U8)
            ),
            Embed4mePlayerParser.videoSources(ciphertext, playerUrl)
        )
        assertEquals(
            """{"cfNative":"$proxy","source":"$source","title":"Current"}""",
            Embed4mePlayerParser.decryptHexPayload(ciphertext)
        )
        assertTrue(Embed4mePlayerParser.videoSources("00ff", playerUrl).isEmpty())
    }

    @Test
    fun `resolution session emits decrypted media and never api ciphertext`() = runBlocking {
        val proxy = "https://dm21.embed4me.vip/v4/pl/current/master.m3u8?k=signed"
        val ciphertext = encryptHex("""{"cfNative":"$proxy"}""")
        val request = Embed4mePlayerParser.apiRequest(playerUrl, providerUrl)!!
        val links = mutableListOf<ExtractorLink>()
        val requestedApis = mutableListOf<String>()
        val session = LinkResolutionSession(
            api = DutamovieProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> error("hash player must use its API") },
            playerApiFetcher = { url, _, _ ->
                requestedApis += url
                if (url == request.videoApiUrl) ciphertext else error("unexpected api: $url")
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link -> link.takeIf { it.url == proxy } }
        )

        assertTrue(session.resolve(playerUrl, providerUrl))
        assertEquals(listOf(request.videoApiUrl), requestedApis)
        assertEquals(listOf(proxy), links.map { it.url })
        assertTrue(links.none { it.url.contains("/api/v1/") })
    }

    private fun encryptHex(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
