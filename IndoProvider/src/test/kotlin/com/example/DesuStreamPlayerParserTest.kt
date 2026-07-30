package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesuStreamPlayerParserTest {
    private val wrapperUrl =
        "https://desustream.info/current/current/v5/index.php?id=fixture"
    private val wrapperHtml =
        """
        <script>
          const res = await fetch(`?mode=json&_=${'$'}{Date.now()}`, {
            cache: "no-store"
          });
        </script>
        """.trimIndent()

    @Test
    fun `builds the same-page JSON endpoint and validates its nested video`() {
        assertEquals(
            "https://desustream.info/current/current/v5/index.php?mode=json&_=1234",
            DesuStreamPlayerParser.apiUrl(wrapperHtml, wrapperUrl, 1234L)
        )
        assertEquals(
            "https://nested.example.test/embed/current",
            DesuStreamPlayerParser.videoUrl(
                """{"video":"https://nested.example.test/embed/current"}""",
                wrapperUrl
            )
        )
        assertNull(
            DesuStreamPlayerParser.videoUrl(
                """{"video":"https://nested.example.test/embed/current?token="}""",
                wrapperUrl
            )
        )
    }

    @Test
    fun `resolution session follows DesuStream JSON with the wrapper referer`() = runBlocking {
        val mediaUrl = "https://cdn.example.test/current/master.m3u8"
        val apiRequests = mutableListOf<Pair<String, String?>>()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = OtakudesuProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == wrapperUrl) wrapperHtml else error("Unexpected page: $url")
            },
            playerApiFetcher = { url, referer, _ ->
                apiRequests += url to referer
                """{"video":"$mediaUrl"}"""
            },
            extractorLoader = { _, _, _, _ ->
                error("Generic extraction must not run for a recognized DesuStream wrapper")
            },
            mediaLinkProbe = { it }
        )

        assertTrue(session.resolve(wrapperUrl, "https://otakudesu.blog/episode/current/"))
        assertEquals(1, apiRequests.size)
        assertTrue(apiRequests.single().first.contains("?mode=json&_="))
        assertEquals(wrapperUrl, apiRequests.single().second)
        assertEquals(mediaUrl, links.single().url)
        assertEquals(wrapperUrl, links.single().referer)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
    }
}
