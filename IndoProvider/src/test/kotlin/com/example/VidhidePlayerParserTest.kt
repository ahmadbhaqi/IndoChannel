package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VidhidePlayerParserTest {
    private val player = "https://vidhide.org/embed/fixture"
    private val password = "1791605634"
    private val media = "https://s1.vidhide.org/hls/fixture?gt=token"
    private val payload = """{"status":"ok","sources":[{"file":"$media","type":"hls","label":"Original"},{"file":"http://127.0.0.1/video.mp4","type":"mp4"}]}"""

    private fun encodedPage(endpoint: String = "https://s1.vidhide.org/api-config/"): String {
        val api = Base64.getEncoder().encodeToString(endpoint.toByteArray())
        val packed = "eval(function(p,a,c,k,e,d){return p}('0=\"4\";1=\"5\";2=\"6\";3=\"7\";',36,8,'pd|ps|kaken|apx|$password|token,,|body-,token,,|$api'.split('|'),0,{}))"
        val digits = listOf("(c^_^o)", "(ﾟΘﾟ)", "((o^_^o)-(ﾟΘﾟ))", "(o^_^o)", "(ﾟｰﾟ)", "((ﾟｰﾟ)+(ﾟΘﾟ))", "((o^_^o)+(o^_^o))", "((ﾟｰﾟ)+(o^_^o))")
        val encoded = packed.map { char ->
            "(ﾟДﾟ)[ﾟεﾟ]+" + char.code.toString(8).map { digits[it.digitToInt()] }.joinToString("+") + "+"
        }.joinToString("")
        return "<script>ﾟωﾟﾉ=0;(ﾟДﾟ)[ﾟoﾟ]+$encoded(ﾟДﾟ)[ﾟoﾟ])</script>"
    }

    private fun encrypt(): String {
        val salt = ByteArray(16) { it.toByte() }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 10_000, 384)).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 32), "AES"), IvParameterSpec(key.copyOfRange(32, 48)))
        return Base64.getEncoder().encodeToString(salt + cipher.doFinal(payload.toByteArray()))
    }

    @Test
    fun `AA encoded packed bootstrap builds only an owned API request`() {
        val request = assertNotNull(VidhidePlayerParser.resolveRequest(encodedPage(), player))
        assertEquals("https://s1.vidhide.org/api/?p=token%2C%2C", request.apiUrl)
        assertEquals("body-,token,,", request.body)
        assertEquals(password, request.password)
        assertNull(VidhidePlayerParser.resolveRequest(encodedPage("https://evil.example/api-config/"), player))
        assertNull(VidhidePlayerParser.resolveRequest(encodedPage(), "https://vidhide.org@evil.example/embed/fixture"))
    }

    @Test
    fun `encrypted response preserves extensionless HLS and rejects unsafe sources`() {
        assertEquals(listOf(FreeonMediaSource("Original", media, "hls")), VidhidePlayerParser.sources(encrypt(), password))
        assertEquals(emptyList(), VidhidePlayerParser.sources(encrypt(), "wrong"))
        assertEquals(emptyList(), VidhidePlayerParser.sources("not-base64", password))
    }

    @Test
    fun `session resolves native Vidhide before a generic extractor and verifies media`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        var generic = false
        var probed = false
        val session = LinkResolutionSession(
            api = DutamovieProvider(), subtitleCallback = {}, callback = links::add,
            pageFetcher = { _, _ -> encodedPage() },
            vidhideApiFetcher = { encrypt() },
            extractorLoader = { _, _, _, _ -> generic = true; false },
            mediaLinkProbe = { probed = true; it }
        )
        assertTrue(session.resolve(player, "http://165.227.229.131/eps/fixture/"))
        assertTrue(probed)
        assertFalse(generic)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
        assertEquals(media, links.single().url)
    }
    @Test
    fun `native parser API empty sources and failed probes preserve generic fallback`() = runBlocking {
        val fallback = newExtractorLink("fixture", "fixture", "https://cdn.example/fallback.mp4", ExtractorLinkType.VIDEO)
        for (failure in listOf("parser", "api", "empty", "probe", "stall")) {
            val links = mutableListOf<ExtractorLink>()
            var generic = false
            val session = LinkResolutionSession(
                api = DutamovieProvider(), subtitleCallback = {}, callback = links::add,
                pageFetcher = { _, _ -> if (failure == "parser") "<html>changed bootstrap</html>" else encodedPage() },
                vidhideApiFetcher = {
                    when (failure) {
                        "api" -> throw java.io.IOException("temporary API failure")
                        "stall" -> { delay(10_000); encrypt() }
                        "empty" -> """{"status":"ok","sources":[]}"""
                        else -> encrypt()
                    }
                },
                extractorLoader = { _, _, _, emit ->
                    generic = true
                    emit(fallback)
                    true
                },
                mediaLinkProbe = { link -> link.takeUnless { failure == "probe" && it.url == media } },
                candidateTimeoutMs = 1_000L
            )
            assertTrue(session.resolve(player, "http://165.227.229.131/eps/fixture/"), failure)
            assertTrue(generic, failure)
            assertEquals("https://cdn.example/fallback.mp4", links.single().url, failure)
        }
    }
}
