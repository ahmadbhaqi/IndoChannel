package com.example

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure link-collection logic used by the gmr/"gomovies" theme providers
 * (Ngefilm, Gomov, Dutamovie, Pusatfilm). These verify the part of the fix that no longer
 * relies on a single source: the default embedded player iframe AND every download/mirror
 * link must be surfaced so a single dead server can't hide the valid ones.
 */
class GmrLinksTest {

    private val fixUrl: (String) -> String = { "https://site.com$it" }

    private fun collect(html: String): List<String> {
        val doc = Jsoup.parse(html, "https://site.com/movie/")
        return collectGmrDirectLinks(doc, fixUrl)
    }

    @Test
    fun collectsDefaultEmbedIframe() {
        val links = collect(
            """<div class="gmr-embed-responsive"><iframe src="https://embed.host/v/abc"></iframe></div>"""
        )
        assertEquals(listOf("https://embed.host/v/abc"), links)
    }

    @Test
    fun collectsMovieplayIframe() {
        val links = collect(
            """<div class="movieplay"><iframe src="https://embed.host/play/1"></iframe></div>"""
        )
        assertEquals(listOf("https://embed.host/play/1"), links)
    }

    @Test
    fun prefersLitespeedSrcAttr() {
        val links = collect(
            """<div class="gmr-embed-responsive"><iframe data-litespeed-src="https://embed.host/real" src="https://embed.host/lazy"></iframe></div>"""
        )
        assertEquals(listOf("https://embed.host/real"), links)
    }

    @Test
    fun normalizesProtocolRelativeIframe() {
        val links = collect(
            """<div class="gmr-embed-responsive"><iframe src="//embed.host/v/xyz"></iframe></div>"""
        )
        assertEquals(listOf("https://embed.host/v/xyz"), links)
    }

    @Test
    fun normalizesRelativeIframeWithFixUrl() {
        val links = collect(
            """<div class="movieplay"><iframe src="/embed/55"></iframe></div>"""
        )
        assertEquals(listOf("https://site.com/embed/55"), links)
    }

    @Test
    fun collectsDownloadMirrorLinks() {
        val links = collect(
            """
            <div class="gmr-download-wrap">
              <a href="https://mirror.host/file1">720p</a>
              <a href="https://mirror.host/file2">1080p</a>
            </div>
            """.trimIndent()
        )
        assertTrue(links.contains("https://mirror.host/file1"))
        assertTrue(links.contains("https://mirror.host/file2"))
    }

    @Test
    fun capturesBothEmbedAndDownloadLinks() {
        val links = collect(
            """
            <div class="gmr-embed-responsive"><iframe src="https://embed.host/v/main"></iframe></div>
            <div id="download"><a href="https://mirror.host/dl">Download</a></div>
            """.trimIndent()
        )
        assertEquals(2, links.size)
        assertTrue(links.contains("https://embed.host/v/main"))
        assertTrue(links.contains("https://mirror.host/dl"))
    }

    @Test
    fun deduplicatesRepeatedUrls() {
        val links = collect(
            """
            <div class="gmr-embed-responsive"><iframe src="https://embed.host/same"></iframe></div>
            <div class="movieplay"><iframe src="https://embed.host/same"></iframe></div>
            """.trimIndent()
        )
        assertEquals(listOf("https://embed.host/same"), links)
    }

    @Test
    fun ignoresBlankIframeSrc() {
        val links = collect(
            """<div class="gmr-embed-responsive"><iframe src=""></iframe></div>"""
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun ignoresNonHttpDownloadLinks() {
        val links = collect(
            """<div class="gmr-download-wrap"><a href="javascript:void(0)">x</a></div>"""
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun emptyWhenNoPlayer() {
        val links = collect("""<div class="some-other-content"><p>No player here</p></div>""")
        assertTrue(links.isEmpty())
    }
}
