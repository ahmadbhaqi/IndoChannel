package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KawanfilmPlayerParserTest {
    @Test
    fun `all ajax player tabs are assembled into one mirror race`() {
        val detailUrl = "https://web.kawanfilm21.co/download-current/"
        val ajaxUrl = "https://web.kawanfilm21.co/wp-admin/admin-ajax.php"
        val pages = listOf(
            Jsoup.parse(
                """<iframe src="https://vidshare.rpmvid.com/#one"></iframe>""",
                ajaxUrl
            ) to ajaxUrl,
            Jsoup.parse(
                """<iframe src="https://winvids.strp2p.com/#two"></iframe>""",
                ajaxUrl
            ) to ajaxUrl,
            Jsoup.parse(
                """<iframe src="https://abyssplayer.com/three"></iframe>""",
                ajaxUrl
            ) to ajaxUrl,
            Jsoup.parse(
                """<iframe src="https://morencius.com/embed/four"></iframe>""",
                ajaxUrl
            ) to ajaxUrl
        )

        val candidates = KawanfilmPlayerParser.ajaxResolutionCandidates(
            pages = pages,
            detailUrl = detailUrl
        )

        assertEquals(
            listOf(
                "https://vidshare.rpmvid.com/#one",
                "https://winvids.strp2p.com/#two",
                "https://abyssplayer.com/three",
                "https://morencius.com/embed/four"
            ),
            candidates.map { candidate -> candidate.url }
        )
        assertTrue(candidates.all { candidate -> candidate.referer == detailUrl })
    }

    @Test
    fun `duplicate ajax mirrors enter the race only once`() {
        val ajaxUrl = "https://web.kawanfilm21.co/wp-admin/admin-ajax.php"
        val duplicate = Jsoup.parse(
            """
                <iframe src="https://morencius.com/embed/current"></iframe>
                <iframe data-src="https://morencius.com/embed/current"></iframe>
            """.trimIndent(),
            ajaxUrl
        )

        assertEquals(
            1,
            KawanfilmPlayerParser.ajaxResolutionCandidates(
                pages = listOf(duplicate to ajaxUrl),
                detailUrl = "https://web.kawanfilm21.co/download-current/"
            ).size
        )
    }

    @Test
    fun `later ajax winner is not starved by many hanging direct candidates`() = runBlocking {
        val winner = "ajax-winner"

        val resolved = withTimeout(500) {
            resolveKawanfilmMirrorRace(
                directCandidates = (1..48).map { index -> "direct-$index" },
                ajaxCandidates = listOf("ajax-hanging", winner),
                maxConcurrency = 3,
                canContinue = { true }
            ) { candidate ->
                if (candidate == winner) {
                    true
                } else {
                    awaitCancellation()
                }
            }
        }

        assertTrue(resolved)
    }

    @Test
    fun `ajax collection keeps completed pages when its shared deadline expires`() = runBlocking {
        val pages = collectKawanfilmAjaxResults(
            requests = listOf("fast", "hanging"),
            totalTimeoutMs = 100,
            maxConcurrency = 2
        ) { request ->
            if (request == "hanging") {
                delay(5_000)
            }
            "page-$request"
        }

        assertEquals(listOf("page-fast"), pages)
    }
}
