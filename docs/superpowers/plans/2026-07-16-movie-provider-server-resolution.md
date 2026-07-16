# Movie Provider Server Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep every provider visible while resolving direct media, AsiaStream, PlaySobat, upstream extractors, and one nested player page through one failure-tolerant pipeline.

**Architecture:** A per-request `LinkResolutionSession` in `ProviderLinkLoader.kt` owns visited candidates, emitted links, callbacks, and bounded recursion. Pure URL, interstitial, and AsiaStream parsing stays in focused helper functions; movie providers only collect candidates and submit them to the session.

**Tech Stack:** Kotlin 2.1, Cloudstream pre-release API, NiceHttp, Jsoup 1.18.3, Jackson 2.13.1, Kotlin/JUnit tests, Gradle 8.12, Android SDK 35.

## Global Constraints

- Keep all existing `IndoPlugin` provider registrations unchanged.
- Do not bypass ISP filtering, Cloudflare challenges, or access controls.
- Maximum generic iframe recursion depth is exactly `1` from the original provider candidate.
- Direct media detection uses the case-insensitive URI path and supports `.m3u8` and `.mp4` with query strings.
- Never swallow `kotlin.coroutines.cancellation.CancellationException`.
- Actual callback emission, not the upstream extractor Boolean, defines success.
- Add no new dependencies and do not change unrelated catalog, search, or metadata behavior.
- On this Windows workspace, set `ANDROID_HOME=C:\Users\ahmad\AppData\Local\Android\Sdk` for Gradle verification commands.
- Do not stage or commit `graphify-out/`.

---

## File Structure

- Modify `IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt`: URL classification, `LinkResolutionSession`, direct link emission, known adapters, upstream extraction, and bounded nested-page fallback.
- Modify `IndoProvider/src/main/kotlin/com/example/InlineDataParser.kt`: pure AsiaStream `sniff(...)` parser and master-playlist URL construction.
- Modify `IndoProvider/src/main/kotlin/com/example/ProviderHtmlParser.kt`: pure interstitial/upstream-error page detection.
- Modify `IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt`: parser and resolver regression fixtures.
- Modify `IndoProvider/src/test/kotlin/com/example/ProviderDomainTest.kt`: complete registration and movie-provider migration assertions.
- Modify `IndoProvider/src/main/kotlin/com/example/{Ngefilm,Dutamovie,Pusatfilm,Rebahin,Gomov,Idlix,Kitanonton,Filmapik}Provider.kt`: submit collected candidates through one session per `loadLinks()` invocation.

---

### Task 1: Pure Source Classification and Page Parsing

**Files:**
- Modify: `IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/InlineDataParser.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/ProviderHtmlParser.kt`

**Interfaces:**
- Produces: `directMediaType(url: String): ExtractorLinkType?`
- Produces: `InlineDataParser.asiaStreamMasterUrl(html: String, playerUrl: String): String?`
- Produces: `ProviderHtmlParser.isNonContentPage(html: String): Boolean`

- [ ] **Step 1: Write failing pure-parser tests**

Add the import and tests below to `ProviderHtmlParserTest.kt`:

