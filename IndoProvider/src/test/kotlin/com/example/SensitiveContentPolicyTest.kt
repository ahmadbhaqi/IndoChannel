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
    fun `allows ecchi taxonomy unless an explicit marker is also present`() {
        listOf(
            "Ecchi",
            "Ecchi Romance",
            "/genre/ecchi/",
            "Anime, Ecchi, Comedy",
            "Ecchi 18+",
            "18+",
            "NC-17"
        ).forEach { category ->
            assertFalse(
                SensitiveContentPolicy.isBlocked(
                    title = "Ordinary Anime",
                    url = "https://provider.example/ordinary-anime/",
                    categories = listOf(category)
                ),
                category
            )
        }

        listOf(
            "Ecchi Hentai",
            "Ecchi NSFW",
            "Ecchi Adult",
            "18+ Hentai",
            "Japanese AV",
            "/genre/jav/",
            "Porn"
        ).forEach { category ->
            assertTrue(
                SensitiveContentPolicy.isBlocked(
                    title = "Ordinary Anime",
                    url = "https://provider.example/ordinary-anime/",
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
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Hentai Academy Episode 1",
                url = "https://provider.example/hentai-academy-episode-1/",
                categories = emptyList()
            )
        )
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "The Hentai Prince Porn Parody",
                url = "https://provider.example/the-hentai-prince-porn-parody/",
                categories = listOf("Comedy")
            )
        )
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "I Was Absorbed In A Family Restaurant Affair With A " +
                    "Married Woman With Huge Breasts I Met At A Part time Job (2021)",
                url = "https://provider.example/play/nonton-i-was-absorbed-in-a-family-" +
                    "restaurant-affair-with-a-married-woman-with-huge-breasts-2021/",
                categories = emptyList()
            )
        )
        listOf(
            "NSFW Special",
            "Erotic Private Story",
            "Film Semi Jepang"
        ).forEach { title ->
            assertTrue(
                SensitiveContentPolicy.isBlocked(
                    title = title,
                    url = "https://provider.example/${title.lowercase().replace(' ', '-')}/",
                    categories = emptyList()
                ),
                title
            )
        }
    }

    @Test
    fun `blocks sexual catalog signals without treating age ratings as sexual`() {
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Sex in Public (2026)",
                url = "https://provider.example/sex-in-public-2026/",
                categories = listOf("Documentary", "Movie")
            )
        )
        listOf(
            "Vivamax",
            "/category/vivamax/",
            "Sexy",
            "Sexual",
            "Erotic"
        ).forEach { category ->
            assertTrue(
                SensitiveContentPolicy.isBlocked(
                    title = "Booking (2026)",
                    url = "https://provider.example/booking-2026/",
                    categories = listOf("Drama", category)
                ),
                category
            )
        }
        listOf("18+", "NC-17", "Ecchi", "Ecchi 18+").forEach { category ->
            assertFalse(
                SensitiveContentPolicy.isBlocked(
                    title = "Violent Night",
                    url = "https://provider.example/violent-night/",
                    categories = listOf("Action", category)
                ),
                category
            )
        }
    }

    @Test
    fun `does not block ambiguous words in normal titles`() {
        listOf(
            "Sex Education Season 4",
            "The Hentai Prince and the Stony Cat",
            "xXx: Return of Xander Cage",
            "Semi-Pro",
            "Adult Beginners",
            "Asia M Championship",
            "Kelas Bintang School",
            "The Pink Film Camera",
            "Sanger Things",
            "Violent Night 18+"
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
        listOf(
            "Young Adult",
            "Adult Animation",
            "Adult Comedy",
            "Adult Swim"
        ).forEach { category ->
            assertFalse(
                SensitiveContentPolicy.isBlocked(
                    title = "Ordinary Animation",
                    url = "https://provider.example/ordinary-animation/",
                    categories = listOf(category)
                ),
                category
            )
        }
        assertTrue(
            SensitiveContentPolicy.isBlocked(
                title = "Ordinary Animation",
                url = "https://provider.example/ordinary-animation/",
                categories = listOf("Young Adult / Adult")
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
