package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Production candidates come from the policy-taking read seam** (capability `architecture-guards`;
 * law: `gallery-status`, `photo-selection-policy`).
 *
 * `EventPhotoSet` takes `suspend (SelectionPolicy) -> List<Candidate>` rather than the
 * `ports/CandidateSource` itself, because it lives in `model/` — the innermost zone, which references
 * nothing project-internal outside itself. Features hold the port and bind `source::candidates`.
 *
 * That lambda is the hazard this guard exists for, and it is not hypothetical: the seam previously
 * existed with exactly this signature and **all nine call sites ignored the parameter**, each fetching
 * eagerly elsewhere and handing over a finished list. The policy therefore never reached the platform
 * that could have narrowed on it, the walk paid a ~110 ms resource read for assets it was about to
 * discard, and two consumers of "the admitted set" disagreed. A type cannot catch that; a lambda always
 * type-checks.
 *
 * So this guard reads the *shape*: in production `:domain` code, an `EventPhotoSet` construction must
 * either pass a **method reference** to a real source (`source::candidates`) or close over candidates
 * that a **policy-taking call** already produced. The allowlist below names the second kind — each entry
 * is a place where candidates are genuinely in hand and a fresh read would be wrong, not merely
 * unnecessary.
 *
 * Tests are out of scope on purpose: a test constructing candidates by hand is the point of a test.
 */
class EventPhotoSetSourceTest {

    /**
     * Production sites that legitimately close over already-produced candidates, with the reason each is
     * not a re-introduction of the bug.
     */
    private val allowed = mapOf(
        // The cycle's discovery already took the policy — `discoverResources(token, policy)` — so the
        // platform narrowed before these candidates existed. Re-reading here would fetch twice.
        "UploadCycle.kt" to "candidates came from discoverResources(token, policy)",
        // The manifest projects the LEDGER, not the library: its rows are already-uploaded facts, and
        // there is no walk to take a policy in the first place.
        "DeviceManifest.kt" to "projected from ledger rows; no library read exists here",
    )

    /** `EventPhotoSet(` … `)` opening a lambda block rather than passing a method reference. */
    private val lambdaConstruction = Regex("""EventPhotoSet\([^)]*\)\s*\{""")

    @Test
    fun `production EventPhotoSet constructions take a real source`() {
        val sources = ZoneGates.domainMainFiles()
        assertTrue(sources.isNotEmpty(), "no :domain production source found — has the core moved?")

        val violations = sources.asSequence()
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    val code = line.substringBefore("//")
                    if (!lambdaConstruction.containsMatchIn(code)) return@mapNotNull null
                    if (file.name in allowed) return@mapNotNull null
                    "${file.name}:${i + 1} builds an EventPhotoSet from a lambda rather than a source's " +
                        "`::candidates`. Every nine call sites of this seam once ignored their policy " +
                        "parameter, so the platform never narrowed and two consumers disagreed about the " +
                        "admitted set. Pass a `ports/CandidateSource` method reference, or add this site " +
                        "to the allowlist in this test with the reason its candidates already exist."
                }
            }
            .toList()
        ZoneGates.assertNoViolations("event-photo-set-source", violations)
    }

    /**
     * Vacuity check: the allowlist must describe reality. An entry naming a file that no longer builds an
     * `EventPhotoSet` is a stale exemption, and a stale exemption is how a guard quietly stops guarding.
     */
    @Test
    fun `every allowlisted site still constructs one`() {
        val building = ZoneGates.domainMainFiles().asSequence()
            .filter { lambdaConstruction.containsMatchIn(it.readText()) }
            .mapTo(mutableSetOf()) { it.name }

        val stale = allowed.keys - building
        assertTrue(stale.isEmpty(), "stale allowlist entries — delete them: $stale")
    }

    /** The seam itself must still take the policy, or the guard above is checking a shape that is gone. */
    @Test
    fun `the candidate seam still takes the policy`() {
        val file = File(ZoneGates.domainSrc, "ports/src/commonMain/kotlin/app/snapsync/ports/CandidateSource.kt")
        assertTrue(file.isFile, "ports/CandidateSource.kt not found — has the read seam moved?")
        assertTrue(
            file.readText().contains("candidates(policy: SelectionPolicy)"),
            "CandidateSource no longer takes a SelectionPolicy — the relay this change removed is back",
        )
    }
}
