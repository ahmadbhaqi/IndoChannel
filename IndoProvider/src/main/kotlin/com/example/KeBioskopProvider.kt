package com.example

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val KEBIOSKOP_MAIN_URL = "https://kebioskop21.cfd"

class KeBioskopProvider : MainAPI() {
    override var mainUrl = KEBIOSKOP_MAIN_URL
    override var name = "keBioskop21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "category/movie/" to "Movie",
        "category/box-office/" to "Box Office",
        "category/2026/" to "2026",
        "category/action/" to "Action",
        "category/horor/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = KeBioskopParser.categoryPageUrl(request.data, page)
        val fetch = app.get(requestUrl)
        return newHomePageResponse(
            request.name,
            KeBioskopParser.catalogCards(fetch.text, fetch.url)
                .mapNotNull { card -> card.toSearchResponse() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val fetch = app.get("$mainUrl/?s=$encoded")
        return KeBioskopParser.catalogCards(fetch.text, fetch.url)
            .mapNotNull { card -> card.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = KeBioskopParser.providerUrl(url)
            ?: throw ErrorLoadingException("$name rejected a foreign movie URL")
        val fetch = app.get(requestUrl)
        val canonicalUrl = KeBioskopParser.providerUrl(fetch.url)
            ?: throw ErrorLoadingException("$name redirected to a foreign movie host")
        val details = KeBioskopParser.detail(fetch.text, canonicalUrl)
        if (details.title.isBlank()) throw ErrorLoadingException("$name returned a page without a movie title")

        return newMovieLoadResponse(details.title, canonicalUrl, TvType.Movie, canonicalUrl) {
            posterUrl = details.posterUrl
            year = details.year
            plot = details.synopsis
            tags = details.genres
            duration = details.duration ?: 0
            addTrailer(details.trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailUrl = KeBioskopParser.providerUrl(data) ?: return false
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val detailFetch = try {
            resolver.withinBudget {
                app.get(detailUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
            } ?: return false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val canonicalUrl = KeBioskopParser.providerUrl(detailFetch.url) ?: return false
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String): KeBioskopHttpResponse {
                    val response = resolver.withinBudget {
                        app.get(url, referer = referer, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
                    } ?: error("$name resolution budget exhausted")
                    return KeBioskopHttpResponse(response.text, response.url)
                }

                override suspend fun postPlay(
                    url: String,
                    data: Map<String, String>,
                    referer: String
                ): KeBioskopHttpResponse {
                    val response = resolver.withinBudget {
                        app.post(
                            url,
                            data = data,
                            referer = referer,
                            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                        )
                    } ?: error("$name resolution budget exhausted")
                    return KeBioskopHttpResponse(response.text, response.url)
                }
            },
            genericResolver = { candidate, referer -> resolver.resolve(candidate, referer) },
            genericBatchResolver = { candidates ->
                resolver.resolveFirstVerified(candidates)
            },
            canContinue = { resolver.canContinue }
        )
        return playback.resolve(detailFetch.text, canonicalUrl) && resolver.loaded
    }

    private fun KeBioskopCatalogCard.toSearchResponse(): SearchResponse? {
        if (SensitiveContentPolicy.isBlocked(title, url)) return null
        return newMovieSearchResponse(
            title,
            url,
            TvType.Movie
        ) {
            posterUrl = this@toSearchResponse.posterUrl
            year = this@toSearchResponse.year
        }
    }
}

internal data class KeBioskopCatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?
)

internal data class KeBioskopDetails(
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val synopsis: String?,
    val genres: List<String>,
    val duration: Int?,
    val trailerUrl: String?
)

internal object KeBioskopParser {
    fun categoryPageUrl(path: String, page: Int): String {
        val categoryPath = path.trim('/').takeIf(String::isNotBlank) ?: return KEBIOSKOP_MAIN_URL
        return if (page <= 1) {
            "$KEBIOSKOP_MAIN_URL/$categoryPath/"
        } else {
            "$KEBIOSKOP_MAIN_URL/$categoryPath/page/$page/"
        }
    }

    fun providerUrl(raw: String?): String? = runCatching {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return@runCatching null
        val base = URI(KEBIOSKOP_MAIN_URL)
        val parsed = URI(value)
        val resolved = if (parsed.isAbsolute) parsed else base.resolve(parsed)
        if (
            resolved.scheme?.equals("https", ignoreCase = true) != true ||
            !resolved.host.equals(base.host, ignoreCase = true) ||
            resolved.port != base.port ||
            resolved.userInfo != null
        ) return@runCatching null
        buildString {
            append(KEBIOSKOP_MAIN_URL)
            append(resolved.rawPath?.takeIf(String::isNotBlank) ?: "/")
            resolved.rawQuery?.let { append('?').append(it) }
            resolved.rawFragment?.let { append('#').append(it) }
        }
    }.getOrNull()

    fun catalogCards(html: String, pageUrl: String): List<KeBioskopCatalogCard> {
        val document = Jsoup.parse(html, pageUrl)
        return document.select("div.moviefilm")
            .asSequence()
            .filterNot(::isNavigationCard)
            .mapNotNull { card -> catalogCard(card, pageUrl) }
            .distinctBy(KeBioskopCatalogCard::url)
            .toList()
    }

    fun detail(html: String, pageUrl: String): KeBioskopDetails {
        val document = Jsoup.parse(html, pageUrl)
        val rawTitle = document.selectFirst(".filmcontent h1, h1")?.text()
            ?: document.selectFirst("script[type='application/ld+json']")
                ?.data()
                ?.let { json -> Regex("\\\"headline\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.getOrNull(1) }
        val metadata = document.select(".filmicerik li")
        val synopsisParagraph = document.selectFirst(".filmicerik > p")?.text()
        val year = yearFrom(rawTitle)
            ?: metadata.asSequence().mapNotNull { yearFrom(it.text()) }.firstOrNull()
        val paragraphGenres = document.select(".filmicerik > p a[href*='/category/movie/']")
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
        val genreLine = metadata.firstOrNull { it.text().contains("genre", ignoreCase = true) }
        val genres = paragraphGenres.takeIf { it.isNotEmpty() }
            ?: genreLine?.select("a")
                ?.map { it.text().trim() }
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
            ?: genreLine?.text()
                ?.substringAfter(':', "")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                .orEmpty()
        val duration = metadata.asSequence()
            .map { it.text() }
            .firstOrNull { it.contains("duration", ignoreCase = true) || it.contains("durasi", ignoreCase = true) }
            ?.let { Regex("\\b(\\d{1,4})\\s*(?:min|menit)\\b", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val posterElement = document.selectFirst(".filmaltiimg img, meta[property=og:image]")
            ?: document.selectFirst(".filmcontent img, .filmicerik img")
        val poster = posterElement
            ?.let { element ->
                if (element.tagName().equals("meta", ignoreCase = true)) element.attr("content")
                else ProviderHtmlParser.imageSource(element)
            }
            ?.let { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
        val trailer = document.selectFirst(".vp-yt-type[href]")
            ?.attr("href")
            ?.let { ProviderHtmlParser.absoluteUrl(it, pageUrl) }

        return KeBioskopDetails(
            title = cleanTitle(rawTitle).orEmpty(),
            posterUrl = poster,
            year = year,
            synopsis = synopsisParagraph
                ?.let { paragraph ->
                    Regex("(?i).*?\\bceritanya\\s+tentang\\s*")
                        .replaceFirst(paragraph, "")
                        .trim()
                        .takeIf(String::isNotBlank)
                        ?: paragraph
                }
                ?.let(MovieMetadataParser::meaningfulDescription),
            genres = genres,
            duration = duration,
            trailerUrl = trailer
        )
    }

    private fun catalogCard(card: Element, pageUrl: String): KeBioskopCatalogCard? {
        val link = card.selectFirst(".movief a[href]") ?: return null
        val rawTitle = link.text().trim().takeIf(String::isNotBlank) ?: return null
        val title = cleanTitle(rawTitle) ?: return null
        val url = ProviderHtmlParser.absoluteUrl(link.attr("href"), pageUrl)
            ?.let(::providerUrl)
            ?: return null
        if (SensitiveContentPolicy.isBlockedCatalogCard(card, title, url)) return null
        val poster = ProviderHtmlParser.imageSource(card.selectFirst("img"))
            ?.let { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
        return KeBioskopCatalogCard(title, url, poster, yearFrom(rawTitle))
    }

    private fun isNavigationCard(card: Element): Boolean = card.parents().any { parent ->
        parent.tagName() in setOf("aside", "nav", "footer") ||
            parent.id().equals("sidebar", ignoreCase = true) ||
            parent.classNames().any { it.contains("sidebar", ignoreCase = true) }
    }

    private fun cleanTitle(raw: String?): String? = MovieMetadataParser.title(raw)
        ?.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)"), "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun yearFrom(raw: String?): Int? = Regex("\\b(?:19|20)\\d{2}\\b")
        .find(raw.orEmpty())
        ?.value
        ?.toIntOrNull()
}

internal data class KeBioskopHttpResponse(val html: String, val url: String)

internal interface KeBioskopPlaybackNetwork {
    suspend fun get(url: String, referer: String): KeBioskopHttpResponse
    suspend fun postPlay(url: String, data: Map<String, String>, referer: String): KeBioskopHttpResponse
}

internal class KeBioskopPlayerOrchestrator(
    private val network: KeBioskopPlaybackNetwork,
    private val genericResolver: suspend (url: String, referer: String) -> Boolean,
    private val genericBatchResolver: (
        suspend (candidates: List<PlayerResolutionCandidate>) -> Boolean
    )? = null,
    private val canContinue: () -> Boolean = { true }
) {
    suspend fun resolve(detailHtml: String, detailUrl: String): Boolean {
        val candidates = Jsoup.parse(detailHtml, detailUrl)
            .select("iframe#player[src], .filmicerik iframe[src], .filmcontent iframe[src]")
            .mapNotNull { iframe ->
                ProviderHtmlParser.firstIframeSource(iframe)
                    ?.let { ProviderHtmlParser.absoluteUrl(it, detailUrl) }
            }
            .distinct()
        for (candidate in candidates) {
            if (!canContinue()) break
            try {
                if (isIntermediary(candidate)) {
                    val gate = network.get(candidate, detailUrl)
                    if (!canContinue()) break
                    if (!isIntermediary(gate.url)) continue
                    val gateDocument = Jsoup.parse(gate.html, gate.url)
                    val playForm = gateDocument.select("form").firstOrNull { form ->
                        form.attr("method").equals("post", ignoreCase = true) &&
                            form.select("button[name=play][value=play]").isNotEmpty()
                    } ?: continue
                    val rawAction = playForm.attr("action").trim()
                    val postTarget = when {
                        rawAction.isBlank() -> gate.url
                        rawAction.startsWith('?') -> gate.url
                            .substringBefore('#')
                            .substringBefore('?') + rawAction
                        else -> ProviderHtmlParser.absoluteUrl(rawAction, gate.url)
                    } ?: continue
                    if (!isIntermediary(postTarget)) continue
                    if (!canContinue()) break
                    val response = network.postPlay(postTarget, mapOf("play" to "play"), detailUrl)
                    val finalIframes = Jsoup.parse(response.html, response.url)
                        .select("iframe")
                        .mapNotNull { iframe ->
                            ProviderHtmlParser.firstIframeSource(iframe)
                                ?.let { ProviderHtmlParser.absoluteUrl(it, response.url) }
                        }
                    val finalCandidates = finalIframes.map { finalUrl ->
                        PlayerResolutionCandidate(finalUrl, response.url)
                    }
                    val batchLoaded = genericBatchResolver?.invoke(finalCandidates)
                    if (batchLoaded == true) return true
                    if (genericBatchResolver == null) {
                        for (finalUrl in finalIframes) {
                            if (!canContinue()) break
                            if (genericResolver(finalUrl, response.url)) return true
                        }
                    }
                } else if (genericResolver(candidate, detailUrl)) {
                    return true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A stale player must not suppress later player candidates.
            }
        }
        return false
    }

    companion object {
        private val intermediaryHosts = setOf(
            "streaming.${URI(KEBIOSKOP_MAIN_URL).host}",
            "streaming.kebioskop21.pro"
        )

        fun isIntermediary(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                intermediaryHosts.any { host -> uri.host.equals(host, ignoreCase = true) } &&
                uri.userInfo == null &&
                uri.port in setOf(-1, 443) &&
                uri.path == "/apidrive.php"
        }.getOrDefault(false)
    }
}
