package com.example

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal suspend fun resolveIndomaxPlayerPhases(
    primary: PlayerResolutionCandidate?,
    loadFallbacks: suspend () -> List<PlayerResolutionCandidate>,
    resolveBatch: suspend (List<PlayerResolutionCandidate>) -> Boolean
): Boolean {
    if (primary != null && resolveBatch(listOf(primary))) return true
    val fallbacks = loadFallbacks()
    return fallbacks.isNotEmpty() && resolveBatch(fallbacks)
}

class IndomaxProvider : MainAPI() {
    override var mainUrl = "https://idmxl.ink"
    override var name = "Indomax"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "category/serial-tv/page/%d/" to "TV Series",
        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",
        "category/animation/page/%d/" to "Animation",
        "category/anime/page/%d/" to "Anime",
        "category/comedy/page/%d/" to "Comedy",
        "category/donghua/page/%d/" to "Donghua",
        "category/thriller/page/%d/" to "Thriller",
        "country/china/page/%d/" to "China",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/philippines/page/%d/" to "Philippines",
        "country/thailand/page/%d/" to "Thailand"
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
            IndomaxParser.catalogItems(document, fetch.url).map { it.toSearchResult() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        val fetch = getProviderPage(
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv"
        ) ?: return emptyList()
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return emptyList()

        val document = Jsoup.parse(fetch.body, fetch.url)
        return IndomaxParser.catalogItems(document, fetch.url).map { it.toSearchResult() }
    }

