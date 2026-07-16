package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in checks against the current upstream sites. */
class FilmapikLiveTest {
    @Test
    fun `filmapik emits a current media link`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = withTimeout(90_000) {
            FilmapikProvider().loadLinks(
                "https://filmapik.college/nonton-film-summers-last-resort-2026-subtitle-indonesia",
                false,
                subtitles::add,
                links::add
            )
        }

        val probes = links.associate { link ->
            val code = try {
                withTimeout(30_000) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + ("Range" to "bytes=0-31"),
                        timeout = 30L
                    ).code
                }
            } catch (_: Exception) {
                null
            }
            link.url to code
        }
        println("Filmapik loaded=$loaded probes=$probes")
        assertTrue(loaded && links.isNotEmpty(), "Filmapik did not emit a media link")
        assertTrue(
            probes.values.any { it in 200..299 },
            "Filmapik emitted no reachable media URL: $probes"
        )
    }
}
