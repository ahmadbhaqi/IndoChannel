package com.example

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class AnoboyProviderTest {
    @Test
    fun `content taxonomy ignores adult navigation outside the scoped article`() {
        val document = Jsoup.parse(
            """
                <nav><a href="/category/hentai/">Hentai</a></nav>
                <main>
                  <article>
                    <h1 class="entry-title">Ordinary Anime</h1>
                    <div class="entry-meta">
                      <a rel="category tag" href="/category/anime/">Anime</a>
                      <a rel="category tag" href="/category/drama/">Drama</a>
                    </div>
                  </article>
                </main>
            """.trimIndent(),
            "https://anoboy.xyz/episode/ordinary/"
        )

        assertEquals(listOf("Anime", "Drama"), AnoboyContentPolicy.categories(document))
        assertEquals(false, AnoboyContentPolicy.isBlocked(document, document.location()))
    }

    @Test
    fun `content taxonomy ignores an adult sidebar inside the main layout`() {
        val document = Jsoup.parse(
            """
                <main>
                  <aside><a href="/category/hentai/">Hentai</a></aside>
                  <article>
                    <h1 class="entry-title">Ordinary Anime</h1>
                    <div class="entry-meta">
                      <a rel="category tag" href="/category/drama/">Drama</a>
                    </div>
                  </article>
                </main>
            """.trimIndent(),
            "https://anoboy.xyz/episode/ordinary/"
        )

        assertEquals(listOf("Drama"), AnoboyContentPolicy.categories(document))
        assertEquals(false, AnoboyContentPolicy.isBlocked(document, document.location()))
    }

    @Test
    fun `content taxonomy blocks an adult category inside the scoped article`() {
        val document = Jsoup.parse(
            """
                <main>
                  <article>
                    <h1 class="entry-title">Neutral Looking Title</h1>
                    <div class="entry-meta">
                      <a rel="category tag" href="/category/hentai/">Hentai</a>
                    </div>
                  </article>
                </main>
            """.trimIndent(),
            "https://anoboy.xyz/episode/neutral-looking-title/"
        )

        assertTrue(AnoboyContentPolicy.isBlocked(document, document.location()))
    }

    @Test
    fun `uses current domain and rehomes only legacy Anoboy urls`() {
        val currentBase = AnoboyProvider().mainUrl
        val legacyHosts = setOf("ww1.anoboy.boo")

        assertEquals("https://anoboy.xyz", currentBase)
        assertEquals(
            "https://anoboy.xyz/episode/example/?server=2#player",
            ProviderHtmlParser.normalizeProviderPageUrl(
                "https://ww1.anoboy.boo/episode/example/?server=2#player",
                currentBase,
                legacyHosts
            )
        )
        assertEquals(
            "https://anoboy.xyz/episode/example/?server=2#player",
            ProviderHtmlParser.normalizeProviderPageUrl(
                "/episode/example/?server=2#player",
                currentBase,
                legacyHosts
            )
        )
        assertEquals(
            null,
            ProviderHtmlParser.normalizeProviderPageUrl(
                "https://foreign.example/episode/example/",
                currentBase,
                legacyHosts
            )
        )
    }

    @Test
    fun `resolves uploads candidates against the fetched episode url`() {
        assertEquals(
            listOf("https://redirected.anoboy.xyz/uploads/player.html" to "https://redirected.anoboy.xyz/episode/example/"),
            AnoboyPlaybackParser.episodeCandidates(
                Jsoup.parse("<iframe src='/uploads/player.html'></iframe>"),
                "https://redirected.anoboy.xyz/episode/example/"
            ).map { it.url to it.referer }
        )
    }

    @Test
    fun `fetches uploads wrappers with episode referer and resolves wrapper response urls`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { url, referer ->
                calls += "fetch:$url@$referer"
                AnoboyFetchedPage(
                    "https://player.example/frame/index.html",
                    Jsoup.parse("<video><source src='/video/master.m3u8'></video>")
                )
            },
            bloggerResolver = { _, _ -> false },
            genericResolver = { url, referer ->
                calls += "generic:$url@$referer"
                true
            }
        )

        assertEquals(
            true,
            router.resolve(
                "https://redirected.anoboy.xyz/uploads/player.html",
                "https://redirected.anoboy.xyz/episode/example/"
            )
        )
        assertEquals(
            listOf(
                "fetch:https://redirected.anoboy.xyz/uploads/player.html@https://redirected.anoboy.xyz/episode/example/",
                "generic:https://player.example/video/master.m3u8@https://player.example/frame/index.html"
            ),
            calls
        )
    }

    @Test
    fun `does not fetch an unsafe uploads wrapper`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { url, _ ->
                calls += "fetch:$url"
                null
            },
            bloggerResolver = { _, _ -> false },
            genericResolver = { url, _ ->
                calls += "generic:$url"
                true
            }
        )

        assertEquals(false, router.resolve("http://127.0.0.1/uploads/player.html", "https://anoboy.xyz/"))
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `routes direct uploads media without fetching it as a wrapper`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { url, _ ->
                calls += "fetch:$url"
                null
            },
            bloggerResolver = { _, _ -> false },
            genericResolver = { url, referer ->
                calls += "generic:$url@$referer"
                true
            }
        )

        assertEquals(
            true,
            router.resolve(
                "https://cdn.example/uploads/episode.mp4",
                "https://anoboy.xyz/episode/example/"
            )
        )
        assertEquals(
            listOf("generic:https://cdn.example/uploads/episode.mp4@https://anoboy.xyz/episode/example/"),
            calls
        )
    }

    @Test
    fun `stops cyclic uploads wrappers after each candidate is visited once`() = runBlocking {
        val first = "https://player.example/uploads/first.html"
        val second = "https://player.example/uploads/second.html"
        val fetched = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { url, _ ->
                fetched += url
                val nested = if (url == first) second else first
                AnoboyFetchedPage(url, Jsoup.parse("<iframe src='$nested'></iframe>", url))
            },
            bloggerResolver = { _, _ -> false },
            genericResolver = { _, _ -> false }
        )

        assertEquals(false, router.resolve(first, "https://anoboy.xyz/episode/example/"))
        assertEquals(listOf(first, second), fetched)
    }

    @Test
    fun `shared budget prevents Anoboy candidate network work`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { _, _ ->
                calls += "fetch"
                null
            },
            bloggerResolver = { _, _ ->
                calls += "blogger"
                true
            },
            genericResolver = { _, _ ->
                calls += "generic"
                true
            },
            canContinue = { false }
        )

        assertEquals(
            false,
            router.resolve(
                "https://player.example/uploads/player.html",
                "https://anoboy.xyz/episode/example/"
            )
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `schedules generic resolution after Blogger reports no link`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { _, _ -> null },
            bloggerResolver = { url, referer ->
                calls += "blogger:$url@$referer"
                false
            },
            genericResolver = { url, referer ->
                calls += "generic:$url@$referer"
                true
            }
        )

        assertEquals(
            true,
            router.resolve("https://www.blogger.com/video.g?token=abc", "https://anoboy.xyz/episode/1/")
        )
        assertEquals(
            listOf(
                "blogger:https://www.blogger.com/video.g?token=abc@https://anoboy.xyz/episode/1/",
                "generic:https://www.blogger.com/video.g?token=abc@https://anoboy.xyz/episode/1/"
            ),
            calls
        )
    }

    @Test
    fun `attempts tokenless exact Blogger video g before generic fallback`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { _, _ -> null },
            bloggerResolver = { url, referer ->
                calls += "blogger:$url@$referer"
                false
            },
            genericResolver = { url, referer ->
                calls += "generic:$url@$referer"
                true
            }
        )

        assertEquals(
            true,
            router.resolve("https://www.blogger.com/video.g", "https://anoboy.xyz/episode/1/")
        )
        assertEquals(
            listOf(
                "blogger:https://www.blogger.com/video.g@https://anoboy.xyz/episode/1/",
                "generic:https://www.blogger.com/video.g@https://anoboy.xyz/episode/1/"
            ),
            calls
        )
    }

    @Test
    fun `does not treat evilblogger as a Blogger host`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { _, _ -> null },
            bloggerResolver = { _, _ ->
                calls += "blogger"
                true
            },
            genericResolver = { _, _ ->
                calls += "generic"
                true
            }
        )

        assertEquals(true, router.resolve("https://evilblogger.com/video.g", "https://anoboy.xyz/episode/1/"))
        assertEquals(listOf("generic"), calls)
    }

    @Test
    fun `continues after an ordinary mirror failure`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { _, _ -> null },
            bloggerResolver = { _, _ -> false },
            genericResolver = { url, _ ->
                calls += url
                if (url.endsWith("first")) error("broken mirror")
                true
            }
        )

        assertEquals(
            true,
            router.resolveAll(
                listOf(
                    AnoboyPlayerCandidate("https://video.example/first", "https://anoboy.xyz/episode/1/"),
                    AnoboyPlayerCandidate("https://video.example/second", "https://anoboy.xyz/episode/1/")
                )
            )
        )
        assertEquals(
            listOf("https://video.example/first", "https://video.example/second"),
            calls
        )
    }

    @Test
    fun `propagates cancellation without attempting later mirrors`() = runBlocking {
        val calls = mutableListOf<String>()
        val router = AnoboyCandidateRouter(
            wrapperFetcher = { _, _ -> null },
            bloggerResolver = { _, _ -> false },
            genericResolver = { url, _ ->
                calls += url
                throw CancellationException("cancel")
            }
        )

        try {
            router.resolveAll(
                listOf(
                    AnoboyPlayerCandidate("https://video.example/first", "https://anoboy.xyz/episode/1/"),
                    AnoboyPlayerCandidate("https://video.example/second", "https://anoboy.xyz/episode/1/")
                )
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("https://video.example/first"), calls)
    }
}
