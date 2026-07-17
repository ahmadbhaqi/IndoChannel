package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

class PlaySobatResolutionTest {
    @Suppress("DEPRECATION_ERROR")
    private fun link(url: String): ExtractorLink = ExtractorLink(
        "test",
        "test",
        url,
        "",
        0,
        ExtractorLinkType.M3U8,
        emptyMap(),
        null
    )

    @Test
    fun `PlaySobat mirrors defer Abyss chunks and discard dead advertising aliases`() {
        val urls = listOf(
            "https://mdfx9dc8n.net/e/mixdrop",
            "https://dintezuvio.com/embed/dead",
            "https://omg10.com/redirect/ad",
            "https://www.omg10.com/redirect/another-ad",
            "https://cloudplay.p2pstream.vip/#torrent",
            "https://dood.la/e/slow",
            "https://hglink.to/e/wish",
            "https://abyssplayer.com/current"
        )

        assertEquals(
            listOf(
                "https://hglink.to/e/wish",
                "https://mdfx9dc8n.net/e/mixdrop",
                "https://dood.la/e/slow",
                "https://cloudplay.p2pstream.vip/#torrent",
                "https://abyssplayer.com/current"
            ),
            orderPlaySobatMirrorUrls(urls)
        )
    }

    @Test
    fun `PlaySobat timeout advances to a working sibling and stops after emission`() = runBlocking {
        val requests = mutableListOf<String>()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> "<html><body>player</body></html>" },
            extractorLoader = { url, _, _, callback ->
                requests += url
                when (url) {
                    "https://slow.example/embed" -> awaitCancellation()
                    "https://fast.example/embed" -> {
                        callback(link("https://cdn.example/master.m3u8"))
                        true
                    }
                    else -> error("redundant mirror should not be requested")
                }
            },
            playSobatUrlParser = {
                listOf(
                    "https://slow.example/embed",
                    "https://fast.example/embed",
                    "https://unused.example/embed"
                )
            },
            playSobatMirrorTimeoutMs = 25
        )

        assertTrue(session.resolve("https://playsobat.xyz/e/current", "https://provider.example/item"))
        assertEquals(
            listOf("https://slow.example/embed", "https://fast.example/embed"),
            requests
        )
        assertEquals("https://cdn.example/master.m3u8", links.single().url)
    }

    @Test
    fun `PlaySobat mirror timeout does not swallow external cancellation`(): Unit = runBlocking {
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { _, _ -> "<html><body>player</body></html>" },
            extractorLoader = { _, _, _, _ -> throw CancellationException("cancelled by caller") },
            playSobatUrlParser = { listOf("https://slow.example/embed") },
            playSobatMirrorTimeoutMs = 1_000
        )

        assertFailsWith<CancellationException> {
            runBlocking {
                session.resolve("https://playsobat.xyz/e/current", "https://provider.example/item")
            }
        }
    }
}