```kotlin
import com.lagradost.cloudstream3.utils.ExtractorLinkType

@Test
fun `directMediaType reads media extension from uri path`() {
    assertEquals(
        ExtractorLinkType.M3U8,
        directMediaType("https://cdn.example/video/MASTER.M3U8?token=abc")
    )
    assertEquals(
        ExtractorLinkType.VIDEO,
        directMediaType("https://cdn.example/video/movie.mp4?download=1")
    )
    assertNull(directMediaType("https://player.example/embed/123"))
}

@Test
fun `asiaStreamMasterUrl parses live sniff configuration`() {
    val html = """
        <script>
            sniff("K8OFQSVM","7","51b7dae1031b20174cacc7e69d6e4bf0",null,
                [{"label":"","file":"/thumbnails.vtt","kind":"thumbnails"}],1,1,false);
        </script>
    """.trimIndent()

    assertEquals(
        "https://watch.asiastream.cc/m3u8/7/51b7dae1031b20174cacc7e69d6e4bf0/master.txt?s=1&cache=1",
        InlineDataParser.asiaStreamMasterUrl(html, "https://watch.asiastream.cc/watch?v=K8OFQSVM")
    )
}

@Test
fun `asiaStreamMasterUrl rejects malformed and cross scheme player urls`() {
    val html = """sniff("slug","7","hash",null,[],1,1,false);"""

    assertNull(InlineDataParser.asiaStreamMasterUrl("no player config", "https://watch.asiastream.cc/watch?v=x"))
    assertNull(InlineDataParser.asiaStreamMasterUrl(html, "javascript:alert(1)"))
}

@Test
fun `isNonContentPage recognizes upstream interstitials and errors`() {
    assertTrue(ProviderHtmlParser.isNonContentPage("<title>Internet Positif</title>"))
    assertTrue(ProviderHtmlParser.isNonContentPage("<title>Just a moment...</title><script src='https://challenges.cloudflare.com/x'></script>"))
    assertTrue(ProviderHtmlParser.isNonContentPage("SQLSTATE[HY000] [2006] MySQL server has gone away"))
    assertTrue(ProviderHtmlParser.isNonContentPage("   "))
    assertFalse(ProviderHtmlParser.isNonContentPage("<iframe src='https://video.example/embed'></iframe>"))
}
```

Also add `assertFalse` and `assertTrue` imports from `kotlin.test` if they are not already present.

- [ ] **Step 2: Run the parser tests and verify RED**

Run:

```powershell
$env:ANDROID_HOME='C:\Users\ahmad\AppData\Local\Android\Sdk'
.\gradlew.bat :IndoProvider:testDebugUnitTest --tests com.example.ProviderHtmlParserTest --no-daemon
```

Expected: compilation fails because `directMediaType`, `asiaStreamMasterUrl`, and `isNonContentPage` do not exist.

- [ ] **Step 3: Implement direct-media classification**

Add the import and function to `ProviderLinkLoader.kt`:

```kotlin
import com.lagradost.cloudstream3.utils.ExtractorLinkType

internal fun directMediaType(url: String): ExtractorLinkType? {
    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrNull() ?: return null
    return when {
        path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
        path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
        else -> null
    }
}
```

- [ ] **Step 4: Implement AsiaStream parsing**

Add this constant and function inside `InlineDataParser`:

```kotlin
private val asiaStreamSniffRegex = Regex(
    """(?s)sniff\(\s*"[^"]*"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(?:null|"[^"]*")\s*,\s*\[.*?]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*(?:true|false)\s*\)"""
)

fun asiaStreamMasterUrl(html: String, playerUrl: String): String? {
    val match = asiaStreamSniffRegex.find(html) ?: return null
    val uid = match.groupValues[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
    val hash = match.groupValues[2].takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
    val cache = match.groupValues[3]
    return runCatching {
        val player = java.net.URI(playerUrl)
        if (player.scheme !in setOf("http", "https") || player.host.isNullOrBlank()) return null
        java.net.URI(
            player.scheme,
            player.authority,
            "/m3u8/$uid/$hash/master.txt",
            "s=1&cache=$cache",
            null
        ).toString()
    }.getOrNull()
}
```

- [ ] **Step 5: Implement non-content detection**

Add this function inside `ProviderHtmlParser`:

```kotlin
fun isNonContentPage(html: String): Boolean {
    val normalized = html.trim().lowercase()
    if (normalized.isEmpty()) return true
    return listOf(
        "<title>internet positif</title>",
        "<title>just a moment...</title>",
        "challenges.cloudflare.com",
        "enable javascript and cookies to continue",
        "mysql server has gone away",
        "sqlstate[hy000] [2006]"
    ).any(normalized::contains)
}
```

- [ ] **Step 6: Run the parser tests and verify GREEN**

Run the same targeted Gradle command from Step 2.

Expected: `ProviderHtmlParserTest` passes with zero failures.

- [ ] **Step 7: Commit the pure parsing layer**

