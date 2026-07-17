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
 * Current pins and their dispositions (the first three are BURN-DOWN items — they move to Kotlin
 * under the transcriber law by forwarding the raw input whole; the last is the one candidate
 * irreducible, pending the SDK check):
 *  - iOSApp.swift `guard` ×2 — push-payload field extraction (forward `userInfo` whole) and the
 *    NSUserActivity type filter (forward the activity whole).
 *  - iOSApp.swift `if` ×1 — the scenePhase split (forward the raw phase, or replace with UIKit
 *    lifecycle notifications observed from Kotlin).
 *  - BackgroundUploadExtension.swift `switch` ×1 — constructs the system result type. Kotlin enums
 *    reach Swift as ObjC classes, so `default:` is compiler-mandated; it maps to FAILURE by design.
 *    Settle on next mac session: if `PHBackgroundResourceUploadProcessingResult` is ObjC-visible,
 *    even this moves into Kotlin and the pin table empties.
 */
class SwiftShellGuardTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** file (relative) → keyword → pinned count. Exact match, both directions. */
    private val pins: Map<String, Map<String, Int>> = mapOf(
        "iosApp/iosApp/iOSApp.swift" to mapOf("if" to 1, "guard" to 2, "switch" to 0),
        "iosApp/iosApp/ContentView.swift" to mapOf("if" to 0, "guard" to 0, "switch" to 0),
        "iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift" to
            mapOf("if" to 0, "guard" to 0, "switch" to 1),
    )

    private fun swiftFiles(): List<File> = File(repoRoot, "iosApp").walkTopDown()
        .filter { it.isFile && it.extension == "swift" }
        .toList()

    private fun decisionCounts(file: File): Map<String, Int> {
        val code = file.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        return listOf("if", "guard", "switch").associateWith { kw ->
            Regex("""\b$kw\b""").findAll(code).count()
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
