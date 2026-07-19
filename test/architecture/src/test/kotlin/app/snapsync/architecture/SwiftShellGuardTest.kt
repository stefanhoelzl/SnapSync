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
}
