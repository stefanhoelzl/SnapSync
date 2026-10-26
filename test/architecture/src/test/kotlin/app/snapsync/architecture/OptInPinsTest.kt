package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every opt-in that is a decision is pinned, with its reason** (`docs/patterns.md`, "Opt-in capabilities").
 *
 * A `@RequiresOptIn` marker makes "only X may do Y" a compile error everywhere else, and anyone may write
 * `@OptIn(X::class)` to silence it — so where production opts in is pinned here, exact in both directions: a new opt-in
 * fails until it is argued, and a pin outliving its opt-in fails too. Two kinds are pinned:
 *
 *  - **the project's own markers**, found rather than listed: every `@RequiresOptIn` annotation class in production
 *    source. A declaration that carries a marker on instead of opting in is not an opt-in: its callers' are;
 *  - **the library markers the library itself calls delicate** ([DELICATE]) — the sites where someone accepted a risk.
 *    A marker that only says "this is cinterop" or "not yet stable" is not pinned.
 *
 * No build script opts a module into either kind: a module-wide opt-in would silence this pin for the whole module.
 * Test equipment — test source sets, the rig's sources, the test modules, the mocks, and the JVM root and desktop
 * harness (never linked into a shipped binary, `ModuleSetTest`'s SUPPORT group) — opts in freely: it plays the core or
 * the platform.
 */
class OptInPinsTest {

    /** Production file → marker → how many `@OptIn(…)` of it the file holds, and why. */
    private val pins: Map<String, Map<String, Pin>> = mapOf(
        "/domain/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/EntryPoints.kt" to mapOf(
            "InboundImplementation" to Pin(4, "the four entry-point bases, each implementing its inbound kind"),
        ),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/SnapSyncApp.kt" to mapOf(
            "CompositionOnly" to Pin(1, "the app's composition: its handler bundles and the selection it follows"),
        ),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt" to mapOf(
            "CompositionOnly" to Pin(1, "every process's first act: the metrics handlers and the crash reporter's handlers"),
        ),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/ExtensionCore.kt" to mapOf(
            "CompositionOnly" to Pin(1, "the extension's composition: its one entry port, registered"),
        ),
        "/domain/domain/src/commonMain/kotlin/app/snapsync/domain/membership/DeletionVerdict.kt" to mapOf(
            "MintsConfirmedDeletion" to Pin(
                2,
                "the verdicts that confirm a deletion: a read of the event, and a write the server refused as over",
            ),
        ),
        "/domain/data/src/commonMain/kotlin/app/snapsync/data/membership/MembershipRows.kt" to mapOf(
            "MintsConfirmedNotJoined" to Pin(1, "the one read that says the store holds no membership"),
        ),
        "/domain/data/src/commonMain/kotlin/app/snapsync/data/membership/MembershipEnds.kt" to mapOf(
            "MintsLeftMembership" to Pin(1, "the one transaction that ends a membership"),
        ),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/AppServices.kt" to mapOf(
            "AppProcessOnly" to Pin(1, "the app's device identity, the one that may mint"),
        ),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/BackendComposition.kt" to mapOf(
            "AppProcessOnly" to Pin(1, "the app's credential, the one that attests"),
        ),
        "/domain/entrypoints/src/commonMain/kotlin/app/snapsync/entrypoints/AppEntryPoints.kt" to mapOf(
            "EntryPointCall" to Pin(3, "the wake's prelude, the key's restore and the rig's reset call their use cases"),
            "MintsDevReset" to Pin(1, "the rig's reset, the one other reason the key is forgotten (PENDING_CLEANUPS, 4.5)"),
        ),
        "/domain/format/src/commonMain/kotlin/app/snapsync/format/EventKeyText.kt" to mapOf(
            "KeyMaterial" to Pin(1, "a key's text: its bytes, spelled"),
            "MakesEventKey" to Pin(1, "a key's text, read back as the key"),
        ),
        "/domain/data/src/commonMain/kotlin/app/snapsync/data/crypto/EventKeyIds.kt" to mapOf(
            "KeyMaterial" to Pin(1, "a key's id is derived from its bytes"),
            "MakesEventKey" to Pin(1, "a fresh key, from the platform's secure generator"),
            "MintsIdentifiedKey" to Pin(1, "the one derivation that says which id a key has"),
        ),
        "/domain/data/src/commonMain/kotlin/app/snapsync/data/crypto/EventKeyStore.kt" to mapOf(
            "MintsKeptKey" to Pin(1, "the one keep that says a join's key is kept"),
        ),
        "/domain/data/src/commonMain/kotlin/app/snapsync/data/crypto/FileCipher.kt" to mapOf(
            "KeyMaterial" to Pin(1, "the cipher: each file's key is derived from the event key's bytes"),
        ),
        "/domain/entrypoints/src/commonMain/kotlin/app/snapsync/entrypoints/MembershipEntryPoints.kt" to mapOf(
            "EntryPointCall" to Pin(1, "the membership's entry points and the steps they share call its use cases"),
        ),
        "/domain/application/src/commonMain/kotlin/app/snapsync/application/membership/EnrollDevice.kt" to mapOf(
            "MintsEnrolled" to Pin(1, "the one enrolment the server admitted"),
        ),
        "/domain/services/src/commonMain/kotlin/app/snapsync/services/membership/MembershipProjection.kt" to mapOf(
            "ExperimentalForInheritanceCoroutinesApi" to
                Pin(1, "a hand-written StateFlow over the membership, for the old zones (MIGRATING)"),
        ),
        "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/AppUploader.kt" to mapOf(
            "AppProcessOnly" to Pin(1, "the app's uploader binds the memoised discovery; the extension walks afresh"),
        ),
        "/domain/host/src/commonMain/kotlin/app/snapsync/host/ComposedApp.kt" to mapOf(
            "CompositionOnly" to Pin(1, "the host's registration"),
            "InboundImplementation" to Pin(1, "the screen's live state — its taps are an entry point, `OnTap`"),
        ),
        "/domain/services/src/commonMain/kotlin/app/snapsync/services/logs/ProcessLogWriters.kt" to mapOf(
            "CompositionOnly" to Pin(1, "installing the structured log's writers, once per process"),
        ),
        "/domain/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/Runtime.kt" to mapOf(
            "InternalCoroutinesApi" to Pin(
                3,
                "the supervised scope's handler and job, and `endProcess` handing an InvalidProgramState to the library's " +
                    "last resort: coroutines' internals, re-read at every bump",
            ),
        ),
        "/domain/presentation/src/commonMain/kotlin/app/snapsync/presentation/ScreenModel.kt" to mapOf(
            "ExperimentalForInheritanceCoroutinesApi" to Pin(1, "a hand-written StateFlow over the screen model (4.9)"),
        ),
        "/domain/services/src/commonMain/kotlin/app/snapsync/services/gallery/GrantReading.kt" to mapOf(
            "ExperimentalForInheritanceCoroutinesApi" to Pin(1, "a hand-written StateFlow over the platform's grant (4.4)"),
        ),
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/ios/qos/ThreadQos.kt" to mapOf(
            "DelicateCoroutinesApi" to Pin(1, "each lane's thread is never closed: it lives as long as the process"),
        ),
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/crypto/IosCrypto.kt" to mapOf(
            "DelicateCryptographyApi" to Pin(1, "the primitives the encrypted file format is defined over"),
        ),
    )

    private class Pin(val count: Int, @Suppress("unused") val reason: String)

    private fun isProduction(path: String): Boolean {
        val sourceSet = path.substringAfter("/src/", "").substringBefore('/')
        return listOf("/domain/", "/adapter/", "/app/", "/ui/").any { path.startsWith(it) } &&
            TEST_EQUIPMENT.none { path.startsWith(it) } &&
            sourceSet.isNotEmpty() && !sourceSet.endsWith("Test") && sourceSet != "test" && sourceSet != "rig"
    }

    private val production by lazy { SourceScan.kotlinFiles().filter { isProduction(it.path) } }

    /** Every `@RequiresOptIn` annotation class production source declares. */
    private val projectMarkers by lazy {
        production.flatMap { src -> MARKER.findAll(src.text).map { it.groupValues[1] } }.toSet()
    }

    @Test
    fun `the project's markers are found`() {
        assertTrue(production.size >= 300, "scanned only ${production.size} production files — the source tree moved")
        assertTrue(
            projectMarkers.containsAll(setOf("InboundImplementation", "CompositionOnly", "AppProcessOnly")),
            "the markers found were $projectMarkers — the scan no longer sees a `@RequiresOptIn` declaration",
        )
    }

    @Test
    fun `production opts into a marker only where pinned`() {
        val pinned = projectMarkers + DELICATE
        val found = production.associate { src ->
            src.path to OPT_IN.findAll(src.text)
                .flatMap { optIn -> pinned.filter { Regex("""\b$it\b""").containsMatchIn(optIn.groupValues[1]) } }
                .groupingBy { it }.eachCount()
        }.filterValues { it.isNotEmpty() }.toSortedMap()
        assertEquals(
            pins.mapValues { (_, byMarker) -> byMarker.mapValues { it.value.count } }.toSortedMap(),
            found,
            "the opt-ins to a pinned marker drifted from their pins. A new one: argue it here, or carry the marker on " +
                "instead. One gone: delete its pin.",
        )
    }

    @Test
    fun `no build script opts a module into a pinned marker`() {
        val pinned = projectMarkers + DELICATE
        val scripts = SourceScan.files(setOf("kts"))
        assertTrue(scripts.size >= 30, "scanned only ${scripts.size} build scripts — the scope is broken")
        val modulewide = scripts.flatMap { script ->
            MODULE_WIDE.findAll(script.text).mapNotNull { m ->
                pinned.firstOrNull { m.value.contains(it) }?.let { "${script.path}: ${m.value.trim()}" }
            }.toList()
        }
        assertEquals(emptyList(), modulewide, "a module-wide opt-in silences the pin for a whole module")
    }

    private companion object {
        val TEST_EQUIPMENT = listOf("/adapter/generic/mock/", "/app/desktop/", "/app/jvm/")
        val DELICATE = setOf(
            "DelicateCoroutinesApi",
            "InternalCoroutinesApi",
            "ExperimentalForInheritanceCoroutinesApi",
            "DelicateCryptographyApi",
        )
        val MARKER = Regex("""@RequiresOptIn\b[\s\S]*?annotation\s+class\s+(\w+)""")
        val OPT_IN = Regex("""@(?:file:)?OptIn\(([^)]*)\)""")
        val MODULE_WIDE = Regex("""(?:optIn\.add|languageSettings\.optIn|optIn)\s*\([^)]*\)|-opt-in=[\w.]+""")
    }
}
