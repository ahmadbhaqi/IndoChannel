package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
