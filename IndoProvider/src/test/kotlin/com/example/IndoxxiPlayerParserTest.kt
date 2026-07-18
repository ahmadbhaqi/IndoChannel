package com.example

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

class IndoxxiPlayerParserTest {
    @Test
    fun `AJAX fragment keeps script-only media candidates`() {
        val pageUrl = "https://filmbioskop21.lk21.in.net/indonesia/current/"
        val document = Jsoup.parse(
            """
            <div class="player-shell"></div>
            <script>
              const player = { file: "https://cdn.current.example/video/master.m3u8" };
            </script>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            listOf("https://cdn.current.example/video/master.m3u8"),
            IndoxxiPlayerParser.pageMediaUrls(document, pageUrl)
        )
    }

    @Test
    fun `player page combines DOM iframe and packed script sources without duplicates`() {
        val pageUrl = "https://filmbioskop21.lk21.in.net/player/current/"
        val document = Jsoup.parse(
            """
            <iframe data-src="https://abyssplayer.com/embed/current"></iframe>
            <video><source src="/media/current.mp4"></video>
            <script>var source = { src: "https://cdn.current.example/master.m3u8" };</script>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            listOf(
                "https://abyssplayer.com/embed/current",
                "https://filmbioskop21.lk21.in.net/media/current.mp4",
                "https://cdn.current.example/master.m3u8"
            ),
            IndoxxiPlayerParser.pageMediaUrls(document, pageUrl)
        )
    }
}
