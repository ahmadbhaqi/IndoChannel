package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class FreeonPlayerParserTest {
    @Test
    fun `unicode packer exposes freeon api without recursive regex`() {
        val packed = """
            eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(c/a))+String.fromCharCode(c%a+161)};return p}('¡ ¢="//plyr.freeon.site/api/?signed=1";',95,2,'var|url'.split('|')))
        """.trimIndent()

        assertEquals(
            listOf("https://plyr.freeon.site/api/?signed=1"),
            FreeonPlayerParser.apiUrls(packed, "https://plyr.freeon.site/embed/id")
        )
    }

    @Test
    fun `unicode unpacker does not reprocess tokens introduced by dictionary values`() {
        val token0 = 161.toChar()
        val token1 = 162.toChar()
        val packed = """
            eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(c/a))+String.fromCharCode(c%a+161)};return p}('$token0 $token1',95,2,'var|$token0'.split('|')))
        """.trimIndent()

        assertEquals("var $token0", FreeonPlayerParser.unpackUnicodePacker(packed))
    }

    @Test
    fun `freeon response retains extensionless video source`() {
        val json = """
            {
              "status":"ok",
              "sources":[
                {"file":"https://web.opendrive.com/api/v1/download/file.json/id?inline=1","type":"video/mp4","label":"Original"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                FreeonMediaSource(
                    "Original",
                    "https://web.opendrive.com/api/v1/download/file.json/id?inline=1",
                    "video/mp4"
                )
            ),
            FreeonPlayerParser.sources(json)
        )
    }

    @Test
    fun `filmapik player resolves relative efek streams`() {
        val html = """
            <script>
              const sources = [
                {'label':'360p','type':'video/mp4','file':'/stream/360/current/__001'},
                {'label':'720p','type':'video/mp4','file':'/stream/720/current/__001'}
              ];
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                FilmapikMediaSource("360p", "https://v2.efek.stream/stream/360/current/__001", 360),
                FilmapikMediaSource("720p", "https://v2.efek.stream/stream/720/current/__001", 720)
            ),
            FilmapikPlayerParser.sources(html, "https://v2.efek.stream/v/current")
        )
    }
}
