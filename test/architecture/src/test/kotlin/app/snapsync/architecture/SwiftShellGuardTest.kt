package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Swift is a transcriber — its decisions are pinned, exactly** (capability `architecture-guards`;
 * law: `module-architecture` "Shells are wiring only"; decision record: `establish-target-architecture`).
 *
 * The zero-conditional shell law is enforced on Kotlin by tooling that reads Kotlin; nothing in this
 * repo parses Swift — and the Swift shells are where a silent shipping failure already lived (the
 * dropped event links, 2026-07-16). So the Swift posture is pinned by COUNT, per file, per decision
 * keyword, and the match is EXACT in both directions: a new decision fails (it must move to Kotlin
 * or argue a Swift-only API into a pin), and a removed one fails too (the pin table must shrink in
 * the same commit, so the table never overstates the debt).
 *
 * Since migration step 12 the shells are transcribers: every OS callback forwards its raw,
 * ObjC-visible input whole (`userInfo` dictionaries, the `NSUserActivity`, the scene lifecycle via
 * `NSNotificationCenter` observed from Kotlin), so `if`/`guard`/`switch` are all pinned at ZERO.
 *
 * ONE pin remains, and it is irreducible (settled forcing proof ① of migration step 12):
 *  - BackgroundUploadExtension.swift `??` ×1 — `PHBackgroundResourceUploadProcessingResult` is
 *    **Swift-only** (declared in the SDK's swiftinterface, no ObjC header), so Kotlin cannot
 *    construct it; the shell builds it via `init?(rawValue:)` from the raw Int the tested Kotlin
 *    mapping decided, and the `?? .failure` nil fallback keeps an untaught raw value a visible,
 *    retried failure (the posture the previous `switch`'s compiler-mandated `default:` carried).
 *    Re-evaluate at iOS 27 GM (~Sept 2026) with the async extension protocol.
 *
 * `??` joined the counted keywords with that pin (the `architecture-guards` spec always named it):
 * a nil-coalesce is a decision by another name, and counting only `if`/`guard`/`switch` would let
 * the table read zero while fallbacks accumulate.
 */
class SwiftShellGuardTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val keywords = listOf("if", "guard", "switch", "??")

    /** file (relative) → keyword → pinned count. Exact match, both directions. */
    private val pins: Map<String, Map<String, Int>> = mapOf(
        "iosApp/iosApp/iOSApp.swift" to mapOf("if" to 0, "guard" to 0, "switch" to 0, "??" to 0),
        "iosApp/iosApp/ContentView.swift" to mapOf("if" to 0, "guard" to 0, "switch" to 0, "??" to 0),
        "iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift" to
            mapOf("if" to 0, "guard" to 0, "switch" to 0, "??" to 1),
        // The marketing-screenshot binary's shell. All zeros, and it should stay that way: this target
        // exists to render one screen, and every OS callback the app's shell transcribes is one this
        // binary has no entitlement to receive. A decision appearing here would mean forge has grown a
        // second way to be driven.
        "iosApp/SnapSyncForge/ForgeApp.swift" to
            mapOf("if" to 0, "guard" to 0, "switch" to 0, "??" to 0),
    )

    private fun swiftFiles(): List<File> = File(repoRoot, "iosApp").walkTopDown()
        .filter { it.isFile && it.extension == "swift" }
        .toList()

    private fun decisionCounts(file: File): Map<String, Int> {
        val code = file.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        return keywords.associateWith { kw ->
            val pattern = if (kw == "??") Regex("""\?\?""") else Regex("""\b$kw\b""")
            pattern.findAll(code).count()
        }
    }

    @Test
    fun `the guard actually scanned the Swift shells`() {
        val files = swiftFiles().map { it.toRelativeString(repoRoot) }.toSet()
        assertTrue(files.isNotEmpty(), "Swift guard scanned nothing — iosApp/ has moved")
        assertEquals(
            pins.keys,
            files,
            "the Swift file set changed — a new shell file needs a pin row (even an all-zero one), " +
                "and a deleted one must leave the table",
        )
    }

    @Test
    fun `every Swift decision is pinned, exactly`() {
        swiftFiles().forEach { file ->
            val relative = file.toRelativeString(repoRoot)
            val expected = pins[relative] ?: return@forEach // file-set drift is the other test's job
            assertEquals(
                expected,
                decisionCounts(file),
                "$relative: Swift decision count drifted from its pins. MORE than pinned: the new " +
                    "decision moves to Kotlin (forward the raw ObjC-visible input whole — the " +
                    "transcriber law) unless a Swift-only API forces it, in which case pin it WITH " +
                    "the forcing proof in this file's KDoc. FEWER than pinned: good — shrink the pin " +
                    "in this same commit so the table never overstates the debt.",
            )
        }
    }

    /**
     * **Every Swift shell function forwards to Kotlin** (spec `architecture-guards`, "The shell
     * gates"; spec `module-architecture`, "Absence is never silent").
     *
     * A shell function that reaches no Kotlin is invisible by construction: this layer is
     * wiring-only and untested by project rule, and os_log redacts an interpolated `NSLog`
     * wholesale, so a Swift-side log line reaches neither `idevicesyslog` nor `debug.log`. Two such
     * holes existed when this rule was written, and both were platform events nobody could see:
     * `notifyTermination()` — the OS announcing it is KILLING the upload cycle — did nothing at all,
     * and `didFailToRegisterForRemoteNotificationsWithError` only `NSLog`ged, so a device that
     * silently never receives a push said so nowhere.
     *
     * "Does nothing" is not an exemption. A no-op may be the right BEHAVIOR — `notifyTermination`
     * genuinely has nothing to persist — but it is never the right RECORD.
     */
    @Test
    fun `every Swift shell function forwards to Kotlin`() {
        // The forge binary has its own entry point because it links a different framework — it cannot
        // reach `MainViewControllerKt`, which lives in `SnapSyncKit` and would drag `SnapSyncRoot` in.
        val roots = listOf(
            "SnapSyncRoot.shared",
            "UploadExtensionRoot.shared",
            "MainViewControllerKt",
            "ForgeViewControllerKt",
        )
        var checked = 0
        swiftFiles().forEach { file ->
            val relative = file.toRelativeString(repoRoot)
            val lines = file.readText().lines()
            lines.forEachIndexed { index, line ->
                if (!Regex("""^\s{4}(?:required |private |public )?func\s+\w+""").containsMatchIn(line)) return@forEachIndexed
                val body = lines.drop(index).take(BODY_SCAN_LINES).joinToString("\n")
                // Match exemptions against the whole SIGNATURE: several of these are named
                // `application(...)` and are told apart only by a later argument label, and the
                // signature may span lines.
                val signature = body.substringBefore("{")
                if (EXEMPT.any { signature.contains(it) }) return@forEachIndexed
                checked++
                assertTrue(
                    roots.any { body.contains(it) },
                    "$relative:${index + 1} — this Swift function reaches no Kotlin:\n    ${line.trim()}\n" +
                        "A shell function either forwards to the composition root or does not exist. " +
                        "Doing no WORK can be right; recording NOTHING never is — this layer is " +
                        "untested by rule and os_log redacts an interpolated NSLog wholesale, so a " +
                        "Swift-only log line lands nowhere at all. Forward the raw ObjC-visible input " +
                        "whole and let Kotlin decide and record.",
                )
            }
        }
        // Non-vacuity twin: a changed `func` shape must fail here, never pass by matching nothing.
        assertTrue(checked >= 8, "the forwarding rule matched only $checked functions — the scan is broken")
    }

    private companion object {
        /** Enough lines to cover a shell function's body; they are all short by the transcriber law. */
        const val BODY_SCAN_LINES = 14

        /**
         * Pinned exemptions, each with its forcing proof (spec `module-architecture`, "Necessity
         * claims carry forcing proofs"). Add one only for a function the platform requires to EXIST
         * but never uses to tell us anything — never for one that merely looks uninteresting.
         *
         * - `configurationForConnecting` — the OS asking WHICH scene configuration to use, answered
         *   with a `UISceneConfiguration` (API contract). It is a question about our own wiring, not
         *   a fact about the outside world, so it has nothing to record; and it is the hook that
         *   installs the scene delegate, whose absence `EventLinkDeliveryTest` already fails on
         *   loudly. Expiry: if it ever branches on `options` (an incoming activity, say), it becomes
         *   a delivery path and the exemption dies.
         * - `updateUIViewController` — a `UIViewControllerRepresentable` protocol requirement (API
         *   contract). SwiftUI calls it to push new SwiftUI state into a wrapped controller; the
         *   wrapped controller here is Compose, which owns its own state entirely and is handed
         *   nothing from SwiftUI. It is a re-render hook, not a platform EVENT, so there is no fact
         *   about the outside world for it to record. Expiry: if `ComposeView` ever gains a property
         *   SwiftUI feeds it, this becomes a real update path and the exemption dies with it.
         */
        val EXEMPT = setOf("updateUIViewController", "configurationForConnecting")
    }
}
