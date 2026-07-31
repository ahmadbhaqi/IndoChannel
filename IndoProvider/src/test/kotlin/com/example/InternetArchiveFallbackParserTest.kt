package com.example

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InternetArchiveFallbackParserTest {
    @Test
    fun `search response keeps only safe movie coordinates and exact identity`() {
        val response = parseJson<InternetArchiveSearchResponse>(
            """
            {
              "response": {
                "numFound": 3,
                "docs": [
                  {
                    "identifier": "GENESIS_Paradise_Lost",
                    "title": "Genesis: Paradise Lost (2017)",
                    "mediatype": "movies",
                    "year": 2017
                  },
                  {
                    "identifier": "../unsafe",
                    "title": "Genesis: Paradise Lost (2017)",
                    "mediatype": "movies",
                    "year": 2017
                  },
                  {
                    "identifier": "genesis-study-guide",
                    "title": "Genesis: Paradise Lost Study Guide",
                    "mediatype": "texts",
                    "year": 2017
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val request = NomatFallbackRequest("Genesis Paradise Lost", 2017)

        val candidates = InternetArchiveFallbackParser.searchCandidates(response)

        assertEquals(1, candidates.size)
        assertTrue(InternetArchiveFallbackParser.isExactCandidate(request, candidates.single()))
        assertFalse(
            InternetArchiveFallbackParser.isExactCandidate(
                NomatFallbackRequest("Paradise Lost", 2017),
                candidates.single()
            )
        )
    }

    @Test
    fun `metadata selects a public full length mp4 from the exact item`() {
        val response = parseJson<InternetArchiveMetadataResponse>(
            """
            {
              "is_dark": false,
              "metadata": {
                "identifier": "GENESIS_Paradise_Lost",
                "title": "Genesis: Paradise Lost (2017)",
                "mediatype": "movies",
                "date": "2017"
              },
              "files": [
                {
                  "name": "Genesis trailer.mp4",
                  "format": "MPEG4",
                  "size": "12000000",
                  "length": "120.5",
                  "source": "original"
                },
                {
                  "name": "Genesis.Paradise.Lost.2017.1080p.mp4",
                  "format": "MPEG4",
                  "size": "2089559585",
                  "length": "6574.07",
                  "source": "original",
                  "private": "false"
                }
              ]
            }
            """.trimIndent()
        )
        val request = NomatFallbackRequest("Genesis Paradise Lost", 2017)
        val candidate = InternetArchiveSearchCandidate(
            identifier = "GENESIS_Paradise_Lost",
            title = "Genesis: Paradise Lost (2017)",
            year = 2017
        )

        val media = assertNotNull(
            InternetArchiveFallbackParser.playbackMedia(request, candidate, response)
        )

        assertEquals(6_574, media.durationSeconds)
        assertEquals(
            "https://archive.org/download/GENESIS_Paradise_Lost/" +
                "Genesis.Paradise.Lost.2017.1080p.mp4",
            media.mediaUrl
        )
        assertEquals(
            "https://archive.org/details/GENESIS_Paradise_Lost",
            media.itemUrl
        )
    }

    @Test
    fun `metadata rejects mismatched private and unsafe media`() {
        val request = NomatFallbackRequest("Genesis Paradise Lost", 2017)
        val candidate = InternetArchiveSearchCandidate(
            identifier = "GENESIS_Paradise_Lost",
            title = "Genesis: Paradise Lost (2017)",
            year = 2017
        )
        val wrongTitle = parseJson<InternetArchiveMetadataResponse>(
            """
            {
              "metadata": {
                "identifier": "GENESIS_Paradise_Lost",
                "title": "Paradise Lost",
                "mediatype": "movies",
                "year": 2017
              },
              "files": [{
                "name": "movie.mp4",
                "format": "MPEG4",
                "size": "100000000",
                "length": "5400"
              }]
            }
            """.trimIndent()
        )
        val privateFile = parseJson<InternetArchiveMetadataResponse>(
            """
            {
              "metadata": {
                "identifier": "GENESIS_Paradise_Lost",
                "title": "Genesis: Paradise Lost",
                "mediatype": "movies",
                "year": 2017
              },
              "files": [{
                "name": "../movie.mp4",
                "format": "MPEG4",
                "size": "100000000",
                "length": "5400",
                "private": true
              }]
            }
            """.trimIndent()
        )

        assertNull(InternetArchiveFallbackParser.playbackMedia(request, candidate, wrongTitle))
        assertNull(InternetArchiveFallbackParser.playbackMedia(request, candidate, privateFile))
    }

    @Test
    fun `network normalizer allows only bounded archive api coordinates`() {
        val searchUrl = assertNotNull(
            InternetArchiveFallbackParser.searchUrl(
                NomatFallbackRequest("Genesis Paradise Lost", 2017)
            )
        )

        assertTrue(searchUrl.startsWith("https://archive.org/advancedsearch.php?"))
        assertEquals(searchUrl, InternetArchiveFallbackParser.networkUrl(searchUrl))
        assertEquals(
            "https://archive.org/metadata/GENESIS_Paradise_Lost",
            InternetArchiveFallbackParser.metadataUrl("GENESIS_Paradise_Lost")
        )
        assertNull(InternetArchiveFallbackParser.metadataUrl("../private"))
        assertNull(
            InternetArchiveFallbackParser.networkUrl(
                "https://archive.org.evil.example/metadata/GENESIS_Paradise_Lost"
            )
        )
        assertNull(
            InternetArchiveFallbackParser.networkUrl(
                "https://archive.org/download/GENESIS_Paradise_Lost/movie.mp4"
            )
        )
    }
}
