package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **No Apple identifier appears in the CODE of `:domain`'s `model/`, `ports/` or `feature/`**
 * (capability `architecture-guards`, requirement "The platform-identifier gate"; law:
 * `module-architecture`, "Ports are the I/O boundary named for the need"). Decision record:
 * `changes/…/enforce-port-boundary` (D1, D2, D8).
 *
 * The port law is complete and correct and had no gate for its second violation class. Every zone gate
 * before this one inspects **imports** — and the sites that motivated this one import nothing.
 * `isConfigFileAbsence(domain: String?, code: Long)` was an `NSError` translation table whose signature
 * is `(String?, Long) -> Boolean`: the Apple-ness lived entirely in the literals, so no import gate
 * could ever see it. Four such sites were found by a 122-file audit of `:domain` and moved to the
 * adapters that already held their inputs; this gate is what stops them coming back.
 *
 * **Comments and KDoc are exempt, and that exemption is the whole design** (D2). Measured with the
 * token set below across all ~113 files of the three zones: scanning source **including** comments
 * flags **48** files — an allowlist that size *is* the codebase, and nobody reads it; scanning with
 * comments **stripped** flags **5**, every one of them a real identifier in real code. (The audit in
 * the decision record measured 37 and 6 with a looser token set; the ratio is the finding, not the
 * exact counts.) The exemption will read like an oversight to a future
 * reader ("shouldn't we check the docs too?"), so it is normative in the spec, not folklore here: a
 * KDoc recording that an opaque payload is a `PHAssetResource` on iOS is a binding note, which is the
 * kind of documentation a second implementer needs, and a gate that policed it would be answered by
 * rewording rather than by fixing anything (the same reasoning [MainLaneContainmentTest] applies to
 * the bare word `runBlocking`).
 *
 * ## What this gate CANNOT see — a green run is not a claim that the core is platform-neutral
 *
 * The gate is **lexical**. A decoder over another system's values written in **bare integers** — a
 * `when` over `0L`, `1L`, `2L` that is in fact a `UIApplicationState` table — is indistinguishable
 * from arithmetic and is **not** caught. That is not a corner case: the audit that produced this
 * change sorted `:domain` by "does this file contain Apple vocabulary", and the one site that would
 * silently return a *wrong* answer to a second platform was the one site the sort did not flag.
 *
 * Worse, the gate's hits are **anti-correlated with risk**. It fires on *named* constants, which are
 * the safer kind: `isConfigFileAbsence` was namespaced as `(domain, code)` and its `else -> false`
 * made an unknown platform defer safely. It is silent on *unnamespaced* integer tables, where a second
 * platform's values collide with Apple's and the decode yields a confidently wrong answer rather than
 * a safe default. So: this gate contains a vocabulary. It does not decide whether the core encodes a
 * platform. Read a green run as "no Apple *name* crossed", and nothing more.
 *
 * A naming convention that would have closed the gap — "a decoder over another system's values must
 * name that system in its identifier" — was considered and rejected (D8): every known instance either
 * moves to an adapter in this change or is explicitly out of scope, so the convention would have
 * landed with zero live instances.
 *
 * ## Why Konsist is not used here
 *
 * Unlike [KeychainContainmentTest] and [MainLaneContainmentTest], this gate reads files directly
 * through [ZoneGates], which the five zone gates already share: the scan is zone-scoped (a path
 * property, which is what `ZoneGates.zoneFiles` resolves) rather than repo-wide, and the comment
 * stripping below needs the raw file text either way. Konsist would add a PSI parse and its Kotlin
 * 2.0 version lag for nothing.
 */
class PlatformIdentifierTest {

    /**
     * The zones the law calls platform-free. `flow/` and `compose/` are deliberately **out of scope**:
     * `compose/` is the composition and legitimately names what it builds, and `flow/` is covered by
     * the flow-no-ports gate. Whether `:ui:presentation` should be scanned is unresolved (design, Open
     * Questions) — it is subject to the presentation-imports gate and was not part of the audit.
     */
    private val zones = listOf("model", "ports", "feature")