```powershell
git add -- IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt IndoProvider/src/main/kotlin/com/example/InlineDataParser.kt IndoProvider/src/main/kotlin/com/example/ProviderHtmlParser.kt IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt
git commit -m "Add movie source parsing primitives"
```

---

### Task 2: Per-Request Resolution Session and Direct Links

**Files:**
- Modify: `IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt`

**Interfaces:**
- Consumes: `directMediaType(url: String): ExtractorLinkType?`
- Produces: `LinkResolutionSession(api, subtitleCallback, callback, pageFetcher, extractorLoader, maxDepth)`
- Produces: `suspend fun LinkResolutionSession.resolve(raw: String?, referer: String?): Boolean`
- Produces: `val LinkResolutionSession.loaded: Boolean`
- Preserves: `MainAPI.loadResolvedExtractorWithResult(...)` as a one-candidate compatibility wrapper.

- [ ] **Step 1: Write failing direct-link and continuation tests**

Add these imports and tests to `ProviderHtmlParserTest.kt`:

```kotlin
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

@Test
fun `resolution session emits direct hls only once`() = runBlocking {
    val links = mutableListOf<ExtractorLink>()
    val session = LinkResolutionSession(
        api = RebahinProvider(),
        subtitleCallback = {},
        callback = links::add,
        pageFetcher = { _, _ -> error("direct media must not fetch a player page") },
        extractorLoader = { _, _, _, _ -> false }
    )

    assertTrue(session.resolve("https://cdn.example/master.m3u8?token=abc", "https://provider.example/item"))
    assertFalse(session.resolve("https://cdn.example/master.m3u8?token=abc", "https://provider.example/item"))
    assertTrue(session.loaded)
    assertEquals(1, links.size)
    assertEquals(ExtractorLinkType.M3U8, links.single().type)
    assertEquals("https://provider.example/item", links.single().referer)
}

@Test
fun `failed candidate does not prevent a later direct candidate`() = runBlocking {
    val links = mutableListOf<ExtractorLink>()
    val session = LinkResolutionSession(
        api = RebahinProvider(),
        subtitleCallback = {},
        callback = links::add,
        pageFetcher = { _, _ -> "<title>Internet Positif</title>" },
        extractorLoader = { _, _, _, _ -> false }
    )

    assertFalse(session.resolve("https://unsupported.example/embed", "https://provider.example/item"))
    assertTrue(session.resolve("https://cdn.example/movie.mp4", "https://provider.example/item"))
    assertEquals(listOf("https://cdn.example/movie.mp4"), links.map { it.url })
}

@Test
fun `resolution session rethrows cancellation`() {
    val session = LinkResolutionSession(
        api = RebahinProvider(),
        subtitleCallback = {},
        callback = {},
        pageFetcher = { _, _ -> "" },
        extractorLoader = { _, _, _, _ -> throw CancellationException("cancelled") }
    )

    assertFailsWith<CancellationException> {
        runBlocking { session.resolve("https://unsupported.example/embed", null) }
    }
}
```

Add imports for `ExtractorLink`, `assertFalse`, `assertTrue`, and `runBlocking` as required.

- [ ] **Step 2: Run the new tests and verify RED**

Run the targeted `ProviderHtmlParserTest` Gradle command.

Expected: compilation fails because `LinkResolutionSession` does not exist.

- [ ] **Step 3: Add injectable resolver types and session state**

Replace the one-off resolution implementation in `ProviderLinkLoader.kt` with the following types and class skeleton, retaining `toPlayableUrl` and `loadExtractorWithResult`:

