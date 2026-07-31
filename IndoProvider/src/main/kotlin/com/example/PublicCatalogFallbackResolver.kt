package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves an exact, full-length public catalog copy after a provider's own
 * mirrors fail. Keeping this path shared prevents providers from drifting into
 * different title, year, duration, host, or media-probe policies.
 */
internal suspend fun MainAPI.loadExactPublicCatalogFallback(
    safeHttp: ProviderHttpSafetyClient,
    requests: List<NomatFallbackRequest>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    sessionTimeoutMs: Long = PUBLIC_CATALOG_SESSION_TIMEOUT_MS
): Boolean {
    val orderedRequests = requests
        .asSequence()
        .filter { request -> request.title.isNotBlank() }
        .distinct()
        .take(PUBLIC_CATALOG_MAX_REQUESTS)
        .toList()
    if (orderedRequests.isEmpty()) return false

    val resolver = LinkResolutionSession(
        this,
        subtitleCallback,
        callback,
        candidateTimeoutMs = PUBLIC_CATALOG_CANDIDATE_TIMEOUT_MS,
        sessionTimeoutMs = sessionTimeoutMs,
        mediaProbeAttempts = PUBLIC_CATALOG_MEDIA_PROBE_ATTEMPTS
    )

    for (request in orderedRequests) {
        if (!resolver.canContinue) break
        for (searchUrl in BstationFallbackParser.searchUrls(request)) {
            if (!resolver.canContinue) break
            val searchPage = getPublicCatalogResource(
                resolver = resolver,
                safeHttp = safeHttp,
                url = searchUrl,
                normalizer = ProviderUrlNormalizer(BstationFallbackParser::networkUrl),
                headers = BstationFallbackParser.requestHeaders,
                referer = BSTATION_ORIGIN,
                maxBodyBytes = PUBLIC_CATALOG_BSTATION_SEARCH_BODY_LIMIT_BYTES
            ) ?: continue
            val candidates = BstationFallbackParser.searchCandidates(searchPage.body)
                .asSequence()
                .filter { candidate ->
                    BstationFallbackParser.isExactCandidate(request, candidate)
                }
                .take(PUBLIC_CATALOG_BSTATION_MAX_RESULTS)
                .toList()
            for (candidate in candidates) {
                if (!resolver.canContinue) break
                val playUrl = BstationFallbackParser.playUrl(candidate.aid) ?: continue
                val playResponse = getPublicCatalogResource(
                    resolver = resolver,
                    safeHttp = safeHttp,
                    url = playUrl,
                    normalizer = ProviderUrlNormalizer(BstationFallbackParser::networkUrl),
                    headers = BstationFallbackParser.requestHeaders,
                    referer = candidate.pageUrl,
                    maxBodyBytes = PUBLIC_CATALOG_BSTATION_PLAY_BODY_LIMIT_BYTES
                )?.body?.let { body ->
                    runCatching { parseJson<BstationPlayResponse>(body) }.getOrNull()
                } ?: continue
                val media = BstationFallbackParser.playbackMedia(playResponse) ?: continue
                val mediaHeaders = BstationFallbackParser.mediaHeaders(candidate.pageUrl)
                val link = try {
                    val audio = newAudioFile(media.audioUrl) {
                        headers = mediaHeaders
                    }
                    newExtractorLink(
                        name,
                        "$name Bstation ${media.height}p",
                        media.videoUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        referer = candidate.pageUrl
                        quality = media.height
                        headers = mediaHeaders
                        audioTracks = listOf(audio)
                    }
                } catch (_: LinkageError) {
                    continue
                }
                if (resolver.emitResolved(link)) return true
            }
        }
    }
    if (resolver.loaded) return true

    for (request in orderedRequests) {
        if (!resolver.canContinue) break
        val searchUrl = InternetArchiveFallbackParser.searchUrl(request) ?: continue
        val searchResponse = getPublicCatalogResource(
            resolver = resolver,
            safeHttp = safeHttp,
            url = searchUrl,
            normalizer = ProviderUrlNormalizer(InternetArchiveFallbackParser::networkUrl),
            headers = InternetArchiveFallbackParser.requestHeaders,
            maxBodyBytes = PUBLIC_CATALOG_ARCHIVE_SEARCH_BODY_LIMIT_BYTES
        )?.body?.let { body ->
            runCatching { parseJson<InternetArchiveSearchResponse>(body) }.getOrNull()
        } ?: continue
        val candidates = InternetArchiveFallbackParser.searchCandidates(searchResponse)
            .asSequence()
            .filter { candidate ->
                InternetArchiveFallbackParser.isExactCandidate(request, candidate)
            }
            .take(PUBLIC_CATALOG_ARCHIVE_MAX_RESULTS)
            .toList()
        for (candidate in candidates) {
            if (!resolver.canContinue) break
            val metadataUrl =
                InternetArchiveFallbackParser.metadataUrl(candidate.identifier) ?: continue
            val metadata = getPublicCatalogResource(
                resolver = resolver,
                safeHttp = safeHttp,
                url = metadataUrl,
                normalizer = ProviderUrlNormalizer(InternetArchiveFallbackParser::networkUrl),
                headers = InternetArchiveFallbackParser.requestHeaders,
                maxBodyBytes = PUBLIC_CATALOG_ARCHIVE_METADATA_BODY_LIMIT_BYTES
            )?.body?.let { body ->
                runCatching { parseJson<InternetArchiveMetadataResponse>(body) }.getOrNull()
            } ?: continue
            val media = InternetArchiveFallbackParser.playbackMedia(
                request = request,
                candidate = candidate,
                response = metadata
            ) ?: continue
            if (resolver.resolveInline(media.mediaUrl, media.itemUrl)) return true
        }
    }
    return resolver.loaded
}

