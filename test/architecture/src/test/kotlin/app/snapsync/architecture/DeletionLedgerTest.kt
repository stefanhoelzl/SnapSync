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
            if (File(repoRoot, "capability").exists()) add("a capability/ tree (the zone died with the migration — features are :domain packages)")
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
            // begins (`module-architecture`, "Ports are the I/O boundary named for the need"). With the
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
            // disagree. The manifest is a projection of the ledger now (capability `device-manifest`).
            declared(files, "fun load" + "Accumulator").forEach {
                add("the device-manifest accumulator in $it (the manifest projects from the ledger)")
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
        }
        assertTrue(
            resurrections.isEmpty(),
            "retired dead weight resurrected — delete it again, or delete its ledger row here in " +
                "the same commit with the argument in the PR:\n  " + resurrections.joinToString("\n  "),
        )
    }
}
