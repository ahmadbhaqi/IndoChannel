package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class ProviderExpansionTest {
    private val projectRoot = listOf(File("."), File(".."))
        .first { File(it, "IndoProvider").exists() }
    private val sourceRoot = File(projectRoot, "IndoProvider/src/main/kotlin/com/example")

    private fun source(fileName: String): String = File(sourceRoot, fileName).readText()

    @Test
    fun `movie additions are registered from most to least popular`() {
        val expected = listOf(
            "MovieboxProvider",
            "PencurimovieProvider",
            "SarangfilmProvider",
            "NomatProvider",
            "IndomaxProvider",
            "KawanfilmProvider"
        )

        assertRegistrationOrder(expected)
        assertGroupStartsWith("// Movie & TV Series", "// Anime", expected)
    }

    @Test
    fun `anime additions are registered from most to least popular`() {
        val expected = listOf("KuramanimeProvider", "AnimasuProvider")
        assertRegistrationOrder(expected)
        assertGroupStartsWith("// Anime", null, expected)
    }

    @Test
    fun `new providers use verified current domains`() {
        val expectedDomains = mapOf(
            "MovieboxProvider.kt" to "https://h5-api.aoneroom.com",
            "PencurimovieProvider.kt" to "https://ww21.pencurimovie.sbs",
            "SarangfilmProvider.kt" to "https://sarangfilm.diy",
            "NomatProvider.kt" to "https://nomat.shop",
            "IndomaxProvider.kt" to "https://idmxl.ink",
            "KawanfilmProvider.kt" to "https://web.kawanfilm21.co",
            "KuramanimeProvider.kt" to "https://v19.kuramanime.ing",
            "AnimasuProvider.kt" to "https://v2.animasu.work"
        )

        expectedDomains.forEach { (fileName, domain) ->
            val provider = File(sourceRoot, fileName)
            assertTrue(provider.isFile, "$fileName must be implemented")
            assertTrue(
                provider.readText().contains("""override var mainUrl = "$domain""""),
                "$fileName must use $domain"
            )
        }
    }

    @Test
    fun `animasu uses the fast homepage only for the first update catalog page`() {
        val mainUrl = "https://v2.animasu.work"

        assertEquals(
            "$mainUrl/",
            AnimasuCatalogRouting.pageUrl(mainUrl, 1, "urutan=update")
        )
        assertEquals(
            "$mainUrl/pencarian/?urutan=update&halaman=2",
            AnimasuCatalogRouting.pageUrl(mainUrl, 2, "urutan=update")
        )
        assertEquals(
            "$mainUrl/pencarian/?status=&tipe=Movie&urutan=update&halaman=1",
            AnimasuCatalogRouting.pageUrl(
                mainUrl,
                1,
                "status=&tipe=Movie&urutan=update"
            )
        )
        assertEquals(
            listOf(
                "$mainUrl/",
                "$mainUrl/pencarian/?urutan=update&halaman=1"
            ),
            AnimasuCatalogRouting.pageUrls(mainUrl, 1, "urutan=update")
        )
        assertEquals(
            listOf("$mainUrl/pencarian/?urutan=update&halaman=2"),
            AnimasuCatalogRouting.pageUrls(mainUrl, 2, "urutan=update")
        )
        val attempts = AnimasuCatalogRouting.pageUrls(mainUrl, 1, "urutan=update")
            .indices
            .map { index -> AnimasuCatalogRouting.timeoutSeconds(index, 2) }
        assertEquals(listOf(20L, 35L), attempts)
        assertTrue(attempts.sum() <= 55L)
    }

    @Test
    fun `new providers use the shared bounded link resolver`() {
        val providers = listOf(
            "MovieboxProvider.kt",
            "PencurimovieProvider.kt",
            "SarangfilmProvider.kt",
            "NomatProvider.kt",
            "IndomaxProvider.kt",
            "KawanfilmProvider.kt",
            "KuramanimeProvider.kt",
            "AnimasuProvider.kt"
        )

        providers.forEach { fileName ->
            val provider = source(fileName)
            assertEquals(
                1,
                Regex("""\bLinkResolutionSession\s*\(""").findAll(provider).count(),
                "$fileName must keep its resolver session count bounded"
            )
            assertFalse(provider.contains("private var directUrl"), "$fileName must not share redirect state")
        }

        val publicCatalogFallback = source("PublicCatalogFallbackResolver.kt")
        assertEquals(
            1,
            Regex("""\bLinkResolutionSession\s*\(""")
                .findAll(publicCatalogFallback)
                .count(),
            "the shared public catalog fallback must own exactly one bounded session"
        )
    }

    @Test
    fun `provider repairs bump the plugin release`() {
        val moduleBuild = File(projectRoot, "IndoProvider/build.gradle.kts").readText()
        val pluginVersion = Regex("""(?m)^version\s*=\s*(\d+)\s*$""")
            .find(moduleBuild)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        assertTrue(pluginVersion != null && pluginVersion >= 24)
    }

    @Test
    fun `moviebox episode declarations are normalized deterministically`() {
        assertEquals(listOf(1, 2, 3), movieboxEpisodeNumbers("1,3,2,3", 12))
        assertEquals(listOf(1, 2, 3), movieboxEpisodeNumbers(null, 3))
        assertEquals(emptyList(), movieboxEpisodeNumbers(null, 0))
        assertEquals(listOf(1, 2), movieboxEpisodeNumbers("-1,0,1,2,10001", 3))
        assertEquals(2_000, movieboxEpisodeNumbers(null, 10_000).size)

        val tooManySeasons = (1..200).map { season ->
            MovieboxDetailResponse.Season(season = season, maxEp = 1)
        }
        assertEquals(100, movieboxEpisodeCoordinates(tooManySeasons).size)

        val oversizedCatalog = (1..100).map { season ->
            MovieboxDetailResponse.Season(season = season, maxEp = 100)
        }
        assertEquals(5_000, movieboxEpisodeCoordinates(oversizedCatalog).size)
    }

    @Test
    fun `moviebox search retries a transient empty response before falling back`() = runBlocking {
        val expected = MovieboxItem(
            subjectId = "42",
            subjectType = 1,
            title = "The Dawn",
            detailPath = "the-dawn-AbCdEf12345",
            hasResource = true
        )
        var remoteAttempts = 0
        var homepageAttempts = 0

        val result = resolveMovieboxSearchCandidates(
            query = "The",
            remoteSearch = {
                remoteAttempts += 1
                if (remoteAttempts == 1) emptyList() else listOf(expected)
            },
            homepageFallback = {
                homepageAttempts += 1
                emptyList()
            }
        )

        assertEquals(listOf(expected), result)
        assertEquals(2, remoteAttempts)
        assertEquals(0, homepageAttempts)
    }

    @Test
    fun `moviebox search uses only matching playable homepage items after repeated failure`() =
        runBlocking {
            val matching = MovieboxItem(
                subjectId = "42",
                subjectType = 1,
                title = "Dawn of Justice",
                detailPath = "dawn-of-justice-AbCdEf12345",
                hasResource = true
            )
            val unrelated = matching.copy(
                subjectId = "43",
                title = "Night Patrol",
                detailPath = "night-patrol-AbCdEf12345"
            )
            val unavailable = matching.copy(
                subjectId = "44",
                title = "Dawn Preview",
                detailPath = "dawn-preview-AbCdEf12345",
                hasResource = false
            )
            var remoteAttempts = 0
            var homepageAttempts = 0

            val result = resolveMovieboxSearchCandidates(
                query = "Dawn",
                remoteSearch = {
                    remoteAttempts += 1
                    error("temporary upstream failure")
                },
                homepageFallback = {
                    homepageAttempts += 1
                    listOf(unrelated, unavailable, matching)
                }
            )

            assertEquals(listOf(matching), result)
            assertEquals(2, remoteAttempts)
            assertEquals(1, homepageAttempts)
        }

    @Test
    fun `nomat decodes bounded base64 player buttons`() {
        val url = "https://player.example/embed/nomat"
        val encoded = Base64.getEncoder().encodeToString(url.toByteArray())
        val document = Jsoup.parse("""<div class="server-item" data-url="$encoded"></div>""")

        assertEquals(listOf(url), NomatParser.serverUrls(document))
    }

    @Test
    fun `nomat only fetches provider owned or explicit playback hosts`() {
        val mainUrl = "https://nomat.shop"
        assertEquals(
            "https://nomat.shop/play/sample",
            NomatParser.playbackPageUrl("https://nomat.shop/play/sample", mainUrl)
        )
        assertEquals(
            "https://nomat.shop/play/sample",
            NomatParser.playbackPageUrl("https://nomat.site/play/sample", mainUrl)
        )
        assertEquals(
            "https://nomat.site/play/sample",
            NomatParser.networkPlaybackPageUrl("https://nomat.site/play/sample", mainUrl)
        )
        assertEquals(
            "https://nontonhemat.link/watch/sample",
            NomatParser.playbackPageUrl("https://nontonhemat.link/watch/sample", mainUrl)
        )
        assertEquals(
            null,
            NomatParser.playbackPageUrl("https://attacker.example/watch/sample", mainUrl)
        )
        assertEquals(
            null,
            NomatParser.playbackPageUrl("http://127.0.0.1/admin", mainUrl)
        )
        assertEquals(
            "https://nontonhemat.link/watch/next",
            NomatParser.redirectTarget(
                "/watch/next",
                "https://nontonhemat.link/watch/start",
                mainUrl
            )
        )
        assertEquals(
            null,
            NomatParser.redirectTarget(
                "https://attacker.example/admin",
                "https://nontonhemat.link/watch/start",
                mainUrl
            )
        )
    }

    @Test
    fun `nomat preserves coded catalog entries without disabling the shared policy`() {
        val codedCard = Jsoup.parse(
            """<article><a href="/category/genre/jav/">Genre</a></article>"""
        ).selectFirst("article")!!
        val ordinaryCard = Jsoup.parse(
            """<article><a href="/category/genre/jav/">Genre</a></article>"""
        ).selectFirst("article")!!

        assertFalse(
            NomatParser.shouldBlockCatalogCard(
                codedCard,
                "DVAJ-710 Example title",
                "https://nomat.shop/play/dvaj-710-example"
            )
        )
        assertTrue(
            NomatParser.shouldBlockCatalogCard(
                ordinaryCard,
                "Ordinary Drama",
                "https://nomat.shop/play/ordinary-drama"
            )
        )
    }

    @Test
    fun `nomat encodes search terms as one path segment`() {
        assertEquals("film%20baru%2F2026", NomatParser.searchPathSegment("film baru/2026"))
    }

    @Test
    fun `animasu decodes mirror html before resolving`() {
        val iframe = """<iframe src="https://player.example/embed/animasu"></iframe>"""
        val encoded = Base64.getEncoder().encodeToString(iframe.toByteArray())
        val unrelated = Base64.getEncoder().encodeToString(
            """<iframe src="https://attacker.example/unrelated"></iframe>""".toByteArray()
        )
        val html = """
            <div class="mobius">
                <select class="mirror"><option value="$encoded">720p</option></select>
            </div>
            <select id="theme"><option value="$unrelated">Theme</option></select>
        """.trimIndent()

        assertEquals(
            listOf("https://player.example/embed/animasu"),
            AnimasuParser.playerUrls(html, "https://v2.animasu.work/episode/sample")
        )
    }

    @Test
    fun `kuramanime extracts relative and protocol relative players`() {
        val html = """
            <div id="player">
                <iframe src="//player.example/embed/kurama"></iframe>
                <div data-video="/player/fallback"></div>
            </div>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://player.example/embed/kurama",
                "https://v19.kuramanime.ing/player/fallback"
            ),
            KuramanimeParser.playerUrls(html, "https://v19.kuramanime.ing/anime/a/episode/1")
        )
    }

    @Test
    fun `kuramanime rejects catalog cards marked as explicit`() {
        val card = Jsoup.parseBodyFragment(
            """
            <div class="product__item">
                <div class="pin">
                    <i class="fa fa-fire"></i>
                    <i class="fa-solid fa-droplet"></i>
                </div>
                <div class="product__item__text">
                    <h5><a href="/anime/5087/explicit/episode/4">Explicit title</a></h5>
                </div>
            </div>
            """.trimIndent()
        ).selectFirst(".product__item")

        assertNull(KuramanimeParser.catalogAnchor(assertNotNull(card)))
    }

    @Test
    fun `kuramanime keeps ecchi catalog cards without the explicit marker`() {
        val card = Jsoup.parseBodyFragment(
            """
            <div class="product__item">
                <div class="pin"><i class="fa fa-fire"></i></div>
                <div class="product__item__text">
                    <h5><a href="/anime/1/high-school-dxd/episode/1">High School DxD</a></h5>
                </div>
            </div>
            """.trimIndent()
        ).selectFirst(".product__item")

        assertEquals(
            "High School DxD",
            KuramanimeParser.catalogAnchor(assertNotNull(card))?.text()
        )
    }

    @Test
    fun `kuramanime rejects finished cards only in the ongoing catalog`() {
        val card = Jsoup.parseBodyFragment(
            """
            <div class="product__item">
                <div class="status"><span>SELESAI</span></div>
                <h5>
                    <a href="/anime/3/boruto-naruto-next-generations/episode/293">
                        Boruto: Naruto Next Generations
                    </a>
                </h5>
            </div>
            """.trimIndent()
        ).selectFirst(".product__item")

        assertNull(
            KuramanimeParser.catalogAnchor(
                assertNotNull(card),
                excludeFinished = true
            )
        )
        assertEquals(
            "Boruto: Naruto Next Generations",
            KuramanimeParser.catalogAnchor(card, excludeFinished = false)?.text()
        )
    }

    @Test
    fun `kuramanime recognizes only ongoing catalog request paths`() {
        assertTrue(
            KuramanimeParser.isOngoingCatalog(
                "https://v19.kuramanime.ing/anime/ongoing?order_by=updated&page="
            )
        )
        assertTrue(
            KuramanimeParser.isOngoingCatalog(
                "https://v19.kuramanime.ing/quick/ongoing/"
            )
        )
        assertFalse(
            KuramanimeParser.isOngoingCatalog(
                "https://v19.kuramanime.ing/anime/finished?next=/anime/ongoing"
            )
        )
    }

    @Test
    fun `kuramanime ongoing filter ignores selesai in an anime title`() {
        val card = Jsoup.parseBodyFragment(
            """
            <div class="product__item">
                <div class="status"><span>SEDANG TAYANG</span></div>
                <h5>
                    <a href="/anime/42/sample/episode/4">Koi ga Selesai Made</a>
                </h5>
            </div>
            """.trimIndent()
        ).selectFirst(".product__item")

        assertEquals(
            "Koi ga Selesai Made",
            KuramanimeParser.catalogAnchor(
                assertNotNull(card),
                excludeFinished = true
            )?.text()
        )
    }

    @Test
    fun `kuramanime ignores foreign episode links`() {
        val document = Jsoup.parse(
            """
                <div id="episodeLists">
                    <a href="/anime/1/sample/episode/1">Episode 1</a>
                    <a href="https://foreign.example/anime/1/sample/episode/2">Episode 2</a>
                </div>
                <aside>
                    <a href="/anime/2/other-show/episode/9">Episode 9</a>
                </aside>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://v19.kuramanime.ing/anime/1/sample/episode/1" to "Episode 1"
            ),
            KuramanimeParser.episodeLinks(document, "https://v19.kuramanime.ing/anime/1/sample/")
        )
    }

    @Test
    fun `anime player candidate parsing is bounded`() {
        val options = (1..80).joinToString("") { index ->
            val encoded = Base64.getEncoder().encodeToString(
                """<iframe src="https://player.example/embed/$index"></iframe>""".toByteArray()
            )
            """<option value="$encoded">$index</option>"""
        }
        val animasuHtml = """<div class="mirror"><select>$options</select></div>"""
        assertTrue(
            AnimasuParser.playerUrls(animasuHtml, "https://v2.animasu.work/episode/sample").size <= 48
        )

        val kuramaButtons = (1..80).joinToString("") { index ->
            """<div data-video="https://player.example/embed/$index"></div>"""
        }
        assertTrue(
            KuramanimeParser.playerUrls(
                """<div id="player">$kuramaButtons</div>""",
                "https://v19.kuramanime.ing/anime/a/episode/1"
            ).size <= 48
        )

        listOf("AnimasuProvider.kt", "KuramanimeProvider.kt").forEach { fileName ->
            assertTrue(
                source(fileName).contains("PopularProviderLinkLimits.playerElements("),
                "$fileName must cap matching elements before decoding them"
            )
        }
    }

    @Test
    fun `kuramanime ignores encoded controls outside player containers`() {
        val player = Base64.getEncoder().encodeToString(
            """<iframe src="https://player.example/embed/kurama"></iframe>""".toByteArray()
        )
        val unrelated = Base64.getEncoder().encodeToString(
            """<iframe src="https://attacker.example/unrelated"></iframe>""".toByteArray()
        )
        val html = """
            <div id="player"><select><option value="$player">720p</option></select></div>
            <select id="theme"><option value="$unrelated">Theme</option></select>
        """.trimIndent()

        assertEquals(
            listOf("https://player.example/embed/kurama"),
            KuramanimeParser.playerUrls(
                html,
                "https://v19.kuramanime.ing/anime/a/episode/1"
            )
        )
    }

    @Test
    fun `anime fallbacks ignore media and scripts outside player containers`() {
        val emptyScripts = (1..80).joinToString("") {
            "<script></script>"
        }
        val lazyExternalScripts = (1..80).joinToString("") { index ->
            """<script data-src="/assets/lazy-player-$index.js"></script>"""
        }
        val externalScripts = (1..60).joinToString("") { index ->
            """<script src="/assets/player-$index.js"></script>"""
        }
        val html = """
            <iframe src="https://attacker.example/outside-frame"></iframe>
            <script>const outside = { src: "https://attacker.example/outside-script" };</script>
            <div id="player">
                $emptyScripts
                $lazyExternalScripts
                $externalScripts
                <iframe src="https://player.example/inside-frame"></iframe>
                <script>const inside = { file: "https://player.example/inside-script.m3u8" };</script>
            </div>
        """.trimIndent()
        val expected = listOf(
            "https://player.example/inside-frame",
            "https://player.example/inside-script.m3u8"
        )

        assertEquals(
            expected,
            KuramanimeParser.playerUrls(
                html,
                "https://v19.kuramanime.ing/anime/a/episode/1"
            )
        )
        assertEquals(
            expected,
            AnimasuParser.playerUrls(
                html.replace("""id="player"""", """id="server""""),
                "https://v2.animasu.work/episode/sample"
            )
        )

        listOf("AnimasuProvider.kt", "KuramanimeProvider.kt").forEach { fileName ->
            val provider = source(fileName)
            assertTrue(provider.contains("PopularProviderLinkLimits.scopedMediaUrls("))
            assertFalse(provider.contains("ProviderHtmlParser.mediaSources(document)"))
            assertFalse(provider.contains("InlineDataParser.inlinePlayerUrls(html)"))
        }
    }

    @Test
    fun `popular muvipro providers cap ajax requests before network fanout`() {
        val tabs = (1..80).joinToString("") { index ->
            """<div class="tab-content-ajax" id="player$index"></div>"""
        }
        val document = Jsoup.parse(
            """<div id="muvipro_player_content_id" data-id="321"></div>$tabs"""
        )

        assertEquals(16, PopularProviderLinkLimits.muviproAjaxRequests(document).size)
        listOf("SarangfilmProvider.kt", "KawanfilmProvider.kt").forEach { fileName ->
            assertTrue(
                source(fileName).contains("PopularProviderLinkLimits.muviproAjaxRequests(document)"),
                "$fileName must use the bounded AJAX request list"
            )
        }
    }

    @Test
    fun `compact season and episode labels are parsed independently`() {
        assertEquals(1 to 2, PopularProviderEpisodeParser.position("S1 E2"))
        assertEquals(3 to 12, PopularProviderEpisodeParser.position("Season 3 Episode 12"))
        assertEquals(null to 7, PopularProviderEpisodeParser.position("Episode 7"))
    }

    @Test
    fun `moviebox rejects unsafe subtitle destinations`() = runBlocking {
        assertTrue(movieboxSubtitleFile("Indonesia", "https://subtitle.example/file.vtt") != null)
        assertEquals(null, movieboxSubtitleFile("Internal", "http://127.0.0.1/admin"))
        assertEquals(null, movieboxSubtitleFile("Invalid", "file:///tmp/subtitle.vtt"))
    }

    @Test
    fun `documented live flag includes provider expansion checks`() {
        val liveTest = File(
            projectRoot,
            "IndoProvider/src/test/kotlin/com/example/ProviderExpansionLiveTest.kt"
        ).readText()
        assertTrue(liveTest.contains("""System.getenv("RUN_LIVE_PROVIDER_TESTS")"""))
        assertFalse(liveTest.contains("RUN_LIVE_PROVIDER_EXPANSION_TESTS"))
    }

    @Test
    fun `kuramanime catalog prefers the title link over an outer episode card`() {
        val card = Jsoup.parse(
            """
                <div class="product__item">
                    <a href="/anime/5044/example/episode/4">
                        <span class="ep">Ep 4 / 12</span>
                    </a>
                    <h5>
                        <a href="/anime/5044/example/episode/4">Example Anime</a>
                    </h5>
                </div>
            """.trimIndent()
        ).selectFirst("div.product__item")!!

        assertEquals("Example Anime", KuramanimeParser.catalogAnchor(card)?.text())
    }

    @Test
    fun `bounded node filters declare tail for host jsoup abi compatibility`() {
        val filterClasses = listOf(
            "com.example.PopularProviderLinkLimits\$playerElements\$1",
            "com.example.PopularProviderLinkLimits\$scopedMediaUrls\$1"
        ).map { className -> Class.forName(className) }

        filterClasses.forEach { filterClass ->
            assertTrue(
                filterClass.declaredMethods.any { method ->
                    method.name == "tail" &&
                        method.parameterTypes.contentEquals(
                            arrayOf(org.jsoup.nodes.Node::class.java, Int::class.javaPrimitiveType)
                        )
                },
                "${filterClass.name} must implement NodeFilter.tail instead of relying on " +
                    "the compile-time Jsoup default method"
            )
        }
    }

    @Test
    fun `kuramanime extracts only scoped hydrated hls sources`() {
        val html = """
            <div id="animeVideoPlayer" data-hls-src="//cdn.example/master.m3u8"></div>
            <div data-hls-src="https://attacker.example/outside.m3u8"></div>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/master.m3u8"),
            KuramanimeParser.playerUrls(
                html,
                "https://v19.kuramanime.ing/anime/a/episode/1"
            )
        )
    }

    @Test
    fun `kuramanime resolves hydrated playback before static fallback candidates`() = runBlocking {
        val events = mutableListOf<String>()
        var loaded = false

        val result = resolveKuramanimeCandidatesHydrationFirst(
            staticCandidates = listOf("https://player.example/static"),
            staticReferer = "https://v19.kuramanime.ing/episode/static",
            hydrate = {
                events += "hydrate"
                KuramanimeCandidateBatch(
                    urls = listOf("https://cdn.example/hydrated.m3u8"),
                    referer = "https://v19.kuramanime.ing/episode/hydrated"
                )
            },
            canContinue = { true },
            isLoaded = { loaded },
            resolve = { url, referer ->
                events += "resolve:$url:$referer"
                loaded = true
            }
        )

        assertTrue(result)
        assertEquals(
            listOf(
                "hydrate",
                "resolve:https://cdn.example/hydrated.m3u8:" +
                    "https://v19.kuramanime.ing/episode/hydrated"
            ),
            events
        )
    }

    @Test
    fun `kuramanime uses static candidates when hydration is unavailable`() = runBlocking {
        val events = mutableListOf<String>()
        var loaded = false

        val result = resolveKuramanimeCandidatesHydrationFirst(
            staticCandidates = listOf("https://player.example/static"),
            staticReferer = "https://v19.kuramanime.ing/episode/static",
            hydrate = {
                events += "hydrate"
                null
            },
            canContinue = { true },
            isLoaded = { loaded },
            resolve = { url, referer ->
                events += "resolve:$url:$referer"
                loaded = true
            }
        )

        assertTrue(result)
        assertEquals(
            listOf(
                "hydrate",
                "resolve:https://player.example/static:" +
                    "https://v19.kuramanime.ing/episode/static"
            ),
            events
        )
    }

    @Test
    fun `kuramanime stops after the first verified hydrated candidate`() = runBlocking {
        val resolved = mutableListOf<String>()
        var loaded = false

        assertTrue(
            resolveKuramanimeCandidatesHydrationFirst(
                staticCandidates = listOf("https://player.example/static"),
                staticReferer = "https://v19.kuramanime.ing/episode/static",
                hydrate = {
                    KuramanimeCandidateBatch(
                        urls = listOf(
                            "https://cdn.example/first.m3u8",
                            "https://cdn.example/second.m3u8"
                        ),
                        referer = "https://v19.kuramanime.ing/episode/hydrated"
                    )
                },
                canContinue = { true },
                isLoaded = { loaded },
                resolve = { url, _ ->
                    resolved += url
                    loaded = true
                }
            )
        )
        assertEquals(listOf("https://cdn.example/first.m3u8"), resolved)
    }

    @Test
    fun `kuramanime parses rotating token bootstrap without fixed secrets`() {
        val bootstrap = """
            function refetchJsVar(a) {
                let d = document.querySelector("#appUrl").value,
                    routeName = "rotatingRoute123";
                const script = `${'$'}{d}/assets/js/${'$'}{routeName}.js`;
            }
        """.trimIndent()
        val configUrl = KuramanimeBootstrap.configurationScriptUrl(
            bootstrap,
            "https://v19.kuramanime.ing/assets/js/arc-signal.min.js?v=169"
        )
        assertEquals(
            "https://v19.kuramanime.ing/assets/js/rotatingRoute123.js",
            configUrl
        )

        val config = assertNotNull(
            KuramanimeBootstrap.configuration(
                """
                    window.process = {
                        env: {
                            MIX_PREFIX_AUTH_ROUTE_PARAM: 'assets/',
                            MIX_AUTH_ROUTE_PARAM: 'rotating-token.txt',
                            MIX_AUTH_KEY: 'headerKey',
                            MIX_AUTH_TOKEN: 'headerToken',
                            MIX_PAGE_TOKEN_KEY: 'pageTokenKey',
                            MIX_STREAM_SERVER_KEY: 'serverKey'
                        }
                    };
                """.trimIndent(),
                configUrl!!
            )
        )
        assertEquals(
            "https://v19.kuramanime.ing/assets/rotating-token.txt",
            config.tokenUrl
        )
        assertEquals("headerKey:headerToken", config.authHeader)
        assertEquals(
            "https://v19.kuramanime.ing/anime/a/episode/1" +
                "?pageTokenKey=tokenValue&serverKey=kuramadrive&page=1",
            KuramanimeBootstrap.hydratedPageUrl(
                "https://v19.kuramanime.ing/anime/a/episode/1",
                "tokenValue",
                config
            )
        )
        assertNull(KuramanimeBootstrap.tokenValue("../internal"))
        assertNull(
            KuramanimeBootstrap.configuration(
                """
                    window.process = {
                        env: {
                            MIX_PREFIX_AUTH_ROUTE_PARAM: '../',
                            MIX_AUTH_ROUTE_PARAM: 'secret.txt',
                            MIX_AUTH_KEY: 'headerKey',
                            MIX_AUTH_TOKEN: 'headerToken',
                            MIX_PAGE_TOKEN_KEY: 'pageTokenKey',
                            MIX_STREAM_SERVER_KEY: 'serverKey'
                        }
                    };
                """.trimIndent(),
                configUrl
            )
        )
        assertNull(
            KuramanimeBootstrap.configuration(
                """
                    window.process = {
                        env: {
                            MIX_PREFIX_AUTH_ROUTE_PARAM: 'assets/',
                            MIX_PREFIX_AUTH_ROUTE_PARAM: 'shadow/',
                            MIX_AUTH_ROUTE_PARAM: 'secret.txt',
                            MIX_AUTH_KEY: 'headerKey',
                            MIX_AUTH_TOKEN: 'headerToken',
                            MIX_PAGE_TOKEN_KEY: 'pageTokenKey',
                            MIX_STREAM_SERVER_KEY: 'serverKey'
                        }
                    };
                """.trimIndent(),
                configUrl
            )
        )
    }

    @Test
    fun `moviebox v2 contract uses detail paths and bundled downloads`() {
        val baseUrl = "https://h5-api.aoneroom.com"
        val detailPath = "avatar-AbCdEf12345"
        assertEquals(
            "$baseUrl/wefeed-h5api-bff/home",
            MovieboxApi.apiUrl("$baseUrl/wefeed-h5api-bff/home", baseUrl)
        )
        assertEquals(
            "https://h5.aoneroom.com/wefeed-h5-bff/web/subject/detail?subjectId=42",
            MovieboxApi.apiUrl(
                "https://h5.aoneroom.com/wefeed-h5-bff/web/subject/detail?subjectId=42",
                baseUrl
            )
        )
        assertNull(MovieboxApi.apiUrl("https://attacker.example/private", baseUrl))
        assertNull(MovieboxApi.apiUrl("http://h5-api.aoneroom.com/plaintext", baseUrl))
        assertEquals(
            "$baseUrl/wefeed-h5api-bff/detail?detailPath=$detailPath",
            MovieboxApi.detailUrl(baseUrl, detailPath)
        )
        assertEquals(
            "$baseUrl/wefeed-h5api-bff/home?host=moviebox.ph",
            MovieboxApi.homeUrl(baseUrl)
        )
        assertEquals(
            "$baseUrl/wefeed-h5api-bff/subject/search",
            MovieboxApi.searchUrl(baseUrl)
        )
        assertEquals(
            "$baseUrl/wefeed-h5api-bff/subject/download" +
                "?subjectId=123&se=2&ep=7&detailPath=$detailPath",
            MovieboxApi.downloadUrl(baseUrl, "123", 2, 7, detailPath)
        )
        assertNull(MovieboxApi.detailUrl(baseUrl, "../internal"))
        assertEquals(
            "https://videodownloader.site/",
            MovieboxApi.apiHeaders["Referer"]
        )
        assertEquals(
            "https://videodownloader.site",
            MovieboxApi.mediaHeaders["Origin"]
        )
        assertEquals(
            """{"timezone":"Africa/Nairobi"}""",
            MovieboxApi.apiHeaders["X-Client-Info"]
        )
        assertEquals(
            MovieboxLoadData(id = "123"),
            MovieboxApi.loadData("123")
        )
        assertEquals(
            MovieboxLoadData(id = "123", detailPath = detailPath),
            MovieboxApi.loadData(
                """{"id":"123","detailPath":"$detailPath"}"""
            )
        )
        assertEquals(
            "https://h5.aoneroom.com/wefeed-h5-bff/web/subject/detail?subjectId=123",
            MovieboxApi.legacyDetailUrl("123")
        )
        assertNull(MovieboxApi.loadData("../internal"))
        assertNull(MovieboxApi.legacyDetailUrl("../internal"))
        assertEquals(
            "Bearer test-token",
            MovieboxApi.authorizationHeader("""{"token":"test-token"}""")
        )
        assertEquals(
            "Bearer abc+/==",
            MovieboxApi.authorizationHeader("""{"token":"abc+/=="}""")
        )
        assertNull(MovieboxApi.authorizationHeader("""{"token":""}"""))
        assertNull(MovieboxApi.authorizationHeader("{\"token\":\"line\\r\\nbreak\"}"))

        val response = jacksonObjectMapper().readValue(
            """
                {
                  "code": 0,
                  "message": "ok",
                  "data": {
                    "downloads": [
                      {
                        "id": "video-1",
                        "url": "https://media.example/video.mp4",
                        "resolution": 720,
                        "size": 1024
                      }
                    ],
                    "captions": [
                      {
                        "id": "caption-1",
                        "lan": "id",
                        "lanName": "Indonesian",
                        "url": "https://subtitle.example/id.vtt",
                        "size": 100,
                        "delay": 0
                      }
                    ],
                    "limited": false,
                    "limitedCode": "",
                    "hasResource": true
                  }
                }
            """.trimIndent(),
            MovieboxDownloadResponse::class.java
        )
        assertEquals(720, response.data?.downloads?.single()?.resolution)
        assertEquals("Indonesian", response.data?.captions?.single()?.languageName)
        assertEquals(true, response.data?.hasResource)
    }

    @Test
    fun `moviebox home omits coming soon items without resources`() {
        val response = jacksonObjectMapper().readValue(
            """
                {
                  "code": 0,
                  "data": {
                    "operatingList": [
                      {
                        "title": "Popular",
                        "subjects": [
                          {
                            "subjectId": "1001",
                            "subjectType": 1,
                            "title": "Ready",
                            "detailPath": "ready-AbCdEf12345",
                            "hasResource": true
                          },
                          {
                            "subjectId": "1002",
                            "subjectType": 1,
                            "title": "Segera Hadir",
                            "detailPath": "soon-AbCdEf12345",
                            "hasResource": false
                          }
                        ],
                        "banner": {
                          "items": [
                            {
                              "subject": {
                                "subjectId": "1003",
                                "subjectType": 2,
                                "title": "Playable Series",
                                "detailPath": "series-AbCdEf12345",
                                "hasResource": true
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
            """.trimIndent(),
            MovieboxHomeResponse::class.java
        )

        assertEquals(
            listOf("1001", "1003"),
            response.availableItems().mapNotNull { it.subjectId }
        )
    }

    @Test
    fun `rotating providers include verified owned domain aliases`() {
        val expectedAliases = mapOf(
            "PencurimovieProvider.kt" to listOf(
                "ww73.pencurimovie.bond",
                "pencurimovie.bond",
                "pencurimovie.sbs"
            ),
            "SarangfilmProvider.kt" to listOf(
                "sarangfilm.asia",
                "sarangfilm.uno",
                "sarangfilm.world",
                "sarangfilm.link",
                "sarangfilm21.com"
            ),
            "NomatProvider.kt" to listOf("nomat.site", "nomat.store", "nomat.asia"),
            "IndomaxProvider.kt" to listOf(
                "akses7.indomax21.xyz",
                "akses8.indomax21.xyz",
                "akses6.indomax21.xyz",
                "akses10.indomax21.xyz"
            ),
            "KawanfilmProvider.kt" to listOf(
                "tv2.kawanfilm21.co",
                "kawanfilm21.co",
                "kawanfilm21.online"
            ),
            "KuramanimeProvider.kt" to listOf(
                "v11.kuramanime.tel",
                "v17.kuramanime.ing"
            ),
            "AnimasuProvider.kt" to listOf(
                "v1.animasu.app",
                "v1.animasu.work",
                "animasu.com"
            )
        )

        expectedAliases.forEach { (fileName, aliases) ->
            val provider = source(fileName)
            aliases.forEach { alias ->
                assertTrue(provider.contains("\"$alias\""), "$fileName must trust owned alias $alias")
            }
        }
    }

    private fun assertRegistrationOrder(providerNames: List<String>) {
        val plugin = source("IndoPlugin.kt")
        val positions = providerNames.map { provider ->
            val matches = Regex(
                """(?m)^\s*registerMainAPI\($provider\(\)\)\s*$"""
            ).findAll(plugin).toList()
            assertEquals(1, matches.size, "$provider must be registered exactly once")
            matches.single().range.first
        }
        assertEquals(positions.sorted(), positions, "providers must follow popularity order")
    }

    private fun assertGroupStartsWith(
        startMarker: String,
        endMarker: String?,
        providerNames: List<String>
    ) {
        val plugin = source("IndoPlugin.kt")
        val group = plugin.substringAfter(startMarker).let { content ->
            endMarker?.let(content::substringBefore) ?: content
        }
        val registrations = Regex("""registerMainAPI\((\w+)\(\)\)""")
            .findAll(group)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            providerNames,
            registrations.take(providerNames.size),
            "new providers must lead their registration group"
        )
    }
}
