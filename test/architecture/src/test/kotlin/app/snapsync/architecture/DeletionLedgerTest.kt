package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The retired dead weight stays dead** (promoted from the migration beacon's deletion-ledger
 * row at the finale, per the beacon's own contract). Each entry below was deliberately deleted
 * during the migration, with its rationale in the corresponding decision record — and each is the
 * kind of thing that grows back innocently (a convenience interface here, a second uploader
 * there). Resurrection is not forbidden forever; it is forbidden *silently*: bringing one back
 * means deleting its row here in the same commit, with the argument in the PR.
 *
 * Patterns quote the retired names, so this guard excludes its own source from the scan (the same
 * self-exclusion the beacon applied).
 */
class DeletionLedgerTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun sources(): List<File> = listOf("adapter", "domain", "app", "test", "ui").flatMap { root ->
        File(repoRoot, root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/src/" in it.path }
            .filterNot { it.name == "DeletionLedgerTest.kt" }
            .toList()
    }

    private fun declared(files: List<File>, pattern: String): List<String> = files
        .filter { Regex(pattern).containsMatchIn(it.readText()) }
        .map { it.toRelativeString(repoRoot) }

    @Test
    fun `the deletion ledger's retired items stay dead`() {
        val files = sources()
        assertTrue(files.isNotEmpty(), "deletion-ledger gate scanned zero sources — the roots moved")
        val toml = File(repoRoot, "gradle/libs.versions.toml").readText()

        val resurrections = buildList {
            if ("zxing" in toml) add("zxing catalog entries (QR is the OS camera's job — delete-dead-weight)")
            if ("kotlincrypto" in toml) add("kotlincrypto catalog entries (no client-side crypto — delete-dead-weight)")
            if (File(
                    repoRoot,
                    "capability",
                ).exists()
            ) {
                add("a capability/ tree (the zone died with the migration — features are :domain packages)")
            }
            // RETIRED ROWS — `LedgerReader`, `LoggingPushReceiver`, `EventMetadataSource`.
            //
            // All three retired a declaration for being **single-implementation interface ceremony**.
            // That judgement did not survive: `enforce-port-boundary` brought `LeaveNotifier` back under
            // this ledger's own reversal clause, because a **port** is not an interface justified by a
            // second implementation — it is the declared boundary where the core stops and an external
            // system begins, and deleting the interface made the composition carry the crossing as an
            // opaque closure instead, invisible to every gate that reads types.
            //
            // `EventMetadataSource` is an HTTP client interface, which is that same boundary by that same
            // definition. Keeping its row would have this guard block a CORRECT change, and a ledger row
            // that argues against the law is worse than no row. The rows are retired rather than left to
            // be discovered one reversal at a time.
            // RETIRED ROW — `interface LeaveNotifier` ("the class is the seam", delete-dead-weight).
            // Deliberately resurrected by `enforce-port-boundary`, per this gate's own contract: the
            // 2026-07-17 judgement was that a single-implementation interface is ceremony, and that
            // reasoning does not survive the law it collides with. A port is not justified by a second
            // implementation — it is the declared boundary where the core stops and an external system
            // begins (`docs/architecture.md`, "Ports are the I/O boundary named for the need"). With the
            // interface gone, the composition handed the core a `suspend (eventId) -> Unit` closure over
            // the adapter instead, which is the same crossing made invisible to every gate that reads
            // types. The row is deleted rather than narrowed because there is nothing left to keep dead.
            if (declared(files, "enum class Arrow" + "Level").isNotEmpty() &&
                declared(files, "enum class Arrow" + """\b""").isNotEmpty()
            ) {
                add("Arrow/ArrowLevel duplicate enum (unified at migration step 9)")
            }
            // The device-manifest accumulator: a second durable structure tracking the same
            // deletion-aware asset set as the upload ledger, with different columns and the same
            // pruning signals. The ledger already had to be right about all of it — a wrong row
            // re-uploads a library or hides a photo forever — so the accumulator could only ever
            // disagree. The manifest is a projection of the ledger now (capability `photo-sharing`).
            declared(files, "fun load" + "Accumulator").forEach {
                add("the device-manifest accumulator in $it (the manifest projects from the ledger)")
            }
            // The world (`:test:world`) and its mini-edge: a second composition of the app, beside the JVM root, and a
            // second backend, beside the real `api/` and the backend mock — each one more answer to keep in step with
            // the first, and the world's operator faces reached past the protocol into the composed core. The JVM root
            // (`:app:jvm`) over the mocks is the one off-device composition; its tests are rig tests (11g2b).
            if (File(
                    repoRoot,
                    "test/world/build.gradle.kts",
                ).exists()
            ) {
                add("a :test:world module (the JVM root over the mocks replaced it — 11g2b)")
            }
            declared(files, """(class|object) (MiniEdge|BackendStore)\b""").forEach {
                add("the mini-edge in $it (the backend mock and the real api/ are the two backends — 11g2b)")
            }
            // The forge: a status screen over canned inputs, as a binary for the marketing screenshots and a desktop
            // harness for review. It could show a frame the app never reached, and it rotted between dispatches. The
            // raws come from the real app over launch adapters, and review is the world harness's (12).
            if (File(
                    repoRoot,
                    "app/ios/forge",
                ).exists()
            ) {
                add("an :app:ios:forge module (the raws come from the real app — 12)")
            }
            if (File(
                    repoRoot,
                    "iosApp/SnapSyncForge",
                ).exists()
            ) {
                add("a SnapSyncForge Xcode target (the raws come from the real app — 12)")
            }
            declared(files, """fun (forgeStatusHost|ForgeHarnessRoot)\b""").forEach {
                add("a forged status host in $it (every UI state is reached through the world harness's levers — 12)")
            }
            // PRODUCTION uploaders only. The retired item was a second *uploader*; the repo names a
            // test after its subject (`HttpEnrollmentTest`), so `class \w*Enrollment` matches the test
            // of the surviving uploader as surely as a resurrected one. Narrowed rather than the row
            // deleted: a real second uploader in production source still trips this.
            val enrollments =
                declared(files.filterNot { it.name.endsWith("Test.kt") }, """class \w*""" + "Enrollment")
            if (enrollments.size > 1) {
                add("Enrollment ×${enrollments.size} (exactly one uploader serves all): ${enrollments.sorted()}")
            }
            // The pre-v0.1 Keychain relics: the unscoped device-id slot the app adopted from before minting, the
            // pre-App-Group album-map slot, and the in-place protection upgrade. Every device has run a build that
            // writes the shared slot background-readable, so each was a branch no installed device can reach.
            declared(files, """\bDEVICE_ID_""" + "LEGACY\\b").forEach {
                add("the legacy device-id slot in $it (every device holds its id in the shared slot)")
            }
            declared(files, """\bALBUM_MAP_""" + "LEGACY\\b").forEach {
                add("the legacy album-map slot in $it (its one-shot migration is retired)")
            }
            declared(files, """\bmigrate""" + "Protection\\b").forEach {
                add("the in-place Keychain protection upgrade in $it (every item is written background-readable)")
            }
            // The other shims for state no ≥0.4 device holds: the start-up removal of the join marker a ≤0.3 build
            // wrote, the album map's Keychain-to-App-Group migration, and the millisecond-tolerant bound parse.
            declared(files, """fun remove""" + "OrphanedJoinMarker\\b").forEach {
                add("the orphaned join-marker removal in $it (no ≥0.4 device holds the key)")
            }
            declared(files, """\b(fun album""" + "MapSource|AlbumMap" + "Source)\\b").forEach {
                add("the album map's Keychain migration in $it (no ≥0.4 device holds a Keychain map)")
            }
            declared(files, """fun parse""" + "Tolerant\\b").forEach {
                add("the millisecond-tolerant bound parse in $it (every stored bound is second precision)")
            }
        }
        assertTrue(
            resurrections.isEmpty(),
            "retired dead weight resurrected — delete it again, or delete its ledger row here in " +
                "the same commit with the argument in the PR:\n  " + resurrections.joinToString("\n  "),
        )
    }

    /**
     * The open-cells list: the port-grid cells no clause covered yet, scaffolding while the gap clauses were written.
     * Emptied and deleted; every grid cell is covered with no exception (`ClauseCoversTest`, which also refuses any
     * other file beside the contracts module's sources and recordings). Same reversal clause as the ledger above.
     */
    @Test
    fun `the open-cells list stays dead`() {
        val resurrections = buildList {
            if (File(repoRoot, "test/contracts/open-" + "cells.txt").exists()) add("test/contracts/open-cells.txt")
            declared(sources(), """\b(open""" + "Cells|OPEN_" + "CELLS)" + "\\w*\\b").forEach {
                add("an open-cells exemption in $it")
            }
        }
        assertTrue(
            resurrections.isEmpty(),
            "every grid cell is covered, with no list of exceptions — write the clause instead:\n  " +
                resurrections.joinToString("\n  "),
        )
    }
}
