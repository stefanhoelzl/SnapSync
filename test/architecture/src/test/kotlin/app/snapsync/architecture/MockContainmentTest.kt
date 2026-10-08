package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **No shipped binary links a mock** (`docs/architecture.md`, "A build-time-only module is contained by compilation";
 * `docs/testing.md`, "Launch-time adapters").
 *
 * `:adapter:generic:mock` compiles for `iosArm64` so a RIG build of the app can hand a launch-time adapters' mocked
 * systems their mocks on a phone. Before that target existed, a device framework could not link it — the missing
 * target was the containment. Now the containment is the build-time switch alone, and this pins it:
 *
 *  - the shipped roots, `:app:ios`, `:app:ios:extension` and `:app:android`, name the rig-only modules — the mocks, the launch
 *    adapters that load them (`:test:launch-adapters`), the control channel and its contracts — only on a line that is itself under
 *    `-Psnapsync.rig=true` (`if (rigEnabled)`);
 *  - nothing else a shipped root links — the closure of their main project dependencies, rig lines excluded — reaches
 *    any of them;
 *  - no production Kotlin imports a mock's package: the types a production root composes over, `AppDevicePorts` and
 *    `ExtensionDevicePorts`, are `:domain:compose`'s, so the prod adapter set reaches no mock by construction.
 *
 * The rig's own hook directories live under `test/` and are compiled into the roots only under the same switch.
 */
class MockContainmentTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun buildFileOf(module: String): File =
        File(repoRoot, module.removePrefix(":").replace(':', '/') + "/build.gradle.kts")

    /** Code lines of [file] — comments dropped, so a line that only DESCRIBES a dependency is not one. */
    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.substringBefore("//") }.filter { it.isNotBlank() }

    @Test
    fun `the shipped roots name the rig-only modules only under the rig switch`() {
        SHIPPED_ROOTS.forEach { root ->
            val file = buildFileOf(root)
            assertTrue(file.isFile, "$root's build file is missing — the shipped roots moved")
            val naming = codeLines(file).filter { line -> RIG_ONLY.any { "\"$it\"" in line } }
            assertTrue(
                naming.isNotEmpty(),
                "$root names no rig-only module at all — re-point this gate at where the rig build links them",
            )
            val unswitched = naming.filterNot { "if (rigEnabled)" in it }
            assertEquals(
                emptyList(),
                unswitched,
                "$root names a rig-only module outside `if (rigEnabled)` — a production build of it would link it",
            )
        }
    }

    @Test
    fun `nothing a shipped root links reaches a rig-only module`() {
        val reached = mutableSetOf<String>()
        val queue = ArrayDeque(SHIPPED_ROOTS)
        while (queue.isNotEmpty()) {
            val module = queue.removeFirst()
            if (!reached.add(module)) continue
            val file = buildFileOf(module)
            if (!file.isFile) continue
            mainProjectDependencies(file).forEach { queue.addLast(it) }
        }
        assertTrue(
            reached.size >= 10,
            "the shipped closure has only ${reached.size} modules — the scan is broken: $reached",
        )
        assertEquals(
            emptySet(),
            RIG_ONLY.toSet() intersect reached,
            "a shipped root links a rig-only module through its main dependencies",
        )
    }

    @Test
    fun `no production Kotlin imports a mock`() {
        val production = SourceScan.kotlinFiles().filter { src ->
            listOf("/domain/", "/adapter/", "/app/", "/ui/").any { src.path.startsWith(it) } &&
                !src.path.startsWith("/adapter/generic/mock/") &&
                !src.path.startsWith("/app/jvm/") &&
                !src.path.startsWith("/app/desktop/") &&
                !Regex("""/src/(\w*[Tt]est|rig)/""").containsMatchIn(src.path)
        }
        assertTrue(production.size >= 200, "scanned only ${production.size} production files — the scope is broken")
        val importing = production.filter { "import app.snapsync.mock." in it.text }.map { it.path }
        assertEquals(emptyList(), importing, "production Kotlin imports a mock — a shipped binary would carry it")
    }

    /** A line that declares a main dependency: not a test configuration, not rig-switched, not coverage crediting. */
    private fun isMainDependency(line: String): Boolean = listOf(
        "testImplementation",
        RIG,
        "kover(",
    ).none { it in line }

    /**
     * The `project(":…")` dependencies [file] declares for MAIN code: not inside a test source set's block or a
     * rig-switched one, not on a `testImplementation`, and not on a rig-switched line. Brace depth tracks the enclosing
     * blocks.
     */
    private fun mainProjectDependencies(file: File): List<String> {
        val stack = ArrayDeque<String>()
        val found = mutableListOf<String>()
        codeLines(file).forEach { line ->
            val excluded = stack.any { header -> TEST_BLOCK.containsMatchIn(header) || RIG in header }
            if (!excluded && isMainDependency(line)) PROJECT.findAll(line).forEach { found += it.groupValues[1] }
            // Open and close the blocks this line opens and closes, in order.
            line.forEachIndexed { i, c ->
                when (c) {
                    '{' -> stack.addLast(line.substring(0, i))
                    '}' -> stack.removeLastOrNull()
                }
            }
        }
        return found
    }

    private companion object {
        /** What only a rig build links: the mocks, the launch choice, the control channel and its in-app contracts. */
        val RIG_ONLY = listOf(":adapter:generic:mock", ":test:launch-adapters", ":test:rig", ":test:contracts")

        /** The roots whose binaries ship: the app's `SnapSyncKit`, the extension's `SnapSyncUploadKit`, the Android app. */
        val SHIPPED_ROOTS = listOf(":app:ios", ":app:ios:extension", ":app:android")

        /** The rig build's switch, as every build file spells it. */
        const val RIG = "rigEnabled"

        val PROJECT = Regex("""project\(\s*"(:[^"]+)"\s*\)""")

        /** A test source set's block header — `commonTest.dependencies`, `jvmTest { … }`, `iosSimulatorArm64Test`. */
        val TEST_BLOCK = Regex("""\w+Test\b""")
    }
}
