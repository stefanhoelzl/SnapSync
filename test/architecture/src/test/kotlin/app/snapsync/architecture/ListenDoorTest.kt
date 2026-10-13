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
 *  - a `*Handlers` bundle is constructed only in `compose/` (production source), whose handlers are the entry points
 *    — and in the host zone for the handlers that need the status host to exist first (`UiHandlers`: an intent is
 *    the container's);
 *  - `inbound` is called only from `compose/`'s registration (`AppCore.inbound`, which the host calls once it is
 *    assembled) and the host zone, once per adapter — and from `snapSyncProcess` for the per-process event ports
 *    (`CrashReporter`, `ProcessMetrics`), which every root sets up before it composes anything, and from
 *    `snapSyncExtension` for the upload extension's `ExtensionHost`: that process has no host zone at all.
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
    fun `handlers are built only in the composition and host zones`() {
        val sites = filesMatching(Regex("""(?<!class )\b[A-Z]\w*Handlers\("""))
        assertTrue(sites.isNotEmpty(), "no handler bundle is built anywhere — the scan is broken")
        assertEquals(
            setOf(
                // The app's entry points, each the handler of the delivery it is named for.
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/AppInbound.kt",
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ExtensionCore.kt",
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt",
                // The UI handlers: they reach the status host, which only the host zone may build.
                "/domain/host/src/commonMain/kotlin/app/snapsync/host/ComposedApp.kt",
            ),
            sites,
            "a `*Handlers` bundle built outside `compose/` and the host zone is a callback wired past the " +
                "composition's door",
        )
    }

    @Test
    fun `inbound is called only from the composition and host zones`() {
        val sites = filesMatching(Regex("""\.(inbound|listen)\("""))
        assertTrue(sites.isNotEmpty(), "no event port is registered anywhere — the scan is broken")
        assertEquals(
            setOf(
                // The app's event ports, registered once the host is assembled (`AppCore.inbound`, the host's call).
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/AppInbound.kt",
                "/domain/host/src/commonMain/kotlin/app/snapsync/host/ComposedApp.kt",
                // The per-process event ports: set up by every root before any composition (the extension has no host).
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt",
                // The upload extension's one entry port, registered by its own composition — it has no host zone.
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ExtensionCore.kt",
            ),
            sites,
            "an event port registered outside the composition escapes the one registration per adapter",
        )
    }
}
