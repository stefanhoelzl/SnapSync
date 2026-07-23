package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The capture-date bounds are compared in exactly one place** (capability `architecture-guards`; law:
 * `photo-selection-policy`).
 *
 * This guard exists because of a shipped bug, and it is aimed precisely at how that bug happened rather
 * than at how it looked.
 *
 * `photo-selection-policy` has always said "one policy, applied at one place". Four consumers needed the
 * answer — the byte upload, the device manifest, the own-device status total `N`, and the join-time
 * shareable-count preview — and each assembled the rules by hand. When `add-event-date-range` added the
 * capture-date **ceiling**, it reached the byte filter and the preview and missed the other two. Nothing
 * caught it: `Contribution.Since -> contribution.cutoff` (silently dropping `until`) compiles exactly as
 * readily as the correct destructure, and every fixture in the suite used `until = null`, so no test
 * could tell the difference. It took a closed-window event with a post-ceiling photo on a real device to
 * surface it — as a status screen pegged below 100% forever, and a `device.json` offering other members
 * bytes that were never uploaded.
 *
 * So the rule is not "apply both bounds"; a consumer that re-applies the policy correctly today is still
 * the *shape* that drifts tomorrow. The rule is that a consumer may not compare a capture date **at
 * all** — it receives the admitted set and filters by nothing. Comparing `creationDate` against anything
 * is therefore a violation wherever it appears outside `SelectionPolicy.kt`, however correct the
 * comparison happens to be.
 *
 * **Textual and blunt on purpose**, like the other zone gates: it reads source rather than types, so it
 * catches a comparison written against a metadata string, a raw asset field, or a ledger row alike —
 * none of which a compiler check on the value classes would see.
 */
class SelectionPolicyContainmentTest {

    /** The one file allowed to compare a capture date — the single admission. */
    private val admissionSite = "SelectionPolicy.kt"

    /**
     * Files that legitimately mention `creationDate` without deciding admission: the neutral facts value
     * and the mapping that *carries* the date across the walk seam, the wire/manifest DTOs that merely
     * hold it, and the date vocabulary itself.
     */
    private val carriers = setOf(
        "AssetFacts.kt",
        "EventDates.kt",
        "RawAsset.kt",
        "RawAssetMapping.kt",
        "DeviceManifest.kt",
        "DeviceManifestMapping.kt",
        "Ledger.kt",
        "Resource.kt",
    )

    /** A comparison operator applied to a capture date — the shape that drifted. */
    private val comparison = Regex("""creationDate\s*(>=|<=|>|<)|(>=|<=|>|<)\s*creationDate""")

    @Test
    fun `no consumer compares a capture date outside the single admission`() {
        val roots = listOf(
            File(ZoneGates.domainSrc, "commonMain/kotlin/app/snapsync"),
        ).filter { it.isDirectory }
        assertTrue(roots.isNotEmpty(), "no :domain source found — has the module moved?")

        val violations = roots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filter { it.name != admissionSite && it.name !in carriers }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    val code = line.substringBefore("//")
                    // Prose describes the comparison constantly — that is the point of the doc comments.
                    // Only executable lines are the guard's business.
                    if (isComment(code)) return@mapNotNull null
                    if (!comparison.containsMatchIn(code)) return@mapNotNull null
                    "${file.name}:${i + 1} compares a capture date outside `$admissionSite` — a consumer " +
                        "takes the admitted set, it does not re-apply a bound. This is the exact shape that " +
                        "dropped the ceiling at the device manifest and at `N` (capability " +
                        "`photo-selection-policy`)."
                }
            }
        ZoneGates.assertNoViolations("selection-policy-containment", violations)
    }

    /** A KDoc / block-comment continuation line. Crude, and sufficient for this codebase's style. */
    private fun isComment(code: String): Boolean =
        code.trimStart().let { it.startsWith("*") || it.startsWith("/*") }

    /** The guard is only worth anything if the admission is actually where the comparison lives. */
    @Test
    fun `the single admission does compare the capture date`() {
        val file = File(ZoneGates.domainSrc, "commonMain/kotlin/app/snapsync/model/$admissionSite")
        assertTrue(file.isFile, "$admissionSite not found at $file — has the admission moved?")
        assertTrue(
            comparison.containsMatchIn(file.readText()),
            "$admissionSite no longer compares a capture date — the guard above would then pass vacuously " +
                "while nothing enforces the range at all",
        )
    }
}
