package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoePlayerParserTest {
    @Test
    fun `decodes current packed config and ignores the decoy source assignment`() {
        val packed =
            "DQAkGT9AJ2pmn1gx^^pRITKJ93AIkeEJ9Z~@BHkJKKMIJ2DmGHMC%?MmEoKGEAF2p5GRMa*~qyyoKUOMFy1frzkZ!!sGt1MKAIF2MeHKOZ#&BHkTKKuiAJEfrzkZ^^rRynMU1MpTI5IKOy~@GH1fHzk6Jy1fFHcy%?oUcfGQAIpJq4IScx*~rHknM308Ezq9JHca!!rSuXMKb0Jyj3JKOC#&rSH1KUx7oSW9EHga^^Z2gjGQyZIy12o3Oq~@oUL2JwyZsTM4CScx%?Z0IoKT1AEx9fnyqa*~sGAjG3kMFzq9FIcy!!rGgnKJ5ipTq5IQMz#&o1IkG297FzM3FHcb^^omufMJ5EAH95pa1z~@ryIYM3WAoSWfJQIp%?sSx2MK1AsTt="
        val playerUrl = "https://player.example.test/e/current"
        val html =
            """
            <script>
                var source='https://test-videos.example.test/decoy.mp4';
            </script>
            <script type="application/json">["$packed"]</script>
            """.trimIndent()

        val playback = VoePlayerParser.playback(html, playerUrl)

        assertEquals(
            listOf("https://cdn.example.test/video/master.m3u8"),
            playback?.sources?.map { it.url }
        )
        assertEquals(
            listOf("https://cdn.example.test/sub/id.vtt"),
            playback?.tracks?.map { it.url }
        )
        assertFalse(playback?.sources.orEmpty().any { "decoy" in it.url })
    }

    @Test
    fun `follows the VOE redirect and resolves relative captions with the target referer`() = runBlocking {
        val voeUrl = "https://voe.sx/e/current"
        val targetUrl = "https://player.example.test/e/current"
        val hlsUrl = "https://cdn.example.test/current/master.m3u8"
        val directUrl = "https://cdn.example.test/current/video.mp4"
        val packed = pack(
            """
            {
              "source":"$hlsUrl",
              "direct_access_url":"$directUrl",
              "captions":[
                {"file":"/vtt/current_id.srt","label":"Indonesian","kind":"captions"}
              ]
            }
            """.trimIndent()
        )
        val requests = mutableListOf<Pair<String, String?>>()
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val session = LinkResolutionSession(
            api = PencurimovieProvider(),
            subtitleCallback = subtitles::add,
            callback = links::add,
            pageFetcher = { url, referer ->
                requests += url to referer
                when (url) {
                    voeUrl ->
                        """<script>window.location.href = '$targetUrl'</script>"""

                    targetUrl ->
                        """<script type="application/json">["$packed"]</script>"""

                    else -> error("Unexpected request: $url")
                }
            },
            extractorLoader = { _, _, _, _ ->
                error("The generic extractor must not consume a recognized VOE shell")
            },
            mediaLinkProbe = { it }
        )

        assertTrue(session.resolve(voeUrl, "https://provider.example.test/movie"))
        assertEquals(
            listOf<Pair<String, String?>>(
                voeUrl to "https://provider.example.test/movie",
                targetUrl to voeUrl
            ),
            requests
        )
        assertEquals(setOf(hlsUrl, directUrl), links.map { it.url }.toSet())
        assertTrue(links.any { it.url == hlsUrl && it.type == ExtractorLinkType.M3U8 })
        assertTrue(links.all { it.referer == targetUrl })
        assertEquals("https://player.example.test/vtt/current_id.srt", subtitles.single().url)
    }

    @Test
    fun `rejects unsafe redirects and malformed packed payloads`() {
        assertNull(
            VoePlayerParser.redirectTarget(
                "<script>location.href='http://127.0.0.1/private'</script>",
                "https://voe.sx/e/current"
            )
        )
        assertNull(
            VoePlayerParser.playback(
                """<script type="application/json">["not-packed"]</script>""",
                "https://player.example.test/e/current"
            )
        )
    }

    @Test
    fun `skips an earlier decodable decoy config without playable sources`() {
        val decoy = pack("""{"captions":[]}""")
        val valid = pack(
            """
            {
              "source":"https://cdn.example.test/current/master.m3u8",
              "captions":[]
            }
            """.trimIndent()
        )
        val html =
            """
            <script type="application/json">["$decoy"]</script>
            <script type="application/json">["$valid"]</script>
            """.trimIndent()

        assertEquals(
            listOf("https://cdn.example.test/current/master.m3u8"),
            VoePlayerParser.playback(
                html,
                "https://voe.sx/e/current"
            )?.sources?.map { it.url }
        )
    }

    private fun pack(json: String): String {
        val innerBase64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val shifted = innerBase64.reversed()
            .map { (it.code + 3).toChar() }
            .joinToString("")
            .toByteArray(Charsets.ISO_8859_1)
        return rot13(Base64.getEncoder().encodeToString(shifted))
    }

    private fun rot13(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    in 'A'..'Z' -> 'A' + ((char - 'A' + 13) % 26)
                    in 'a'..'z' -> 'a' + ((char - 'a' + 13) % 26)
                    else -> char
                }
            )
        }
    }
}
