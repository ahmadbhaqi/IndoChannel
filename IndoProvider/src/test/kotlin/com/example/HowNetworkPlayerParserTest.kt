package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HowNetworkPlayerParserTest {
    private val playerUrl = "https://cloud.hownetwork.xyz/player.php?id=current_01"

    @Test
    fun `builds only the strict same-host AJAX request`() {
        val request = HowNetworkPlayerParser.apiRequest(playerUrl)!!

        assertEquals(
            "https://cloud.hownetwork.xyz/api.php?id=current_01",
            request.apiUrl
        )
        assertEquals("https://playeriframe.sbs/", request.form["r"])
        assertEquals("stream.hownetwork.xyz", request.form["d"])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        assertNull(HowNetworkPlayerParser.apiRequest("https://evil.example/player.php?id=current_01"))
        assertNull(HowNetworkPlayerParser.apiRequest("https://cloud.hownetwork.xyz/?id=../bad"))
    }

    @Test
    fun `parses bounded safe media and rejects private URLs`() {
        val json = """
            {"data":[
              {"file":"https://cdn.example/current/master.m3u8","label":"720p"},
              {"file":"http://127.0.0.1/private.mp4","label":"bad"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                HowNetworkMediaSource(
                    "720p",
                    "https://cdn.example/current/master.m3u8",
                    ExtractorLinkType.M3U8
                )
            ),
            HowNetworkPlayerParser.sources(json, playerUrl)
        )
    }

    @Test
    fun `resolver emits API media and never the player shell`() = runBlocking {
        val mediaUrl = "https://cdn.example/current/master.m3u8"
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            howNetworkApiFetcher = { request ->
                assertEquals(playerUrl, request.playerUrl)
                """{"data":[{"file":"$mediaUrl","label":"720p"}]}"""
            },
            pageFetcher = { _, _ -> error("HowNetwork must use its AJAX API") },
            extractorLoader = { _, _, _, _ -> error("Generic extractor must not run") }
        )

        assertTrue(session.resolve(playerUrl, "https://rebahinxxi3.lol/movie/current/"))
        assertEquals(listOf(mediaUrl), links.map { it.url })
    }
}
