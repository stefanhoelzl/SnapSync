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
     * The invariant substring every OS-handler receipt emits **only** when its deadline fires.
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

    /**
     * Every file that may emit [expiryLine], each with the expiry it reports.
     *
     * **Derived and compared in both directions**, not read from one hard-coded path. This started as a
     * single-file check on `OsReceipt`, which was exactly right while `OsReceipt` was the only receipt —
     * and went quietly wrong the moment a second one appeared: `BackgroundEventsReceipts` bounds a wait
     * for a drain signal, and a reword of ITS line would have blinded every consumer for the two
     * background-`URLSession` handlers while this guard stayed green. The set is what needs pinning, not
     * the file.
     *
     * A new emitter therefore fails the build until it is declared here with the expiry it reports, which
     * is the same bargain the trigger-coverage gate above imposes.
     */
    private val expiryEmitters = mapOf(
        "domain/src/commonMain/kotlin/app/snapsync/ports/OsReceipt.kt" to
            "the receipt's own deadline, released while its work runs on",
        "domain/src/commonMain/kotlin/app/snapsync/ports/BackgroundEventsReceipts.kt" to
            "the bound on a wait for a drain signal that never came",
    )

    /** Production Kotlin, excluding build output and test sources (which quote the line in prose). */
    private fun productionKotlin(): List<File> = repoRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { it.path.contains("/build/") }
        .filterNot { it.path.contains("/test/") }
        .filterNot { it.name.endsWith("Test.kt") }
        .toList()

    @Test
    fun `every declared expiry emitter still emits the pinned line`() {
        expiryEmitters.forEach { (path, expiry) ->
            assertTrue(
                read(path).contains(expiryLine),
                "the deadline-expiry log line changed in $path (which reports $expiry). Its presence is " +
                    "the ONLY authoritative answer to whether a handler was released because the work " +
                    "finished or because the bound fired, and the rig's consumers read its ABSENCE as " +
                    "\"finished\" — so a reword makes every receipted scenario pass while hiding the " +
                    "regressions it watches for. Expected to find: \"$expiryLine\"",
            )
        }
    }

    @Test
    fun `the set of expiry emitters is exactly the declared one`() {
        val sources = productionKotlin()
        assertTrue(sources.size >= 100, "scanned only ${sources.size} files — this gate proves nothing")

        val found = sources.filter { it.readText().contains(expiryLine) }
            .map { it.relativeTo(repoRoot).path }
            .toSet()
        assertEquals(
            expiryEmitters.keys,
            found,
            "the set of files emitting the expiry line drifted from the pinned inventory. An UNDECLARED " +
                "emitter is the dangerous one: consumers read the line's absence as \"the work finished\", " +
                "so an emitter nobody pinned can be reworded without any guard noticing. Declare it with " +
                "the expiry it reports, or remove it.",
        )
    }

    @Test
    fun `each emitter emits the line exactly once`() {
        expiryEmitters.keys.forEach { path ->
            assertEquals(
                1,
                Regex(Regex.escape(expiryLine)).findAll(read(path)).count(),
                "the expiry line appears more than once in $path. Absence of the line must remain " +
                    "equivalent to \"the handler was released because the work completed\" — a second " +
                    "emission site within one file destroys that equivalence for every consumer.",
            )
        }
    }
}