private suspend fun getPublicCatalogResource(
    resolver: LinkResolutionSession,
    safeHttp: ProviderHttpSafetyClient,
    url: String,
    normalizer: ProviderUrlNormalizer,
    headers: Map<String, String>,
    referer: String? = null,
    maxBodyBytes: Int
): ProviderHttpResult? = retryPublicCatalogResource(
    attempts = PUBLIC_CATALOG_HTTP_ATTEMPTS
) {
    resolver.withinBudget(PUBLIC_CATALOG_CANDIDATE_TIMEOUT_MS) {
        safeHttp.get(
            url = url,
            normalizer = normalizer,
            headers = headers,
            referer = referer,
            maxBodyBytes = maxBodyBytes,
            timeoutSeconds = PUBLIC_CATALOG_HTTP_TIMEOUT_SECONDS
        )
    }?.takeIf { result ->
        result.code in 200..299 &&
            !ProviderHtmlParser.isNonContentPage(result.body)
    }
}

internal suspend fun <T> retryPublicCatalogResource(
    attempts: Int,
    request: suspend (attempt: Int) -> T?
): T? {
    require(attempts in 1..3)
    repeat(attempts) { attempt ->
        val result = try {
            request(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (result != null) return result
    }
    return null
}

private const val BSTATION_ORIGIN = "https://www.bilibili.tv/"
private const val PUBLIC_CATALOG_SESSION_TIMEOUT_MS = 35_000L
private const val PUBLIC_CATALOG_CANDIDATE_TIMEOUT_MS = 20_000L
private const val PUBLIC_CATALOG_HTTP_TIMEOUT_SECONDS = 20L
private const val PUBLIC_CATALOG_HTTP_ATTEMPTS = 2
private const val PUBLIC_CATALOG_MEDIA_PROBE_ATTEMPTS = 2
private const val PUBLIC_CATALOG_BSTATION_SEARCH_BODY_LIMIT_BYTES = 2_000_000
private const val PUBLIC_CATALOG_BSTATION_PLAY_BODY_LIMIT_BYTES = 2_000_000
private const val PUBLIC_CATALOG_BSTATION_MAX_RESULTS = 3
private const val PUBLIC_CATALOG_ARCHIVE_SEARCH_BODY_LIMIT_BYTES = 1_000_000
private const val PUBLIC_CATALOG_ARCHIVE_METADATA_BODY_LIMIT_BYTES = 2_000_000
private const val PUBLIC_CATALOG_ARCHIVE_MAX_RESULTS = 4
private const val PUBLIC_CATALOG_MAX_REQUESTS = 4