    /**
     * The Apple vocabulary, in the two shapes Apple actually writes it.
     *
     * **Prefix classes** (`NS*`, `PH*`, `UI*`, `AV*`, `kSec*`) require a **lowercase letter** in the
     * token. That is not a hedge, it is what separates Apple's namespace from ours: Apple's symbols
     * are CamelCase (`NSError`, `PHAssetResourceType`, `UIApplicationState`, `AVAsset`), while an
     * ALL-CAPS run is a SCREAMING_SNAKE constant, which in this codebase is always ours. Without the
     * rule, `AV[A-Z]` flags `AVAILABLE` and `PH[A-Z]` flags every `PHOTO_*` constant in a photo app —
     * i.e. exactly the "false positives on ordinary English" that turn a gate into noise. The cost is
     * stated rather than hidden: an all-caps Apple symbol (`NSURL` written bare, with no
     * `NSURLSession` beside it) falls through this class, and is caught only if it also trips a
     * product name below.
     *
     * **Product names** are matched in their canonical spelling, case-sensitively, plus the
     * SCREAMING_SNAKE forms we ourselves use (`PHOTOKIT`, `URL_SESSION`). Case-sensitivity is
     * load-bearing here too: `PushRegistration` builds the backend's JSON discriminator
     * `kind: "apns"`, which is a wire value on our own protocol, not a platform API the core calls —
     * a case-insensitive `apns` would flag it and teach the next reader that the gate cries wolf.
     */
    private val appleForms = listOf(
        Regex("""\b(?:NS|PH|UI|AV)[A-Za-z0-9]*[a-z][A-Za-z0-9]*"""),
        Regex("""\bkSec[A-Z][A-Za-z0-9]*"""),
        Regex("""\bPhotoKit\b|\bPHOTOKIT\b"""),
        Regex("""\bKeychain"""),
        Regex("""\bDarwin\b"""),
        Regex("""\bURLSession\b|\bURL_SESSION"""),
        Regex("""\bBGTask"""),
        Regex("""\bAPNs\b"""),
        Regex("""\bXPC\b"""),
    )

    /**
     * **Accepted exceptions.** Identifiers the owner has judged to belong in a platform-free zone.
     * Each states its reason; a pin without one decays into a list of whatever failed the gate last.
     */
    private val accepted: Map<String, Set<String>> = mapOf(
        // The `UploadTier` members the pure `resolveComposition` selects. They name upload *tiers*
        // this app defines — not platform APIs the core calls — and the resolver is a total function
        // over `OsFacts`, so a second tier is a new member, not a new coupling. Accepted, not deferred.
        "domain/src/commonMain/kotlin/app/snapsync/model/CompositionMode.kt" to setOf("PHOTOKIT", "URL_SESSION"),
    )

    /**
     * **Deferred debt**, kept separate from [accepted] on purpose: these are real violations of the
     * law that this change did not fix, and reading them as "accepted" would launder them.
     *
     * - `ports/Keychain.kt`, `feature/album/AlbumMapMigration.kt` — the
     *   `Keychain` port family is the same violation (a port named for Apple technology), deferred
     *   with reasons in design D6: it touches `KeychainDeviceIdentity`, whose stored value is written
     *   once and **never rewritten**, so a wrong group or key name freezes permanently on a value
     *   whose loss is unrecoverable — that exact failure has already happened once (2026-07-20: two
     *   device ids across two processes, and the app re-imported every photo it had uploaded) — and
     *   the simulator coverage that would make the reshape verifiable does not exist yet.
     *   **Expiry:** these pins are deleted by D6's value-preserving reshape.
     *   `ports/ConfigPorts.kt` was a third entry in this family until the Stage-2 change deleted
     *   `configReadFrom` — its only `KeychainRead`-typed function — with the read-only legacy
     *   fallback it served (capability `event-rejoin-reconciliation`). The debt was discharged by
     *   the code's removal rather than by the reshape: an expiry trigger is a floor, not a schedule,
     *   and the exact-in-both-directions pin is what made the discharge visible (this gate failed on
     *   the stale pin the moment the code went).
     * - `ports/OsReceipt.kt` — `ReceiptDeadlines.URL_SESSION_EVENTS` names the OS entry point whose
     *   handler budget it holds. `OsReceipt` is the port *for* OS entry points, and its two siblings
     *   (`SILENT_PUSH`, `BACKGROUND_TASK`) are already neutral, so this is a naming slip rather than a
     *   structural one. **Expiry:** dies with the iOS 18–26.0 app-driven tier.
     *
     * A pin here is not permission. It is a receipt, exact in both directions (below), so the debt
     * cannot quietly outlive the code that owes it.
     */
    private val deferred: Map<String, Set<String>> = mapOf(
        "domain/src/commonMain/kotlin/app/snapsync/ports/Keychain.kt" to setOf("Keychain"),
        "domain/src/commonMain/kotlin/app/snapsync/feature/album/AlbumMapMigration.kt" to setOf("Keychain"),
        "domain/src/commonMain/kotlin/app/snapsync/ports/OsReceipt.kt" to setOf("URL_SESSION"),
    )

    private val pins: Map<String, Set<String>> = accepted + deferred

    /**
     * Kotlin source with comments blanked out — the load-bearing half of this gate (D2), shared with
     * [CompositionSeamTest] and pinned below on a sample rather than on the codebase. Its rationale
     * (why it is hand-written, why string literals stay) lives with the implementation in [ZoneGates].
     */
    private fun stripComments(source: String): String = ZoneGates.stripComments(source)

    private fun zoneFiles(): List<File> = zones.flatMap { zone ->
        ZoneGates.zoneFiles(ZoneGates.domainSrc, zone)
            ?: error("platform-identifier gate: no `$zone` zone under domain/src — the scan is stale, fix it")
    }

    private fun File.repoRelative(): String = toRelativeString(ZoneGates.repoRoot).replace('\\', '/')

