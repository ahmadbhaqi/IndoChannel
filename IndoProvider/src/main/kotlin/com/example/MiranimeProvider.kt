package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MiranimeProvider : MainAPI() {
    override var mainUrl = "https://miranime.net"
    override var name = "Miranime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "ongoing-anime" to "Ongoing",
        "daftar-anime" to "Daftar Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        val document = app.get("$mainUrl$path").document
        return newHomePageResponse(request.name, document.toAnimeResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search?keyword=$encodedQuery").document
        return document.toAnimeResults()
    }

    private fun Document.toAnimeResults(): List<AnimeSearchResponse> {
        return select("a[href$='-sub-indo']:has(img)")
            .mapNotNull { it.toAnimeResult() }
            .distinctBy { it.url }
    }

    private fun Element.toAnimeResult(): AnimeSearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() && !it.startsWith("/nonton/") } ?: return null
        val image = selectFirst("img")
        val title = image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: text().trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(image))
        val url = ProviderHtmlParser.absoluteUrl(href, mainUrl) ?: return null
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title], meta[name=twitter:title]")
            ?.attr("content")
            ?.replace("Nonton dan Download", "")
            ?.substringBefore("Miranime")
            ?.trim()
            ?.dropLastWhile { !it.isLetterOrDigit() && it != ')' }
            ?.takeIf { it.isNotBlank() }
            ?: document.title()
                .substringBefore("Miranime")
                .trim()
                .dropLastWhile { !it.isLetterOrDigit() && it != ')' }
                .takeIf { it.isNotBlank() }
            ?: return null
        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val episodes = document.select("a[href^='/nonton/'], a[href^='$mainUrl/nonton/']")
            .mapNotNull { episodeElement ->
                val href = episodeElement.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val episode = Regex("""episode-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(ProviderHtmlParser.absoluteUrl(href, mainUrl) ?: return@mapNotNull null) {
                    name = episode?.let { "Episode $it" } ?: episodeElement.text().trim()
                    this.episode = episode
                    posterUrl = poster
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

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
        var loaded = false
        InlineDataParser.miranimeSourceUrls(document.html()).forEach { raw ->
            loaded = loadResolvedExtractorWithResult(raw, data, subtitleCallback, callback) || loaded
        }
        return loaded
    }
}