```kotlin
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.coroutines.cancellation.CancellationException

internal typealias PlayerPageFetcher = suspend (url: String, referer: String?) -> String
internal typealias CloudstreamExtractorLoader = suspend (
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) -> Boolean

internal class LinkResolutionSession(
    private val api: MainAPI,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
    private val pageFetcher: PlayerPageFetcher = { url, referer -> app.get(url, referer = referer).text },
    private val extractorLoader: CloudstreamExtractorLoader = ::loadExtractorWithResult,
    private val maxDepth: Int = 1
) {
    private val visitedCandidates = mutableSetOf<String>()
    private val emittedUrls = mutableSetOf<String>()

    val loaded: Boolean get() = emittedUrls.isNotEmpty()

    suspend fun resolve(raw: String?, referer: String?): Boolean {
        val before = emittedUrls.size
        val url = api.toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
        if (!visitedCandidates.add(url)) return false

        try {
            val mediaType = directMediaType(url)
            if (mediaType != null) {
                emitDirect(url, referer, mediaType)
            } else {
                extractorLoader(url, referer, subtitleCallback, ::emit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Candidate failure is isolated; later candidates must still run.
        }
        return emittedUrls.size > before
    }

    private suspend fun emitDirect(url: String, referer: String?, type: ExtractorLinkType) {
        emit(newExtractorLink(api.name, api.name, url, type) {
            this.referer = referer.orEmpty()
            quality = Qualities.Unknown.value
            headers = referer?.let { mapOf("Referer" to it) }.orEmpty()
        })
    }

    private fun emit(link: ExtractorLink) {
        if (link.url.isNotBlank() && emittedUrls.add(link.url)) callback(link)
    }
}
```

- [ ] **Step 4: Preserve the compatibility wrapper**

Implement `loadResolvedExtractorWithResult` as:

```kotlin
internal suspend fun MainAPI.loadResolvedExtractorWithResult(
    raw: String?,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return LinkResolutionSession(this, subtitleCallback, callback).resolve(raw, referer)
}
```

Keep `isTrailerUrl()` in the same file and make it accessible to the session.

- [ ] **Step 5: Run targeted tests and verify GREEN**

Run the targeted `ProviderHtmlParserTest` Gradle command.

Expected: all direct-link, continuation, cancellation, and existing parser tests pass.

- [ ] **Step 6: Commit the session foundation**

```powershell
git add -- IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt
git commit -m "Add per-request link resolution session"
```

---

### Task 3: Known Adapters and Bounded Nested-Page Fallback

**Files:**
- Modify: `IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt`

**Interfaces:**
- Consumes: `InlineDataParser.asiaStreamMasterUrl(...)`
- Consumes: `ProviderHtmlParser.isNonContentPage(...)`
- Extends: `LinkResolutionSession.resolve(...)` with PlaySobat, AsiaStream, and one-level nested resolution.

- [ ] **Step 1: Write failing adapter and nested-page tests**

Add these tests:

```kotlin
@Test
fun `resolution session converts AsiaStream watch page to hls`() = runBlocking {
    val links = mutableListOf<ExtractorLink>()
    val session = LinkResolutionSession(
        api = RebahinProvider(),
        subtitleCallback = {},
        callback = links::add,
        pageFetcher = { _, _ ->
            """<script>sniff("slug","7","51b7dae1031b20174cacc7e69d6e4bf0",null,[],1,1,false);</script>"""
        },
        extractorLoader = { _, _, _, _ -> false }
    )

    assertTrue(session.resolve("https://watch.asiastream.cc/watch?v=K8OFQSVM", "https://provider.example/item"))
    assertEquals(
        "https://watch.asiastream.cc/m3u8/7/51b7dae1031b20174cacc7e69d6e4bf0/master.txt?s=1&cache=1",
        links.single().url
    )
    assertEquals(ExtractorLinkType.M3U8, links.single().type)
    assertEquals("https://watch.asiastream.cc/watch?v=K8OFQSVM", links.single().referer)
}

@Test
fun `resolution session follows one relative nested iframe`() = runBlocking {
    val links = mutableListOf<ExtractorLink>()
    val session = LinkResolutionSession(
        api = RebahinProvider(),
        subtitleCallback = {},
        callback = links::add,
        pageFetcher = { _, _ -> "<iframe src='/media/master.m3u8?token=abc'></iframe>" },
        extractorLoader = { _, _, _, _ -> false }
    )

    assertTrue(session.resolve("https://player.example/embed/1", "https://provider.example/item"))
    assertEquals("https://player.example/media/master.m3u8?token=abc", links.single().url)
}

@Test
fun `resolution session bounds iframe cycles`() = runBlocking {
    var fetches = 0
    val session = LinkResolutionSession(
        api = RebahinProvider(),
        subtitleCallback = {},
        callback = {},
        pageFetcher = { url, _ ->
            fetches++
            if (url.endsWith("/a")) "<iframe src='/b'></iframe>" else "<iframe src='/a'></iframe>"
        },
        extractorLoader = { _, _, _, _ -> false },
        maxDepth = 1
    )

    assertFalse(session.resolve("https://player.example/a", null))
    assertEquals(1, fetches)
}
```

