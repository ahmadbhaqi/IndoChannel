# Provider URL Additions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register seven requested Indonesian movie/anime provider URLs as first-class Cloudstream providers.

**Architecture:** Add one small Kotlin provider class per requested source, reusing compatible existing scraper implementations by inheritance. Register the new providers in `IndoPlugin.kt`, and protect the requested domains plus registration with unit tests.

**Tech Stack:** Kotlin, Android Gradle Plugin, Cloudstream plugin stubs, Kotlin test/JUnit.

## Global Constraints

- Keep changes narrowly scoped to provider registration and scraper reuse.
- Do not implement fully custom scrapers per site in this task.
- Use existing provider patterns such as `IdlixProvider : RebahinProvider()`.
- Preserve the requested exact `mainUrl` values.

---

## File Structure

- Modify `IndoProvider/src/test/kotlin/com/example/ProviderDomainTest.kt`: add tests for all requested provider domains and `IndoPlugin.kt` registrations.
- Create `IndoProvider/src/main/kotlin/com/example/IndoxxiProvider.kt`: alias provider for `https://comblank.com`.
- Create `IndoProvider/src/main/kotlin/com/example/FilmapikProvider.kt`: alias provider for `https://filmapik.to`.
- Create `IndoProvider/src/main/kotlin/com/example/IndofilmProvider.kt`: alias provider for `https://yuhhaber.com`.
- Create `IndoProvider/src/main/kotlin/com/example/AnimeindoProvider.kt`: alias provider for `https://anime-indo.lol`.
- Create `IndoProvider/src/main/kotlin/com/example/OploverzProvider.kt`: alias provider for `https://plus.oploverz.ltd`.
- Create `IndoProvider/src/main/kotlin/com/example/ZoronimeProvider.kt`: alias provider for `https://zoronime.live`.
- Create `IndoProvider/src/main/kotlin/com/example/MiranimeProvider.kt`: alias provider for `https://miranime.net`.
- Modify `IndoProvider/src/main/kotlin/com/example/KuronimeProvider.kt`: make the class `open` so anime aliases can reuse it.
- Modify `IndoProvider/src/main/kotlin/com/example/IndoPlugin.kt`: register all seven new providers.
- Modify `README.md`: include the new provider names in the public list.

## Task 1: Provider Domain And Registration Tests

**Files:**
- Modify: `IndoProvider/src/test/kotlin/com/example/ProviderDomainTest.kt`

**Interfaces:**
- Consumes: source files as plain text from `IndoProvider/src/main/kotlin/com/example`.
- Produces: tests that fail until provider files and registrations exist.

- [ ] **Step 1: Write the failing test**

Replace `ProviderDomainTest.kt` with:

```kotlin
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
            "FilmapikProvider.kt" to """override var mainUrl = "https://filmapik.to"""",
            "IndofilmProvider.kt" to """override var mainUrl = "https://yuhhaber.com""""
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat IndoProvider:testDebugUnitTest --tests com.example.ProviderDomainTest`

Expected: FAIL because files such as `IndoxxiProvider.kt` do not exist or registrations are missing.

- [ ] **Step 3: Commit**

Do not commit yet; this is the red half of the TDD cycle and must be paired with Task 2 and Task 3 implementation.

## Task 2: Movie Provider Aliases

**Files:**
- Create: `IndoProvider/src/main/kotlin/com/example/IndoxxiProvider.kt`
- Create: `IndoProvider/src/main/kotlin/com/example/FilmapikProvider.kt`
- Create: `IndoProvider/src/main/kotlin/com/example/IndofilmProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/IndoPlugin.kt`

**Interfaces:**
- Consumes: `open class RebahinProvider : MainAPI()`.
- Produces: provider classes with default constructors used by `registerMainAPI(...)`.

- [ ] **Step 1: Add movie alias provider files**

Create `IndoxxiProvider.kt`:

```kotlin
package com.example

class IndoxxiProvider : RebahinProvider() {
    override var mainUrl = "https://comblank.com"
    override var name = "Indoxxi"
}
```

Create `FilmapikProvider.kt`:

```kotlin
package com.example

class FilmapikProvider : RebahinProvider() {
    override var mainUrl = "https://filmapik.to"
    override var name = "Filmapik"
}
```

Create `IndofilmProvider.kt`:

```kotlin
package com.example

class IndofilmProvider : RebahinProvider() {
    override var mainUrl = "https://yuhhaber.com"
    override var name = "Indofilm"
}
```

- [ ] **Step 2: Register movie aliases**

