package com.example

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            "MovieboxProvider.kt" to "https://moviebox.ph",
            "PencurimovieProvider.kt" to "https://ww73.pencurimovie.bond",
            "SarangfilmProvider.kt" to "https://sarangfilm.uno",
            "NomatProvider.kt" to "https://nomat.site",
            "KawanfilmProvider.kt" to "https://tv2.kawanfilm21.co",
            "KuramanimeProvider.kt" to "https://v11.kuramanime.tel",
            "AnimasuProvider.kt" to "https://v1.animasu.app"
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
    fun `new providers use the shared bounded link resolver`() {
        val providers = listOf(
            "MovieboxProvider.kt",
            "PencurimovieProvider.kt",
            "SarangfilmProvider.kt",
            "NomatProvider.kt",
            "KawanfilmProvider.kt",
            "KuramanimeProvider.kt",
            "AnimasuProvider.kt"
        )

        providers.forEach { fileName ->
            val provider = source(fileName)
            assertEquals(
                1,
                Regex("""\bLinkResolutionSession\s*\(""").findAll(provider).count(),
                "$fileName must create one shared resolution session"
            )
            assertTrue(provider.contains("return resolver.loaded"), "$fileName must report actual playback")
            assertFalse(provider.contains("private var directUrl"), "$fileName must not share redirect state")
        }
    }

    @Test
    fun `provider expansion bumps the plugin release`() {
        val moduleBuild = File(projectRoot, "IndoProvider/build.gradle.kts").readText()
        assertTrue(Regex("""(?m)^version\s*=\s*15\s*$""").containsMatchIn(moduleBuild))
    }

    @Test
    fun `moviebox episode declarations are normalized deterministically`() {
        assertEquals(listOf(1, 2, 3), movieboxEpisodeNumbers("1,3,2,3", 12))
        assertEquals(listOf(1, 2, 3), movieboxEpisodeNumbers(null, 3))
        assertEquals(emptyList(), movieboxEpisodeNumbers(null, 0))
        assertEquals(listOf(1, 2), movieboxEpisodeNumbers("-1,0,1,2,10001", 3))
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
        val mainUrl = "https://nomat.site"
        assertEquals(
            "https://nomat.site/play/sample",
            NomatParser.playbackPageUrl("https://nomat.site/play/sample", mainUrl)
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

        val provider = source("NomatProvider.kt")
        assertTrue(provider.contains("allowRedirects = false"))
        assertTrue(provider.contains("NomatParser.redirectTarget("))
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
            AnimasuParser.playerUrls(html, "https://v1.animasu.app/episode/sample")
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
                "https://v11.kuramanime.tel/player/fallback"
            ),
            KuramanimeParser.playerUrls(html, "https://v11.kuramanime.tel/anime/a/episode/1")
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
                "https://v11.kuramanime.tel/anime/1/sample/episode/1" to "Episode 1"
            ),
            KuramanimeParser.episodeLinks(document, "https://v11.kuramanime.tel/anime/1/sample/")
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
            AnimasuParser.playerUrls(animasuHtml, "https://v1.animasu.app/episode/sample").size <= 48
        )

        val kuramaButtons = (1..80).joinToString("") { index ->
            """<div data-video="https://player.example/embed/$index"></div>"""
        }
        assertTrue(
            KuramanimeParser.playerUrls(
                """<div id="player">$kuramaButtons</div>""",
                "https://v11.kuramanime.tel/anime/a/episode/1"
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
                "https://v11.kuramanime.tel/anime/a/episode/1"
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
                "https://v11.kuramanime.tel/anime/a/episode/1"
            )
        )
        assertEquals(
            expected,
            AnimasuParser.playerUrls(
                html.replace("""id="player"""", """id="server""""),
                "https://v1.animasu.app/episode/sample"
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
    fun `redirected provider pages and ajax candidates retain trust boundaries`() {
        val pencuri = source("PencurimovieProvider.kt")
        assertTrue(pencuri.contains("providerUrl(fetch.url) ?: return null"))
        assertTrue(pencuri.contains("providerUrl(fetch.url) ?: return false"))

        listOf("SarangfilmProvider.kt", "KawanfilmProvider.kt").forEach { fileName ->
            val provider = source(fileName)
            assertTrue(provider.contains("ProviderHtmlParser.absoluteUrl(candidate, response.url)"))
            assertTrue(provider.contains("resolver.resolve(playerUrl, pageUrl)"))
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
