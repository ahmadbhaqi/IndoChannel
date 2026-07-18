package com.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DutamovieSchedulingRegressionTest {
    @Test
    fun `eager candidates finish their own attempt without a shared cutoff`() = runBlocking {
        val attempted = mutableListOf<String>()

        val loaded = withTimeout(500) {
            DutamoviePlayerParser.resolveEagerCandidates(
                eager = listOf("slow-server", "healthy-server"),
                canContinue = { true }
            ) { candidate ->
                attempted += candidate
                if (candidate == "slow-server") delay(75)
                candidate == "healthy-server"
            }
        }

        assertTrue(loaded)
        assertEquals(listOf("slow-server", "healthy-server"), attempted)
    }

    @Test
    fun `post discovery scheduling cannot starve initial candidate three`() {
        val detailUrl = "https://austincomputerworks.org/movie/current/"
        val initialThree = "https://generic-initial.example/embed/three" to detailUrl
        val initialFour = "https://voe.sx/e/four" to detailUrl
        val discovered = listOf(
            "https://morencius.com/embed/one" to detailUrl,
            "https://abyssplayer.com/embed/two" to detailUrl,
            "https://embedpyrox.xyz/embed/three" to detailUrl,
            "https://another-discovered.example/embed/four" to detailUrl
        )

        val schedule = DutamoviePlayerParser.postDiscoverySchedule(
            deferredInitial = listOf(initialThree, initialFour),
            discovered = discovered
        )

        assertTrue(schedule.indexOf(initialThree) in 0..1, schedule.toString())
        assertTrue(schedule.indexOf(initialFour) in 2..3, schedule.toString())
        assertEquals(discovered.toSet() + setOf(initialThree, initialFour), schedule.toSet())
    }

    @Test
    fun `ajax candidates resolve relative URLs against response but refer to detail page`() {
        val responseUrl = "https://austincomputerworks.org/wp-admin/player/tab-one/"
        val detailUrl = "https://austincomputerworks.org/movie/current/"
        val document = Jsoup.parse(
            """
            <iframe src="../../../embeds/player-one"></iframe>
            <script>const source = { file: "../hls/master.m3u8", type: "hls" };</script>
            """.trimIndent(),
            responseUrl
        )

        assertEquals(
            listOf(
                "https://austincomputerworks.org/embeds/player-one" to detailUrl,
                "https://austincomputerworks.org/wp-admin/player/hls/master.m3u8" to detailUrl
            ),
            DutamoviePlayerParser.ajaxMediaCandidates(document, responseUrl, detailUrl)
        )
    }
}
