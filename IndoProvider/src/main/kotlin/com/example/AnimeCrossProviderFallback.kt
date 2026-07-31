package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class AnimeFallbackRequest(
    val title: String,
    val episode: Int?
)

internal data class AnimeFallbackResult(
    val subtitles: List<SubtitleFile>,
    val links: List<ExtractorLink>
)

/**
 * Resolves a failed anime episode through another registered Indonesian
 * catalog. Search, detail and episode coordinates must all agree before any
 * fallback callback is exposed to the caller.
 */
internal object AnimeCrossProviderFallback {
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_SEARCH_RESULTS = 8
    private const val DEFAULT_TIMEOUT_MS = 55_000L
    private val episodeRegex =
        Regex("""(?i)\b(?:episode|eps?\.?)\s*[-:]?\s*(\d+)\b""")
    private val urlEpisodeRegex =
        Regex("""(?i)(?:^|[-_/])(?:episode|eps?)[-_]?(\d+)(?:[-_/]|$)""")
    private val subtitleSuffixRegex =
        Regex("""(?i)\b(?:subtitle\s+indonesia|sub\s*indo)\b.*$""")
    private val nontonPrefixRegex = Regex("""(?i)^nonton(?:\s+anime)?\s+""")

    fun request(pageTitle: String?, pageUrl: String): AnimeFallbackRequest? {
        val urlText = runCatching {
            URI(pageUrl).path.orEmpty()
                .trim('/')
                .substringAfterLast('/')
                .replace('-', ' ')
                .replace('_', ' ')
        }.getOrDefault("")
        val rawTitle = pageTitle
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: urlText.takeIf(String::isNotBlank)
            ?: return null
        val episode = episodeRegex.find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: runCatching {
                urlEpisodeRegex.find(URI(pageUrl).path.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }.getOrNull()
        val title = cleanTitle(rawTitle)?.take(MAX_TITLE_LENGTH) ?: return null
        return AnimeFallbackRequest(title, episode)
    }

    fun isExactTitle(expected: String, candidate: String): Boolean {
        val expectedKey = titleKey(expected)
        return expectedKey.isNotEmpty() && expectedKey == titleKey(candidate)
    }

    fun isEpisodeMatch(target: Int?, episode: Int?, label: String?): Boolean {
        if (target == null) return true
        if (episode == target) return true
        return episodeRegex.find(label.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull() == target
    }

    fun canSelectEpisode(target: Int?, episodeCount: Int): Boolean =
        episodeCount == 1 || target != null

    suspend fun resolve(
        request: AnimeFallbackRequest,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        providerFactories: List<() -> MainAPI> = listOf(
            { AnimasuProvider() },
            { KuramanimeProvider() }
        ),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        if (providerFactories.isEmpty()) return false
        val result = withTimeoutOrNull(timeoutMs.coerceIn(1L, 120_000L)) {
            raceProviders(request, isCasting, providerFactories)
        } ?: return false
        result.subtitles.forEach(subtitleCallback)
        result.links.forEach(callback)
        return result.links.isNotEmpty()
    }

    private suspend fun raceProviders(
        request: AnimeFallbackRequest,
        isCasting: Boolean,
        providerFactories: List<() -> MainAPI>
    ): AnimeFallbackResult? = supervisorScope {
        val winner = CompletableDeferred<AnimeFallbackResult?>()
        val jobs = providerFactories.take(4).map { factory ->
            launch {
                val result = resolveProvider(factory, request, isCasting)
                if (result != null) winner.complete(result)
            }
        }
        val completion = launch {
            jobs.joinAll()
            winner.complete(null)
        }
        val result = winner.await()
        if (result != null) jobs.forEach { it.cancel() }
        jobs.joinAll()
        completion.cancel()
        result
    }

    private suspend fun resolveProvider(
        factory: () -> MainAPI,
        request: AnimeFallbackRequest,
        isCasting: Boolean
    ): AnimeFallbackResult? {
        return try {
            val provider = factory()
            val candidates = provider.search(request.title).orEmpty()
                .asSequence()
                .filter { candidate -> isExactTitle(request.title, candidate.name) }
                .take(MAX_SEARCH_RESULTS)
                .toList()
            for (candidate in candidates) {
                val detail = provider.load(candidate.url) as? AnimeLoadResponse
                    ?: continue
                if (!isExactTitle(request.title, detail.name)) continue
                val episodes = detail.episodes.values.flatten().distinctBy { it.data }
                if (!canSelectEpisode(request.episode, episodes.size)) continue
                val episode = episodes
                    .firstOrNull { item ->
                        isEpisodeMatch(request.episode, item.episode, item.name)
                    }
                    ?: continue
                val subtitles = mutableListOf<SubtitleFile>()
                val links = mutableListOf<ExtractorLink>()
                val loaded = provider.loadLinks(
                    episode.data,
                    isCasting,
                    subtitles::add,
                    links::add
                )
                if (loaded && links.isNotEmpty()) {
                    return AnimeFallbackResult(
                        subtitles = subtitles.toList(),
                        links = links.toList()
                    )
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun cleanTitle(raw: String): String? {
        return MovieMetadataParser.title(raw)
            ?.replace(nontonPrefixRegex, " ")
            ?.replace(episodeRegex, " ")
            ?.replace(subtitleSuffixRegex, " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim(' ', '-', ':', '|')
            ?.takeIf(String::isNotBlank)
    }

    private fun titleKey(raw: String): String {
        val value = cleanTitle(raw).orEmpty()
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "")
    }
}
