package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KawanfilmProvider : MainAPI() {
    override var mainUrl = "https://web.kawanfilm21.co"
    override var name = "Kawanfilm"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    private val ownedHosts =
        setOf("tv2.kawanfilm21.co", "kawanfilm21.co", "kawanfilm21.online")
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "page/%d/?s&search=advanced&post_type=movie" to "Update Terbaru",
        "category/box-office/page/%d/" to "Box Office",
        "category/action/page/%d/" to "Action",
        "category/animation/page/%d/" to "Animasi",
        "category/comedy/page/%d/" to "Komedi",
        "category/drama/page/%d/" to "Drama",
        "category/horror/page/%d/" to "Horor",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/japan/page/%d/" to "Jepang"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page.coerceAtLeast(1))
        val fetch = getProviderPage("$mainUrl/$path")
            ?: return newHomePageResponse(request.name, emptyList())
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return newHomePageResponse(request.name, emptyList())
        val document = Jsoup.parse(fetch.body, fetch.url)
        return newHomePageResponse(
            request.name,
            document.select("article.item").mapNotNull { it.toSearchResult() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val fetch = getProviderPage("$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv")
            ?: return emptyList()
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return emptyList()
        return Jsoup.parse(fetch.body, fetch.url)
            .select("article.item")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = ProviderHtmlParser.firstTitledLink(this) ?: return null
        val href = providerUrl(anchor.attr("href")) ?: return null
        val title = MovieMetadataParser.title(anchor.text()) ?: return null
        if (SensitiveContentPolicy.isBlockedCatalogCard(this, title, href)) return null
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this))
        val quality = selectFirst("div.gmr-qual, div.gmr-quality-item")?.text()?.trim()
        val episodeBadge = selectFirst("div.gmr-numbeps")?.text()?.trim()
        val isSeries = href.contains("/tv/", ignoreCase = true) || !episodeBadge.isNullOrBlank()
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = providerUrl(url) ?: return null
        val fetch = getProviderPage(requestUrl) ?: return null
        val canonicalUrl = fetch.url
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return null
        val document = Jsoup.parse(fetch.body, canonicalUrl)
        val title = MovieMetadataParser.title(document.selectFirst("h1.entry-title")?.text())
            ?: return null
        val poster = fixUrlNull(
            ProviderHtmlParser.firstImageSource(document, "figure.pull-left img, figure img")
        )
        val tags = document.select("div.gmr-moviedata a[href*=/genre/]").map { it.text().trim() }
        val year = document.select("a[href*=/year/]")
            .firstNotNullOfOrNull { Regex("""(?:19|20)\d{2}""").find(it.text())?.value?.toIntOrNull() }
        val description = MovieMetadataParser.synopsis(document)
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()
        val actors = document.select("span[itemprop=actors] a").map { it.text().trim() }
        val duration = document.selectFirst("span[property=duration]")?.text()
            ?.filter(Char::isDigit)?.toIntOrNull()
        val recommendations = document.select("article.item.col-md-20, article.item")
            .mapNotNull { it.toSearchResult() }
        val episodeLinks = document.select("div.vid-episodes a[href], div.gmr-listseries a[href]")
        val episodes = episodeLinks.mapNotNull { link ->
            val href = providerUrl(link.attr("href")) ?: return@mapNotNull null
            val label = link.attr("title").ifBlank { link.text() }.trim()
            DutamoviePlayerParser.newEpisode(this, href, label, poster)
        }.distinctBy { it.data }
        val tvType = RotatingMovieDetailClassifier.classify(canonicalUrl, episodes.size)
            ?: throw ErrorLoadingException("Tautan episode belum tersedia")

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addScore(rating)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addScore(rating)
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
        val requestUrl = providerUrl(data) ?: return false
        val fetch = getProviderPage(requestUrl) ?: return false
        val pageUrl = fetch.url
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return false
        val document = Jsoup.parse(fetch.body, pageUrl)
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val candidates = buildList {
            addAll(ProviderHtmlParser.mediaSources(document))
            addAll(document.select("ul.muvipro-player-tabs a[href]").map { it.attr("href") })
            addAll(ProviderHtmlParser.downloadCandidateUrls(document, pageUrl))
        }.mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }.distinct().take(48)
        candidates.forEach { candidate ->
            if (resolver.canContinue) resolver.resolve(candidate, pageUrl)
        }

        val baseUrl = URI(pageUrl).let { "${it.scheme}://${it.rawAuthority}" }
        for (request in PopularProviderLinkLimits.muviproAjaxRequests(document)) {
            if (!resolver.canContinue) break
            try {
                val response = safeHttp.postForm(
                    url = "$baseUrl/wp-admin/admin-ajax.php",
                    form = request.toPostData(),
                    normalizer = ProviderUrlNormalizer(::networkProviderUrl),
                    referer = pageUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                if (
                    response.code !in 200..299 ||
                    ProviderHtmlParser.isNonContentPage(response.body)
                ) continue
                val responseDocument = Jsoup.parse(response.body, response.url)
                ProviderHtmlParser.mediaSources(responseDocument).take(48).forEach { candidate ->
                    val playerUrl = ProviderHtmlParser.absoluteUrl(candidate, response.url)
                        ?: return@forEach
                    if (resolver.canContinue) resolver.resolve(playerUrl, pageUrl)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Continue with the remaining player tabs.
            }
        }
        return resolver.loaded
    }

    private suspend fun getProviderPage(url: String): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer(::networkProviderUrl),
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun providerUrl(raw: String?): String? = ProviderHtmlParser.normalizeProviderPageUrl(
        raw,
        mainUrl,
        ownedHosts
    )

    private fun networkProviderUrl(raw: String?): String? =
        ProviderHtmlParser.preserveProviderPageUrl(raw, mainUrl, ownedHosts)
}
