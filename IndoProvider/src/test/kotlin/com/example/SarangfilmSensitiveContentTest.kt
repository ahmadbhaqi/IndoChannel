package com.example

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SarangfilmSensitiveContentTest {
    @Test
    fun `catalog policy reads category metadata from the current card`() {
        val card = Jsoup.parse(
            """
            <article class="item-infinite">
                <h2><a href="/trending/ordinary-drama/">Ordinary Drama</a></h2>
                <a href="/category/drama/" rel="category tag">Drama</a>
                <a href="/category/film-semi/" rel="category tag">Film Semi</a>
            </article>
            """.trimIndent(),
            "https://sarangfilm.diy/"
        ).selectFirst("article")!!

        assertTrue(
            SarangfilmContentPolicy.isBlockedCatalogCard(
                card,
                "Ordinary Drama",
                "https://sarangfilm.diy/trending/ordinary-drama/"
            )
        )
    }

    @Test
    fun `detail policy scopes taxonomy to movie metadata instead of the site menu`() {
        val normal = Jsoup.parse(
            """
            <nav><a href="/category/film-semi/">Film Semi</a></nav>
            <main>
                <div class="gmr-moviedata">
                    <strong>Genre:</strong>
                    <a href="/category/drama/" rel="category tag">Drama</a>
                </div>
            </main>
            """.trimIndent(),
            "https://sarangfilm.diy/"
        )
        val sensitive = Jsoup.parse(
            """
            <main>
                <div class="gmr-moviedata">
                    <strong>Genre:</strong>
                    <a href="/category/drama/" rel="category tag">Drama</a>
                    <a href="/category/film-semi/" rel="category tag">Film Semi</a>
                </div>
            </main>
            """.trimIndent(),
            "https://sarangfilm.diy/"
        )

        assertFalse(
            SarangfilmContentPolicy.isBlockedDetail(
                normal,
                "Ordinary Drama",
                "https://sarangfilm.diy/trending/ordinary-drama/"
            )
        )
        assertTrue(
            SarangfilmContentPolicy.isBlockedDetail(
                sensitive,
                "Ordinary Drama",
                "https://sarangfilm.diy/trending/ordinary-drama/"
            )
        )
    }
}