- [ ] **Step 2: Run targeted tests and verify RED**

Run the targeted `ProviderHtmlParserTest` command.

Expected: AsiaStream emits nothing and nested iframe tests fail.

- [ ] **Step 3: Implement layered resolution**

Add `org.jsoup.Jsoup` import and replace the session's `resolve` body with a public wrapper plus this internal resolver:

```kotlin
suspend fun resolve(raw: String?, referer: String?): Boolean {
    val before = emittedUrls.size
    val url = api.toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
    resolveCandidate(url, referer, depth = 0)
    return emittedUrls.size > before
}

private suspend fun resolveCandidate(url: String, referer: String?, depth: Int) {
    if (depth > maxDepth || !visitedCandidates.add(url)) return
    try {
        directMediaType(url)?.let { type ->
            emitDirect(url, referer, type)
            return
        }

        val host = URI(url).host.orEmpty().lowercase()
        if (host == "playsobat.xyz" || host.endsWith(".playsobat.xyz")) {
            val html = pageFetcher(url, referer)
            if (ProviderHtmlParser.isNonContentPage(html)) return
            InlineDataParser.playSobatUrls(html).forEach { nested ->
                api.toPlayableUrl(nested)?.let { resolveCandidate(it, url, depth + 1) }
            }
            return
        }

        if (host == "asiastream.cc" || host.endsWith(".asiastream.cc")) {
            val html = pageFetcher(url, referer)
            if (ProviderHtmlParser.isNonContentPage(html)) return
            InlineDataParser.asiaStreamMasterUrl(html, url)?.let { master ->
                emitDirect(master, url, ExtractorLinkType.M3U8)
            }
            return
        }

        val beforeExtractor = emittedUrls.size
        extractorLoader(url, referer, subtitleCallback, ::emit)
        if (emittedUrls.size > beforeExtractor || depth >= maxDepth) return

        val html = pageFetcher(url, referer)
        if (ProviderHtmlParser.isNonContentPage(html)) return
        val document = Jsoup.parse(html, url)
        ProviderHtmlParser.mediaSources(document).forEach { nested ->
            ProviderHtmlParser.absoluteUrl(nested, url)?.let {
                resolveCandidate(it, url, depth + 1)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Continue with other top-level candidates.
    }
}
```

The `depth >= maxDepth` guard applies only to generic HTML expansion. Known adapters and upstream extractors may still resolve the current depth, but cannot create an unbounded generic traversal.

- [ ] **Step 4: Run targeted tests and verify GREEN**

Run the targeted `ProviderHtmlParserTest` command.

Expected: all adapter, nested-page, cycle, direct-link, and parser tests pass.

- [ ] **Step 5: Commit layered adapters**

```powershell
git add -- IndoProvider/src/main/kotlin/com/example/ProviderLinkLoader.kt IndoProvider/src/test/kotlin/com/example/ProviderHtmlParserTest.kt
git commit -m "Resolve AsiaStream and nested player sources"
```

---

### Task 4: Migrate Movie Providers Without Hiding Registrations

**Files:**
- Modify: `IndoProvider/src/test/kotlin/com/example/ProviderDomainTest.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/NgefilmProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/DutamovieProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/PusatfilmProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/RebahinProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/GomovProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/IdlixProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/KitanontonProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/FilmapikProvider.kt`

