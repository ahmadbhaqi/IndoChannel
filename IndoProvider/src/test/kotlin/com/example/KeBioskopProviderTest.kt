package com.example

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class KeBioskopProviderTest {
    @Test
    fun `catalog parser returns only movie cards with normalized poster title and year`() {
        val cards = KeBioskopParser.catalogCards(
            """
            <div class="moviefilm"><div class="movief"><a href="/nonton-film-example-2026/"> Example Movie (2026) </a></div><img data-src="/images/example.jpg"></div>
            <aside><div class="moviefilm"><div class="movief"><a href="https://kebioskop21.cfd/sidebar/">Sidebar (2024)</a></div><img src="/sidebar.jpg"></div></aside>
            """.trimIndent(),
            "https://kebioskop21.cfd/category/movie/"
        )

        assertEquals(1, cards.size)
        assertEquals("Example Movie", cards.single().title)
        assertEquals(2026, cards.single().year)
        assertEquals("https://kebioskop21.cfd/nonton-film-example-2026/", cards.single().url)
        assertEquals("https://kebioskop21.cfd/images/example.jpg", cards.single().posterUrl)
    }

    @Test
    fun `detail parser extracts clean movie metadata`() {
        val details = KeBioskopParser.detail(
            """
            <script type="application/ld+json">{"headline":"Peaky Blinders: The Immortal Man (2026)"}</script>
            <div class="filmcontent"><img src="/google-icon.jpg"><h1>Peaky Blinders: The Immortal Man (2026) Sub Indo</h1></div>
            <div class="filmaltiimg"><img src="/poster.jpg"></div>
            <div class="filmicerik"><p>Peaky Blinders: The Immortal Man (2026), Film yang bergenre <a href="/category/movie/Crime/">Crime</a>, <a href="/category/movie/Drama/">Drama</a> ini yang ceritanya tentang A returned gangster faces his past.</p><ul>
              <li>Release: 2026</li><li>Duration: 113 min</li>
            </ul></div>
            <a class="vp-yt-type" href="https://youtu.be/trailer">Trailer</a>
            """.trimIndent(),
            "https://kebioskop21.cfd/nonton-film-peaky-blinders/"
        )

        assertEquals("Peaky Blinders: The Immortal Man", details.title)
        assertEquals("https://kebioskop21.cfd/poster.jpg", details.posterUrl)
        assertEquals(2026, details.year)
        assertEquals("A returned gangster faces his past.", details.synopsis)
        assertEquals(listOf("Crime", "Drama"), details.genres)
        assertEquals(113, details.duration)
        assertEquals("https://youtu.be/trailer", details.trailerUrl)
    }

    @Test
    fun `provider URL normalization preserves local path query fragment and rejects foreign host`() {
        assertEquals(
            "https://kebioskop21.cfd/nonton-film/example/?part=2#player",
            KeBioskopParser.providerUrl("/nonton-film/example/?part=2#player")
        )
        assertEquals(
            "https://kebioskop21.cfd/nonton-film/example/?part=2#player",
            KeBioskopParser.providerUrl("https://kebioskop21.cfd/nonton-film/example/?part=2#player")
        )
        assertNull(KeBioskopParser.providerUrl("https://evil.example/nonton-film/example/"))
        assertNull(KeBioskopParser.providerUrl("//evil.example/nonton-film/example/"))
        assertNull(KeBioskopParser.providerUrl("https://www.kebioskop21.cfd/nonton-film/example/"))
    }

    @Test
    fun `category page URL uses the root category for page one and numbered paths afterward`() {
        assertEquals(
            "https://kebioskop21.cfd/category/movie/",
            KeBioskopParser.categoryPageUrl("category/movie/", 1)
        )
        assertEquals(
            "https://kebioskop21.cfd/category/movie/page/3/",
            KeBioskopParser.categoryPageUrl("category/movie/", 3)
        )
        assertEquals(
            "https://kebioskop21.cfd/category/horor/page/2/",
            KeBioskopParser.categoryPageUrl("category/horor/", 2)
        )
    }

    @Test
    fun `only the exact apidrive intermediary is recognized`() {
        assertTrue(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.kebioskop21.cfd/apidrive.php?id=1"))
        assertTrue(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.kebioskop21.cfd:443/apidrive.php?id=1"))
        assertTrue(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.kebioskop21.pro/apidrive.php?id=1"))
        assertTrue(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.kebioskop21.pro:443/apidrive.php?id=1"))
        assertFalse(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.evil.example/apidrive.php?id=1"))
        assertFalse(KeBioskopPlayerOrchestrator.isIntermediary("https://cdn.kebioskop21.cfd/apidrive.php?id=1"))
        assertFalse(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.kebioskop21.pro/other.php?id=1"))
        assertFalse(KeBioskopPlayerOrchestrator.isIntermediary("https://evil.example/apidrive.php?id=1"))
        assertFalse(KeBioskopPlayerOrchestrator.isIntermediary("https://user@streaming.kebioskop21.pro/apidrive.php?id=1"))
        assertFalse(KeBioskopPlayerOrchestrator.isIntermediary("https://streaming.kebioskop21.pro:8443/apidrive.php?id=1"))
    }

    @Test
    fun `intermediary follows redirected relative form action and resolves relative final iframe`() = runBlocking {
        val detailUrl = "https://kebioskop21.cfd/nonton-film/example/"
        val playerUrl = "https://streaming.kebioskop21.pro/apidrive.php?id=abc"
        val gateUrl = "https://streaming.kebioskop21.pro/apidrive.php?token=gate"
        val formUrl = "https://streaming.kebioskop21.pro/apidrive.php?token=form&id=abc"
        val postUrl = "https://streaming.kebioskop21.pro/posted/result/index.html?token=response"
        val finalUrl = "https://streaming.kebioskop21.pro/posted/embed/movie?v=movie&sub=id"
        val calls = mutableListOf<String>()
        val postRequests = mutableListOf<Triple<String, Map<String, String>, String>>()
        val resolved = mutableListOf<Pair<String, String>>()
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String): KeBioskopHttpResponse {
                    calls += "GET $url referer=$referer"
                    return KeBioskopHttpResponse(
                        "<form method='post' action='?token=form&amp;id=abc'><button name='play' value='play'></button></form>",
                        gateUrl
                    )
                }

                override suspend fun postPlay(
                    url: String,
                    data: Map<String, String>,
                    referer: String
                ): KeBioskopHttpResponse {
                    calls += "POST $url referer=$referer"
                    postRequests += Triple(url, data, referer)
                    return KeBioskopHttpResponse("<iframe src='../embed/movie?v=movie&amp;sub=id'></iframe>", postUrl)
                }
            },
            genericResolver = { url, referer -> resolved += url to referer; true }
        )

        assertTrue(playback.resolve("<iframe id='player' src='$playerUrl'></iframe>", detailUrl))
        assertEquals(
            listOf(
                "GET $playerUrl referer=$detailUrl",
                "POST $formUrl referer=$detailUrl"
            ),
            calls
        )
        assertEquals(listOf(Triple(formUrl, mapOf("play" to "play"), detailUrl)), postRequests)
        assertEquals(listOf(finalUrl to postUrl), resolved)
    }

    @Test
    fun `blank or missing intermediary form action posts to redirected gate URL`() = runBlocking {
        val detailUrl = "https://kebioskop21.cfd/nonton-film/example/"
        val playerUrl = "https://streaming.kebioskop21.pro/apidrive.php?id=abc"
        val gateUrl = "https://streaming.kebioskop21.pro/apidrive.php?token=gate"
        val postTargets = mutableListOf<String>()

        for (form in listOf(
            "<form method='post'><button name='play' value='play'></button></form>",
            "<form method='post' action='   '><button name='play' value='play'></button></form>"
        )) {
            val playback = KeBioskopPlayerOrchestrator(
                network = object : KeBioskopPlaybackNetwork {
                    override suspend fun get(url: String, referer: String) =
                        KeBioskopHttpResponse(form, gateUrl)

                    override suspend fun postPlay(
                        url: String,
                        data: Map<String, String>,
                        referer: String
                    ): KeBioskopHttpResponse {
                        assertEquals(mapOf("play" to "play"), data)
                        assertEquals(detailUrl, referer)
                        postTargets += url
                        return KeBioskopHttpResponse(
                            "<iframe src='https://abysscdn.com/?v=movie'></iframe>",
                            gateUrl
                        )
                    }
                },
                genericResolver = { _, _ -> true }
            )

            assertTrue(playback.resolve("<iframe id='player' src='$playerUrl'></iframe>", detailUrl))
        }

        assertEquals(listOf(gateUrl, gateUrl), postTargets)
    }

    @Test
    fun `intermediary batches independent final iframe mirrors`() = runBlocking {
        val detailUrl = "https://kebioskop21.cfd/nonton-film/example/"
        val playerUrl = "https://streaming.kebioskop21.cfd/apidrive.php?id=abc"
        val first = "https://abysscdn.com/?v=first"
        val second = "https://firestream.to/e/second"
        val batches = mutableListOf<List<PlayerResolutionCandidate>>()
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String) =
                    KeBioskopHttpResponse(
                        "<form method='post'><button name='play' value='play'></button></form>",
                        playerUrl
                    )

                override suspend fun postPlay(
                    url: String,
                    data: Map<String, String>,
                    referer: String
                ) = KeBioskopHttpResponse(
                    "<iframe src='$first'></iframe><iframe src='$second'></iframe>",
                    playerUrl
                )
            },
            genericResolver = { _, _ -> error("final mirrors must use the bounded batch") },
            genericBatchResolver = { candidates ->
                batches += candidates
                true
            }
        )

        assertTrue(playback.resolve("<iframe id='player' src='$playerUrl'></iframe>", detailUrl))
        assertEquals(
            listOf(
                PlayerResolutionCandidate(first, playerUrl),
                PlayerResolutionCandidate(second, playerUrl)
            ),
            batches.single()
        )
    }

    @Test
    fun `current direct fullscreen iframe without legacy player id resolves`() = runBlocking {
        val detailUrl = "https://kebioskop21.cfd/nonton-film-todo-kayod-2-2026-sub-indo/"
        val playerUrl = (
            "https://abysscdn.com/?v=YH8gMDbrb" +
                "&thumbnail=https://streaming.kebioskop21.cfd/playme.jpg" +
                "&sub=https://subtitle.kebioskop21.cfd/sub21/streaming/kebioskop21.srt" +
                "&sub-lang=Indonesia"
            )
        val resolved = mutableListOf<Pair<String, String>>()
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(
                    url: String,
                    referer: String
                ): KeBioskopHttpResponse = error("direct iframe must not enter the intermediary gate")

                override suspend fun postPlay(
                    url: String,
                    data: Map<String, String>,
                    referer: String
                ): KeBioskopHttpResponse = error("direct iframe must not post an intermediary form")
            },
            genericResolver = { url, referer ->
                resolved += url to referer
                true
            }
        )

        assertTrue(
            playback.resolve(
                """
                <div class="filmicerik">
                    <div style="position:relative;display:inline-block;width:100%;">
                        <iframe
                            width="100%"
                            height="400"
                            src="//abysscdn.com/?v=YH8gMDbrb&amp;thumbnail=https://streaming.kebioskop21.cfd/playme.jpg&amp;sub=https://subtitle.kebioskop21.cfd/sub21/streaming/kebioskop21.srt&amp;sub-lang=Indonesia"
                            frameborder="0"
                            scrolling="0"
                            allowfullscreen>
                        </iframe>
                    </div>
                </div>
                """.trimIndent(),
                detailUrl
            )
        )
        assertEquals(listOf(playerUrl to detailUrl), resolved)
    }

    @Test
    fun `non intermediary iframe uses generic resolver directly`() = runBlocking {
        val detailUrl = "https://kebioskop21.cfd/nonton-film/example/"
        val resolved = mutableListOf<Pair<String, String>>()
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String): KeBioskopHttpResponse = error("network should not run")
                override suspend fun postPlay(url: String, data: Map<String, String>, referer: String): KeBioskopHttpResponse = error("network should not run")
            },
            genericResolver = { url, referer -> resolved += url to referer; true }
        )

        assertTrue(playback.resolve("<iframe id='player' src='/embed/example'></iframe>", detailUrl))
        assertEquals(listOf("https://kebioskop21.cfd/embed/example" to detailUrl), resolved)
    }

    @Test
    fun `ordinary intermediary failure does not prevent a later player candidate`() = runBlocking {
        val detailUrl = "https://kebioskop21.cfd/nonton-film/example/"
        val fallbackUrl = "https://embed.example/movie"
        val resolved = mutableListOf<Pair<String, String>>()
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String): KeBioskopHttpResponse {
                    error("dead intermediary")
                }

                override suspend fun postPlay(url: String, data: Map<String, String>, referer: String): KeBioskopHttpResponse = error("unreachable")
            },
            genericResolver = { url, referer -> resolved += url to referer; true }
        )

        assertTrue(
            playback.resolve(
                """
                <iframe id='player' src='https://streaming.kebioskop21.pro/apidrive.php?id=dead'></iframe>
                <iframe id='player' src='$fallbackUrl'></iframe>
                """.trimIndent(),
                detailUrl
            )
        )
        assertEquals(listOf(fallbackUrl to detailUrl), resolved)
    }

    @Test
    fun `exhausted playback budget prevents further network work`() = runBlocking {
        val calls = mutableListOf<String>()
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String): KeBioskopHttpResponse {
                    calls += "get"
                    error("network must not run")
                }

                override suspend fun postPlay(
                    url: String,
                    data: Map<String, String>,
                    referer: String
                ): KeBioskopHttpResponse {
                    calls += "post"
                    error("network must not run")
                }
            },
            genericResolver = { _, _ ->
                calls += "resolver"
                true
            },
            canContinue = { false }
        )

        assertFalse(
            playback.resolve(
                "<iframe id='player' src='https://streaming.kebioskop21.pro/apidrive.php?id=abc'></iframe>",
                "https://kebioskop21.cfd/nonton-film/example/"
            )
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `playback orchestration propagates cancellation`() {
        val playback = KeBioskopPlayerOrchestrator(
            network = object : KeBioskopPlaybackNetwork {
                override suspend fun get(url: String, referer: String): KeBioskopHttpResponse {
                    throw CancellationException("cancelled")
                }

                override suspend fun postPlay(url: String, data: Map<String, String>, referer: String): KeBioskopHttpResponse = error("unreachable")
            },
            genericResolver = { _, _ -> false }
        )

        assertFailsWith<CancellationException> {
            runBlocking {
                playback.resolve(
                    "<iframe id='player' src='https://streaming.kebioskop21.pro/apidrive.php?id=abc'></iframe>",
                    "https://kebioskop21.cfd/nonton-film/example/"
                )
            }
        }
    }
}
