package com.example

import java.net.URLDecoder
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimeStreamParserTest {
    @Test
    fun `animeindo reads extensionless hls declared by jwplayer`() {
        val html = """
            player.setup({
                sources: [{
                    "file":"/play.php?n=current-episode",
                    "type":"application/vnd.apple.mpegurl"
                }]
            });
        """.trimIndent()

        assertEquals(
            listOf(
                InlinePlayerSource(
                    url = "/play.php?n=current-episode",
                    mimeType = "application/vnd.apple.mpegurl"
                )
            ),
            InlineDataParser.inlinePlayerSources(html)
        )
    }

    @Test
    fun `animeindo recognizes extensionless blogger mp4`() {
        assertTrue(
            InlineDataParser.isDirectHttpVideo(
                "https://rr2---sn.example.googlevideo.com/videoplayback?expire=1&mime=video%2Fmp4"
            )
        )
        assertFalse(InlineDataParser.isDirectHttpVideo("https://example.com/player.php?id=1"))
    }

    @Test
    fun `animeindo applies hls mime only to its matching source`() {
        assertEquals(
            ExtractorLinkType.VIDEO,
            animeindoSourceType("https://cdn.example/movie.mp4", sourceDeclaresHls = true)
        )
        assertEquals(
            ExtractorLinkType.M3U8,
            animeindoSourceType("https://cdn.example/play.php?id=1", sourceDeclaresHls = true)
        )
        assertEquals(
            null,
            animeindoSourceType("https://player.example/embed/current", sourceDeclaresHls = false)
        )
    }

    @Test
    fun `inline player sources do not leak one mime type to sibling urls`() {
        val html = """
            sources: [
                {file:"/play.php?n=current",type:"application/vnd.apple.mpegurl"},
                {file:"https://player.example/embed/backup"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                InlinePlayerSource("/play.php?n=current", "application/vnd.apple.mpegurl"),
                InlinePlayerSource("https://player.example/embed/backup", null)
            ),
            InlineDataParser.inlinePlayerSources(html)
        )
    }

    @Test
    fun `blogger bootstrap and rpc response yield playable video`() {
        val bootstrap = InlineDataParser.bloggerBootstrap(
            """window.WIZ_global_data={"FdrFJe":"-12345","cfb2h":"boq_build_20260709"};"""
        )
        assertEquals(BloggerBootstrap("-12345", "boq_build_20260709"), bootstrap)

        val token = "AD6v5dx-current"
        val payload = InlineDataParser.bloggerRpcPayload(token)
        assertTrue(payload.contains("WcwnYd"))
        assertTrue(payload.contains(token))

        val formBody = InlineDataParser.bloggerRpcFormBody(token)
        assertTrue(formBody.startsWith("f.req="))
        assertTrue(formBody.endsWith("&"), "Blogger rejects the otherwise valid form without its trailing separator")
        assertEquals(
            payload,
            URLDecoder.decode(formBody.removePrefix("f.req=").removeSuffix("&"), Charsets.UTF_8.name())
        )

        val inner = """[1,null,[["https://rr2---sn.example.googlevideo.com/videoplayback?expire=1&mime=video/mp4",[18]]],"https://example.com/thumb.jpg"]"""
        val escapedInner = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .writeValueAsString(inner)
        val response = """)]}\'

            [["wrb.fr","WcwnYd",$escapedInner,null,null,null,"generic"]]
        """.trimIndent()

        assertEquals(
            listOf("https://rr2---sn.example.googlevideo.com/videoplayback?expire=1&mime=video/mp4"),
            InlineDataParser.bloggerVideoUrls(response)
        )
    }

    @Test
    fun `blogger legacy config parser handles nested objects`() {
        val response = """
            <script>
            var VIDEO_CONFIG = {
              "meta":{"title":"A brace } inside a string"},
              "streams":[{
                "play_url":"https://rr2---sn.example.googlevideo.com/videoplayback?expire=1&mime=video/mp4",
                "format":{"mime":"video/mp4","itag":18}
              }]
            };
            window.afterConfig = true;
            </script>
        """.trimIndent()

        assertEquals(
            listOf("https://rr2---sn.example.googlevideo.com/videoplayback?expire=1&mime=video/mp4"),
            InlineDataParser.bloggerVideoUrls(response)
        )
    }

    @Test
    fun `blogger token parser rejects unrelated hosts`() {
        val token = InlineDataParser.bloggerToken(
            "https://www.blogger.com/video.g?token=AD6v5dx-current"
        )
        assertEquals("AD6v5dx-current", token)
        assertEquals(null, InlineDataParser.bloggerToken("https://example.com/video.g?token=fake"))
        assertEquals(null, InlineDataParser.bloggerToken("https://evilblogger.com/video.g?token=fake"))
    }

    @Test
    fun `kuronime source id accepts either javascript quote style`() {
        assertEquals("double", InlineDataParser.kuronimeSourceId("var _0xa100d42aa = \"double\";"))
        assertEquals("single", InlineDataParser.kuronimeSourceId("var _0xa100d42aa = 'single';"))
    }

}
