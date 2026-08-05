package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jsoup.Jsoup

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
}
