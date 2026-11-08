package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Events arrive through `inbound`, and every delivery is an entry point** (`docs/architecture.md`, "Events arrive
 * through `inbound`"). An event port's handlers are built in ONE zone and registered from it, so what the platform
 * tells the core is always one of the composition's entry points — never a callback some root or adapter wired up
 * for itself:
 *
 *  - a `*Handlers` bundle is constructed only in the composition layer (production source), whose handlers are the
 *    entry points;
 *  - `inbound` is called only from a composing function, once per adapter — `composeApp` for the app's event ports,
 *    `startProcess` for the per-process event port (`ProcessMetrics`), which every root sets up before it composes
 *    anything, and `composeExtension` for the upload extension's `ExtensionHost`.
 *
 * Both are pinned exact-in-set and non-vacuous: a gate that finds no construction site or no registration would be
 * passing on nothing. Test equipment (contract bindings, the rig, the world) is not production and registers its
 * own handlers freely.
 */
class ListenDoorTest {

    /** Hand-written production Kotlin: any `*Main` source set. */
    private val production = SourceScan.kotlinFiles().filter { src ->
        src.path.startsWith("/domain/") || src.path.startsWith("/adapter/") || src.path.startsWith("/app/") ||
            src.path.startsWith("/ui/")
    }.filter { src -> src.path.substringAfter("/src/", "").substringBefore('/').endsWith("Main") }

    private fun filesMatching(pattern: Regex): Set<String> =
        production.filter { pattern.containsMatchIn(ZoneGates.stripComments(it.text)) }.mapTo(sortedSetOf()) { it.path }

    @Test
    fun `handlers are built only in the composition layer`() {
        val sites = filesMatching(Regex("""(?<!class )\b[A-Z]\w*Handlers\("""))
        assertTrue(sites.isNotEmpty(), "no handler bundle is built anywhere — the scan is broken")
        assertEquals(
            setOf(
                // The app's entry points, each the handler of the delivery it is named for, the push's among them.
                "/domain/composition/app/src/commonMain/kotlin/app/snapsync/composition/app/ComposedApp.kt",
                "/domain/composition/app/src/commonMain/kotlin/app/snapsync/composition/app/AppGraph.kt",
                "/domain/composition/extension/src/commonMain/kotlin/app/snapsync/composition/extension/" +
                    "ExtensionComposition.kt",
                "/domain/composition/process/src/commonMain/kotlin/app/snapsync/composition/process/ProcessGraph.kt",
            ),
            sites,
            "a `*Handlers` bundle built outside the composition layer is a callback wired past its door",
        )
    }

    @Test
    fun `inbound is called only from the composition layer`() {
        val sites = filesMatching(Regex("""\.(inbound|listen)\("""))
        assertTrue(sites.isNotEmpty(), "no event port is registered anywhere — the scan is broken")
        assertEquals(
            setOf(
                // The app's event ports, each registered once by its one composing function (`composeApp`).
                "/domain/composition/app/src/commonMain/kotlin/app/snapsync/composition/app/ComposedApp.kt",
                // The per-process event ports: set up by every root before any composition (the extension has no host).
                "/domain/composition/process/src/commonMain/kotlin/app/snapsync/composition/process/ProcessGraph.kt",
                // The upload extension's one entry port, registered by its own composition — it has no host zone.
                "/domain/composition/extension/src/commonMain/kotlin/app/snapsync/composition/extension/" +
                    "ExtensionComposition.kt",
            ),
            sites,
            "an event port registered outside the composition escapes the one registration per adapter",
        )
    }
}
