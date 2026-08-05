package com.example

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayarKacaSearchPolicyTest {
    @Test
    fun `all query words must be present in a relevant title`() {
        assertTrue(
            LayarKacaSearchPolicy.matches(
                query = "Toy Boy",
                candidate = "Toy Boy (2019)"
            )
        )
    }

    @Test
    fun `a shared prefix does not make an unrelated title relevant`() {
        assertFalse(
            LayarKacaSearchPolicy.matches(
                query = "Toy Boy",
                candidate = "Toy Story of Terror"
            )
        )
    }

    @Test
    fun `matching is case insensitive and punctuation tolerant`() {
        assertTrue(
            LayarKacaSearchPolicy.matches(
                query = "Spider-Man",
                candidate = "SPIDER MAN: Across the Spider-Verse (2023)"
            )
        )
    }
}
