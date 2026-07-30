package com.example

import com.lagradost.cloudstream3.TvType
import java.net.URI

internal object RotatingMovieDetailClassifier {
    private val seriesPath = Regex("(?i)(?:^|/)tv(?:/|$)")

    /**
     * A known series page is usable only after at least one real episode link
     * survives provider-host and episode-label validation.
     */
    fun classify(canonicalUrl: String, validatedEpisodeCount: Int): TvType? {
        require(validatedEpisodeCount >= 0)
        if (validatedEpisodeCount > 0) return TvType.TvSeries

        val path = runCatching { URI(canonicalUrl).path.orEmpty() }.getOrDefault("")
        return if (seriesPath.containsMatchIn(path)) null else TvType.Movie
    }
}
