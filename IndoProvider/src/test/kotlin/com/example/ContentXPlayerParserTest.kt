package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

class ContentXPlayerParserTest {
    private val mapper = jacksonObjectMapper()
    private val playerUrl = "https://playerngefilm21.rpmlive.online/#oe5w9x"
    private val providerUrl = "https://new39.ngefilm.site/gangland-2025/"

    @Test
    fun `builds the current encrypted video endpoint from a fragment embed`() {
        assertEquals(
            "https://playerngefilm21.rpmlive.online/api/v1/video" +
                "?id=oe5w9x&w=1920&h=1080&r=new39.ngefilm.site",
            ContentXPlayerParser.apiUrl(playerUrl, providerUrl)
        )
        assertNull(ContentXPlayerParser.apiUrl("https://playerngefilm21.rpmlive.online/", providerUrl))
        assertNull(ContentXPlayerParser.apiUrl("https://playerngefilm21.rpmlive.online/#../admin", providerUrl))
        assertNull(ContentXPlayerParser.apiUrl("https://unrelated.example/#oe5w9x", providerUrl))
        assertNull(ContentXPlayerParser.apiUrl("javascript:alert(1)#oe5w9x", providerUrl))
    }

    @Test
    fun `decrypts and prioritizes concrete ContentX HLS sources`() {
        val encrypted = encryptedResponse(
            mapOf(
                "source" to "https://origin.example/current/master.m3u8",
                "cfNative" to "https://playerngefilm21.rpmlive.online/v4/current/master.m3u8?k=signed",
                "hlsVideoTiktok" to "/hls/current/tt/master.m3u8",
                "cf" to "https://edge.example/current/cf-master.txt"
            )
        )

        assertEquals(
            listOf(
                "https://playerngefilm21.rpmlive.online/hls/current/tt/master.m3u8",
                "https://playerngefilm21.rpmlive.online/v4/current/master.m3u8?k=signed",
                "https://origin.example/current/master.m3u8"
            ),
            ContentXPlayerParser.playback("  $encrypted\r\n", playerUrl)?.sources?.map { it.url }
        )
    }

    @Test
    fun `rejects malformed oversized and tampered encrypted responses`() {
        assertNull(ContentXPlayerParser.playback("not-hex", playerUrl))
        assertNull(ContentXPlayerParser.playback("00".repeat(1_000_001), playerUrl))
        assertNull(ContentXPlayerParser.playback("00".repeat(16), playerUrl))
        assertNull(
            ContentXPlayerParser.playback(
                encryptedResponse(mapOf("cfNative" to "javascript:alert(1)")),
                playerUrl
            )
        )
    }

    @Test
    fun `link session resolves fragment embed without invoking the generic extractor`() = runBlocking {
        val apiUrl = ContentXPlayerParser.apiUrl(playerUrl, providerUrl)!!
        val encrypted = encryptedResponse(
            mapOf(
                "cfNative" to "https://media.example/current/master.m3u8"
            )
        )
        val requests = mutableListOf<Pair<String, String?>>()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = NgefilmProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, referer ->
                requests += url to referer
                if (url == apiUrl) encrypted else error("Unexpected request: $url")
            },
            extractorLoader = { _, _, _, _ -> error("Generic extractor should not run") }
        )

        assertTrue(session.resolve(playerUrl, providerUrl))
        assertEquals(listOf<Pair<String, String?>>(apiUrl to playerUrl), requests)
        assertEquals("https://media.example/current/master.m3u8", links.single().url)
        assertEquals(playerUrl, links.single().referer)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
    }

    private fun encryptedResponse(fields: Map<String, Any>): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.US_ASCII))
        )
        return cipher.doFinal(mapper.writeValueAsBytes(fields)).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
