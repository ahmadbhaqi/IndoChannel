package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v19.kuramanime.ing"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    private val ownedHosts = setOf(
        "v19.kuramanime.ing",
        "v8.kuramanime.tel",
        "v9.kuramanime.tel",
        "v10.kuramanime.tel",
        "v11.kuramanime.tel",
        "v17.kuramanime.tel",
        "v17.kuramanime.ing"
    )
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Anime",
        "$mainUrl/anime?order_by=most_viewed&page=" to "Paling Banyak Ditonton"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val fetch = getProviderPage(request.data + page.coerceAtLeast(1))
            ?: return newHomePageResponse(request.name, emptyList())
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return newHomePageResponse(request.name, emptyList())
        val document = Jsoup.parse(fetch.body, fetch.url)
        val excludeFinished = KuramanimeParser.isOngoingCatalog(request.data)
        return newHomePageResponse(
            request.name,
            document.select("div#animeList div.product__item, div.col-lg-4.col-md-6.col-sm-6")
                .mapNotNull { it.toSearchResult(excludeFinished) }
                .distinctBy { it.url }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val fetch = getProviderPage("$mainUrl/anime?search=$encoded&order_by=latest")
            ?: return emptyList()
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return emptyList()
        return Jsoup.parse(fetch.body, fetch.url).select("div#animeList div.product__item")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(excludeFinished: Boolean = false): AnimeSearchResponse? {
        val anchor = KuramanimeParser.catalogAnchor(this, excludeFinished) ?: return null
        val href = animeUrl(anchor.attr("href")) ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        if (SensitiveContentPolicy.isBlocked(title, href)) return null
        val poster = fixUrlNull(
            selectFirst("div.product__item__pic")?.attr("data-setbg")
                ?: ProviderHtmlParser.firstImageSource(this)
        )
        val episode = Regex("""(?i)ep\s*(\d+)""")
            .find(selectFirst("div.ep span, span.ep")?.text().orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = animeUrl(url) ?: return null
        if (SensitiveContentPolicy.isBlocked(null, requestUrl)) return null
        val fetch = getProviderPage(requestUrl) ?: return null
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return null
        val document = Jsoup.parse(fetch.body, fetch.url)
        val title = document.selectFirst(".anime__details__title h3, h1")?.text()
            ?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".anime__details__pic")?.attr("data-setbg")
                ?: ProviderHtmlParser.firstImageSource(document, ".anime__details__pic img")
        )
        val description = document.selectFirst(".anime__details__text > p, .synopsis")?.text()?.trim()
        val tags = document.select(
            ".anime__details__widget li:contains(Genre:) a, a[href*='/properties/genre/']"
        ).map { it.text().trim() }.filter(String::isNotBlank).distinct()
        if (SensitiveContentPolicy.isBlocked(title, requestUrl, categories = tags)) return null
        val metadataText = document.select(".anime__details__widget").text()
        val year = Regex("""(?:19|20)\d{2}""").find(metadataText)?.value?.toIntOrNull()
        val status = when {
            metadataText.contains("Sedang Tayang", ignoreCase = true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
        val episodes = mutableListOf<Episode>()
        for (page in 1..10) {
            val pageDocument = if (page == 1) {
                document
            } else {
                val pageFetch = getProviderPage("${requestUrl.trimEnd('/')}?page=$page")
                    ?: break
                if (
                    pageFetch.code !in 200..299 ||
                    ProviderHtmlParser.isNonContentPage(pageFetch.body)
                ) break
                Jsoup.parse(pageFetch.body, pageFetch.url)
            }
            val pageEpisodes = KuramanimeParser.episodeLinks(pageDocument, requestUrl)
            if (pageEpisodes.isEmpty()) {
                if (page > 1) break
            } else {
                pageEpisodes.forEach { (episodeUrl, label) ->
                    val number = Regex("""\d+(?:[.,]\d+)?""").find(label)?.value
                        ?.replace(',', '.')?.toDoubleOrNull()?.toInt()
                    episodes += newEpisode(
                        AnimePlaybackDataCodec.encode(
                            url = episodeUrl,
                            title = label,
                            categories = tags,
                            detailUrl = requestUrl
                        ),
                        initializer = {
                            episode = number
                            name = label
                            posterUrl = poster
                        },
                        fix = false
                    )
                }
            }
        }
        val distinctEpisodes = episodes.distinctBy { it.data }
        val typeLabel = document.selectFirst("li:contains(Tipe:) a")?.text().orEmpty()
        val type = when {
            typeLabel.contains("movie", ignoreCase = true) && distinctEpisodes.size <= 1 ->
                TvType.AnimeMovie
            typeLabel.contains("ova", ignoreCase = true) ||
                typeLabel.contains("special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }
        val recommendations = document.select("div#randomList > a[href]").mapNotNull { anchor ->
            val href = animeUrl(anchor.attr("href")) ?: return@mapNotNull null
            val recommendationTitle = anchor.selectFirst("h5")?.text()?.trim()
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (SensitiveContentPolicy.isBlocked(recommendationTitle, href)) return@mapNotNull null
            newAnimeSearchResponse(recommendationTitle, href, TvType.Anime) {
                posterUrl = fixUrlNull(
                    anchor.selectFirst(".product__sidebar__view__item")?.attr("data-setbg")
                )
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        return newAnimeLoadResponse(title, requestUrl, type) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, distinctEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = AnimePlaybackDataCodec.decode(data)
        if (AnimePlaybackDataCodec.isBlocked(data)) return false
        val pageUrl = episodeUrl(playback?.url ?: data) ?: return false
        if (SensitiveContentPolicy.isBlocked(null, pageUrl)) return false
        val response = getProviderPage(pageUrl) ?: return false
        if (
            response.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(response.body)
        ) return false
        val episodeDocument = Jsoup.parse(response.body, response.url)
        val episodeTitle = episodeDocument.selectFirst("h1, .anime__details__title h3")
            ?.text()
        val episodeTags = episodeDocument.select(
            ".anime__details__widget li:contains(Genre:) a, " +
                ".anime__details__widget a[href*='/properties/genre/']"
        ).map { it.text().trim() }
        if (
            SensitiveContentPolicy.isBlocked(
                episodeTitle,
                response.url,
                categories = episodeTags
            )
        ) return false
        val staticCandidates = KuramanimeParser.playerUrls(response.body, response.url)
        val hydratedCandidates = fetchHydratedCandidates(response)
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = KuramanimeParser::fragmentMediaUrls
        )
        return resolveKuramanimeCandidatesHydrationFirst(
            staticCandidates = staticCandidates,
            staticReferer = response.url,
            hydrate = { hydratedCandidates },
            canContinue = { resolver.canContinue },
            isLoaded = { resolver.loaded },
            resolve = { candidate, referer -> resolver.resolve(candidate, referer) }
        )
    }

    private suspend fun fetchHydratedCandidates(
        response: ProviderHttpResult
    ): KuramanimeCandidateBatch? {
        return try {
            val document = Jsoup.parse(response.body, response.url)
            val pageMetadata = KuramanimeBootstrap.pageMetadata(document, response.url)
                ?: return null
            val checkUrl = providerAssetUrl(pageMetadata.checkUrl, response.url)
                ?: return null
            val checkResponse = getProviderAsset(
                checkUrl,
                response.url,
                response.url,
                KURAMANIME_TOKEN_BODY_LIMIT_BYTES
            ) ?: return null
            if (
                checkResponse.code !in 200..299 ||
                ProviderHtmlParser.isNonContentPage(checkResponse.body)
            ) return null
            val checkedPage = KuramanimeBootstrap.checkPageValue(checkResponse.body)
                ?: return null
            val secureLoaderUrl = providerAssetUrl(
                pageMetadata.secureLoaderUrl,
                response.url
            ) ?: return null
            val secureLoaderResponse = getProviderAsset(
                secureLoaderUrl,
                response.url,
                response.url,
                KURAMANIME_SCRIPT_BODY_LIMIT_BYTES
            ) ?: return null
            if (
                secureLoaderResponse.code !in 200..299 ||
                ProviderHtmlParser.isNonContentPage(secureLoaderResponse.body)
            ) return null
            val secureLoaderAuthorization =
                KuramanimeBootstrap.secureLoaderAuthorization(
                    secureLoaderResponse.body,
                    secureLoaderUrl
                ) ?: return null
            val directConfigurationUrl = document
                .select("script.js__var[src]")
                .mapNotNull { providerAssetUrl(it.attr("src"), response.url) }
                .firstOrNull { !it.contains("arc-signal", ignoreCase = true) }
            val configurationUrl = directConfigurationUrl ?: run {
                val bootstrapUrl = document
                    .select("script[src*='arc-signal']")
                    .mapNotNull { providerAssetUrl(it.attr("src"), response.url) }
                    .firstOrNull()
                    ?: return null
                val bootstrapResponse = getProviderAsset(
                    bootstrapUrl,
                    response.url,
                    response.url,
                    KURAMANIME_SCRIPT_BODY_LIMIT_BYTES
                ) ?: return null
                if (
                    bootstrapResponse.code !in 200..299 ||
                    ProviderHtmlParser.isNonContentPage(bootstrapResponse.body)
                ) return null
                KuramanimeBootstrap.configurationScriptUrl(
                    bootstrapResponse.body,
                    bootstrapResponse.url
                )?.let { providerAssetUrl(it, bootstrapResponse.url) }
                    ?: return null
            }
            val configurationResponse = getProviderAsset(
                configurationUrl,
                response.url,
                response.url,
                KURAMANIME_SCRIPT_BODY_LIMIT_BYTES
            ) ?: return null
            if (
                configurationResponse.code !in 200..299 ||
                ProviderHtmlParser.isNonContentPage(configurationResponse.body)
            ) return null
            val configuration = KuramanimeBootstrap.configuration(
                configurationResponse.body,
                configurationResponse.url
            ) ?: return null
            val tokenUrl = providerAssetUrl(configuration.tokenUrl, configurationResponse.url)
                ?: return null
            val tokenResponse = getProviderAsset(
                tokenUrl,
                response.url,
                configurationResponse.url,
                KURAMANIME_TOKEN_BODY_LIMIT_BYTES,
                mapOf(
                    "X-Fuck-ID" to configuration.authHeader,
                    "X-Request-ID" to KuramanimeBootstrap.requestId(),
                    "X-Request-Index" to "0"
                )
            ) ?: return null
            if (
                tokenResponse.code !in 200..299 ||
                ProviderHtmlParser.isNonContentPage(tokenResponse.body)
            ) return null
            val token = KuramanimeBootstrap.tokenValue(tokenResponse.body) ?: return null
            val hydrationRequest = KuramanimeBootstrap.hydrationRequest(
                episodeUrl = response.url,
                accessToken = token,
                configuration = configuration,
                page = checkedPage,
                secureLoaderAuthorization = secureLoaderAuthorization,
                csrfToken = pageMetadata.csrfToken
            ) ?: return null
            val hydratedUrl = networkProviderUrl(hydrationRequest.url)
                ?: return null
            val hydratedResponse = postProviderPage(
                url = hydratedUrl,
                form = hydrationRequest.form,
                headers = hydrationRequest.headers,
                cookies = hydrationRequest.cookies,
                referer = response.url,
                timeoutSeconds = KURAMANIME_BOOTSTRAP_TIMEOUT_SECONDS
            ) ?: return null
            if (
                hydratedResponse.code !in 200..299 ||
                ProviderHtmlParser.isNonContentPage(hydratedResponse.body)
            ) return null
            val hydratedUrls = KuramanimeParser.playerUrls(
                hydratedResponse.body,
                hydratedResponse.url
            )
            KuramanimeCandidateBatch(
                urls = hydratedUrls,
                referer = hydratedResponse.url
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getProviderPage(
        url: String,
        referer: String? = null,
        timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
    ): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer(::networkProviderUrl),
            referer = referer,
            timeoutSeconds = timeoutSeconds
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun getProviderAsset(
        url: String,
        referer: String,
        baseUrl: String,
        maxBodyBytes: Int,
        headers: Map<String, String> = emptyMap()
    ): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer { providerAssetUrl(it, baseUrl) },
            headers = headers,
            referer = referer,
            maxBodyBytes = maxBodyBytes,
            timeoutSeconds = KURAMANIME_BOOTSTRAP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun postProviderPage(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        referer: String,
        timeoutSeconds: Long
    ): ProviderHttpResult? = try {
        safeHttp.postForm(
            url = url,
            form = form,
            normalizer = ProviderUrlNormalizer(::networkProviderUrl),
            headers = headers,
            referer = referer,
            cookies = cookies,
            timeoutSeconds = timeoutSeconds
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun providerAssetUrl(raw: String?, baseUrl: String): String? {
        return ProviderHtmlParser.preserveProviderPageUrl(raw, baseUrl, ownedHosts)
    }

    private fun animeUrl(raw: String?): String? {
        val normalized = ProviderHtmlParser.normalizeProviderPageUrl(
            raw,
            mainUrl,
            ownedHosts
        ) ?: return null
        return if (normalized.contains("/episode/", ignoreCase = true)) {
            normalized.substringBefore("/episode/").trimEnd('/') + "/"
        } else {
            normalized
        }
    }

    private fun episodeUrl(raw: String?): String? = ProviderHtmlParser.normalizeProviderPageUrl(
        raw,
        mainUrl,
        ownedHosts
    )

    private fun networkProviderUrl(raw: String?): String? =
        ProviderHtmlParser.preserveProviderPageUrl(raw, mainUrl, ownedHosts)

    private companion object {
        const val KURAMANIME_SCRIPT_BODY_LIMIT_BYTES = 262_144
        const val KURAMANIME_TOKEN_BODY_LIMIT_BYTES = 4_096
        const val KURAMANIME_BOOTSTRAP_TIMEOUT_SECONDS = 10L
    }
}

internal data class KuramanimeCandidateBatch(
    val urls: List<String>,
    val referer: String
)

internal suspend fun resolveKuramanimeCandidatesHydrationFirst(
    staticCandidates: List<String>,
    staticReferer: String,
    hydrate: suspend () -> KuramanimeCandidateBatch?,
    canContinue: () -> Boolean,
    isLoaded: () -> Boolean,
    resolve: suspend (url: String, referer: String) -> Unit
): Boolean {
    val hydrated = hydrate()
    for (candidate in hydrated?.urls.orEmpty().distinct().take(12)) {
        if (isLoaded() || !canContinue()) break
        resolve(candidate, hydrated?.referer ?: staticReferer)
    }
    if (isLoaded()) return true
    for (candidate in staticCandidates.distinct()) {
        if (isLoaded() || !canContinue()) break
        resolve(candidate, staticReferer)
    }
    return isLoaded()
}

internal object KuramanimeParser {
    private const val MAX_ENCODED_PLAYER_SIZE = 32_768
    private const val MAX_PLAYER_CANDIDATES = 48
    private const val PLAYER_MEDIA_SELECTOR =
        "#player iframe, #player video, #player source, " +
            "#player script:not([src]):not([data-src]):not([data-litespeed-src]), #player [data-iframe], " +
            ".server iframe, .server video, .server source, " +
            ".server script:not([src]):not([data-src]):not([data-litespeed-src]), .server [data-iframe], " +
            ".mirror iframe, .mirror video, .mirror source, " +
            ".mirror script:not([src]):not([data-src]):not([data-litespeed-src]), .mirror [data-iframe], " +
            ".streaming-server iframe, .streaming-server video, .streaming-server source, " +
            ".streaming-server script:not([src]):not([data-src]):not([data-litespeed-src]), " +
            ".streaming-server [data-iframe]"
    private const val FRAGMENT_MEDIA_SELECTOR =
        "iframe, video, source, script:not([src]):not([data-src]):not([data-litespeed-src]), " +
            "[data-iframe], " +
            "meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player]"
    private val legacyHosts = setOf(
        "v8.kuramanime.tel",
        "v9.kuramanime.tel",
        "v10.kuramanime.tel",
        "v11.kuramanime.tel",
        "v17.kuramanime.tel",
        "v17.kuramanime.ing"
    )

    fun isOngoingCatalog(requestData: String): Boolean {
        val path = runCatching { URI(requestData).path.orEmpty() }
            .getOrDefault("")
            .trimEnd('/')
        return path.equals("/anime/ongoing", ignoreCase = true) ||
            path.equals("/quick/ongoing", ignoreCase = true)
    }

    fun catalogAnchor(
        element: Element,
        excludeFinished: Boolean = false
    ): Element? {
        if (element.selectFirst(".fa-droplet") != null) return null
        if (
            excludeFinished &&
            element.select(".status span").any {
                it.text().trim().equals("SELESAI", ignoreCase = true)
            }
        ) return null
        return element.selectFirst("h5 a[href]")?.takeIf { it.text().isNotBlank() }
            ?: element.select("a[href*='/anime/'], a[href*='/episode/']")
                .firstOrNull { it.text().trim().isNotBlank() }
    }

    fun episodeLinks(document: Document, baseUrl: String): List<Pair<String, String>> {
        val encodedList = document.selectFirst("#episodeLists")?.attr("data-content")
            ?.takeIf { it.isNotBlank() }
            ?.let { Jsoup.parse(it, baseUrl) }
        val anchors = document.select(
            "#episodeLists a[href], .episode-list a[href], .eplister a[href]"
        ) + encodedList?.select(
            "a.btn.btn-sm.btn-danger[href], a[href*='/episode/']"
        ).orEmpty()
        return anchors.mapNotNull { anchor ->
            val url = ProviderHtmlParser.normalizeProviderPageUrl(
                anchor.attr("href"),
                baseUrl,
                legacyHosts
            ) ?: return@mapNotNull null
            val label = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            url to label
        }.distinctBy { it.first }
    }

    fun playerUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        val hydratedHls = document.select("#animeVideoPlayer[data-hls-src]")
            .map { it.attr("data-hls-src") }
        val playerElements = PopularProviderLinkLimits.playerElements(
            document,
            "#player option[value], #player [data-video], #player [data-url], " +
                ".server option[value], .server [data-video], .server [data-url], " +
                ".mirror option[value], .streaming-server a[href]"
        )
        val direct = buildList {
            addAll(PopularProviderLinkLimits.scopedMediaUrls(document, PLAYER_MEDIA_SELECTOR))
            addAll(
                playerElements.flatMap { element ->
                    listOf(
                        element.attr("data-video"),
                        element.attr("data-url"),
                        element.attr("value"),
                        element.attr("href")
                    )
                }
            )
        }
        val decoded = playerElements.flatMap { element ->
            decodedPlayerValue(
                listOf(element.attr("value"), element.attr("data-video"), element.attr("data-url"))
                    .firstOrNull { it.isNotBlank() },
                playerUrl
            )
        }
        return (hydratedHls + direct + decoded)
            .mapNotNull { candidate ->
                candidate.takeIf { value ->
                    value.startsWith("http://", true) ||
                        value.startsWith("https://", true) ||
                        value.startsWith("//") ||
                        value.startsWith("/")
                }?.let { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            }
            .filter(::isSafeRemoteHttpUrl)
            .distinct()
            .take(MAX_PLAYER_CANDIDATES)
    }

    fun fragmentMediaUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        return PopularProviderLinkLimits.scopedMediaUrls(document, FRAGMENT_MEDIA_SELECTOR)
    }

    private fun decodedPlayerValue(raw: String?, playerUrl: String): List<String> {
        val value = raw?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_ENCODED_PLAYER_SIZE }
            ?: return emptyList()
        val decoded = decodeBase64Compat(value)?.toString(Charsets.UTF_8)
            ?.takeIf { it.length <= MAX_ENCODED_PLAYER_SIZE }
            ?: return emptyList()
        if (isSafeRemoteHttpUrl(decoded.trim())) return listOf(decoded.trim())
        val document = Jsoup.parse(decoded, playerUrl)
        return PopularProviderLinkLimits.scopedMediaUrls(document, FRAGMENT_MEDIA_SELECTOR)
    }
}

internal data class KuramanimeBootstrapConfig(
    val tokenUrl: String,
    val authHeader: String,
    val pageTokenKey: String,
    val streamServerKey: String
)

internal data class KuramanimePageMetadata(
    val checkUrl: String,
    val secureLoaderUrl: String,
    val csrfToken: String
)

internal data class KuramanimeHydrationRequest(
    val url: String,
    val form: Map<String, String>,
    val headers: Map<String, String>,
    val cookies: Map<String, String>
)

internal object KuramanimeBootstrap {
    private const val MAX_SCRIPT_SIZE = 262_144
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val safeRouteValue = Regex("""^[A-Za-z0-9_./-]{1,256}$""")
    private val safeIdentifier = Regex("""^[A-Za-z_][A-Za-z0-9_]{0,127}$""")
    private val safeToken = Regex("""^[A-Za-z0-9_-]{1,256}$""")
    private val safeSecureLoaderAuthorization = Regex("""^[A-Za-z0-9_-]{20,128}$""")
    private val safeCsrfToken = Regex("""^[A-Za-z0-9_-]{8,256}$""")
    private val knownSecureLoaderAuthorizations = mapOf(
        "1448" to "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur"
    )
    private const val REQUEST_ID_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun pageMetadata(document: Document, pageUrl: String): KuramanimePageMetadata? {
        val checkUrl = ProviderHtmlParser.absoluteUrl(
            document.selectFirst("#checkEp[value]")?.attr("value"),
            pageUrl
        )?.takeIf(::isSafeRemoteHttpUrl) ?: return null
        val secureLoaderUrl = ProviderHtmlParser.absoluteUrl(
            document.selectFirst("#tokenAuthJs[value]")?.attr("value"),
            pageUrl
        )?.takeIf(::isSafeRemoteHttpUrl) ?: return null
        val csrfToken = document.selectFirst("meta[name=csrf-token][content]")
            ?.attr("content")
            ?.trim()
            ?.takeIf(safeCsrfToken::matches)
            ?: return null
        return KuramanimePageMetadata(
            checkUrl = checkUrl,
            secureLoaderUrl = secureLoaderUrl,
            csrfToken = csrfToken
        )
    }

    fun checkPageValue(raw: String?): Int? {
        val value = raw?.trim()?.removeSurrounding("\"")?.trim()
            ?.takeIf { Regex("""^\d{1,5}$""").matches(it) }
            ?: return null
        return value.toIntOrNull()?.takeIf { it in 1..10_000 }
    }

    fun secureLoaderAuthorization(script: String, loaderUrl: String): String? {
        if (script.isBlank() || script.length > MAX_SCRIPT_SIZE) return null
        val explicit = listOf(
            Regex(
                """(?i)\bauthorization\s*[:=]\s*["']([A-Za-z0-9_-]{20,128})["']"""
            ),
            Regex("""(?i)\bBearer\s+([A-Za-z0-9_-]{20,128})""")
        ).firstNotNullOfOrNull { pattern ->
            pattern.find(script)?.groupValues?.getOrNull(1)
        }
        if (explicit != null) return explicit.takeIf(safeSecureLoaderAuthorization::matches)
        val version = Regex("""(?:[?&])v=(\d{1,8})(?:&|$)""")
            .find(loaderUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return knownSecureLoaderAuthorizations[version]
            ?.takeIf(safeSecureLoaderAuthorization::matches)
    }

    fun configurationScriptUrl(script: String, bootstrapUrl: String): String? {
        if (script.isBlank() || script.length > MAX_SCRIPT_SIZE) return null
        val variable = Regex(
            """/assets/js/\$\{([A-Za-z_$][A-Za-z0-9_$]*)\}\.js"""
        ).find(script)?.groupValues?.getOrNull(1)
        val basename = variable?.let { identifier ->
            Regex(
                """\b${Regex.escape(identifier)}\s*=\s*["']([A-Za-z0-9_-]{1,128})["']"""
            ).find(script)?.groupValues?.getOrNull(1)
        }
        val rawUrl = basename?.let { "/assets/js/$it.js" }
            ?: Regex(
                """["']((?:https?://[^"']+)?/assets/js/[A-Za-z0-9_-]{1,128}\.js(?:\?[^"']*)?)["']"""
            ).find(script)?.groupValues?.getOrNull(1)
            ?: return null
        return ProviderHtmlParser.absoluteUrl(rawUrl, bootstrapUrl)
            ?.takeIf(::isSafeRemoteHttpUrl)
    }

    fun configuration(script: String, configurationUrl: String): KuramanimeBootstrapConfig? {
        if (script.isBlank() || script.length > MAX_SCRIPT_SIZE) return null
        val environment = environmentObject(script) ?: return null
        val requiredKeys = setOf(
            "MIX_PREFIX_AUTH_ROUTE_PARAM",
            "MIX_AUTH_ROUTE_PARAM",
            "MIX_AUTH_KEY",
            "MIX_AUTH_TOKEN",
            "MIX_PAGE_TOKEN_KEY",
            "MIX_STREAM_SERVER_KEY"
        )
        val matches = Regex(
            """\b(MIX_[A-Z0-9_]+)\s*:\s*["']([^"']{1,512})["']"""
        ).findAll(environment)
            .filter { it.groupValues[1] in requiredKeys }
            .groupBy { it.groupValues[1] }
        if (requiredKeys.any { matches[it]?.size != 1 }) return null
        val values = matches.mapValues { (_, entries) -> entries.single().groupValues[2].trim() }
        val prefix = values["MIX_PREFIX_AUTH_ROUTE_PARAM"] ?: return null
        val route = values["MIX_AUTH_ROUTE_PARAM"] ?: return null
        val authKey = values["MIX_AUTH_KEY"] ?: return null
        val authToken = values["MIX_AUTH_TOKEN"] ?: return null
        val pageTokenKey = values["MIX_PAGE_TOKEN_KEY"] ?: return null
        val streamServerKey = values["MIX_STREAM_SERVER_KEY"] ?: return null
        if (
            listOf(prefix, route).any { !safeRouteValue.matches(it) || ".." in it } ||
            !safeToken.matches(authKey) ||
            !safeToken.matches(authToken) ||
            !safeIdentifier.matches(pageTokenKey) ||
            !safeIdentifier.matches(streamServerKey)
        ) return null
        val tokenPath = "/" + listOf(prefix.trim('/'), route.trim('/'))
            .filter(String::isNotBlank)
            .joinToString("/")
        val tokenUrl = ProviderHtmlParser.absoluteUrl(tokenPath, configurationUrl)
            ?.takeIf(::isSafeRemoteHttpUrl)
            ?: return null
        return KuramanimeBootstrapConfig(
            tokenUrl = tokenUrl,
            authHeader = "$authKey:$authToken",
            pageTokenKey = pageTokenKey,
            streamServerKey = streamServerKey
        )
    }

    fun tokenValue(raw: String?): String? =
        raw?.trim()?.takeIf(safeToken::matches)

    fun hydratedPageUrl(
        episodeUrl: String,
        token: String,
        configuration: KuramanimeBootstrapConfig,
        page: Int = 1
    ): String? {
        if (!isSafeRemoteHttpUrl(episodeUrl) || tokenValue(token) == null) return null
        if (
            !safeIdentifier.matches(configuration.pageTokenKey) ||
            !safeIdentifier.matches(configuration.streamServerKey) ||
            page !in 1..10_000
        ) return null
        val withoutFragment = episodeUrl.substringBefore('#')
        val separator = if ('?' in withoutFragment) '&' else '?'
        return buildString {
            append(withoutFragment)
            append(separator)
            append(configuration.pageTokenKey)
            append('=')
            append(encode(token))
            append('&')
            append(configuration.streamServerKey)
            append("=kuramadrive&page=")
            append(page)
        }
    }

    fun hydrationRequest(
        episodeUrl: String,
        accessToken: String,
        configuration: KuramanimeBootstrapConfig,
        page: Int,
        secureLoaderAuthorization: String,
        csrfToken: String
    ): KuramanimeHydrationRequest? {
        val authorization = secureLoaderAuthorization.trim()
            .takeIf(safeSecureLoaderAuthorization::matches)
            ?: return null
        val csrf = csrfToken.trim().takeIf(safeCsrfToken::matches) ?: return null
        val url = hydratedPageUrl(episodeUrl, accessToken, configuration, page)
            ?: return null
        val origin = runCatching {
            val uri = URI(url)
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            "${uri.scheme}://${uri.host}$port"
        }.getOrNull()?.takeIf(::isSafeRemoteHttpUrl) ?: return null
        return KuramanimeHydrationRequest(
            url = url,
            form = mapOf("authorization" to authorization),
            headers = mapOf(
                "X-CSRF-TOKEN" to csrf,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "text/html, */*; q=0.01",
                "Origin" to origin,
                "User-Agent" to BROWSER_USER_AGENT
            ),
            cookies = emptyMap()
        )
    }

    fun requestId(): String = buildString(6) {
        repeat(6) {
            append(REQUEST_ID_ALPHABET[Random.nextInt(REQUEST_ID_ALPHABET.length)])
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun environmentObject(script: String): String? {
        val patterns = listOf(
            Regex(
                """(?s)\b(?:window\.)?process\s*=\s*\{\s*env\s*:\s*\{(.{1,$MAX_SCRIPT_SIZE}?)\}\s*\}\s*;?"""
            ),
            Regex(
                """(?s)\b(?:window\.)?process\.env\s*=\s*\{(.{1,$MAX_SCRIPT_SIZE}?)\}\s*;?"""
            )
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(script)?.groupValues?.getOrNull(1)
        }
    }
}
