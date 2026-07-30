package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class FilmapikCatalogPaginationTest {
    private val baseUrl = "https://filmapik.college"

    @Test
    fun `Beranda uses root for page one and WordPress pagination afterward`() {
        assertEquals(
            "$baseUrl/",
            FilmapikCatalogParser.pageUrl(baseUrl, route = "", page = 1)
        )
        assertEquals(
            "$baseUrl/page/2",
            FilmapikCatalogParser.pageUrl(baseUrl, route = "", page = 2)
        )
    }

    @Test
    fun `catalog page numbers are coerced to the first page safely`() {
        assertEquals(
            "$baseUrl/",
            FilmapikCatalogParser.pageUrl(baseUrl, route = "", page = 0)
        )
        assertEquals(
            "$baseUrl/category/box-office/page/1",
            FilmapikCatalogParser.pageUrl(
                baseUrl,
                route = "category/box-office/page/%d",
                page = Int.MIN_VALUE
            )
        )
    }

    @Test
    fun `catalog filter sees sexual taxonomy beside the Filmapik title link`() {
        val document = Jsoup.parse(
            """
            <article class="movie-item">
              <a href="/nonton-film-booking-2026-subtitle-indonesia">
                <img alt="Nonton Film Booking (2026) Subtitle Indonesia">
              </a>
              <a href="/category/vivamax/" rel="category tag">Vivamax</a>
            </article>
            """
        )
        val titleLink = document.selectFirst("a[href*='/nonton-film-']")!!

        assertTrue(
            FilmapikCatalogParser.isBlockedCatalogCard(
                titleLink,
                "Booking (2026)",
                "https://filmapik.college/nonton-film-booking-2026-subtitle-indonesia"
            )
        )
    }
}
