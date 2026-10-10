package com.example

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

class OploverzCurrentSiteParserTest {
    @Test
    fun `current movie cards keep the movie route`() {
        val document = Jsoup.parse(
            """<a href="/movie/digimon"><img alt="Digimon" src="https://backapi.oploverz.ac/poster.jpg"></a>""",
            "https://oploverz.site/"
        )
        assertEquals(
            listOf("https://oploverz.site/movie/digimon"),
            OploverzCurrentSiteParser.catalogItems(document, "https://oploverz.site", true).map { it.url }
        )
    }

    @Test
    fun `movie payload converts its okru id into the advertised player`() {
        val html = """
            <script>window.__payload=[{type:"data",data:{series:{title:"Digimon"},
              episodes:[{streamUrl:[{source:"okru",url:"3739551795799"}]}],
              related:[{streamUrl:[{source:"sd",url:"https://other.example/player"}]}]
            }}]</script>
        """.trimIndent()
        assertEquals(
            listOf("https://ok.ru/videoembed/3739551795799"),
            OploverzCurrentSiteParser.movieStreams(html)
        )
    }

    @Test
    fun `current detail genres are retained for content filtering`() {
        val html = """<script>window.__payload=[{type:"data",data:{series:{title:"Example",
          genres:[{id:90,name:"Action",slug:"action"},{id:91,name:"Ecchi",slug:"ecchi"}]}}}]</script>"""
        assertEquals(listOf("Action", "Ecchi"), OploverzCurrentSiteParser.genres(html))
    }

    @Test
    fun `current series cards and episode links use their own series urls`() {
        val home = Jsoup.parse(
            """<a href="/series/black-clover"><img alt="Black Clover" """ +
                """src="https://backapi.oploverz.ac/uploads/posters/black.jpg"></a>""",
            "https://oploverz.site/"
        )
        assertEquals(
            listOf("https://oploverz.site/series/black-clover"),
            OploverzCurrentSiteParser.catalogItems(home, "https://oploverz.site", true).map { it.url }
        )
        val detail = Jsoup.parse(
            """<a href="/series/black-clover/episode/2">Episode 2</a>""" +
                """<a href="/series/other/episode/1">Other</a>""",
            "https://oploverz.site/series/black-clover"
        )
        assertEquals(
            listOf("https://oploverz.site/series/black-clover/episode/2"),
            OploverzCurrentSiteParser.episodeLinks(detail, "https://oploverz.site/series/black-clover")
                .map { it.first }
        )
    }

    @Test
    fun `episode stream parser selects current episode payload`() {
        val html = """
            <script>window.__payload=[
              {type:"data",data:{episode:{episodeNumber:"7",downloadUrl:[],
                streamUrl:[{source:"sd",url:"https://www.blogger.com/video.g?token=current"}]}}},
              {type:"data",data:{episode:{episodeNumber:"8",downloadUrl:[],
                streamUrl:[{source:"sd",url:"https://www.blogger.com/video.g?token=other"}]}}}
            ]</script>
        """.trimIndent()
        assertEquals(
            listOf("https://www.blogger.com/video.g?token=current"),
            OploverzCurrentSiteParser.episodeStreams(html)
        )
    }
}
