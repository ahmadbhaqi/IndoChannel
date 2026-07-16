package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv.nontonfilm.red"
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/genre/box-office/page/" to "Box Office",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/animation/page/" to "Animation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = app.get(request.data + page).document
            .select("article.item-infinite")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get("$mainUrl/?s=$encodedQuery").document
            .select("article.item-infinite")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title > a, h2 > a, a[rel=bookmark]") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        val href = providerUrl(anchor.attr("href")) ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this))
        val quality = selectFirst("div.gmr-quality-item, div.gmr-qual")?.text()?.trim()
        val isSeries = href.contains("/tv/", ignoreCase = true) ||
            selectFirst("div.gmr-numbeps, div.last-episode") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fetch = app.get(url)
        val document = fetch.document
        val title = document.selectFirst("h1.entry-title, h3[itemprop=name]")
            ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(
            ProviderHtmlParser.imageSource(
                document.selectFirst("img.thumbnail, figure.pull-left > img, img.img-thumbnail")
            )
        )
        val description = document.selectFirst("div[itemprop=description], div.synopsis")?.text()?.trim()
        val year = document.select("a[href*=/year/], span.year")
            .firstNotNullOfOrNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
        val tags = document.select("div.gmr-moviedata a[href*=/genre/], span.jptag a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text().trim() }
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val episodeElements = document.select("div.vid-episodes a[href], div.gmr-listseries a[href], div.episode-list a[href]")
        val isSeries = fetch.url.contains("/tv/", ignoreCase = true) || episodeElements.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeElements.mapNotNull { episodeLink ->
                val href = providerUrl(episodeLink.attr("href")) ?: return@mapNotNull null
                val label = episodeLink.attr("title").takeIf { it.isNotBlank() } ?: episodeLink.text().trim()
                val number = Regex("(?i)(?:episode|eps?)\\s*(\\d+)")
                    .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(label)?.value?.toIntOrNull()
                newEpisode(href) {
                    episode = number
                    name = number?.let { "Episode $it" } ?: label
                    posterUrl = poster
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).document
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)

        ProviderHtmlParser.mediaSources(document, "iframe, div.gmr-embed-responsive iframe")
            .forEach { resolvePlayer(it, data, resolver) }

        LayarKacaPlayerParser.serverPageUrls(document, data).forEach { playerUrl ->
            try {
                val playerDocument = app.get(
                    playerUrl,
                    referer = data,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).document
                ProviderHtmlParser.mediaSources(playerDocument, "iframe, div.gmr-embed-responsive iframe")
                    .forEach { resolvePlayer(it, playerUrl, resolver) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // One dead server must not hide the remaining mirrors.
            }
        }
        return resolver.loaded
    }

    private suspend fun resolvePlayer(raw: String?, referer: String, resolver: LinkResolutionSession) {
        val url = ProviderHtmlParser.absoluteUrl(raw, referer) ?: return
        if (resolver.resolve(url, referer)) return

        try {
            val html = app.get(
                url,
                referer = referer,
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            ).text
            LayarKacaPlayerParser.mediaUrls(html, url).forEach { resolver.resolve(it, url) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep trying the remaining server tabs.
        }
    }

    private fun providerUrl(raw: String): String? = ProviderHtmlParser.absoluteUrl(raw, mainUrl)
}

internal object LayarKacaPlayerParser {
    fun serverPageUrls(document: Document, detailUrl: String): List<String> {
        return document.select(
            "ul.gmr-player-nav a[href], ul.muvipro-player-tabs a[href], ul#player-list a[href]"
        ).mapNotNull { link ->
            ProviderHtmlParser.absoluteUrl(link.attr("href"), detailUrl)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.distinct()
    }

    fun mediaUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        val scriptBodies = document.select("script").flatMap { script ->
            val raw = script.data()
            listOfNotNull(
                raw,
                raw.takeIf { it.contains("eval(function(p,a,c,k,e") }
                    ?.let { runCatching { getAndUnpack(it) }.getOrNull() }
            )
        }
        val inlineUrls = scriptBodies.flatMap { script ->
            val normalized = script
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            Regex("(?i)[\\\"']?(?:file|src)[\\\"']?\\s*[:=]\\s*[\\\"'](https?://[^\\\"']+)[\\\"']")
                .findAll(normalized)
                .map { it.groupValues[1] }
                .toList()
        }
        val sourceUrls = document.select("video[src], video source[src], source[src]").map { it.attr("src") }
        return (inlineUrls + sourceUrls)
            .mapNotNull { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            .distinct()
    }
}