    /** Every Apple token in a file's code, as `line to token` so a report names a site, not a file. */
    private fun sites(file: File): List<Pair<Int, String>> {
        val code = stripComments(file.readText())
        return appleForms
            .flatMap { form -> form.findAll(code).map { it } }
            .map { code.take(it.range.first).count { c -> c == '\n' } + 1 to it.value }
            .sortedBy { it.first }
    }

    /** file (repo-relative) → the DISTINCT Apple tokens its code contains. */
    private fun scan(): Map<String, Set<String>> = zoneFiles()
        .associate { file -> file.repoRelative() to sites(file).map { it.second }.toSortedSet() }
        .filterValues { it.isNotEmpty() }

    @Test
    fun `no Apple identifier appears in the code of model ports or feature`() {
        val found = scan()
        val expected = pins.mapValues { (_, tokens) -> tokens.toSortedSet() }
        val byPath = zoneFiles().associateBy { it.repoRelative() }

        // Name the drift concretely first — a bare two-map diff makes the reader do the work.
        val unpinned = found.keys.sorted()
            .mapNotNull { path ->
                val extra = found.getValue(path) - (expected[path] ?: emptySet())
                if (extra.isEmpty()) null else {
                    val where = byPath[path]?.let { sites(it) }.orEmpty().filter { it.second in extra }
                    "  + $path — " + where.joinToString(", ") { "line ${it.first}: ${it.second}" }
                }
            }
        val stale = expected.keys.sorted()
            .mapNotNull { path ->
                val gone = expected.getValue(path) - (found[path] ?: emptySet())
                if (gone.isEmpty()) null else "  - $path — pinned but absent from the code: ${gone.joinToString()}"
            }

        assertEquals(
            expected,
            found,
            "the platform-identifier pin inventory drifted (law: module-architecture, \"Ports are the " +
                "I/O boundary named for the need\").\n" +
                (if (unpinned.isEmpty()) "" else unpinned.joinToString("\n") + "\n") +
                (if (stale.isEmpty()) "" else stale.joinToString("\n") + "\n") +
                "  `+` — an Apple identifier reached a platform-free zone. Move the translation to the " +
                "adapter that already holds its inputs (that adapter has the live value on the line " +
                "where it fills the neutral one) and take its test with it, where it can assert against " +
                "the real symbol instead of a copy of itself. If it genuinely belongs here, pin it in " +
                "this file WITH ITS REASON — `accepted` if the owner has judged it, `deferred` if it is " +
                "debt with an expiry.\n" +
                "  `-` — a pin outlived its code. Delete it in the same commit, so the inventory can " +
                "never describe code that is not there.\n" +
                "  And remember what this gate does NOT see: an ABI decoder written in bare integers is " +
                "invisible to it. Green here is not a claim that the core is platform-neutral.",
        )
    }

    /**
     * A gate that scans nothing passes vacuously — and this one is one stale path segment away from
     * scanning nothing, since [ZoneGates.zoneFiles] resolves zones by directory name. The floor is
     * modelled on [KotlinShellGuardTest]'s: assert the scope is real BEFORE trusting a green scan.
     * `zoneFiles` itself throws on an absent zone; this pins that the zones that do resolve are
     * populated, and that the whole scan is of a plausible size rather than one surviving file.
     */
    @Test
    fun `the gate actually scanned the three zones it claims to guard`() {
        zones.forEach { zone ->
            val files = ZoneGates.zoneFiles(ZoneGates.domainSrc, zone)
            assertTrue(
                files != null && files.isNotEmpty(),
                "platform-identifier gate: the `$zone` zone resolved to no sources — the layout moved " +
                    "and this gate is passing on an empty scan",
            )
        }
        val total = zoneFiles().size
        assertTrue(
            total > 80,
            "platform-identifier gate: expected the whole of model/ + ports/ + feature/ in scope " +
                "(~113 files at the time of writing), saw $total — the scan is broken",
        )
    }

    /**
     * The comment stripper is the load-bearing part of this gate: strip too much and it passes on
     * everything, strip too little and it flags 48 files and gets deleted. Both directions are pinned
     * here on a sample rather than on the codebase, so the property is checked even when the zones
     * happen to be clean.
     */
    @Test
    fun `comment stripping drops prose and keeps code`() {
        val sample = """
            /** Bound to a PHAssetResource on iOS. /* nested */ Still a comment: NSError. */
            // A line comment naming UIApplicationState.
            val a = NSFooBar
            val b = "https://example.test/x" // trailing comment naming PHAsset
            val c = ""${'"'}raw string with // and /* inside, naming AVAsset""${'"'}
        """.trimIndent()
        val code = stripComments(sample)

        listOf("PHAssetResource", "NSError", "UIApplicationState", "PHAsset").forEach {
            assertTrue(it !in code, "the stripper left `$it`, which was inside a comment")
        }
        listOf("NSFooBar", "https://example.test/x", "AVAsset").forEach {
            assertTrue(it in code, "the stripper removed `$it`, which is code — the gate would pass on it")
        }
        assertEquals(sample.count { it == '\n' }, code.count { it == '\n' }, "line numbering must survive stripping")
    }
}
