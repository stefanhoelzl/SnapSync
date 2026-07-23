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
            declared(files, "interface Ledger" + "Reader").forEach { add("LedgerReader in $it (interface ceremony — readers use LedgerStore)") }
            declared(files, "class Logging" + "PushReceiver").forEach { add("LoggingPushReceiver in $it (log in the receiver that acts)") }
            declared(files, "interface Event" + "MetadataSource").forEach { add("EventMetadataSource in $it (one GET /events client: EventDirectory)") }
            declared(files, "interface Leave" + "Notifier").forEach { add("LeaveNotifier interface in $it (the class is the seam)") }
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
            val enrollments = declared(files, """class \w*""" + "Enrollment")
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
