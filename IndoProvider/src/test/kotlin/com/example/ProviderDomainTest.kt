package com.example

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class ProviderDomainTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    private fun source(fileName: String): String = File(sourceRoot, fileName).readText()

    @Test
    fun `idlix uses requested active domain`() {
        assertTrue(
            source("IdlixProvider.kt").contains("""override var mainUrl = "https://z2.idlixku.com""""),
            "IdlixProvider mainUrl should use https://z2.idlixku.com"
        )
    }

    @Test
    fun `new movie providers use requested domains`() {
        val expectedDomains = mapOf(
            "IndoxxiProvider.kt" to """override var mainUrl = "https://comblank.com"""",
            "FilmapikProvider.kt" to """override var mainUrl = "https://filmapik.fitness"""",
            "IndofilmProvider.kt" to """override var mainUrl = "https://yuhhaber.com"""",
            "KitanontonProvider.kt" to """override var mainUrl = "https://kitanonton.com""""
        )

        expectedDomains.forEach { (fileName, expected) ->
            assertTrue(source(fileName).contains(expected), "$fileName should contain $expected")
        }
    }

    @Test
    fun `new anime providers use requested domains`() {
        val expectedDomains = mapOf(
            "AnimeindoProvider.kt" to """override var mainUrl = "https://anime-indo.lol"""",
            "OploverzProvider.kt" to """override var mainUrl = "https://plus.oploverz.ltd"""",
            "ZoronimeProvider.kt" to """override var mainUrl = "https://zoronime.live"""",
            "MiranimeProvider.kt" to """override var mainUrl = "https://miranime.net""""
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
            "registerMainAPI(IndofilmProvider())",
            "registerMainAPI(AnimeindoProvider())",
            "registerMainAPI(OploverzProvider())",
            "registerMainAPI(ZoronimeProvider())",
            "registerMainAPI(MiranimeProvider())"
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
            "RebahinProvider.kt",
            "GomovProvider.kt",
            "IdlixProvider.kt",
            "KitanontonProvider.kt",
            "FilmapikProvider.kt"
        )

        providers.forEach { fileName ->
            val source = source(fileName)
            assertTrue(source.contains("LinkResolutionSession("), "$fileName should create one shared session")
            assertTrue(!source.contains("loadExtractorWithResult("), "$fileName should not bypass the shared resolver")
        }
    }

    @Test
    fun `plugin keeps every provider registered`() {
        val plugin = source("IndoPlugin.kt")
        val expected = listOf(
            "LayarKacaProvider", "NgefilmProvider", "PusatfilmProvider", "DutamovieProvider",
            "RebahinProvider", "CgvindoProvider", "KitanontonProvider", "GomovProvider",
            "IdlixProvider", "JuraganFilmProvider", "IndoxxiProvider", "FilmapikProvider",
            "IndofilmProvider", "OtakudesuProvider", "SamehadakuProvider", "AnoboyProvider",
            "KuronimeProvider", "AnimeindoProvider", "OploverzProvider", "ZoronimeProvider",
            "MiranimeProvider"
        )

        expected.forEach { provider ->
            assertTrue(plugin.contains("registerMainAPI($provider())"), "$provider must remain visible")
        }
    }

    @Test
    fun `provider fetch fallbacks do not swallow cancellation`() {
        val providers = listOf(
            "NgefilmProvider.kt",
            "DutamovieProvider.kt",
            "PusatfilmProvider.kt",
            "RebahinProvider.kt",
            "GomovProvider.kt",
            "IdlixProvider.kt",
            "KitanontonProvider.kt",
            "FilmapikProvider.kt"
        )
        val caughtSuspendFetch = Regex("""runCatching\s*\{\s*app\.(get|post)""")

        providers.forEach { fileName ->
            assertTrue(
                !caughtSuspendFetch.containsMatchIn(source(fileName)),
                "$fileName should rethrow cancellation from caught fetches"
            )
        }
    }
}
