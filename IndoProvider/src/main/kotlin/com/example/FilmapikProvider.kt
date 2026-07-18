package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class FilmapikProvider : MainAPI() {
    override var mainUrl = "https://filmapik.college"
    override var name = "Filmapik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "category/box-office/page/%d" to "Box Office",
        "release-year/2026/page/%d" to "2026",
        "tvshows-genre/k-drama/page/%d" to "K-Drama"
    )

    private val jsonMapper = jacksonObjectMapper()
    private val searchResultType = jsonMapper.typeFactory.constructMapType(
        Map::class.java,
        String::class.java,
        FilmapikSearchItem::class.java
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.takeIf { it.isNotBlank() }?.format(page)?.let { "/$it" }.orEmpty()
        val document = app.get("$mainUrl$path").document
        return newHomePageResponse(request.name, document.toMovieResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val nonce = app.get(mainUrl).document
            .html()
            .substringAfter("\"searchNonce\":\"", "")
            .substringBefore("\"")
            .takeIf { it.isNotBlank() }
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = if (nonce != null) {
            val json = app.get("$mainUrl/wp-json/filmapik/search/?keyword=$encodedQuery&nonce=$nonce").text
            runCatching {
                jsonMapper.readValue<Map<String, FilmapikSearchItem>>(json, searchResultType)
            }.getOrNull()
                ?.values
                ?.mapNotNull { it.toSearchResponse() }
                .orEmpty()
        } else {
            emptyList()
        }

        return results.ifEmpty {
            app.get("$mainUrl/?s=$encodedQuery").document.toMovieResults()
        }
    }

    private fun Document.toMovieResults(): List<SearchResponse> {
        return select("a[href*='/nonton-film-']:has(img), a[href*='/tvshows/']:has(img)")
            .mapNotNull { it.toMovieResult() }
            .distinctBy { it.url }
    }

    private fun Element.toMovieResult(): SearchResponse? {
        val href = providerUrl(attr("href")) ?: return null
        val image = selectFirst("img")
        val rawTitle = image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val title = MovieMetadataParser.title(rawTitle) ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(image))
        val quality = selectFirst(".badge-quality")?.text()?.trim()
        val type = if (href.contains("/tvshows/", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) {
            posterUrl = poster
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = providerUrl(url) ?: return null
        val fetch = app.get(requestUrl)
        val document = fetch.document
        val canonicalUrl = providerUrl(fetch.url) ?: return null
        val titleElement = document.selectFirst("h1, meta[property=og:title]")
        val title = MovieMetadataParser.title(
            titleElement?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
        ) ?: return null
        val poster = document.selectFirst("script[type='application/ld+json']:contains(image)")
            ?.data()
            ?.let { Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: fixUrlNull(ProviderHtmlParser.imageSource(document.selectFirst(".detail-poster img, img[alt*='Nonton']")))
        val description = MovieMetadataParser.synopsis(document)
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select("meta[property=article:tag]").mapNotNull {
            it.attr("content").takeIf { tag -> tag.isNotBlank() }
        }

        val isSeries = canonicalUrl.contains("/tvshows/", ignoreCase = true)
        return if (isSeries) {
            val episodes = document.select("a.famv-episode-btn[href]")
                .mapNotNull { link ->
                    val href = providerUrl(link.attr("href")) ?: return@mapNotNull null
                    val episodeNumber = Regex("""(?:episode-|EP)(\d+)""", RegexOption.IGNORE_CASE)
                        .find("$href ${link.text()}")
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    val seasonNumber = link.closest(".famv-season-list")
                        ?.attr("data-season")
                        ?.toIntOrNull()
                        ?: Regex("""season-(\d+)""", RegexOption.IGNORE_CASE)
                            .find(href)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    newEpisode(href) {
                        name = link.attr("title").takeIf { it.isNotBlank() } ?: link.text()
                        season = seasonNumber
                        episode = episodeNumber
                        posterUrl = poster
                    }
                }
                .distinctBy { it.data }

            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
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
        val canonicalData = providerUrl(data) ?: return false
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            candidateTimeoutMs = 25_000L,
            sessionTimeoutMs = 75_000L
        )
        val directUrls = mutableSetOf<String>()
        val pages = listOfNotNull(
            FilmapikPlayerParser.playPageUrl(canonicalData),
            canonicalData
        ).distinct()
        for (page in pages) {
            if (!resolver.canContinue || resolver.loaded) break
            try {
                val fetch = app.get(page, referer = canonicalData, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
                val document = fetch.document
                val pageUrl = providerUrl(fetch.url) ?: continue
                val servers = (
                    ProviderHtmlParser.mediaSources(document) +
                        document.select("[data-url]").mapNotNull {
                            it.attr("data-url").takeIf { value -> value.isNotBlank() }
                        } +
                        document.select("select#player-select option[value]").mapNotNull {
                            it.attr("value").takeIf { value -> value.isNotBlank() }
                        } +
                        document.select("a.player-option[href]").mapNotNull {
                            it.attr("href").takeIf { value -> value.isNotBlank() }
                        }
                    ).distinct()
                val downloads = ProviderHtmlParser.downloadCandidateUrls(document, pageUrl)
                val candidates = FilmapikPlayerParser.orderedPlayerCandidates(
                    servers,
                    downloads,
                    pageUrl
                )
                for (raw in candidates) {
                    if (!resolver.canContinue || resolver.loaded) break
                    resolvePlayer(raw, pageUrl, resolver, directUrls)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        return resolver.loaded
    }

    private suspend fun resolvePlayer(
        raw: String?,
        referer: String,
        resolver: LinkResolutionSession,
        directUrls: MutableSet<String>
    ) {
        if (resolver.loaded || !resolver.canContinue) return
        val playerUrl = ProviderHtmlParser.absoluteUrl(raw, referer) ?: return
        if (!FilmapikPlayerParser.isEfekPlayerUrl(playerUrl)) {
            resolver.resolveInline(playerUrl, referer)
            return
        }

        try {
            val html = resolver.withinBudget {
                app.get(
                    playerUrl,
                    referer = referer,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).text
            } ?: return
            for (source in FilmapikPlayerParser.sources(html, playerUrl)) {
                for (mediaUrl in FilmapikPlayerParser.mediaUrlCandidates(source.url)) {
                    if (resolver.loaded || !resolver.canContinue) break
                    if (!directUrls.add(mediaUrl)) continue
                    val attempts = if (FilmapikPlayerParser.isEfekStorageUrl(mediaUrl)) 2 else 1
                    repeat(attempts) {
                        if (resolver.loaded || !resolver.canContinue) return@repeat
                        resolver.emitResolved(
                            newExtractorLink(name, "$name ${source.label}", mediaUrl, ExtractorLinkType.VIDEO) {
                                this.referer = playerUrl
                                quality = source.quality
                                headers = mapOf("Referer" to playerUrl)
                            }
                        )
                        if (resolver.loaded) return
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A dead Efek player must not suppress a later server button.
        }
    }

    data class FilmapikSearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private fun FilmapikSearchItem.toSearchResponse(): SearchResponse? {
        val safeTitle = MovieMetadataParser.title(title) ?: return null
        val safeUrl = providerUrl(url) ?: return null
        val type = if (safeUrl.contains("/tvshows/", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(safeTitle, safeUrl, type) {
            posterUrl = img
        }
    }

    private fun providerUrl(raw: String?): String? =
        FilmapikPlayerParser.normalizePageUrl(raw, mainUrl)

}

internal data class FilmapikMediaSource(
    val label: String,
    val url: String,
    val quality: Int
)

internal object FilmapikPlayerParser {
    private val legacyHosts = setOf("filmapik.to", "filmapik.fitness")
    // Efek currently uses compact numeric shards. Three digits leaves room for
    // v10+ rotations without accepting unbounded or ambiguous host variants.
    private val efekHostRegex = Regex("""(?i)^([vs])([1-9]\d{0,2})\.efek\.stream$""")

    fun normalizePageUrl(raw: String?, currentBaseUrl: String): String? {
        return ProviderHtmlParser.normalizeProviderPageUrl(raw, currentBaseUrl, legacyHosts)
    }

    fun playPageUrl(detailUrl: String): String? {
        return runCatching {
            val uri = URI(detailUrl)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                return@runCatching null
            }
            val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/')
            buildString {
                append(uri.scheme.lowercase())
                append("://")
                append(uri.rawAuthority)
                append(if (path.endsWith("/play", ignoreCase = true)) path else "$path/play")
                uri.rawQuery?.let { append('?').append(it) }
            }.takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }

    fun sources(html: String, playerUrl: String): List<FilmapikMediaSource> {
        val document = Jsoup.parse(html, playerUrl)
        val scripts = document.select("script").flatMap { script ->
            val raw = script.data()
            listOfNotNull(
                raw,
                raw.takeIf { it.contains("eval(function(p,a,c,k,e") }
                    ?.let { runCatching { getAndUnpack(it) }.getOrNull() }
            )
        }

        return scripts.flatMap { script ->
            val normalized = script
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            val fileRegex = Regex(
                "(?i)[\\\"']?file[\\\"']?\\s*[:=]\\s*[\\\"']((?:https?:)?//[^\\\"']+|/[^\\\"']+)[\\\"']"
            )
            fileRegex.findAll(normalized).mapNotNull { match ->
                val url = ProviderHtmlParser.absoluteUrl(match.groupValues[1], playerUrl)
                    ?: return@mapNotNull null
                val context = normalized.substring((match.range.first - 180).coerceAtLeast(0), match.range.first)
                val declaredVideo = context.contains("video/mp4", ignoreCase = true)
                val mediaLike = declaredVideo ||
                    url.contains("/stream/", ignoreCase = true) ||
                    directMediaType(url) != null
                if (!mediaLike) return@mapNotNull null
                val label = Regex("(?i)(\\d{3,4}p)")
                    .findAll(context)
                    .lastOrNull()
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: "Video"
                FilmapikMediaSource(
                    label = label,
                    url = url,
                    quality = Regex("\\d{3,4}").find(label)?.value?.toIntOrNull()
                        ?: Qualities.Unknown.value
                )
            }.toList()
        }.distinctBy { it.url }
    }

    fun isEfekPlayerUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return efekHostRegex.matchEntire(host)?.groupValues?.getOrNull(1)
            ?.equals("v", ignoreCase = true) == true
    }

    fun isEfekStorageUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return efekHostRegex.matchEntire(host)?.groupValues?.getOrNull(1)
            ?.equals("s", ignoreCase = true) == true
    }

    /** Dead Efek shards are common, so preserve every fallback ahead of them. */
    fun orderedPlayerCandidates(
        primary: List<String>,
        fallback: List<String>,
        pageUrl: String
    ): List<String> {
        val primaryUrls = primary.mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            .distinct()
        val fallbackUrls = fallback.mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            .distinct()
        val (efekPlayers, regularPlayers) = primaryUrls.partition(::isEfekPlayerUrl)
        val (efekFallbacks, regularFallbacks) = fallbackUrls.partition(::isEfekPlayerUrl)
        return (regularPlayers + regularFallbacks + efekPlayers + efekFallbacks).distinct()
    }

    /**
     * Efek separates its player (`vN`) and storage (`sN`) hosts. The current
     * player sometimes exposes its v-host in the packed file URL, while the
     * same-numbered s-host serves the ranged MP4 bytes.
     */
    fun mediaUrlCandidates(url: String): List<String> {
        if (!isSafeRemoteHttpUrl(url)) return emptyList()
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val match = efekHostRegex.matchEntire(uri.host.orEmpty()) ?: return listOf(url)
        val shard = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..999 }
            ?: return listOf(url)
        val storageUrl = replaceRawAuthorityHost(uri, "s$shard.efek.stream")
        return listOfNotNull(storageUrl, url).distinct()
    }

    private fun replaceRawAuthorityHost(uri: URI, host: String): String? {
        return runCatching {
            buildString {
                append(uri.scheme)
                append("://")
                uri.rawUserInfo?.let { append(it).append('@') }
                append(host)
                if (uri.port >= 0) append(':').append(uri.port)
                append(uri.rawPath.orEmpty().ifBlank { "/" })
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }.takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }
}
