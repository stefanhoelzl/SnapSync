package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every opt-in to the inbound markers is pinned, with its reason** (`docs/patterns.md`, "The inbound kinds").
 * `@InboundImplementation` lets a class implement a delivery kind — which only the entry-point bases should, so every
 * delivery is labelled, held and contained the same way — and `@CompositionOnly` lets code wire a delivery: build a
 * handler bundle, register it, install the log's writers. A marker anyone may opt into marks nothing, so production
 * source opts in only where [pins] says, exact in both directions: a new opt-in fails here until it is argued, and a
 * pin outliving its opt-in fails too. Test equipment (test source sets, the rig's sources, the test modules) opts in
 * freely: it plays the core or the platform.
 */
class OptInPinsTest {

    /** Production file → how many `@OptIn(…)` of the markers it holds, and why. */
    private val pins = mapOf(
        "/domain/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/EntryPoints.kt" to
            (4 to "the four entry-point bases, each implementing its inbound kind"),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/SnapSyncApp.kt" to
            (1 to "the app's composition: its handler bundles and the selection it follows"),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt" to
            (1 to "every process's first act: the metrics handlers and the crash reporter's shaping handlers"),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ExtensionCore.kt" to
            (1 to "the extension's composition: its one entry port, registered"),
        "/domain/host/src/commonMain/kotlin/app/snapsync/host/ComposedApp.kt" to
            (3 to "the host's registration, and the screen's two deliveries (intents, the live state), which need it"),
        "/domain/services/src/commonMain/kotlin/app/snapsync/services/logs/ProcessLogWriters.kt" to
            (1 to "installing the structured log's writers, once per process"),
    )

    private val optIn = Regex("""OptIn\([^)]*\b(?:InboundImplementation|CompositionOnly)\b""")

    private fun isProduction(path: String): Boolean {
        val sourceSet = path.substringAfter("/src/", "").substringBefore('/')
        return listOf("/domain/", "/adapter/", "/app/", "/ui/").any { path.startsWith(it) } &&
            sourceSet.isNotEmpty() && !sourceSet.endsWith("Test") && sourceSet != "test" && sourceSet != "rig"
    }

    @Test
    fun `production opts into the inbound markers only where pinned`() {
        val files = SourceScan.kotlinFiles().filter { isProduction(it.path) }
        assertTrue(files.size >= 300, "scanned only ${files.size} production files — the source tree moved")
        val found = files.associate { it.path to optIn.findAll(it.text).count() }.filterValues { it > 0 }.toSortedMap()
        assertEquals(
            pins.mapValues { it.value.first }.toSortedMap(),
            found,
            "the opt-ins to InboundImplementation/CompositionOnly drifted from their pins. A new one: subclass an " +
                "entry-point base, or wire the delivery in the composition. One gone: delete its pin.",
        )
    }
}
