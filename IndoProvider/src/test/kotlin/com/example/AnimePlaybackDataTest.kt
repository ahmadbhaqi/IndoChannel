package com.example

import com.lagradost.cloudstream3.newEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimePlaybackDataTest {
    @Test
    fun `playback payload preserves taxonomy for policy re-evaluation`() {
        val encoded = AnimePlaybackDataCodec.encode(
            url = "https://anime.example/episode/ordinary-1/",
            title = "Ordinary Episode 1",
            categories = listOf("Drama", "Hentai"),
            detailUrl = "https://anime.example/anime/ordinary/"
        )

        assertEquals(
            AnimePlaybackData(
                url = "https://anime.example/episode/ordinary-1/",
                title = "Ordinary Episode 1",
                categories = listOf("Drama", "Hentai"),
                detailUrl = "https://anime.example/anime/ordinary/"
            ),
            AnimePlaybackDataCodec.decode(encoded)
        )
        assertTrue(AnimePlaybackDataCodec.isBlocked(encoded))
    }

    @Test
    fun `safe playback payload remains playable and round trips reserved characters`() {
        val encoded = AnimePlaybackDataCodec.encode(
            url = "https://anime.example/episode/safe-1/?server=2&lang=id",
            title = "Safe & Sound",
            categories = listOf("Action", "Adult Cast"),
            detailUrl = "https://anime.example/anime/safe/"
        )

        val decoded = AnimePlaybackDataCodec.decode(encoded)
        assertEquals("https://anime.example/episode/safe-1/?server=2&lang=id", decoded?.url)
        assertEquals(listOf("Action", "Adult Cast"), decoded?.categories)
        assertFalse(AnimePlaybackDataCodec.isBlocked(encoded))
    }

    @Test
    fun `legacy raw urls are not mistaken for encoded playback data`() {
        assertNull(
            AnimePlaybackDataCodec.decode(
                "https://anime.example/episode/legacy-1/?server=2&lang=id"
            )
        )
    }

    @Test
    fun `Cloudstream episode keeps encoded playback data without URL fixing`() {
        val encoded = AnimePlaybackDataCodec.encode(
            url = "https://anime.example/episode/safe-1/",
            title = "Safe Episode 1",
            categories = listOf("Action"),
            detailUrl = "https://anime.example/anime/safe/"
        )

        val episode = AnimasuProvider().newEpisode(
            encoded,
            initializer = {},
            fix = false
        )

        assertEquals(encoded, episode.data)
    }

    @Test
    fun `encoder never creates a payload larger than its decoder accepts`() {
        val encoded = AnimePlaybackDataCodec.encode(
            url = "https://anime.example/episode/" + "u".repeat(4_000),
            title = "t".repeat(512),
            categories = (1..32).map { index -> "$index-${"c".repeat(250)}" },
            detailUrl = "https://anime.example/anime/" + "d".repeat(4_000)
        )

        assertTrue(encoded.length <= 16_384)
        assertTrue(AnimePlaybackDataCodec.decode(encoded) != null)
    }
}
