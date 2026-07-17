package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
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
        val href = ProviderHtmlParser.normalizeProviderPageUrl(link.attr("href"), mainUrl)
            ?: return null
        val rawTitle = link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst(".mli-info h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val title = MovieMetadataParser.title(rawTitle) ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(selectFirst("img")))
        val quality = selectFirst(".mli-quality")?.text()?.trim()
        val isSeries = href.contains("/series/", ignoreCase = true) ||
            href.contains("/episode/", ignoreCase = true) ||
            title.contains("Season", ignoreCase = true) ||
            title.contains("Episode", ignoreCase = true)

        return newMovieSearchResponse(
            title,
            href,
            if (isSeries) TvType.TvSeries else TvType.Movie
        ) {
            posterUrl = poster
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailUrl = ProviderHtmlParser.normalizeProviderPageUrl(url, mainUrl) ?: return null
        val fetch = app.get(detailUrl)
        if (ProviderHtmlParser.normalizeProviderPageUrl(fetch.url, mainUrl) == null) return null
        val document = fetch.document
        val titleElement = document.selectFirst("h1, h3[itemprop=name], meta[property=og:title]")
        val title = MovieMetadataParser.title(
            titleElement?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
        ) ?: return null
        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".mvic-thumb, .mvi-cover")
                ?.attr("style")
                ?.backgroundImageUrl()
                ?.let(::fixUrlNull)
        val description = MovieMetadataParser.synopsis(
            document,
            directSelectors = listOf(
                "[itemprop=reviewBody] p",
                ".sinopsis-indo p",
                ".sinopsis-indo",
                "[itemprop=description] p"
            )
        )
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val tags = document.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val legacyEpisodes = document.select(
            ".episodios a[href], .les-content a[href], .episode a[href], a[href*='/episode/']"
        ).mapNotNull { link ->
            val href = ProviderHtmlParser.normalizeProviderPageUrl(link.attr("href"), mainUrl)
                ?: return@mapNotNull null
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

        val isSeriesDetail = runCatching {
            URI(detailUrl).path.orEmpty().contains("/series/", ignoreCase = true)
        }.getOrDefault(false)
        val watchUrl = if (isSeriesDetail) {
            KitanontonPlayerParser.watchPageUrl(document, fetch.url)
                ?.let { ProviderHtmlParser.normalizeProviderPageUrl(it, mainUrl) }
        } else {
            null
        }
        val watchEpisodes = watchUrl?.let { page ->
            try {
                val watchDocument = app.get(
                    page,
                    referer = fetch.url,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).document
                KitanontonPlayerParser.watchEpisodes(watchDocument)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
        val episodes = if (watchUrl != null && watchEpisodes.isNotEmpty()) {
            watchEpisodes.map { watchEpisode ->
                newEpisode(
                    KitanontonPlayerParser.encodeEpisodeData(
                        detailUrl = detailUrl,
                        watchUrl = watchUrl,
                        episode = watchEpisode.number
                    )
                ) {
                    name = "Episode ${watchEpisode.number}"
                    episode = watchEpisode.number
                    posterUrl = poster
                }
            }
        } else {
            legacyEpisodes
        }

        return if (isSeriesDetail || episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
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
        if (KitanontonPlayerParser.isEpisodeData(data)) {
            val request = KitanontonPlayerParser.decodeEpisodeData(data) ?: return false
            val detailUrl = ProviderHtmlParser.normalizeProviderPageUrl(request.detailUrl, mainUrl)
                ?: return false
            val watchUrl = ProviderHtmlParser.normalizeProviderPageUrl(request.watchUrl, mainUrl)
                ?: return false
            try {
                val fetch = app.get(
                    watchUrl,
                    referer = detailUrl,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                KitanontonPlayerParser.resolveAll(
                    KitanontonPlayerParser.episodePlayerUrls(fetch.document, request.episode)
                ) { source ->
                    resolver.resolve(source, fetch.url)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return false
            }
            return resolver.loaded
        }

        val detailUrl = ProviderHtmlParser.normalizeProviderPageUrl(data, mainUrl) ?: return false
        KitanontonPlayerParser.resolvePages(
            listOfNotNull(KitanontonPlayerParser.playPageUrl(detailUrl), detailUrl)
        ) { page ->
            try {
                val fetch = app.get(
                    page,
                    referer = detailUrl,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                val document = fetch.document
                val playerUrls = ProviderHtmlParser.mediaSources(document) +
                    document.select("[data-iframe]").mapNotNull { server ->
                        KitanontonPlayerParser.decodeServerUrl(server.attr("data-iframe"))
                    }
                KitanontonPlayerParser.resolveAll(playerUrls) { source ->
                    resolver.resolve(source, fetch.url)
                }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        return resolver.loaded
    }

    private fun String.backgroundImageUrl(): String? {
        return substringAfter("url(", "")
            .substringBefore(")")
            .trim(' ', '\'', '"')
            .takeIf { it.isNotBlank() }
    }

}

internal data class KitanontonWatchEpisode(
    val number: Int,
    val mirrors: List<String>
)

internal data class KitanontonEpisodeRequest(
    val detailUrl: String,
    val watchUrl: String,
    val episode: Int
)

internal object KitanontonPlayerParser {
    private const val EPISODE_DATA_PREFIX = "kitanonton-episode:"
    private const val EPISODE_QUERY_KEY = "kitanonton_episode"
    private val episodeNumberRegex = Regex(
        """\b(?:episode|eps?|e)\s*\.?\s*(\d+)\b""",
        RegexOption.IGNORE_CASE
    )

    fun watchPageUrl(document: Document, detailUrl: String): String? {
        val detailHost = runCatching { URI(detailUrl).host.orEmpty().lowercase() }
            .getOrDefault("")
        if (detailHost.isBlank()) return null

        return document.select("a.thumb.mvi-cover[href]").firstNotNullOfOrNull { link ->
            ProviderHtmlParser.absoluteUrl(link.attr("href"), detailUrl)
                ?.takeIf(::isWatchPageUrl)
                ?.takeIf { watchUrl ->
                    runCatching { URI(watchUrl).host.orEmpty().lowercase() }
                        .getOrDefault("") == detailHost
                }
        }
    }

    fun watchEpisodes(document: Document): List<KitanontonWatchEpisode> {
        val mirrorsByEpisode = linkedMapOf<Int, MutableList<String>>()
        document.select("a.btn-eps[data-iframe]").forEach { link ->
            val label = listOf(link.text(), link.attr("title"))
                .joinToString(" ")
                .trim()
            val episode = episodeNumberRegex.find(label)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: return@forEach
            val mirror = decodeServerUrl(link.attr("data-iframe")) ?: return@forEach
            val mirrors = mirrorsByEpisode.getOrPut(episode) { mutableListOf() }
            if (mirror !in mirrors) mirrors += mirror
        }
        return mirrorsByEpisode
            .map { (number, mirrors) -> KitanontonWatchEpisode(number, mirrors.toList()) }
            .sortedBy { it.number }
    }

    fun episodePlayerUrls(document: Document, episode: Int): List<String> {
        return watchEpisodes(document)
            .firstOrNull { it.number == episode }
            ?.mirrors
            .orEmpty()
    }

    fun decodeServerUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val decoded = decodeBase64Compat(value) ?: return@runCatching null
            String(decoded, Charsets.UTF_8)
                .trim()
                .takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }

    fun encodeEpisodeData(detailUrl: String, watchUrl: String, episode: Int): String {
        require(episode > 0)
        require(isSeriesDetailUrl(detailUrl))
        require(isWatchPageUrl(watchUrl))
        require(sameHost(detailUrl, watchUrl))
        val payload = listOf(episode.toString(), detailUrl, watchUrl).joinToString("\n")
        val encoded = encodeBase64UrlNoPadding(payload.toByteArray(Charsets.UTF_8))
        return runCatching {
            val uri = URI(watchUrl)
            buildString {
                append(uri.scheme.lowercase())
                append("://")
                append(uri.rawAuthority)
                append(uri.rawPath)
                append('?')
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append(it).append('&') }
                append(EPISODE_QUERY_KEY).append('=').append(encoded)
                uri.rawFragment?.let { append('#').append(it) }
            }
        }.getOrThrow()
    }

    fun isEpisodeData(data: String): Boolean = encodedEpisodePayload(data) != null

    fun decodeEpisodeData(data: String): KitanontonEpisodeRequest? {
        return runCatching {
            val encoded = encodedEpisodePayload(data) ?: return@runCatching null
            val decoded = decodeBase64Compat(encoded) ?: return@runCatching null
            val parts = String(decoded, Charsets.UTF_8).split('\n')
            if (parts.size != 3) return@runCatching null
            val episode = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return@runCatching null
            val detailUrl = parts[1]
            val watchUrl = parts[2]
            if (!isSeriesDetailUrl(detailUrl) || !isWatchPageUrl(watchUrl)) {
                return@runCatching null
            }
            if (!sameHost(detailUrl, watchUrl)) return@runCatching null
            KitanontonEpisodeRequest(detailUrl, watchUrl, episode)
        }.getOrNull()
    }

    private fun encodedEpisodePayload(data: String): String? {
        val value = data.trim()
        if (value.startsWith(EPISODE_DATA_PREFIX)) {
            return value.removePrefix(EPISODE_DATA_PREFIX).takeIf { it.isNotBlank() }
        }
        val legacyMarker = "/$EPISODE_DATA_PREFIX"
        if (legacyMarker in value) {
            return value.substringAfterLast(legacyMarker).substringBefore('?').substringBefore('#')
                .takeIf { it.isNotBlank() }
        }
        return runCatching {
            URI(value).rawQuery
                ?.split('&')
                ?.firstNotNullOfOrNull { parameter ->
                    parameter.substringAfter('=', "")
                        .takeIf {
                            parameter.substringBefore('=', "") == EPISODE_QUERY_KEY &&
                                it.isNotBlank()
                        }
                }
        }.getOrNull()
    }

    suspend fun resolvePages(pages: List<String>, resolve: suspend (String) -> Unit) {
        for (page in pages.distinct()) resolve(page)
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

    suspend fun resolveAll(urls: List<String>, resolve: suspend (String) -> Unit) {
        for (url in orderPlayerUrls(urls)) resolve(url)
    }

    fun orderPlayerUrls(urls: List<String>): List<String> {
        return urls.distinct().withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { priority(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    private fun priority(url: String): Int {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") ||
                host == "abyss.to" || host.endsWith(".abyss.to") -> 0
            host == "freeon.site" || host.endsWith(".freeon.site") ||
                host == "justplay.cam" || host.endsWith(".justplay.cam") ||
                host == "bysebuho.com" || host.endsWith(".bysebuho.com") ||
                host == "asiastream.cc" || host.endsWith(".asiastream.cc") ||
                host == "playsobat.xyz" || host.endsWith(".playsobat.xyz") -> 1
            host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) -> 3
            else -> 2
        }
    }

    private fun isSeriesDetailUrl(url: String): Boolean {
        if (!isSafeRemoteHttpUrl(url)) return false
        return runCatching {
            URI(url).path.orEmpty().contains("/series/", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun isWatchPageUrl(url: String): Boolean {
        if (!isSafeRemoteHttpUrl(url)) return false
        return runCatching {
            URI(url).path.orEmpty().trimEnd('/').endsWith("/watch", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun sameHost(first: String, second: String): Boolean {
        return runCatching {
            val firstHost = URI(first).host.orEmpty().lowercase()
            val secondHost = URI(second).host.orEmpty().lowercase()
            firstHost.isNotBlank() && firstHost == secondHost
        }.getOrDefault(false)
    }
}
