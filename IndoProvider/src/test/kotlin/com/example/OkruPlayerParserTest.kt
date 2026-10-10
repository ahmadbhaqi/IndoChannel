package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkruPlayerParserTest {
    private val mapper = jacksonObjectMapper()
    private val player = "https://ok.ru/videoembed/123"

    private fun page(metadata: Any): String {
        val options = mapper.writeValueAsString(mapOf("flashvars" to mapOf("metadata" to metadata)))
        return Jsoup.parse("<div></div>").apply {
            selectFirst("div")!!.attr("data-options", options)
        }.html()
    }

    @Test
    fun `current object metadata exposes only safe media for the requested movie`() {
        val metadata = mapOf(
            "movie" to mapOf("id" to "123"),
            "hlsManifestUrl" to "https://vd240.okcdn.ru/video.m3u8?sig=current",
            "videos" to listOf(
                mapOf("name" to "sd", "url" to "https://vd240.okcdn.ru/?type=1"),
                mapOf("name" to "hd", "url" to "https://vd240.okcdn.ru/?type=2"),
                mapOf("name" to "full", "url" to "http://127.0.0.1/video.mp4"),
                mapOf("name" to "hd", "url" to "https://vd240.okcdn.ru/?type=other", "disallowed" to true)
            )
        )
        assertEquals(
            listOf(
                OkruMediaSource("https://vd240.okcdn.ru/video.m3u8?sig=current", 0, ExtractorLinkType.M3U8),
                OkruMediaSource("https://vd240.okcdn.ru/?type=2", 720, ExtractorLinkType.VIDEO),
                OkruMediaSource("https://vd240.okcdn.ru/?type=1", 480, ExtractorLinkType.VIDEO)
            ),
            OkruPlayerParser.sources(page(metadata), player)
        )
        assertEquals(emptyList(), OkruPlayerParser.sources(page(metadata), "https://ok.ru/videoembed/456"))
    }

    @Test
    fun `legacy string metadata remains compatible and foreign players are rejected`() {
        val metadata = mapper.writeValueAsString(mapOf(
            "movie" to mapOf("id" to "123"),
            "videos" to listOf(mapOf("name" to "hd", "url" to "https://vd240.okcdn.ru/?type=2"))
        ))
        assertEquals(1, OkruPlayerParser.sources(page(metadata), player).size)
        assertFalse(OkruPlayerParser.supports("https://evil.example/videoembed/123"))
        assertFalse(OkruPlayerParser.supports("https://ok.ru@evil.example/videoembed/123"))
        assertEquals(emptyList(), OkruPlayerParser.sources("malformed", player))
    }

    @Test
    fun `shared session resolves current OKru metadata for Animasu before generic extraction`() = runBlocking {
        val media = "https://cdn.example/current.m3u8"
        val html = page(mapOf("movie" to mapOf("id" to "123"), "hlsManifestUrl" to media))
        val links = mutableListOf<ExtractorLink>()
        var generic = false
        var probed = false
        val session = LinkResolutionSession(
            api = AnimasuProvider(), subtitleCallback = {}, callback = links::add,
            pageFetcher = { _, _ -> html },
            extractorLoader = { _, _, _, _ -> generic = true; false },
            mediaLinkProbe = { probed = true; it }
        )
        assertTrue(session.resolve(player, "https://animasu.love/episode/fixture/"))
        assertTrue(probed)
        assertFalse(generic)
        assertEquals(media, links.single().url)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
        assertEquals(player, links.single().referer)
        assertEquals("https://ok.ru", links.single().headers["Origin"])
    }

    @Test
    fun `shared OKru parser fetch probe and timeout failures keep generic extraction available`() = runBlocking {
        val media = "https://cdn.example/current.m3u8"
        val html = page(mapOf("movie" to mapOf("id" to "123"), "hlsManifestUrl" to media))
        val fallback = newExtractorLink("fixture", "fixture", "https://cdn.example/fallback.mp4", ExtractorLinkType.VIDEO)
        for (failure in listOf("parser", "fetch", "empty", "probe", "fetch-stall", "probe-stall")) {
            val links = mutableListOf<ExtractorLink>()
            var generic = false
            val session = LinkResolutionSession(
                api = AnimasuProvider(), subtitleCallback = {}, callback = links::add,
                pageFetcher = { _, _ ->
                    when (failure) {
                        "parser" -> "<html>changed player</html>"
                        "fetch" -> throw java.io.IOException("temporary page failure")
                        "empty" -> page(mapOf("movie" to mapOf("id" to "123")))
                        "fetch-stall" -> { delay(10_000); html }
                        else -> html
                    }
                },
                extractorLoader = { _, _, _, emit -> generic = true; emit(fallback); true },
                mediaLinkProbe = { link ->
                    when {
                        link.url == media && failure == "probe" -> null
                        link.url == media && failure == "probe-stall" -> { delay(10_000); link }
                        else -> link
                    }
                },
                candidateTimeoutMs = 1_500L
            )
            assertTrue(session.resolve(player, "https://animasu.love/episode/fixture/"), failure)
            assertTrue(generic, failure)
            assertEquals(fallback.url, links.single().url, failure)
        }
    }
}
