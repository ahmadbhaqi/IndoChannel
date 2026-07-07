package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jsoup.Jsoup

class ProviderHtmlParserTest {
    @Test
    fun `firstIframeSource reads lazy iframe attributes before src`() {
        val element = Jsoup.parse(
            """
            <iframe
                src="https://fallback.example/embed"
                data-src="https://data-src.example/embed"
                data-litespeed-src="https://lazy.example/embed">
            </iframe>
            """.trimIndent()
        ).selectFirst("iframe")

        assertEquals("https://lazy.example/embed", ProviderHtmlParser.firstIframeSource(element))
    }

    @Test
    fun `firstIframeSource ignores blank and script iframe values`() {
        val blank = Jsoup.parse("""<iframe data-src=" " src=""></iframe>""").selectFirst("iframe")
        val script = Jsoup.parse("""<iframe src="javascript:void(0)"></iframe>""").selectFirst("iframe")

        assertNull(ProviderHtmlParser.firstIframeSource(blank))
        assertNull(ProviderHtmlParser.firstIframeSource(script))
    }

    @Test
    fun `muviproAjaxRequests returns post id and tab pairs`() {
        val document = Jsoup.parse(
            """
            <div id="muvipro_player_content_id" data-id="321"></div>
            <div class="tab-content-ajax" id="player1"></div>
            <div class="tab-content-ajax" id="player2"></div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MuviproAjaxRequest(postId = "321", tab = "player1"),
                MuviproAjaxRequest(postId = "321", tab = "player2")
            ),
            ProviderHtmlParser.muviproAjaxRequests(document)
        )
    }

    @Test
    fun `muviproAjaxRequests returns empty when post id is missing`() {
        val document = Jsoup.parse("""<div class="tab-content-ajax" id="player1"></div>""")

        assertEquals(emptyList(), ProviderHtmlParser.muviproAjaxRequests(document))
    }
}
