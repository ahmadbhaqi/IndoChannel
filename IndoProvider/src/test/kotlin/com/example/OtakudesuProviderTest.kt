package com.example

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
    fun `episode parser accepts provider absolute links when response base uri is missing`() {
        val document = Jsoup.parse(
            """
            <div class="episodelist">
              <ul>
                <li>
                  <a href="https://otakudesu.blog/episode/sakh-episode-4-sub-indo/">
                    Sora wa Akai Kawa no Hotori Episode 4 Subtitle Indonesia
                  </a>
                </li>
              </ul>
            </div>
            """.trimIndent()
        )

        val episodes = OtakudesuEpisodeParser.episodes(
            document,
            "https://otakudesu.blog/"
        )

        assertEquals(listOf(4), episodes.map { it.number })
        assertEquals(
            listOf("https://otakudesu.blog/episode/sakh-episode-4-sub-indo/"),
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

    @Test
    fun `playback scheduler keeps later quality alternatives after the first success`() = runBlocking {
        val attempts = mutableListOf<Pair<String, Int>>()
        val mirrors = listOf(
            OtakudesuProvider.ResponseSources("low", "one", "720p"),
            OtakudesuProvider.ResponseSources("high", "two", "1080p")
        )

        val loaded = OtakudesuPlaybackScheduler.resolve(
            mirrors = mirrors,
            canContinue = { true },
            playerSources = { mirror ->
                listOf("https://player.example/${mirror.id}")
            },
            sourceResolver = { source, quality ->
                attempts += source to quality
                true
            }
        )

        assertTrue(loaded)
        assertEquals(
            listOf(
                "https://player.example/low" to 720,
                "https://player.example/high" to 1080
            ),
            attempts
        )
    }

    @Test
    fun `playback scheduler continues after one rotating mirror fails`() = runBlocking {
        val attempts = mutableListOf<String>()
        val mirrors = listOf(
            OtakudesuProvider.ResponseSources("broken", "one", "480p"),
            OtakudesuProvider.ResponseSources("healthy", "two", "720p")
        )

        val loaded = OtakudesuPlaybackScheduler.resolve(
            mirrors = mirrors,
            canContinue = { true },
            playerSources = { mirror ->
                attempts += mirror.id
                if (mirror.id == "broken") error("Rotating mirror failed")
                listOf("https://player.example/${mirror.id}")
            },
            sourceResolver = { _, _ -> true }
        )

        assertTrue(loaded)
        assertEquals(listOf("broken", "healthy"), attempts)
    }

    @Test
    fun `explicit mirror quality overrides extractor quality without rebuilding its link`() = runBlocking {
        val link = newExtractorLink(
            "FileDon",
            "FileDon",
            "https://media.example/master.m3u8",
            ExtractorLinkType.M3U8
        ) {
            quality = 360
        }

        assertSame(link, link.withOtakudesuQuality(1080))
        assertEquals(1080, link.quality)
        assertSame(link, link.withOtakudesuQuality(Qualities.Unknown.value))
        assertEquals(1080, link.quality)
    }
}
