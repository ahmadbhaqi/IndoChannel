package com.example

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveContentPolicyTest {
    @Test
    fun `blocks strong adult category metadata`() {
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Ordinary Drama",
                url = "https://provider.example/ordinary-drama/",
                categories = listOf("Drama", "/category/film-semi/semi-filipina/")
            )
        )
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Ordinary Animation",
                url = "https://provider.example/ordinary-animation/",
                categories = listOf("Anime", "genre/hentai")
            )
        )
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Ordinary Animation",
                url = "https://provider.example/ordinary-animation/",
                categories = listOf("Adult")
            )
        )
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Ordinary Animation",
                url = "https://provider.example/ordinary-animation/",
                categories = listOf("/genre/adult/")
            )
        )
        listOf(
            "Hentai Anime",
            "Erotic Thriller",
            "Ecchi Romance",
            "/genre/hentai-anime/",
            "NSFW"
        ).forEach { category ->
            assertTrue(
                SensitiveContentPolicy.isBlocked(
                    title = "Neutral Looking Title",
                    url = "https://provider.example/neutral-looking-title/",
                    categories = listOf(category)
                ),
                category
            )
        }
    }

    @Test
    fun `blocks unclassified catalog entries with explicit Indonesian slang`() {
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "OFES-046 - Adik Jadi T*** Abis Ditinggal Bikin Sange",
                url = "https://provider.example/trending/ofes-046-adik-jadi-tobrut-bikin-sange/",
                categories = listOf("Trending")
            )
        )
    }

    @Test
    fun `does not block ambiguous words in normal titles`() {
        listOf(
            "Sex Education Season 4",
            "The Hentai Prince and the Stony Cat",
            "xXx: Return of Xander Cage",
            "Semi-Pro",
            "Adult Beginners",
            "Sanger Things"
        ).forEach { title ->
            assertFalse(
                SensitiveContentPolicy.isBlocked(
                    title = title,
                    url = "https://provider.example/${title.lowercase().replace(' ', '-')}/",
                    categories = listOf("Comedy", "Drama")
                ),
                title
            )
        }
        assertFalse(
            SensitiveContentPolicy.isBlocked(
                title = "Ordinary Drama",
                url = "https://provider.example/ordinary-drama/",
                categories = listOf("Drama", "Vivamax")
            )
        )
        assertFalse(
            SensitiveContentPolicy.isBlocked(
                title = "Solo Leveling",
                url = "https://provider.example/solo-leveling/",
                categories = listOf("Action", "Adult Cast")
            )
        )
        assertFalse(
            SensitiveContentPolicy.isBlocked(
                title = "Solo Leveling",
                url = "https://provider.example/solo-leveling/",
                categories = listOf("/genre/adult-cast/")
            )
        )
        assertFalse(
            SensitiveContentPolicy.isBlocked(
                title = "Family Animation",
                url = "https://provider.example/family-animation/",
                categories = listOf("SFW")
            )
        )
    }

    @Test
    fun `uses token boundaries instead of unsafe substrings`() {
        assertFalse(
            SensitiveContentPolicy.isBlocked(
                title = "Passenger",
                url = "https://provider.example/passenger/",
                categories = listOf("Drama")
            )
        )
    }
}