**Interfaces:**
- Consumes: one `LinkResolutionSession` per `loadLinks()` invocation.
- Preserves: all current `IndoPlugin` registrations and provider-specific candidate collection.

- [ ] **Step 1: Write failing migration and registration tests**

Add these tests to `ProviderDomainTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run `ProviderDomainTest` and verify RED**

```powershell
$env:ANDROID_HOME='C:\Users\ahmad\AppData\Local\Android\Sdk'
.\gradlew.bat :IndoProvider:testDebugUnitTest --tests com.example.ProviderDomainTest --no-daemon
```

Expected: the migration test fails for providers that do not create a session; the complete registration test passes.

- [ ] **Step 3: Migrate API/list based providers**

In `IdlixProvider.kt`, `KitanontonProvider.kt`, and `FilmapikProvider.kt`, use these exact candidate blocks:

```kotlin
// IdlixProvider.kt
val resolver = LinkResolutionSession(this, subtitleCallback, callback)
IdlixApiParser.playableUrls(playInfo).forEach { raw ->
    resolver.resolve(raw, mainUrl)
}
return resolver.loaded

// KitanontonProvider.kt
val resolver = LinkResolutionSession(this, subtitleCallback, callback)
document.select(".entry-content a[href], article a[href]").forEach { link ->
    val href = link.attr("href").trim()
    if (href.isBlank() || href.contains("kitanonton.com") || href.startsWith("#")) return@forEach
    resolver.resolve(href, data)
}
return resolver.loaded

// FilmapikProvider.kt
val resolver = LinkResolutionSession(this, subtitleCallback, callback)
servers.forEach { raw -> resolver.resolve(raw, data) }
return resolver.loaded
```

Replace Idlix's `runCatching` fetch with explicit cancellation-safe handling:

```kotlin
val playInfo = try {
    app.get("$apiUrl/watch/play-info/${streamData.contentType}/${streamData.contentId}", headers = apiHeaders).text
} catch (error: kotlin.coroutines.cancellation.CancellationException) {
    throw error
} catch (_: Exception) {
    return false
}
```

- [ ] **Step 4: Migrate Muvipro providers**

For `GomovProvider.kt`, `NgefilmProvider.kt`, and `DutamovieProvider.kt`, replace `var loaded = false` with:

```kotlin
val resolver = LinkResolutionSession(this, subtitleCallback, callback)
```

Replace the calls with these exact session submissions while preserving their surrounding loops and fetches:

```kotlin
// GomovProvider.kt
resolver.resolve(src, "$baseUrl/")
resolver.resolve(iframe, "$baseUrl/")
resolver.resolve(server, "$baseUrl/")

// NgefilmProvider.kt
resolver.resolve(iframe, "$baseUrl/")
resolver.resolve(server, "$baseUrl/")

// DutamovieProvider.kt
resolver.resolve(iframe, "$baseUrl/")
resolver.resolve(server, "$baseUrl/")
```

Also remove intermediate `toPlayableUrl(...)` calls from Ngefilm and Dutamovie so the session performs normalization. End each function with:

```kotlin
return resolver.loaded
```

- [ ] **Step 5: Migrate Rebahin and Pusatfilm**

In `RebahinProvider.loadLinks`, create one session and submit each direct, AJAX, and player-tab candidate:

```kotlin
val resolver = LinkResolutionSession(this, subtitleCallback, callback)

ProviderHtmlParser.mediaSources(document, "iframe, div.gmr-embed-responsive iframe").forEach { src ->
    resolver.resolve(src, data)
}

