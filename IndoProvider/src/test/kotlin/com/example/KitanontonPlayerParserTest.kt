package com.example

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class KitanontonPlayerParserTest {
    @Test
    fun `orders a complete abyss mirror ahead of browser coded players`() {
        val juicy = "https://178.211.139.171/embed/code"
        val unknown = "https://unknown.example/embed/code"
        val abyss = "https://abyssplayer.com/working"

        assertEquals(
            listOf(abyss, unknown, juicy),
            KitanontonPlayerParser.orderPlayerUrls(listOf(juicy, unknown, abyss))
        )
    }

    @Test
    fun `continues through every fallback after an earlier mirror emits`() = runBlocking {
        val visited = mutableListOf<String>()
        val abyss = "https://abyssplayer.com/possibly-expired"
        val unknown = "https://unknown.example/embed/code"
        val juicy = "https://178.211.139.171/embed/code"

        KitanontonPlayerParser.resolveAll(listOf(juicy, unknown, abyss)) { url ->
            visited += url
        }

        assertEquals(listOf(abyss, unknown, juicy), visited)
    }

    @Test
    fun `continues from play route to detail route after an earlier link emits`() = runBlocking {
        val visited = mutableListOf<String>()
        val detail = "https://kitanonton.example/movie"

        KitanontonPlayerParser.resolvePages(listOf("$detail/play", detail)) { page ->
            visited += page
        }

        assertEquals(listOf("$detail/play", detail), visited)
    }
}
