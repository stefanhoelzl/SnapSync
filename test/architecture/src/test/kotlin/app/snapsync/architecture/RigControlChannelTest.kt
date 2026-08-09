package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The three gates the dev/test **control channel** needs (capability `architecture-guards`).
 *
 * The channel itself has no spec, deliberately — every surface it exposes is a mechanical projection of a
 * contract specified elsewhere, exactly like `:test:harness-driver`. These guards cover the three things
 * that are NOT projections and would therefore rot silently:
 *
 *  1. **Trigger coverage** — a hand-picked trigger list cannot tell you it has gone stale.
 *  2. **Loopback-only bind** — a one-token widening that reads as a connectivity fix.
 *  3. **The `OsReceipt` expiry line** — whose ABSENCE consumers read as "the work finished".
 */
class RigControlChannelTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
        return file.readText()
    }

    // ── 1. Trigger coverage is derived, never hand-enumerated ─────────────────────────────────────

    private val root = "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt"
    private val hook = "test/rig/src/hook/kotlin/app/snapsync/rig/hook/Boot.kt"

    /**
     * The app-process platform-entry population, derived from the source.
     *
     * Deriving from the `@PlatformEntry` marker is sound HERE — unlike in `PlatformEntryLoggingTest`,
     * where it would be circular — precisely because that guard already proves the marker is present on
     * every member Swift calls. So the marked set is a superset of the driveable set, and reading it
     * cannot inherit the hand-enumeration hole.
     *
     * Scoped to `SnapSyncRoot` on purpose: the rig runs in the app process, so the extension root's
     * entry points are not reachable from it and are not its to account for.
     */
    private fun derivedEntryPoints(): Set<String> {
        val lines = read(root).lines()
        return lines.indices
            .filter { lines[it].trim() == "@PlatformEntry" }
            .mapNotNull { i ->
                lines.drop(i + 1).take(3)
                    .firstNotNullOfOrNull { Regex("""^\s*fun\s+(\w+)\s*\(""").find(it)?.groupValues?.get(1) }
            }
            .toSet()
    }

    private fun wiredTriggers(): Set<String> =
        Regex(""""(\w+)"\s+to\s+(?:\n\s*)?RigTrigger\.""").findAll(read(hook))
            .map { it.groupValues[1] }.toSet()

    private fun excludedTriggers(): Set<String> =
        Regex(""""(\w+)"\s+to\s*\n?\s*"""").findAll(read(hook))
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `every platform entry point is either wired to a trigger or excluded with a reason`() {
        val derived = derivedEntryPoints()
        // Non-vacuity twin: the shell forwards at least the lifecycle, activity, push and background-task
        // entries. A regex that stops matching must fail here, never pass empty.
        assertTrue(
            derived.size >= 10,
            "derived only ${derived.size} platform entry points from $root — the derivation is broken",
        )
        val wired = wiredTriggers()
        val excluded = excludedTriggers()

        val overlap = wired intersect excluded
        assertTrue(overlap.isEmpty(), "these are both wired AND excluded, which cannot both be true: $overlap")

        assertEquals(
            derived.sorted(),
            (wired + excluded).sorted(),
            "the rig's trigger inventory drifted from the platform-entry population.\n" +
                "  unaccounted (add a trigger, or exclude it WITH THE REASON that makes the omission " +
                "safe): ${(derived - wired - excluded).sorted()}\n" +
                "  named but no longer an entry point (drop it): ${((wired + excluded) - derived).sorted()}",
        )
    }

    @Test
    fun `every exclusion states a reason`() {
        val text = read(hook)
        val reasoned = Regex(""""(\w+)"\s+to\s*\n?\s*"([^"]{20,})""").findAll(text)
            .map { it.groupValues[1] }.toSet()
        val unreasoned = excludedTriggers() - reasoned
        assertTrue(
            unreasoned.isEmpty(),
            "an exclusion with no stated reason is indistinguishable from an oversight: $unreasoned",
        )
    }

    // ── 2. The channel binds the loopback and nothing else ────────────────────────────────────────

    @Test
    fun `the control channel names no bind address but the loopback constant`() {
        val sources = File(repoRoot, "test/rig/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(sources.isNotEmpty(), "loopback gate scanned zero sources — the rig module moved")

        val addresses = sources.flatMap { file ->
            Regex(""""((?:\d{1,3}\.){3}\d{1,3}|0\.0\.0\.0|::|\*)"""").findAll(file.readText())
                .map { file.toRelativeString(repoRoot) to it.groupValues[1] }
        }
        val offending = addresses.filterNot { it.second == "127.0.0.1" }
        assertTrue(
            offending.isEmpty(),
            "the control channel forces OS callbacks and exposes event state, on a phone attached to " +
                "whatever network it is on — it binds the loopback and nothing else. Reach it through a " +
                "host-side port forward:\n  " +
                offending.joinToString("\n  ") { "${it.first}: \"${it.second}\"" },
        )
        assertTrue(
            addresses.any { it.second == "127.0.0.1" },
            "found NO loopback literal in the rig — the gate is scanning the wrong thing",
        )
    }

    // ── 3. The receipt's expiry line is pinned ────────────────────────────────────────────────────

    /**
     * The invariant substring of the line `OsReceipt` emits **only** when its deadline fires.
     *
     * Consumers read the line's PRESENCE as "the bound engaged" and therefore its ABSENCE as "the work
     * finished" — so a reword turns every consumer green while hiding exactly the defect class it watches
     * for. Silent, and in the dangerous direction.
     *
     * Pinned as text rather than extracted into a shared constant on purpose: a public `ports/` constant
     * whose only consumer is test equipment is `:domain` API added for dev tooling, which this design
     * refuses everywhere else. `RuntimeIdentityTest` pins literals the same way, for the same reason.
     */
    private val expiryLine = "OS handler released on its"

    @Test
    fun `the OsReceipt deadline-expiry line is pinned`() {
        val source = read("domain/src/commonMain/kotlin/app/snapsync/ports/OsReceipt.kt")
        assertTrue(
            source.contains(expiryLine),
            "the OsReceipt deadline-expiry log line changed. Its presence is the ONLY authoritative " +
                "answer to whether a handler was released because the work finished or because the bound " +
                "fired, and the rig's consumers read its ABSENCE as \"finished\" — so a reword makes every " +
                "receipted scenario pass while hiding the regressions it watches for. Expected to find: " +
                "\"$expiryLine\"",
        )
    }

    @Test
    fun `the expiry line is emitted on the expiry path only`() {
        val source = read("domain/src/commonMain/kotlin/app/snapsync/ports/OsReceipt.kt")
        assertEquals(
            1,
            Regex(Regex.escape(expiryLine)).findAll(source).count(),
            "the expiry line appears more than once in OsReceipt. Absence of the line must remain " +
                "equivalent to \"the handler was released because the work completed\" — a second " +
                "emission site destroys that equivalence for every consumer.",
        )
    }
}