ProviderHtmlParser.muviproAjaxRequests(document).forEach { request ->
    try {
        val iframe = app.post(
            "$directUrl/wp-admin/admin-ajax.php",
            data = request.toPostData(),
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document.selectFirst("iframe")?.let { ProviderHtmlParser.firstIframeSource(it) }
        resolver.resolve(iframe, "$directUrl/")
    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
        throw error
    } catch (_: Exception) {
    }
}

document.select("ul#player-list > li a, ul.muvipro-player-tabs li a").forEach { link ->
    val href = link.attr("href")
    if (href.isNotBlank()) {
        try {
            val playerUrl = fixProviderUrl(href) ?: return@forEach
            val iframe = app.get(playerUrl).document.selectFirst("iframe")
                ?.let { ProviderHtmlParser.firstIframeSource(it) }
            resolver.resolve(iframe, data)
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
return resolver.loaded
```

In `PusatfilmProvider.loadLinks`, keep its candidate and referer calculation but submit through a session:

```kotlin
val resolver = LinkResolutionSession(this, subtitleCallback, callback)
if (!iframe.isNullOrBlank()) {
    val refererBase = runCatching { getBaseUrl(iframe) }.getOrDefault(mainUrl) + "/"
    resolver.resolve(iframe, refererBase)
}
return resolver.loaded
```

- [ ] **Step 6: Run migration tests and verify GREEN**

Run the `ProviderDomainTest` command from Step 2.

Expected: both shared-session and complete-registration tests pass.

- [ ] **Step 7: Run the full unit suite**

```powershell
$env:ANDROID_HOME='C:\Users\ahmad\AppData\Local\Android\Sdk'
.\gradlew.bat :IndoProvider:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`, zero failed tests.

- [ ] **Step 8: Commit provider migration**

```powershell
git add -- IndoProvider/src/main/kotlin/com/example/NgefilmProvider.kt IndoProvider/src/main/kotlin/com/example/DutamovieProvider.kt IndoProvider/src/main/kotlin/com/example/PusatfilmProvider.kt IndoProvider/src/main/kotlin/com/example/RebahinProvider.kt IndoProvider/src/main/kotlin/com/example/GomovProvider.kt IndoProvider/src/main/kotlin/com/example/IdlixProvider.kt IndoProvider/src/main/kotlin/com/example/KitanontonProvider.kt IndoProvider/src/main/kotlin/com/example/FilmapikProvider.kt IndoProvider/src/test/kotlin/com/example/ProviderDomainTest.kt
git commit -m "Route movie providers through shared resolver"
```

---

### Task 5: Full Verification and Live Evidence

**Files:**
- Verify only; no source file changes expected.

**Interfaces:**
- Verifies every success criterion from `docs/superpowers/specs/2026-07-16-movie-provider-server-resolution-design.md`.

- [ ] **Step 1: Run all unit tests from a clean task state**

```powershell
$env:ANDROID_HOME='C:\Users\ahmad\AppData\Local\Android\Sdk'
.\gradlew.bat :IndoProvider:clean :IndoProvider:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`, zero failed tests.

- [ ] **Step 2: Build the debug plugin**

```powershell
$env:ANDROID_HOME='C:\Users\ahmad\AppData\Local\Android\Sdk'
.\gradlew.bat :IndoProvider:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL` and a debug artifact under `IndoProvider/build/`.

- [ ] **Step 3: Verify source hygiene and registration scope**

```powershell
git diff --check
git status --short
git log -5 --oneline
```

Expected: no whitespace errors; only intentionally untracked `graphify-out/` may remain; commits for parser primitives, session, adapters, and provider migration are present.

- [ ] **Step 4: Re-run the AsiaStream live probe as non-gating evidence**

```powershell
$watch = 'https://watch.asiastream.cc/watch?v=K8OFQSVM'
curl.exe -L --max-time 20 $watch | rg 'sniff\('
curl.exe -L --max-time 20 'https://watch.asiastream.cc/m3u8/7/51b7dae1031b20174cacc7e69d6e4bf0/master.txt?s=1&cache=1' | Select-Object -First 8
```

Expected: the first command prints a `sniff(...)` call and the second starts with `#EXTM3U`. Treat upstream downtime as diagnostic evidence, not a unit-test failure.

- [ ] **Step 5: Record the verified limitation**

In the final handoff, state that providers returning Internet Positif, Cloudflare challenges, empty pages, or upstream database failures remain visible but cannot produce invented servers. Report the exact unit-test and build commands with their fresh exit results.
