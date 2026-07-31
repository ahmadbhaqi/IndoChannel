package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimeCrossProviderFallbackTest {
    @Test
    fun `fallback request preserves season while extracting episode`() {
        val request = AnimeCrossProviderFallback.request(
            pageTitle = "Nonton Grand Blue Season 3 Episode 1 Sub Indo",
            pageUrl = "https://zoronime.live/grand-blue-season-3-episode-1-sub-indo/"
        )

        assertEquals("Grand Blue Season 3", request?.title)
        assertEquals(1, request?.episode)
    }

    @Test
    fun `fallback title matching requires the same season`() {
        assertTrue(
            AnimeCrossProviderFallback.isExactTitle(
                expected = "Grand Blue Season 3",
                candidate = "Grand Blue Season 3"
            )
        )
        assertFalse(
            AnimeCrossProviderFallback.isExactTitle(
                expected = "Grand Blue Season 3",
                candidate = "Grand Blue Season 2"
            )
        )
    }

    @Test
    fun `fallback episode matching does not accept a different number`() {
        assertTrue(AnimeCrossProviderFallback.isEpisodeMatch(1, 1, "Episode 1"))
        assertFalse(AnimeCrossProviderFallback.isEpisodeMatch(1, 2, "Episode 2"))
    }

    @Test
    fun `fallback without an episode coordinate only accepts a single episode detail`() {
        assertTrue(AnimeCrossProviderFallback.canSelectEpisode(null, 1))
        assertFalse(AnimeCrossProviderFallback.canSelectEpisode(null, 2))
        assertTrue(AnimeCrossProviderFallback.canSelectEpisode(7, 12))
    }
}
