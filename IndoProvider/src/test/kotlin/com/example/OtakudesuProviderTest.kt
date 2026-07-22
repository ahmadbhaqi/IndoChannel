package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jsoup.Jsoup

class OtakudesuProviderTest {
    @Test
    fun `episode parser keeps only real same-host episode links across all lists`() {
        val document = Jsoup.parse(
            """
            <div class="episodelist">
              <h4>Example Batch</h4>
              <ul>
                <li><a href="/batch/example-batch-sub-indo/">Example [BATCH] Subtitle Indonesia</a></li>
              </ul>
            </div>
            <div class="episodelist">
              <h4>Example Episode List</h4>
              <ul>
                <li><a href="/episode/example-episode-14-sub-indo/">Example Episode 14 Subtitle Indonesia</a></li>
                <li><a href="https://otakudesu.blog/episode/example-episode-13-sub-indo/">Example Episode 13 Subtitle Indonesia</a></li>
                <li><a href="https://attacker.example/episode/example-episode-99-sub-indo/">Example Episode 99 Subtitle Indonesia</a></li>
              </ul>
            </div>
            <div class="episodelist">
              <h4>Example Lengkap</h4>
              <ul>
                <li><a href="/lengkap/example-sub-indo/">Example Sub Indo : Episode 1 - 14 (End)</a></li>
              </ul>
            </div>
            """.trimIndent(),
            "https://otakudesu.blog/anime/example-sub-indo/"
        )

        val episodes = OtakudesuEpisodeParser.episodes(document)

        assertEquals(listOf(14, 13), episodes.map { it.number })
        assertEquals(
            listOf(
                "/episode/example-episode-14-sub-indo/",
                "https://otakudesu.blog/episode/example-episode-13-sub-indo/"
            ),
            episodes.map { it.href }
        )
    }

    @Test
    fun `episode parser supports pages with a single episode list`() {
        val document = Jsoup.parse(
            """
            <div class="episodelist">
              <ul>
                <li><a href="/episode/example-episode-14-sub-indo/">Example Episode 14 Subtitle Indonesia</a></li>
                <li><a href="/episode/example-episode-13-sub-indo/">Example Episode 13 Subtitle Indonesia</a></li>
              </ul>
            </div>
            """.trimIndent()
        )

        val episodes = OtakudesuEpisodeParser.episodes(document)

        assertEquals(listOf(14, 13), episodes.map { it.number })
        assertEquals(
            listOf(
                "/episode/example-episode-14-sub-indo/",
                "/episode/example-episode-13-sub-indo/"
            ),
            episodes.map { it.href }
        )
    }

    @Test
    fun `episode parser selects the list containing episode anchors instead of a fixed index`() {
        val document = Jsoup.parse(
            """
            <div class="episodelist">
              <ul>
                <li><a href="/episode/example-episode-2-sub-indo/">Example Episode 2 Subtitle Indonesia</a></li>
                <li><a href="/episode/example-episode-1-sub-indo/">Example Episode 1 Subtitle Indonesia</a></li>
              </ul>
            </div>
            <div class="episodelist">
              <ul>
                <li><a href="/batch/example-batch-sub-indo/">Example Batch Subtitle Indonesia</a></li>
              </ul>
            </div>
            """.trimIndent()
        )

        val episodes = OtakudesuEpisodeParser.episodes(document)

        assertEquals(listOf(2, 1), episodes.map { it.number })
        assertEquals(
            listOf(
                "/episode/example-episode-2-sub-indo/",
                "/episode/example-episode-1-sub-indo/"
            ),
            episodes.map { it.href }
        )
    }
}
