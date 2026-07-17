package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AbyssPlayerParserTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `Abyss decoder emits only complete direct MP4 sources`() {
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

    private fun abyssPage(media: String, urlSafe: Boolean = false): String {
        val slug = "current-video"
        val userId = "394115"
        val md5Id = "30893980"
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
