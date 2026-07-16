package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KitanontonProvider : MainAPI() {
    override var mainUrl = "https://kitanonton2.surf"
    override var name = "KitaNonton"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "movies/page/%d/" to "Movies",
        "1000-film-terbaik-sepanjang-masa/page/%d/" to "Film Terbaru",
        "series/page/%d/" to "Series",
        "genre/drama-korea/page/%d/" to "Drama Korea"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        return newHomePageResponse(request.name, document.toMovieResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/?s=$encoded").document.toMovieResults()
    }

    private fun Document.toMovieResults(): List<SearchResponse> {
        return select("div.ml-item:has(a.ml-mask[href])")
            .mapNotNull { it.toMovieResult() }
            .distinctBy { it.url }
    }

    private fun Element.toMovieResult(): SearchResponse? {
        val link = selectFirst("a.ml-mask[href]") ?: return null
        val href = ProviderHtmlParser.absoluteUrl(link.attr("href"), mainUrl) ?: return null
        val title = link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst(".mli-info h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(selectFirst("img")))
        val quality = selectFirst(".mli-quality")?.text()?.trim()
        val isSeries = href.contains("/episode/", ignoreCase = true) ||
            title.contains("Season", ignoreCase = true) ||
            title.contains("Episode", ignoreCase = true)

        return newMovieSearchResponse(
            title.cleanKitanontonTitle(),
            href,
            if (isSeries) TvType.TvSeries else TvType.Movie
        ) {
            posterUrl = poster
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, h3[itemprop=name], meta[property=og:title]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.cleanKitanontonTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".mvic-thumb, .mvi-cover")
                ?.attr("style")
                ?.backgroundImageUrl()
                ?.let(::fixUrlNull)
        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".sinopsis-indo, [itemprop=reviewBody] p, [itemprop=description] p")
                ?.text()
                ?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val tags = document.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val episodes = document.select(
            ".episodios a[href], .les-content a[href], .episode a[href], a[href*='/episode/']"
        ).mapNotNull { link ->
            val href = ProviderHtmlParser.absoluteUrl(link.attr("href"), mainUrl) ?: return@mapNotNull null
            val label = link.text().trim().ifBlank { link.attr("title").trim() }
            val episodeNumber = Regex("""(?:Episode|Ep\.?|E)(\d+)""", RegexOption.IGNORE_CASE)
                .find("$label $href")
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val seasonNumber = Regex("""(?:Season|S)(\d+)""", RegexOption.IGNORE_CASE)
                .find("$label $href")
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            newEpisode(href) {
                name = label.ifBlank { episodeNumber?.let { "Episode $it" } ?: "Episode" }
                season = seasonNumber
                episode = episodeNumber
                posterUrl = poster
            }
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        listOf(data.trimEnd('/') + "/play", data).distinct().forEach { page ->
            try {
                val fetch = app.get(
                    page,
                    referer = data,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                val document = fetch.document
                ProviderHtmlParser.mediaSources(document).forEach { source ->
                    resolver.resolve(source, fetch.url)
                }
                document.select("[data-iframe]").forEach { server ->
                    resolver.resolve(server.attr("data-iframe").decodeServerUrl(), fetch.url)
                }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        return resolver.loaded
    }

    private fun String.decodeServerUrl(): String? {
        val value = trim().takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val decoded = decodeBase64Compat(value) ?: return@runCatching null
            String(decoded, Charsets.UTF_8)
                .trim()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }

    private fun String.backgroundImageUrl(): String? {
        return substringAfter("url(", "")
            .substringBefore(")")
            .trim(' ', '\'', '"')
            .takeIf { it.isNotBlank() }
    }

    private fun String.cleanKitanontonTitle(): String {
        return replace(Regex("""\s*[|–-]\s*KITA\s*NONTON.*$""", RegexOption.IGNORE_CASE), "")
            .replace("Nonton Film", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }
}
