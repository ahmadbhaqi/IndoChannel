package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class PusatfilmPosterUrlTest {
    @Test
    fun `wordpress TMDB poster is moved to a DNS independent image host`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/lcUufwF6o3n6VF6PMLt0uUZLTOM.jpg",
            PusatfilmPosterUrl.normalize(
                "https://v4.pusatfilm21info.com/wp-content/uploads/2026/08/" +
                    "lcUufwF6o3n6VF6PMLt0uUZLTOM-300x450.jpg"
            )
        )
    }

    @Test
    fun `legacy provider poster uses the same image normalization`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/ztadKzIIR0ERYqpHteaPFtk7inP.jpg",
            PusatfilmPosterUrl.normalize(
                "https://v3.pusatfilm21info.com/wp-content/uploads/2026/08/" +
                    "ztadKzIIR0ERYqpHteaPFtk7inP.jpg"
            )
        )
    }

    @Test
    fun `external poster URL is preserved`() {
        val external = "https://blogger.googleusercontent.com/img/current-poster.jpg"

        assertEquals(external, PusatfilmPosterUrl.normalize(external))
    }

    @Test
    fun `long ordinary wordpress filename is not mistaken for a TMDB hash`() {
        val ordinary =
            "https://v4.pusatfilm21info.com/wp-content/uploads/2026/08/" +
                "official-current-movie-poster-300x450.jpg"

        assertEquals(ordinary, PusatfilmPosterUrl.normalize(ordinary))
    }
}
