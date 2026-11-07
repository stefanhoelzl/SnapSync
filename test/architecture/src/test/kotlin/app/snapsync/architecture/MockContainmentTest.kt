package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 *  - no production Kotlin imports a mock's package: the types a production root composes over, `AppDevicePorts`
 *    (`:domain:compose`'s) and `ExtensionDevicePorts` (the composition layer's), reach no mock by construction.
 *
 * The rig's own hook directories live under `test/` and are compiled into the roots only under the same switch.
 */
class MockContainmentTest {

    @Test
    fun `the shipped roots name the rig-only modules only under the rig switch`() {
        SHIPPED_ROOTS.forEach { root ->
            val file = ShippedLinks.buildFileOf(root)
            assertTrue(file.isFile, "$root's build file is missing — the shipped roots moved")
            val naming = ShippedLinks.codeLines(file).filter { line -> RIG_ONLY.any { "\"$it\"" in line } }
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
        val reached = ShippedLinks.closure(SHIPPED_ROOTS)
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

    private companion object {
        /** What only a rig build links: the mocks, the launch choice, the control channel and its in-app contracts. */
        val RIG_ONLY = listOf(":adapter:generic:mock", ":test:launch-adapters", ":test:rig", ":test:contracts")

        /** The roots whose binaries ship: the app's `SnapSyncKit`, the extension's `SnapSyncUploadKit`, the Android app. */
        val SHIPPED_ROOTS = listOf(":app:ios", ":app:ios:extension", ":app:android")
    }
}
