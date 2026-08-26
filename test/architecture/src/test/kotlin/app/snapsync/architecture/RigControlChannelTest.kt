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

    // ── 1. Trigger coverage is derived per COMPOSITION ROOT, never hand-enumerated ────────────

    private val hook = "test/rig/src/hook/kotlin/app/snapsync/rig/hook/Boot.kt"

    /**
     * Where the `/user` maps live. NOT the hook: a command map's bodies are full of decisions, and the hook
     * is compiled into `:app:ios` and scanned by the shell gate. So the hook names the builders and the
     * builders hold the maps, one module across the seam.
     */
    private val builders = "test/rig/src/iosMain/kotlin/app/snapsync/rig/IosRigBuilders.kt"

    /** The host whose public command surface `/user` is derived from. */
    private val hostFile =
        "ui/presentation/src/commonMain/kotlin/app/snapsync/presentation/StatusContainerHost.kt"

    /**
     * The composition roots the channel reaches, by their `/os/<root>/…` group name.
     *
     * **This table is the novelty gate, not a convenience.** Its keys are asserted equal to the groups the
     * hook declares, so adding a group without telling the guard where to derive its population from fails
     * the build rather than quietly creating an unscanned namespace (`architecture-guards`, "Gates fail
     * closed on novelty").
     *
     * That the channel can reach two roots from one process is a property of the simulator host, where the
     * OS never invokes the upload extension and the rig invokes its root directly. In production these are
     * two processes.
     */
    private val roots = mapOf(
        "app" to RootUnderChannel(
            source = "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt",
            excludedIn = "excludedTriggers",
        ),
        "photokit-ext" to RootUnderChannel(
            source = "app/ios/extension/src/iosMain/kotlin/app/snapsync/ios/upload/UploadExtensionRoot.kt",
            excludedIn = "excludedExtensionTriggers",
        ),
    )

    /**
     * @param source the root's own file, whose `@PlatformEntry` members are its population.
     * @param excludedIn the hook function holding this group's exclusions. Every group has one, even when
     *   it excludes nothing — a group with no exclusion function is a group whose omissions could not be
     *   read, and the two are not the same answer.
     */
    private class RootUnderChannel(val source: String, val excludedIn: String)

    /**
     * The group names the hook declares.
     *
     * Matched on the constructor's name ending in `TriggerGroup`, which both the plain constructor and a
     * builder like `extensionTriggerGroup` satisfy, so a group whose maps live in another module is still
     * seen. Deliberately not scoped by argument order: a derivation that broke when someone reordered a
     * named argument would fail as if the inventory had drifted.
     */
    private fun declaredGroups(): Set<String> =
        Regex(""""([\w-]+)"\s+to\s+\w*TriggerGroup\(""")
            .findAll(read(hook))
            .map { it.groupValues[1] }
            .toSet()

    /**
     * One `private fun <name>(` block of the hook, up to the next top-level `private fun` or end of file.
     */
    private fun hookBlock(function: String): String {
        val text = read(hook)
        val at = text.indexOf("private fun $function(")
        assertTrue(at >= 0, "guard is scanning nothing — no `private fun $function(` in $hook")
        val next = text.indexOf("\nprivate fun ", at + 1)
        return if (next < 0) text.substring(at) else text.substring(at, next)
    }

    /** The `object <Name>` a root file declares — the receiver its entry points are invoked on. */
    private fun rootType(file: String): String =
        Regex("""^object (\w+)""", RegexOption.MULTILINE).find(read(file))?.groupValues?.get(1)
            ?: fail("no top-level `object` in $file — the root-type derivation is broken")

    /**
     * One root's platform-entry population, derived from its source.
     *
     * Deriving from the `@PlatformEntry` marker is sound HERE — unlike in `PlatformEntryLoggingTest`,
     * where it would be circular — precisely because that guard already proves the marker is present on
     * every member Swift calls. So the marked set is a superset of the driveable set, and reading it
     * cannot inherit the hand-enumeration hole.
     */
    private fun derivedEntryPoints(file: String): Set<String> {
        val lines = read(file).lines()
        return lines.indices
            .filter { lines[it].trim() == "@PlatformEntry" }
            .mapNotNull { i ->
                lines.drop(i + 1).take(3)
                    .firstNotNullOfOrNull { Regex("""^\s*fun\s+(\w+)\s*\(""").find(it)?.groupValues?.get(1) }
            }
            .toSet()
    }

    /**
     * Which of a root's members the channel actually REACHES — scraped from the call, not the route.
     *
     * The same reasoning `/user` already records: what the coverage question asks is whether every entry
     * point is reachable or consciously not, and that is answered by the call. It also means a route leaf
     * may be renamed without the guard reporting a phantom mismatch.
     *
     * Both call shapes count — `Root.member(` for a lambda body and `Root::member` for a method reference —
     * because the hook uses each where it reads better, and a derivation that saw only one would report a
     * wired entry point as unaccounted.
     *
     * Whole-file rather than per-function: the hook is the single place either root is reached from, and a
     * root's non-entry-point members (`SnapSyncRoot.app`, `.host`, `.permission`) are referenced without a
     * trailing `(` or `::`, so they cannot be mistaken for wired triggers.
     */
    private fun wiredTriggers(type: String): Set<String> =
        Regex("""\b$type(?:\.(\w+)\(|::(\w+))""").findAll(read(hook))
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toSet()

    private fun excludedTriggers(function: String): Set<String> =
        Regex(""""(\w+)"\s+to\s*\n?\s*"""").findAll(hookBlock(function))
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `every declared trigger group has a root the guard can derive from`() {
        val declared = declaredGroups()
        assertTrue(
            declared.isNotEmpty(),
            "derived no trigger groups from $hook — the `triggerGroups` derivation is broken, and a guard " +
                "that scans nothing fails open",
        )
        assertEquals(
            roots.keys.sorted(),
            declared.sorted(),
            "the hook's trigger groups drifted from the roots this guard knows how to scan.\n" +
                "  declared but unscanned (add its root here): ${(declared - roots.keys).sorted()}\n" +
                "  scanned but no longer declared (drop it): ${(roots.keys - declared).sorted()}",
        )
    }

    @Test
    fun `every platform entry point is either wired to a trigger or excluded with a reason`() {
        val failures = buildList {
            for ((group, root) in roots) {
                val derived = derivedEntryPoints(root.source)
                // Non-vacuity, per group: a regex that stops matching must fail here, never pass empty.
                if (derived.isEmpty()) {
                    add("group '$group': derived NO platform entry points from ${root.source} — broken")
                    continue
                }
                val wired = wiredTriggers(rootType(root.source))
                val excluded = excludedTriggers(root.excludedIn)
                val overlap = wired intersect excluded
                if (overlap.isNotEmpty()) {
                    add("group '$group': both wired AND excluded, which cannot both be true: $overlap")
                }
                val accounted = wired + excluded
                if (derived.sorted() != accounted.sorted()) {
                    add(
                        "group '$group': the rig's trigger inventory drifted from ${root.source}'s " +
                            "platform-entry population.\n" +
                            "  unaccounted (add a trigger, or exclude it WITH THE REASON that makes the " +
                            "omission safe): ${(derived - accounted).sorted()}\n" +
                            "  named but no longer an entry point (drop it): ${(accounted - derived).sorted()}",
                    )
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n\n"))
    }

    /**
     * The app root carries the lifecycle, activity, push and background-task entries at minimum. Held
     * separately from the per-group non-vacuity above because this is the one population whose size is
     * known, and a silent shrink there is the failure the derivation exists to refuse.
     */
    @Test
    fun `the app root's derived population is not vacuous`() {
        val derived = derivedEntryPoints(roots.getValue("app").source)
        assertTrue(
            derived.size >= 10,
            "derived only ${derived.size} platform entry points from the app root — the derivation is broken",
        )
    }

    /**
     * Two roots may legitimately declare an entry point of the same name, and the per-group comparison is
     * what keeps both accounted. A flat set across roots would deduplicate the pair and drop one from the
     * inventory while still passing — which is why grouping is structural here rather than cosmetic.
     *
     * Asserted as a property of the derivation rather than of today's names: the guard must be scanning
     * more than one group for the grouping to mean anything at all.
     */
    @Test
    fun `coverage is accounted per group, so two roots may share a member name`() {
        assertTrue(
            roots.size >= 2,
            "only ${roots.size} trigger group(s) — the per-group derivation is untested by this suite",
        )
        val perGroup = roots.mapValues { (_, root) -> derivedEntryPoints(root.source) }
        val total = perGroup.values.sumOf { it.size }
        val flattened = perGroup.values.flatten().toSet().size
        val shared = perGroup.values.flatten().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(
            total - flattened, shared.size,
            "the per-group populations are inconsistent with their union; shared names: $shared",
        )
    }

    @Test
    fun `every exclusion states a reason`() {
        val unreasoned = buildList {
            for ((group, root) in roots) {
                val block = hookBlock(root.excludedIn)
                val reasoned = Regex(""""(\w+)"\s+to\s*\n?\s*"([^"]{20,})""").findAll(block)
                    .map { it.groupValues[1] }.toSet()
                (excludedTriggers(root.excludedIn) - reasoned).forEach { add("$group/$it") }
            }
        }
        assertTrue(
            unreasoned.isEmpty(),
            "an exclusion with no stated reason is indistinguishable from an oversight: $unreasoned",
        )
    }

    // ── 1b. The SAME bargain for `/user`, over a different derived population ─────────────────────

    /**
     * The host's public command surface, derived from source.
     *
     * `fun on…` only. `onSendDiagnostics` is a `val` holding a nullable lambda rather than a function, and
     * it is excluded by name below — deriving `val`s too would drag in every read-model property the host
     * exposes, which are not commands and have no business in a coverage assertion about commands.
     */
    private fun derivedUserCommands(): Set<String> =
        Regex("""^\s{4}fun (on\w+)\(""", RegexOption.MULTILINE)
            .findAll(read(hostFile))
            .map { it.groupValues[1] }
            .toSet() + "onSendDiagnostics"

    /**
     * Which host members the wired commands actually REACH — scraped from `host().onX(` in the builders,
     * not from the URL each is published under.
     *
     * Deriving from the URL name was the first attempt and it was wrong: `/user/leave` reaches
     * `onLeaveEvent`, so a name-based derivation reported `onLeave` unaccounted and `onLeaveEvent` missing,
     * which is a mismatch in the guard rather than in the code. What the coverage question actually asks is
     * "is every host command reachable or consciously not", and that is answered by the call, not the URL.
     */
    private fun wiredUserCommands(): Set<String> =
        Regex("""host\(\)\.(on\w+)\(""").findAll(read(builders))
            .map { it.groupValues[1] }.toSet()

    private fun excludedUserCommands(): Set<String> =
        Regex(""""(on\w+)"\s+to\s*\n?\s*"""").findAll(read(builders))
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `every host command is either wired or excluded with a reason`() {
        val derived = derivedUserCommands()
        assertTrue(
            derived.size >= 15,
            "derived only ${derived.size} commands from $hostFile — the derivation is broken, and a guard " +
                "that scans nothing fails open",
        )
        val wired = wiredUserCommands()
        val excluded = excludedUserCommands()

        val accounted = wired + excluded
        val overlap = wired intersect excluded
        assertTrue(overlap.isEmpty(), "these are both wired AND excluded, which cannot both be true: $overlap")

        assertEquals(
            derived.sorted(),
            accounted.sorted(),
            "the rig's /user inventory drifted from the host's public command surface.\n" +
                "  unaccounted (wire it, or exclude it WITH THE REASON that makes the omission safe): " +
                "${(derived - accounted).sorted()}\n" +
                "  named but no longer a host command (drop it): ${(accounted - derived).sorted()}",
        )
    }

    @Test
    fun `every user-command exclusion states a reason`() {
        val reasoned = Regex(""""(on\w+)"\s+to\s*\n?\s*"([^"]{20,})""").findAll(read(builders))
            .map { it.groupValues[1] }.toSet()
        val unreasoned = excludedUserCommands() - reasoned
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