    private fun IndomaxCatalogItem.toSearchResult(): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = fixUrlNull(this@toSearchResult.posterUrl)
                quality = getQualityFromString(this@toSearchResult.quality)
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = fixUrlNull(this@toSearchResult.posterUrl)
                quality = getQualityFromString(this@toSearchResult.quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = IndomaxParser.providerPageUrl(url, mainUrl) ?: return null
        val fetch = getProviderPage(requestUrl) ?: return null
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return null

        val canonicalUrl = fetch.url
        val document = Jsoup.parse(fetch.body, canonicalUrl)
        val title = MovieMetadataParser.title(
            document.selectFirst("h1.entry-title")?.text()
        ) ?: return null
        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.let { ProviderHtmlParser.absoluteUrl(it, canonicalUrl) }
            ?.let(::fixUrlNull)
            ?: ProviderHtmlParser.firstImageSource(
                document,
                "figure.pull-left img, figure img"
            )?.let { ProviderHtmlParser.absoluteUrl(it, canonicalUrl) }
                ?.let(::fixUrlNull)
        val description = document.selectFirst("div[itemprop=description] p")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: MovieMetadataParser.synopsis(document)
        val tags = document.select(
            "div.gmr-moviedata a[href*='/genre/'], div.gmr-moviedata a[href*='/country/']"
        ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = document.select("a[href*='/year/']")
            .firstNotNullOfOrNull {
                YEAR_REGEX.find(it.text())?.value?.toIntOrNull()
            }
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val actors = document.select(
            "span[itemprop=actor] a, span[itemprop=actors] a"
        ).map { it.text().trim() }.filter { it.isNotBlank() }
        val rating = document.selectFirst("[itemprop=ratingValue]")?.text()?.trim()
            ?: document.selectFirst("div.gmr-rating-bar span")
                ?.attr("style")
                ?.let { RATING_WIDTH_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?.toDoubleOrNull()
                ?.div(10)
                ?.toString()
        val duration = DURATION_REGEX.find(
            document.select("div.gmr-moviedata").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        val recommendations = IndomaxParser.catalogItems(
            document.select("article.item.col-md-20, article.item").toDocument(canonicalUrl),
            canonicalUrl
        ).map { it.toSearchResult() }
        val episodeLinks = document.select(
            "div.vid-episodes a[href], div.gmr-listseries a[href]"
        )
        val isSeries = canonicalUrl.contains("/tv/", ignoreCase = true) ||
            episodeLinks.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeLinks.mapNotNull { link ->
                val href = IndomaxParser.providerPageUrl(link.attr("href"), canonicalUrl)
                    ?: return@mapNotNull null
                val rawLabel = link.attr("title").takeIf { it.isNotBlank() }
                    ?: link.text()
                val label = rawLabel
                    .replace(Regex("""(?i)^permalink\s+ke\s+"""), "")
                    .trim()
                val (_, episodeNumber) = PopularProviderEpisodeParser.position(label)
                newEpisode(href) {
                    episode = episodeNumber
                    name = episodeNumber?.let { "Episode $it" } ?: label
                    posterUrl = poster
                }
            }
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
        val requestUrl = IndomaxParser.providerPageUrl(data, mainUrl) ?: return false
        val fetch = getProviderPage(requestUrl) ?: return false
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return false

        val pageUrl = fetch.url
        val document = Jsoup.parse(fetch.body, pageUrl)
        val resolver = LinkResolutionSession(
            api = this,
            subtitleCallback = subtitleCallback,
            callback = callback,
            pageFetcher = { playerUrl, referer ->
                getPublicPlayerPage(playerUrl, referer)?.body.orEmpty()
            },
            playerApiFetcher = { apiUrl, referer, headers ->
                getPublicPlayerPage(
                    apiUrl,
                    referer,
                    headers,
                    INDOMAX_PLAYER_API_LIMIT_BYTES
                )?.body.orEmpty()
            },
            inlineSourceParser = IndomaxParser::imaxMediaUrls,
            maxCandidates = 12
        )
        val primary = IndomaxParser.primaryPlayerUrl(document, pageUrl)?.let { playerUrl ->
            PlayerResolutionCandidate(playerUrl, pageUrl)
        }
        val resolved = resolveIndomaxPlayerPhases(
            primary = primary,
            loadFallbacks = {
                val remainingPlayers =
                    IndomaxParser.MAX_PLAYER_PAGES - if (primary == null) 0 else 1
                val tabUrls =
                    IndomaxParser.alternateTabUrls(document, pageUrl, remainingPlayers)
                coroutineScope {
                    tabUrls.map { tabUrl ->
                        async {
                            val tabFetch = resolver.withinBudget {
                                getProviderPage(tabUrl)
                            } ?: return@async null
                            if (
                                tabFetch.code !in 200..299 ||
                                ProviderHtmlParser.isNonContentPage(tabFetch.body)
                            ) return@async null
                            val tabDocument = Jsoup.parse(tabFetch.body, tabFetch.url)
                            IndomaxParser.primaryPlayerUrl(tabDocument, tabFetch.url)?.let {
                                playerUrl -> PlayerResolutionCandidate(playerUrl, tabFetch.url)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            },
            resolveBatch = resolver::resolveFirstVerified
        )
        return resolved && resolver.loaded
    }

    private suspend fun getProviderPage(url: String): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer {
                IndomaxParser.providerPageUrl(it, mainUrl)
            },
            maxBodyBytes = INDOMAX_PROVIDER_PAGE_LIMIT_BYTES,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun getPublicPlayerPage(
        url: String,
        referer: String?,
        headers: Map<String, String> = emptyMap(),
        maxBodyBytes: Int = IndomaxParser.MAX_PACKED_INPUT_CHARS
    ): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer(IndomaxParser::publicHttpsUrl),
            headers = headers,
            referer = referer,
            maxBodyBytes = maxBodyBytes,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).takeIf {
            it.code in 200..299 && !ProviderHtmlParser.isNonContentPage(it.body)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val INDOMAX_PROVIDER_PAGE_LIMIT_BYTES = 2_000_000
        const val INDOMAX_PLAYER_API_LIMIT_BYTES = 4_000_000
        val YEAR_REGEX = Regex("""\b(?:19|20)\d{2}\b""")
        val RATING_WIDTH_REGEX = Regex("""(?i)width\s*:\s*([0-9]+(?:\.[0-9]+)?)%""")
        val DURATION_REGEX = Regex("""(?i)duration\s*:\s*(\d+)""")
    }
}

internal data class IndomaxCatalogItem(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val quality: String?,
    val episodeCount: Int?,
    val rating: Double?,
    val isSeries: Boolean
)

internal object IndomaxParser {
    const val MAX_PLAYER_PAGES = 4
    const val MAX_PACKED_INPUT_CHARS = 512_000
    private const val MAX_PACKED_BLOCKS = 4
    private const val MAX_PACKER_HEADER_CHARS = 4_096
    private const val MAX_DICTIONARY_ITEMS = 4_096
    private const val MAX_UNPACKED_OUTPUT_CHARS = 1_000_000
    private const val MAX_IMAX_MEDIA_CANDIDATES = 4
    private const val PACKER_MARKER = "eval(function(p,a,c,k,e,d)"
    private val providerHosts = setOf(
        "idmxl.ink",
        "akses7.indomax21.xyz",
        "akses8.indomax21.xyz",
        "akses6.indomax21.xyz",
        "akses10.indomax21.xyz"
    )
    private val rotatingProviderHostRegex =
        Regex("""^akses\d{1,3}\.indomax21\.xyz$""")
    private val imaxHosts = setOf("imaxstreams.net", "imaxstreams.com")
    private val hlsUrlRegex = Regex(
        """(?i)https://[^\s"'<>\\]+(?:\.m3u8|/master\.txt)(?:\?[^\s"'<>\\]*)?"""
    )
    private val packerArgumentsRegex = Regex(
        """^\s*,\s*(\d{1,3})\s*,\s*(\d{1,5})\s*,"""
    )
    private val dictionarySplitRegex = Regex(
        """^\s*\.split\(\s*['"]\|['"]\s*\)"""
    )

    fun catalogItems(document: Document, pageUrl: String): List<IndomaxCatalogItem> {
        return document.select("article.item-infinite, article.item").mapNotNull { article ->
            val anchor = article.selectFirst("h2.entry-title a[href]") ?: return@mapNotNull null
            val title = anchor.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = providerPageUrl(anchor.attr("href"), pageUrl) ?: return@mapNotNull null
            val poster = ProviderHtmlParser.firstImageSource(
                article,
                "img.wp-post-image, div.content-thumbnail img, img"
            )?.let { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            val quality = article.selectFirst(".gmr-quality-item a, .gmr-quality-item")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val episodeCount = article.selectFirst(".gmr-numbeps span")
                ?.text()
                ?.filter(Char::isDigit)
                ?.toIntOrNull()
            val rating = article.selectFirst(".gmr-rating-item")
                ?.ownText()
                ?.trim()
                ?.toDoubleOrNull()
            IndomaxCatalogItem(
                title = title,
                url = url,
                posterUrl = poster,
                quality = quality,
                episodeCount = episodeCount,
                rating = rating,
                isSeries = episodeCount != null ||
                    url.contains("/tv/", ignoreCase = true)
            )
        }.distinctBy { it.url }
    }

    fun providerPageUrl(raw: String?, baseUrl: String): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 8_192
        } ?: return null
        return runCatching {
            val base = URI(baseUrl)
            val resolved = base.resolve(value)
            val host = resolved.host?.lowercase()?.trimEnd('.') ?: return@runCatching null
            if (
                resolved.scheme?.lowercase() != "https" ||
                resolved.userInfo != null ||
                resolved.rawFragment != null ||
                resolved.port !in setOf(-1, 443) ||
                host !in providerHosts && !rotatingProviderHostRegex.matches(host)
            ) return@runCatching null
            resolved.toASCIIString()
        }.getOrNull()
    }

    fun publicHttpsUrl(raw: String?): String? {
        return publicHttpsCandidate(raw, allowFragment = false)
    }

    fun publicPlayableUrl(raw: String?): String? {
        return publicHttpsCandidate(raw, allowFragment = true)
    }

    private fun publicHttpsCandidate(
        raw: String?,
        allowFragment: Boolean
    ): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 8_192
        } ?: return null
        return runCatching {
            val uri = URI(value)
            if (
                uri.scheme?.lowercase() != "https" ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                (!allowFragment && uri.rawFragment != null) ||
                uri.port !in setOf(-1, 443) ||
                !isSafeRemoteHttpUrl(uri.toASCIIString())
            ) return@runCatching null
            uri.toASCIIString()
        }.getOrNull()
    }

    fun primaryPlayerUrl(document: Document, pageUrl: String): String? {
        val raw = ProviderHtmlParser.firstIframeSource(
            document.selectFirst("div.gmr-embed-responsive iframe")
        ) ?: return null
        return ProviderHtmlParser.absoluteUrl(raw, pageUrl)?.let(::publicPlayableUrl)
    }

    fun alternateTabUrls(
        document: Document,
        pageUrl: String,
        remainingPlayers: Int
    ): List<String> {
        if (remainingPlayers <= 0) return emptyList()
        val current = providerPageUrl(pageUrl, pageUrl)?.substringBefore('#')
        return document.select("ul.muvipro-player-tabs li a[href]").asSequence()
            .filterNot { link ->
                link.hasClass("active") || link.parent()?.hasClass("active") == true
            }
            .mapNotNull { link ->
                ProviderHtmlParser.absoluteUrl(link.attr("href"), pageUrl)
            }
            .mapNotNull { providerPageUrl(it, pageUrl) }
            .filter { it.substringBefore('#') != current }
            .distinct()
            .take(remainingPlayers.coerceAtMost(MAX_PLAYER_PAGES))
            .toList()
    }

    fun imaxMediaUrls(html: String, playerUrl: String): List<String> {
        if (
            html.length > MAX_PACKED_INPUT_CHARS ||
            !isImaxPlayerUrl(playerUrl)
        ) return emptyList()

        val scripts = Jsoup.parse(html, playerUrl).select("script")
            .asSequence()
            .map(Element::data)
            .filter { it.isNotBlank() }
            .filter {
                it.contains(PACKER_MARKER) ||
                    it.contains(".m3u8", ignoreCase = true)
            }
            .take(MAX_PACKED_BLOCKS)
            .toList()
        return scripts.asSequence()
            .flatMap { script ->
                sequenceOf(script) + unpackedScripts(script).asSequence()
            }
            .flatMap { script ->
                hlsUrlRegex.findAll(script.decodeJsUrl()).map { it.value }
            }
            .mapNotNull(::publicHttpsUrl)
            .distinct()
            .take(MAX_IMAX_MEDIA_CANDIDATES)
            .toList()
    }

    private fun isImaxPlayerUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase() == "https" &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            uri.host?.lowercase()?.trimEnd('.') in imaxHosts
    }.getOrDefault(false)

    private fun unpackedScripts(script: String): List<String> {
        val unpacked = mutableListOf<String>()
        var cursor = 0
        while (cursor < script.length && unpacked.size < MAX_PACKED_BLOCKS) {
            val start = script.indexOf(PACKER_MARKER, cursor)
            if (start < 0) break
            unpackClassicPacker(script, start)?.let(unpacked::add)
            cursor = start + PACKER_MARKER.length
        }
        return unpacked
    }

    private fun unpackClassicPacker(script: String, start: Int): String? {
        if (script.length > MAX_PACKED_INPUT_CHARS || start !in script.indices) return null
        val argumentsStart = script.indexOf("}(", start)
            .takeIf { it >= 0 && it - start <= MAX_PACKER_HEADER_CHARS }
            ?: return null
        var cursor = skipWhitespace(script, argumentsStart + 2)
        val payload = readJsQuoted(script, cursor) ?: return null
        cursor = payload.endExclusive

        val argumentPrefix = script.substring(
            cursor,
            (cursor + 100).coerceAtMost(script.length)
        )
        val arguments = packerArgumentsRegex.find(argumentPrefix) ?: return null
        val radix = arguments.groupValues[1].toIntOrNull()
            ?.takeIf { it in 2..62 }
            ?: return null
        val count = arguments.groupValues[2].toIntOrNull()
            ?.takeIf { it in 0..MAX_DICTIONARY_ITEMS }
            ?: return null
        cursor += arguments.range.last + 1
        cursor = skipWhitespace(script, cursor)
        val dictionary = readJsQuoted(script, cursor) ?: return null
        cursor = dictionary.endExclusive
        val suffix = script.substring(
            cursor,
            (cursor + 100).coerceAtMost(script.length)
        )
        if (!dictionarySplitRegex.containsMatchIn(suffix)) return null
        val words = dictionary.value.split('|')
        return unpackTokens(payload.value, words, count, radix)
    }

    private fun unpackTokens(
        payload: String,
        words: List<String>,
        count: Int,
        radix: Int
    ): String? {
        val output = StringBuilder(payload.length.coerceAtMost(MAX_UNPACKED_OUTPUT_CHARS))
        var cursor = 0
        while (cursor < payload.length) {
            if (!payload[cursor].isAsciiWord()) {
                output.append(payload[cursor++])
            } else {
                val start = cursor
                while (cursor < payload.length && payload[cursor].isAsciiWord()) cursor++
                val token = payload.substring(start, cursor)
                val index = decodePackerToken(token, radix)
                val replacement = index
                    ?.takeIf { it in 0 until count }
                    ?.let(words::getOrNull)
                    .orEmpty()
                output.append(replacement.ifEmpty { token })
            }
            if (output.length > MAX_UNPACKED_OUTPUT_CHARS) return null
        }
        return output.toString()
    }

    private fun decodePackerToken(token: String, radix: Int): Int? {
        var value = 0L
        for (character in token) {
            val digit = when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'z' -> character - 'a' + 10
                in 'A'..'Z' -> character - 'A' + 36
                else -> return null
            }
            if (digit >= radix) return null
            value = value * radix + digit
            if (value > Int.MAX_VALUE) return null
        }
        return value.toInt()
    }

    private data class JsQuotedValue(
        val value: String,
        val endExclusive: Int
    )

    private fun readJsQuoted(input: String, quoteIndex: Int): JsQuotedValue? {
        val quote = input.getOrNull(quoteIndex)?.takeIf { it == '\'' || it == '"' }
            ?: return null
        val value = StringBuilder()
        var cursor = quoteIndex + 1
        while (cursor < input.length) {
            val character = input[cursor++]
            when {
                character == quote -> return JsQuotedValue(value.toString(), cursor)
                character != '\\' -> value.append(character)
                cursor >= input.length -> return null
                else -> {
                    val escaped = input[cursor++]
                    when (escaped) {
                        '\\', '\'', '"', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            val decoded = input.decodeHex(cursor, 4) ?: return null
                            value.append(decoded.first)
                            cursor = decoded.second
                        }
                        'x' -> {
                            val decoded = input.decodeHex(cursor, 2) ?: return null
                            value.append(decoded.first)
                            cursor = decoded.second
                        }
                        '\r', '\n' -> Unit
                        else -> value.append(escaped)
                    }
                }
            }
            if (value.length > MAX_PACKED_INPUT_CHARS) return null
        }
        return null
    }

    private fun String.decodeHex(start: Int, length: Int): Pair<Char, Int>? {
        val end = start + length
        if (start < 0 || end > this.length) return null
        val value = substring(start, end).toIntOrNull(16) ?: return null
        return value.toChar() to end
    }

    private fun skipWhitespace(value: String, start: Int): Int {
        var cursor = start
        while (cursor < value.length && value[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun Char.isAsciiWord(): Boolean =
        this == '_' || this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

    private fun String.decodeJsUrl(): String = replace("\\/", "/")
        .replace(Regex("""(?i)\\u0026"""), "&")
        .replace("&amp;", "&")
}

private fun List<Element>.toDocument(baseUrl: String): Document =
    Jsoup.parse(joinToString(separator = "") { it.outerHtml() }, baseUrl)
