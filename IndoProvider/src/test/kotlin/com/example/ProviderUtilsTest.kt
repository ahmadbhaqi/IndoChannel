package com.example

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderUtilsTest {

    // --- getBaseUrl ---

    @Test
    fun getBaseUrl_https() {
        assertEquals("https://example.com", getBaseUrl("https://example.com/path/to/page?q=1"))
    }

    @Test
    fun getBaseUrl_http() {
        assertEquals("http://example.com", getBaseUrl("http://example.com/foo"))
    }

    @Test
    fun getBaseUrl_withPort() {
        assertEquals("https://example.com", getBaseUrl("https://example.com:443/test"))
    }

    @Test
    fun getBaseUrl_rootOnly() {
        assertEquals("https://example.com", getBaseUrl("https://example.com"))
    }

    @Test
    fun getBaseUrl_subdomain() {
        assertEquals("https://sub.example.com", getBaseUrl("https://sub.example.com/page"))
    }

    // --- fixImageQuality ---

    @Test
    fun fixImageQuality_removesResolutionSuffix() {
        assertEquals(
            "https://img.site.com/poster.jpg",
            "https://img.site.com/poster-300x450.jpg".fixImageQuality()
        )
    }

    @Test
    fun fixImageQuality_noSuffix() {
        assertEquals(
            "https://img.site.com/poster.jpg",
            "https://img.site.com/poster.jpg".fixImageQuality()
        )
    }

    @Test
    fun fixImageQuality_nullReturnsNull() {
        assertNull((null as String?).fixImageQuality())
    }

    @Test
    fun fixImageQuality_multipleSuffixes() {
        assertEquals(
            "https://img.site.com/thumb.png",
            "https://img.site.com/thumb-150x150.png".fixImageQuality()
        )
    }

    @Test
    fun fixImageQuality_largeResolution() {
        assertEquals(
            "https://img.site.com/poster.jpg",
            "https://img.site.com/poster-1920x1080.jpg".fixImageQuality()
        )
    }

    // --- getImageAttr ---

    private fun parseElement(html: String, baseUri: String = "https://example.com/"): org.jsoup.nodes.Element {
        val doc = Jsoup.parse(html, baseUri)
        return doc.selectFirst("img")!!
    }

    @Test
    fun getImageAttr_dataSrc() {
        val el = parseElement("""<img data-src="https://cdn.site.com/img.jpg" src="placeholder.jpg">""")
        assertEquals("https://cdn.site.com/img.jpg", el.getImageAttr())
    }

    @Test
    fun getImageAttr_dataLazySrc() {
        val el = parseElement("""<img data-lazy-src="https://cdn.site.com/lazy.jpg" src="placeholder.jpg">""")
        assertEquals("https://cdn.site.com/lazy.jpg", el.getImageAttr())
    }

    @Test
    fun getImageAttr_srcset() {
        val el = parseElement("""<img srcset="https://cdn.site.com/img-300w.jpg 300w, https://cdn.site.com/img-600w.jpg 600w">""")
        assertEquals("https://cdn.site.com/img-300w.jpg", el.getImageAttr())
    }

    @Test
    fun getImageAttr_fallbackSrc() {
        val el = parseElement("""<img src="https://cdn.site.com/fallback.jpg">""")
        assertEquals("https://cdn.site.com/fallback.jpg", el.getImageAttr())
    }

    @Test
    fun getImageAttr_dataSrcPriority() {
        val el = parseElement("""<img data-src="https://cdn.site.com/preferred.jpg" data-lazy-src="https://cdn.site.com/lazy.jpg" src="fallback.jpg">""")
        assertEquals("https://cdn.site.com/preferred.jpg", el.getImageAttr())
    }

    // --- getIframeAttr ---

    private fun parseIframe(html: String, baseUri: String = "https://example.com/"): org.jsoup.nodes.Element? {
        val doc = Jsoup.parse(html, baseUri)
        return doc.selectFirst("iframe")
    }

    @Test
    fun getIframeAttr_litespeedSrc() {
        val el = parseIframe("""<iframe data-litespeed-src="https://embed.site.com/v1" src="https://embed.site.com/v2"></iframe>""")
        assertEquals("https://embed.site.com/v1", el.getIframeAttr())
    }

    @Test
    fun getIframeAttr_fallbackSrc() {
        val el = parseIframe("""<iframe src="https://embed.site.com/player"></iframe>""")
        assertEquals("https://embed.site.com/player", el.getIframeAttr())
    }

    @Test
    fun getIframeAttr_nullElement() {
        assertNull((null as org.jsoup.nodes.Element?).getIframeAttr())
    }

    @Test
    fun getIframeAttr_emptyLitespeedFallsToSrc() {
        val el = parseIframe("""<iframe data-litespeed-src="" src="https://embed.site.com/real"></iframe>""")
        assertEquals("https://embed.site.com/real", el.getIframeAttr())
    }

    // --- fixIframeUrl ---

    @Test
    fun fixIframeUrl_protocolRelative() {
        assertEquals(
            "https://embed.site.com/video",
            fixIframeUrl("//embed.site.com/video") { "https://example.com$it" }
        )
    }

    @Test
    fun fixIframeUrl_http() {
        assertEquals(
            "http://embed.site.com/video",
            fixIframeUrl("http://embed.site.com/video") { "https://example.com$it" }
        )
    }

    @Test
    fun fixIframeUrl_https() {
        assertEquals(
            "https://embed.site.com/video",
            fixIframeUrl("https://embed.site.com/video") { "https://example.com$it" }
        )
    }

    @Test
    fun fixIframeUrl_relativePath() {
        assertEquals(
            "https://example.com/embed/123",
            fixIframeUrl("/embed/123") { "https://example.com$it" }
        )
    }

    // --- parseQualityFromString ---

    @Test
    fun parseQualityFromString_720p() {
        assertEquals(720, parseQualityFromString("720p"))
    }

    @Test
    fun parseQualityFromString_1080p() {
        assertEquals(1080, parseQualityFromString("1080p"))
    }

    @Test
    fun parseQualityFromString_480P_uppercase() {
        assertEquals(480, parseQualityFromString("480P"))
    }

    @Test
    fun parseQualityFromString_embedded() {
        assertEquals(720, parseQualityFromString("[720p] Sub Indo"))
    }

    @Test
    fun parseQualityFromString_null() {
        assertEquals(0, parseQualityFromString(null))
    }

    @Test
    fun parseQualityFromString_noMatch() {
        assertEquals(0, parseQualityFromString("HD"))
    }

    // --- parseEpisodeNumber ---

    @Test
    fun parseEpisodeNumber_standard() {
        assertEquals(5, parseEpisodeNumber("Episode 5"))
    }

    @Test
    fun parseEpisodeNumber_noSpace() {
        assertEquals(12, parseEpisodeNumber("Episode12"))
    }

    @Test
    fun parseEpisodeNumber_inContext() {
        assertEquals(3, parseEpisodeNumber("Naruto Shippuden Episode 3 Sub Indo"))
    }

    @Test
    fun parseEpisodeNumber_noMatch() {
        assertNull(parseEpisodeNumber("Movie Title"))
    }

    // --- cleanTitleForEpisode ---

    @Test
    fun cleanTitleForEpisode_removesPermalinkPrefix() {
        assertEquals("Episode 5", cleanTitleForEpisode("Permalink ke Episode 5"))
    }

    @Test
    fun cleanTitleForEpisode_caseInsensitive() {
        assertEquals("Episode 10", cleanTitleForEpisode("permalink ke Episode 10"))
    }

    @Test
    fun cleanTitleForEpisode_noPrefix() {
        assertEquals("Episode 1", cleanTitleForEpisode("Episode 1"))
    }

    @Test
    fun cleanTitleForEpisode_trims() {
        assertEquals("Episode 7", cleanTitleForEpisode("  Episode 7  "))
    }
}