Add these calls in the Movie & TV Series block of `IndoPlugin.kt`:

```kotlin
registerMainAPI(IndoxxiProvider())
registerMainAPI(FilmapikProvider())
registerMainAPI(IndofilmProvider())
```

- [ ] **Step 3: Run focused test**

Run: `.\gradlew.bat IndoProvider:testDebugUnitTest --tests com.example.ProviderDomainTest`

Expected: Still FAIL until anime providers are added in Task 3.

## Task 3: Anime Provider Aliases

**Files:**
- Modify: `IndoProvider/src/main/kotlin/com/example/KuronimeProvider.kt`
- Create: `IndoProvider/src/main/kotlin/com/example/AnimeindoProvider.kt`
- Create: `IndoProvider/src/main/kotlin/com/example/OploverzProvider.kt`
- Create: `IndoProvider/src/main/kotlin/com/example/ZoronimeProvider.kt`
- Create: `IndoProvider/src/main/kotlin/com/example/MiranimeProvider.kt`
- Modify: `IndoProvider/src/main/kotlin/com/example/IndoPlugin.kt`

**Interfaces:**
- Consumes: `open class KuronimeProvider : MainAPI()`.
- Produces: provider classes with default constructors used by `registerMainAPI(...)`.

- [ ] **Step 1: Make Kuronime subclassable**

Change the class declaration in `KuronimeProvider.kt` from:

```kotlin
class KuronimeProvider : MainAPI() {
```

to:

```kotlin
open class KuronimeProvider : MainAPI() {
```

- [ ] **Step 2: Add anime alias provider files**

Create `AnimeindoProvider.kt`:

```kotlin
package com.example

class AnimeindoProvider : KuronimeProvider() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "Animeindo"
}
```

Create `OploverzProvider.kt`:

```kotlin
package com.example

class OploverzProvider : KuronimeProvider() {
    override var mainUrl = "https://plus.oploverz.ltd"
    override var name = "Oploverz"
}
```

Create `ZoronimeProvider.kt`:

```kotlin
package com.example

class ZoronimeProvider : KuronimeProvider() {
    override var mainUrl = "https://zoronime.live"
    override var name = "Zoronime"
}
```

Create `MiranimeProvider.kt`:

```kotlin
package com.example

class MiranimeProvider : KuronimeProvider() {
    override var mainUrl = "https://miranime.net"
    override var name = "Miranime"
}
```

- [ ] **Step 3: Register anime aliases**

Add these calls in the Anime block of `IndoPlugin.kt`:

```kotlin
registerMainAPI(AnimeindoProvider())
registerMainAPI(OploverzProvider())
registerMainAPI(ZoronimeProvider())
registerMainAPI(MiranimeProvider())
```

- [ ] **Step 4: Run focused test**

Run: `.\gradlew.bat IndoProvider:testDebugUnitTest --tests com.example.ProviderDomainTest`

Expected: PASS.

- [ ] **Step 5: Commit provider code and tests**

Run:

```bash
git add IndoProvider/src/test/kotlin/com/example/ProviderDomainTest.kt IndoProvider/src/main/kotlin/com/example/IndoxxiProvider.kt IndoProvider/src/main/kotlin/com/example/FilmapikProvider.kt IndoProvider/src/main/kotlin/com/example/IndofilmProvider.kt IndoProvider/src/main/kotlin/com/example/AnimeindoProvider.kt IndoProvider/src/main/kotlin/com/example/OploverzProvider.kt IndoProvider/src/main/kotlin/com/example/ZoronimeProvider.kt IndoProvider/src/main/kotlin/com/example/MiranimeProvider.kt IndoProvider/src/main/kotlin/com/example/KuronimeProvider.kt IndoProvider/src/main/kotlin/com/example/IndoPlugin.kt
git commit -m "Add requested Indonesian provider aliases"
```

## Task 4: README Provider List

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: provider names added in Tasks 2 and 3.
- Produces: public documentation listing the new providers.

- [ ] **Step 1: Add provider names to README**

Add the movie names under `Movie & TV Series`:

```markdown
- **Indoxxi**
- **Filmapik**
- **Indofilm**
```

Add the anime names under `Anime`:

```markdown
- **Animeindo**
- **Oploverz**
- **Zoronime**
- **Miranime**
```

- [ ] **Step 2: Run full unit tests**

Run: `.\gradlew.bat IndoProvider:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 3: Run plugin build**

Run: `.\gradlew.bat IndoProvider:make`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit README update**

Run:

```bash
git add README.md
git commit -m "Update provider list"
```
