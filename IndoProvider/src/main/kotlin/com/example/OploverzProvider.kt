package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://plus.oploverz.ltd"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "series" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        val document = app.get("$mainUrl$path").document
        return newHomePageResponse(request.name, document.toSeriesResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.toSeriesResults().filter { it.name.contains(query, ignoreCase = true) }
    }

    private fun Document.toSeriesResults(): List<AnimeSearchResponse> {
        return select("a[href^='/series/']:has(img), a[href^='$mainUrl/series/']:has(img)")
            .mapNotNull { it.toSeriesResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSeriesResult(): AnimeSearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() && !it.contains("/episode/") } ?: return null
        val image = selectFirst("img")
        val title = image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: text().trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(image))
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()
        val data = InlineDataParser.decodeEscapedInlineData(html)
        val title = document.selectFirst("meta[name=twitter:title], meta[property=og:title]")
            ?.attr("content")
            ?.substringBefore("|")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""series:\{.*?title:"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .find(data)
                ?.groupValues
                ?.getOrNull(1)
            ?: return null
        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""description:"([^"]+)"""").find(data)?.groupValues?.getOrNull(1)
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val slug = Regex("""/series/([^/?#]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""slug:"([^"]+)"""").find(data)?.groupValues?.getOrNull(1)
            ?: return null
        val episodes = Regex("""episodeNumber:"?(\d+)"?""")
            .findAll(data)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .distinct()
            .sorted()
            .map { episode ->
                newEpisode("$mainUrl/series/$slug/episode/$episode") {
                    name = "Episode $episode"
                    this.episode = episode
                    posterUrl = poster
                }
            }
            .toList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val episode = Regex("""/episode/(\d+)""").find(data)?.groupValues?.getOrNull(1)?.toIntOrNull()
        var loaded = false
        InlineDataParser.oploverzStreamUrls(document.html(), episode).forEach { raw ->
            loaded = loadResolvedExtractorWithResult(raw, mainUrl, subtitleCallback, callback) || loaded
        }
        return loaded
    }
}
