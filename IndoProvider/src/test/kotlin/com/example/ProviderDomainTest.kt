package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class ProviderDomainTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    private fun source(fileName: String): String = File(sourceRoot, fileName).readText()

    @Test
    fun `new movie providers use requested domains`() {
        val expectedDomains = mapOf(
            "LayarKacaProvider.kt" to """override var mainUrl = "https://tv.nontonfilm.red"""",
            "NgefilmProvider.kt" to """override var mainUrl = "https://new38.ngefilm.site"""",
            "PusatfilmProvider.kt" to """override var mainUrl = "https://v4.pusatfilm21info.com"""",
            "DutamovieProvider.kt" to """override var mainUrl = "https://austincomputerworks.org"""",
            "IndoxxiProvider.kt" to """override var mainUrl = "https://filmbioskop21.lk21.in.net"""",
            "FilmapikProvider.kt" to """override var mainUrl = "https://filmapik.college"""",
            "KitanontonProvider.kt" to """override var mainUrl = "https://kitanonton2.surf"""",
            "RebahinProvider.kt" to """override var mainUrl = "https://rebahinxxi3.lol""""
        )

        expectedDomains.forEach { (fileName, expected) ->
            assertTrue(source(fileName).contains(expected), "$fileName should contain $expected")
        }
    }

    @Test
    fun `new anime providers use requested domains`() {
        val expectedDomains = mapOf(
            "AnimeindoProvider.kt" to """override var mainUrl = "https://anime-indo.lol"""",
            "OploverzProvider.kt" to """override var mainUrl = "https://oploverz.org"""",
            "ZoronimeProvider.kt" to """override var mainUrl = "https://zoronime.live""""
        )

        expectedDomains.forEach { (fileName, expected) ->
            assertTrue(source(fileName).contains(expected), "$fileName should contain $expected")
        }
    }

    @Test
    fun `plugin registers new providers`() {
        val plugin = source("IndoPlugin.kt")
        val expectedRegistrations = listOf(
            "registerMainAPI(IndoxxiProvider())",
            "registerMainAPI(FilmapikProvider())",
            "registerMainAPI(RebahinProvider())",
            "registerMainAPI(AnimeindoProvider())",
            "registerMainAPI(OploverzProvider())",
            "registerMainAPI(ZoronimeProvider())"
        )

        expectedRegistrations.forEach { expected ->
            assertTrue(plugin.contains(expected), "IndoPlugin.kt should contain $expected")
        }
    }

    @Test
    fun `movie providers use one shared resolution session`() {
        val providers = listOf(
            "NgefilmProvider.kt",
            "DutamovieProvider.kt",
            "PusatfilmProvider.kt",
            "KitanontonProvider.kt",
            "FilmapikProvider.kt",
            "RebahinProvider.kt"
        )

        val sessionCreation = Regex("""\bLinkResolutionSession\s*\(""")
        val legacyCalls = listOf("loadExtractorWithResult", "loadResolvedExtractorWithResult")
        providers.forEach { fileName ->
            val source = source(fileName)
            assertEquals(
                1,
                sessionCreation.findAll(source).count(),
                "$fileName should create exactly one shared session"
            )
            legacyCalls.forEach { legacyCall ->
                assertFalse(
                    Regex("""\b$legacyCall\s*\(""").containsMatchIn(source),
                    "$fileName should not call $legacyCall"
                )
            }
        }
    }

    @Test
    fun `plugin keeps every provider registered`() {
        val plugin = source("IndoPlugin.kt")
        val expected = listOf(
            "LayarKacaProvider", "NgefilmProvider", "DutamovieProvider",
            "KitanontonProvider", "IndoxxiProvider", "FilmapikProvider", "RebahinProvider",
            "OtakudesuProvider", "SamehadakuProvider", "AnoboyProvider",
            "KuronimeProvider", "AnimeindoProvider", "OploverzProvider", "ZoronimeProvider"
        )

        expected.forEach { provider ->
            assertTrue(plugin.contains("registerMainAPI($provider())"), "$provider must remain visible")
        }
    }

    @Test
    fun `unhealthy sohib21 aliases are not registered as independent providers`() {
        val plugin = source("IndoPlugin.kt")
        val cloneProviders = listOf(
            "CgvindoProvider",
            "IndofilmProvider",
            "JuraganFilmProvider"
        )

        cloneProviders.forEach { provider ->
            assertFalse(
                plugin.contains("registerMainAPI($provider())"),
                "$provider must stay disabled while it exposes the shared invalid Sohib21 catalog"
            )
        }
    }

    @Test
    fun `pusatfilm stays disabled while its only upstream has no files`() {
        val plugin = source("IndoPlugin.kt")
        assertFalse(
            plugin.contains("registerMainAPI(PusatfilmProvider())"),
            "Pusatfilm must stay disabled while every Kotakajaib file returns File Error"
        )
    }

    @Test
    fun `rotating movie providers use current catalog routes without shared redirect state`() {
        val ngefilm = source("NgefilmProvider.kt")
        val dutamovie = source("DutamovieProvider.kt")

        assertTrue(ngefilm.contains("\"year/2026/page/%d/\" to \"Terbaru\""))
        assertFalse(ngefilm.contains("private var directUrl"))
        assertTrue(dutamovie.contains("\"box-office/page/%d/\" to \"Box Office\""))
        assertTrue(dutamovie.contains("\"serial-tv/page/%d/\" to \"TV Series\""))
        assertFalse(dutamovie.contains("category/box-office"))
        assertFalse(dutamovie.contains("category/serial-tv"))
        assertFalse(dutamovie.contains("private var directUrl"))
    }

    @Test
    fun `miranime is removed from source and registration`() {
        assertFalse(File(sourceRoot, "MiranimeProvider.kt").exists())
        assertFalse(source("IndoPlugin.kt").contains("MiranimeProvider"))
    }

    @Test
    fun `idlix is removed because no non-browser playback endpoint is available`() {
        assertFalse(File(sourceRoot, "IdlixProvider.kt").exists())
        assertFalse(source("IndoPlugin.kt").contains("IdlixProvider"))
    }

    @Test
    fun `gomov is removed because its upstream has no streaming player`() {
        assertFalse(File(sourceRoot, "GomovProvider.kt").exists())
        assertFalse(source("IndoPlugin.kt").contains("GomovProvider"))
    }

    @Test
    fun `fixed providers no longer use unrelated provider aliases`() {
        val independentProviders = listOf(
            "AnimeindoProvider.kt",
            "IndofilmProvider.kt",
            "IndoxxiProvider.kt",
            "LayarKacaProvider.kt"
        )

        independentProviders.forEach { fileName ->
            val provider = source(fileName)
            assertFalse(provider.contains(": RebahinProvider()"), "$fileName must parse its own site")
            assertFalse(provider.contains(": KuronimeProvider()"), "$fileName must parse its own site")
        }
    }

    @Test
    fun `rebahin never rewrites an arbitrary foreign catalog onto its own host`() {
        val provider = source("RebahinProvider.kt")
        assertFalse(provider.contains("normalizeUrlHost"))
        assertTrue(provider.contains("normalizeProviderPageUrl"))
    }

    @Test
    fun `rebahin family builds main pages from each provider host`() {
        listOf(CgvindoProvider(), JuraganFilmProvider()).forEach { provider ->
            assertTrue(
                provider.mainPage.all { request -> request.data.startsWith(provider.mainUrl) },
                "${provider.name} main-page requests must stay on ${provider.mainUrl}"
            )
        }
    }

    @Test
    fun `plugin version is bumped for provider fixes`() {
        val moduleBuild = listOf(
            File("build.gradle.kts"),
            File("IndoProvider/build.gradle.kts")
        ).first { file -> file.exists() && file.readText().contains("cloudstream") }

        assertTrue(
            Regex("""(?m)^version\s*=\s*8\s*$""").containsMatchIn(moduleBuild.readText()),
            "Cloudstream must see these provider fixes as a new plugin release"
        )
    }

    @Test
    fun `per candidate provider fetches rethrow cancellation and skip ordinary failures`() {
        val providerFetches = mapOf(
            "NgefilmProvider.kt" to listOf(
                """app\s*\.\s*get\s*\(""",
                """app\s*\.\s*post\s*\("""
            ),
            "DutamovieProvider.kt" to listOf(
                """app\s*\.\s*get\s*\(""",
                """app\s*\.\s*post\s*\("""
            )
        )

        providerFetches.forEach { (fileName, perCandidateFetches) ->
            val source = source(fileName)
            perCandidateFetches.forEach { fetchPattern ->
                val cancellationSafeFetch = Regex(
                    """(?s)try\s*\{(?:(?!catch\s*\().)*?$fetchPattern(?:(?!catch\s*\().)*?}\s*catch\s*\(\s*(\w+)\s*:\s*CancellationException\s*\)\s*\{\s*throw\s+\1\s*}\s*catch\s*\(\s*_\s*:\s*Exception\s*\)\s*\{"""
                )
                assertTrue(
                    cancellationSafeFetch.containsMatchIn(source),
                    "$fileName should isolate $fetchPattern with cancellation-safe catches"
                )
            }
        }
    }

    @Test
    fun `Pusatfilm iterates every matching iframe`() {
        val loadLinksSource = source("PusatfilmProvider.kt")
            .substringAfter("override suspend fun loadLinks")
        val allIframeIteration = Regex(
            """(?s)document\s*\.\s*select\s*\(\s*"[^"]*iframe[^"]*"\s*\)(?:(?!selectFirst)[\s\S])*?\.\s*forEach\s*\{"""
        )

        assertTrue(
            allIframeIteration.containsMatchIn(loadLinksSource),
            "PusatfilmProvider should iterate the complete iframe selection"
        )
        assertFalse(
            Regex("""\bselectFirst\s*\(""").containsMatchIn(loadLinksSource),
            "PusatfilmProvider loadLinks should not stop at the first iframe"
        )
    }

    @Test
    fun `filmapik reports success only after resolver emits a safe link`() {
        val provider = source("FilmapikProvider.kt")
        assertTrue(provider.contains("return resolver.loaded"))
        assertFalse(provider.contains("resolver.loaded || directUrls.isNotEmpty()"))
    }
}
