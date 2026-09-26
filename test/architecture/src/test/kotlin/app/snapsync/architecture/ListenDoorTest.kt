package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Events arrive through `listen`, and commands cross one door** (`docs/architecture.md`, "Events arrive through
 * `listen`"). An event port's handlers are built in ONE zone and registered from ONE other, so what the platform
 * tells the core is always the composition's own flow commands, service calls and presentation intents — never a
 * callback some root or adapter wired up for itself:
 *
 *  - a `*Handlers` bundle is constructed only in `compose/` (production source), where the handler table lives —
 *    and in the host zone for the handlers that need the status host to exist first (`LinkHandlers`, `UiHandlers`:
 *    a link opens the join gate, an intent is the container's);
 *  - `listen` is called only from the host zone, once per adapter, as the graph is composed — and from
 *    `snapSyncProcess` for the per-process event ports (`CrashReporter`, `ProcessMetrics`): every root sets those up
 *    before it composes anything, and the upload extension has no host zone at all.
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
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/EntryHandlers.kt",
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/GalleryHandlersComposition.kt",
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt",
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/TransferEntries.kt",
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/WakeEntry.kt",
                // The link and UI handlers: both reach the status host, which only the host zone may build.
                "/domain/host/src/commonMain/kotlin/app/snapsync/host/ComposedApp.kt",
            ),
            sites,
            "a `*Handlers` bundle built outside `compose/` and the host zone is a callback wired past the " +
                "composition's door",
        )
    }

    @Test
    fun `listen is called only from the host zone`() {
        val sites = filesMatching(Regex("""\.listen\("""))
        assertTrue(sites.isNotEmpty(), "no event port is listened to anywhere — the scan is broken")
        assertEquals(
            setOf(
                "/domain/host/src/commonMain/kotlin/app/snapsync/host/ComposedApp.kt",
                // The per-process event ports: set up by every root before any composition (the extension has no host).
                "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt",
            ),
            sites,
            "an event port registered outside the host zone escapes the one registration per adapter",
        )
    }
}
