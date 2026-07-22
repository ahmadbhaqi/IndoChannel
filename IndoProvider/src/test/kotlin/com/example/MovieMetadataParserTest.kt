package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jsoup.Jsoup

class MovieMetadataParserTest {
    @Test
    fun `direct synopsis paragraph is preferred over metadata`() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta property="og:description" content="A shorter metadata summary.">
            </head><body>
              <div class="synopsis"><p>The direct synopsis explains the actual story in full.</p></div>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            "The direct synopsis explains the actual story in full.",
            MovieMetadataParser.synopsis(document)
        )
    }

    @Test
    fun `review body is preferred over KitaNonton SEO metadata`() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta property="og:description"
                    content="Nonton Film Sub Indo dan Download Streaming Film Movie Indonesia Thailand India Jepang Online KITANONTON">
            </head><body>
              <div itemprop="reviewBody">
                <p><strong>ONE PIECE HEROINES (2026)</strong>: A sailor follows a mysterious map across a dangerous sea.</p>
              </div>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            "A sailor follows a mysterious map across a dangerous sea.",
            MovieMetadataParser.synopsis(
                document,
                directSelectors = listOf(
                    "[itemprop=reviewBody] p",
                    ".sinopsis-indo",
                    "[itemprop=description] p"
                )
            )
        )
    }

    @Test
    fun `SEO boilerplate is rejected and tagline is used as fallback`() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta property="og:description"
                    content="LK21 LAYARKACA21 REBAHIN BIOSKOPKEREN Nonton Streamin Gratis Sub Indo">
            </head><body>
              <div itemprop="description">
                <p>Website streaming film terlengkap dan terbaru dengan kualitas terbaik tanpa harus registrasi.</p>
              </div>
              <div class="tagline">One night. One last chance.</div>
            </body></html>
            """.trimIndent()
        )

        assertEquals("One night. One last chance.", MovieMetadataParser.synopsis(document))
    }

    @Test
    fun `SEO boilerplate without fallback produces no synopsis`() {
        val document = Jsoup.parse(
            """
            <meta name="description"
                  content="Nonton Film Sub Indo dan Download Streaming Film Movie Indonesia Thailand India Jepang Online KITANONTON">
            <div itemprop="description">
              Website streaming film terlengkap dan terbaru dengan kualitas terbaik.
            </div>
            """.trimIndent()
        )

        assertNull(MovieMetadataParser.synopsis(document))
    }

    @Test
    fun `Indonesian coming soon placeholders are not exposed as a synopsis`() {
        listOf("Segera Hadir", "Segara Hadir").forEach { placeholder ->
            val document = Jsoup.parse(
                """
                <div class="entry-content"><p>$placeholder</p></div>
                <meta name="description" content="$placeholder">
                """.trimIndent()
            )

            assertNull(MovieMetadataParser.synopsis(document), placeholder)
        }
    }

    @Test
    fun `common Windows 1252 UTF 8 mojibake is repaired`() {
        val mojibake = "Drama \u00E2\u20AC\u201C kisah cinta \u00C3\u00A9lite"

        assertEquals(
            "Drama \u2013 kisah cinta \u00E9lite",
            MovieMetadataParser.repairMojibake(mojibake)
        )
        assertEquals(
            "Summer\u2019s Last Resort",
            MovieMetadataParser.repairMojibake("Summer\u00E2\u20AC\u2122s Last Resort")
        )
        assertEquals(
            "Ry\u014Dz\u014D",
            MovieMetadataParser.repairMojibake("Ry\u00C5\u008Dz\u00C5\u008D")
        )
    }

    @Test
    fun `title removes Nonton Film and LK21 wrappers`() {
        assertEquals(
            "Dune: Part Two (2024)",
            MovieMetadataParser.title(
                "LK21 - Nonton Film Dune: Part Two (2024) Subtitle Indonesia - LK21"
            )
        )
        assertEquals(
            "Dune: Part Two (2024)",
            MovieMetadataParser.title(
                "LK21 \u2013 Nonton Film Dune: Part Two (2024) | LayarKaca21"
            )
        )
    }
}
