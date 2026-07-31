package com.example

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class GithubActionsRuntimeTest {
    @Test
    fun `workflows use Node 24 compatible action majors`() {
        val workflowsDirectory = findRepositoryRoot().resolve(".github/workflows")
        val workflowText = Files.newDirectoryStream(workflowsDirectory).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .map { path -> String(Files.readAllBytes(path), Charsets.UTF_8) }
                .joinToString("\n")
        }
        val actionReferences = Regex(
            """uses:\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)@v(\d+)\b"""
        ).findAll(workflowText)
            .groupBy(
                keySelector = { it.groupValues[1] },
                valueTransform = { it.groupValues[2].toInt() }
            )
        val minimumNode24Majors = mapOf(
            "actions/checkout" to 6,
            "actions/setup-java" to 5,
            "android-actions/setup-android" to 4,
            "gradle/actions/setup-gradle" to 6
        )

        minimumNode24Majors.forEach { (action, minimumMajor) ->
            val majors = actionReferences[action].orEmpty()
            assertTrue(majors.isNotEmpty(), "$action is missing from the workflows")
            assertTrue(
                majors.all { it >= minimumMajor },
                "$action must use v$minimumMajor or newer, but found ${majors.sorted()}"
            )
        }
    }

    @Test
    fun `build workflow publishes only through the raw GitHub builds branch`() {
        val repositoryRoot = findRepositoryRoot()
        val workflowPath = repositoryRoot.resolve(".github/workflows/build.yml")
        val workflow = String(Files.readAllBytes(workflowPath), Charsets.UTF_8)
        val rootBuildScript = String(
            Files.readAllBytes(repositoryRoot.resolve("build.gradle.kts")),
            Charsets.UTF_8
        )

        assertTrue(
            "actions/upload-artifact" !in workflow,
            "build workflow must not upload a duplicate GitHub Actions artifact"
        )
        assertTrue(
            "cp IndoProvider/build/IndoProvider.cs3 ../builds/IndoProvider.cs3" in workflow,
            "compiled plugin must be copied into the builds branch checkout"
        )
        assertTrue(
            "cp build/plugins.json ../builds/plugins.json" in workflow,
            "plugin metadata must be copied into the builds branch checkout"
        )
        assertTrue(
            "git push --force" in workflow && "builds" in workflow,
            "build workflow must publish the generated files to the builds branch"
        )
        assertTrue(
            "https://raw.githubusercontent.com/" in workflow &&
                "/builds/plugins.json" in workflow,
            "repo.json must point CloudStream to raw GitHub builds metadata"
        )
        assertTrue(
            """setRepo(System.getenv("GITHUB_REPOSITORY")""" in rootBuildScript,
            "plugins.json must derive its raw GitHub artifact URL from the current repository"
        )
    }

    @Test
    fun `scheduled provider health covers every registered provider`() {
        val repositoryRoot = findRepositoryRoot()
        val workflowPath = repositoryRoot.resolve(".github/workflows/provider-health.yml")
        assertTrue(Files.isRegularFile(workflowPath), "provider-health.yml is missing")
        val workflow = String(Files.readAllBytes(workflowPath), Charsets.UTF_8)

        assertTrue("workflow_dispatch:" in workflow)
        assertTrue("schedule:" in workflow)
        assertTrue("RUN_LIVE_PROVIDER_TESTS: 1" in workflow)
        assertTrue("fail-fast: false" in workflow)
        assertTrue("if: always()" in workflow)
        assertTrue(
            ("--tests \"" + "$" + "{{ matrix.test_class }}\"") in workflow,
            "provider health must execute each matrix test class"
        )
        val timeoutMinutes = Regex("""timeout-minutes:\s*(\d+)""")
            .find(workflow)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        assertTrue(
            timeoutMinutes != null && timeoutMinutes >= 120,
            "provider health timeout must allow the sequential live suites to finish"
        )

        val scheduledClasses = Regex(
            """test_class:\s*com\.example\.([A-Za-z0-9_]+)"""
        ).findAll(workflow)
            .map { it.groupValues[1] }
            .toSet()
        val requiredClasses = setOf(
            "ProviderExpansionLiveTest",
            "MovieProviderCatalogPlaybackLiveTest",
            "IdlixProviderLiveTest",
            "AnimeProviderLiveTest",
            "CloudstreamTesterParityLiveTest"
        )
        assertTrue(
            scheduledClasses.containsAll(requiredClasses),
            "provider health is missing live suites: ${requiredClasses - scheduledClasses}"
        )

        val testSources = Files.newDirectoryStream(
            repositoryRoot.resolve("IndoProvider/src/test/kotlin/com/example"),
            "*.kt"
        ).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .map { path -> String(Files.readAllBytes(path), Charsets.UTF_8) }
                .filter { source ->
                    scheduledClasses.any { className -> "class $className" in source }
                }
                .joinToString("\n")
        }
        val pluginSource = String(
            Files.readAllBytes(
                repositoryRoot.resolve(
                    "IndoProvider/src/main/kotlin/com/example/IndoPlugin.kt"
                )
            ),
            Charsets.UTF_8
        )
        val registeredProviders = Regex(
            """registerMainAPI\(([A-Za-z0-9_]+Provider)\(\)\)"""
        ).findAll(pluginSource)
            .map { it.groupValues[1] }
            .toSet()
        val uncovered = registeredProviders.filterNot { provider ->
            Regex("""\b${Regex.escape(provider)}\s*\(""").containsMatchIn(testSources)
        }
        assertTrue(
            uncovered.isEmpty(),
            "registered providers missing from scheduled live suites: $uncovered"
        )
    }

    private fun findRepositoryRoot(): Path = generateSequence(
        Paths.get("").toAbsolutePath()
    ) { path -> path.parent }
        .firstOrNull { candidate ->
            Files.isDirectory(candidate.resolve(".github/workflows"))
        }
        ?: error("Could not locate .github/workflows from the test working directory")
}
