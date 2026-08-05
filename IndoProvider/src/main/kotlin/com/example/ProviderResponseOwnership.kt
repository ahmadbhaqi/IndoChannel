package com.example

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse

/** Keeps delegated catalog results owned by the wrapper provider. */
internal fun SearchResponse.withProviderOwner(owner: String): SearchResponse = when (this) {
    is AnimeSearchResponse -> copy(apiName = owner)
    is LiveSearchResponse -> copy(apiName = owner)
    is MovieSearchResponse -> copy(apiName = owner)
    is TorrentSearchResponse -> copy(apiName = owner)
    is TvSeriesSearchResponse -> copy(apiName = owner)
    else -> this
}

internal fun LoadResponse.withProviderOwner(owner: String): LoadResponse {
    apiName = owner
    return this
}
