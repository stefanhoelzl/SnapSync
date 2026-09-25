package app.snapsync.contracts

import app.snapsync.model.CandidateRead
import app.snapsync.ports.CandidateSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The states a [CandidateSource]'s library can be found in, as far as a clause cares. */
enum class CandidateSourceState {
    /** The process holds no photo grant (undetermined or denied): there is no library to read. */
    NO_GRANT,

    /** A full grant, and [SEED_COUNT] assets seeded in the clause's window. */
    GRANTED_SEEDED,

    /** A full grant, and nothing seeded in the clause's window. */
    GRANTED_EMPTY_WINDOW,
}

/**
 * What every [CandidateSource] promises (`docs/architecture.md` — this list IS the specification of the
 * port's obligations). The seam's point is that **a counted zero is never "no answer"**: `Readable(empty)`
 * settles the status screen, `NotReadable` says nothing, and the collapse of the two shipped as
 * `SNAPSYNC-14`/`16`.
 *
 * A real binding binds the composition production calls — the grant-aware source over the platform read —
 * because that composition is where the "no answer" half of this contract lives (`docs/architecture.md`,
 * "A live binding binds the composition production calls").
 */
object CandidateSourceContract : Contract<CandidateSourceState, SeededLibrary<CandidateSource>>("CandidateSource") {

    private suspend fun policy(clauseId: String, contributes: Boolean = true) =
        PhotoLibrary.policy(name, clauseId, contributes)

    override val clauses = clauses {

        clause("NO_GRANT_IS_NOT_READABLE", CandidateSourceState.NO_GRANT) { seeded ->
            val read = seeded.port.candidates(policy("NO_GRANT_IS_NOT_READABLE"))
            assertEquals(
                CandidateRead.NotReadable,
                read,
                "without a grant there is no library to count; answering a readable zero is SNAPSYNC-14",
            )
        }

        clause("GRANTED_RETURNS_EVERY_SEEDED_ASSET", CandidateSourceState.GRANTED_SEEDED) { seeded ->
            val read = assertIs<CandidateRead.Readable>(
                seeded.port.candidates(policy("GRANTED_RETURNS_EVERY_SEEDED_ASSET")),
            )
            val returned = read.candidates.mapTo(mutableSetOf()) { it.facts.assetId }
            assertEquals(SEED_COUNT, seeded.ids.size, "the binding seeded what the state promises")
            assertTrue(
                returned.containsAll(seeded.ids),
                "a source may return a superset but never a subset: missing ${seeded.ids - returned}",
            )
        }

        clause("GRANTED_EMPTY_WINDOW_IS_A_COUNTED_ZERO", CandidateSourceState.GRANTED_EMPTY_WINDOW) { seeded ->
            val window = PhotoLibrary.window(name, "GRANTED_EMPTY_WINDOW_IS_A_COUNTED_ZERO")
            val read = assertIs<CandidateRead.Readable>(
                seeded.port.candidates(policy("GRANTED_EMPTY_WINDOW_IS_A_COUNTED_ZERO")),
                "a readable library with nothing in the window is a counted zero, never 'no answer'",
            )
            val inWindow = read.candidates.filter { it.facts.creationDate.iso in window }
            assertTrue(inWindow.isEmpty(), "nothing was seeded in this window, yet ${inWindow.size} came back")
        }

        clause("NON_CONTRIBUTING_POLICY_READS_NOTHING", CandidateSourceState.GRANTED_SEEDED) { seeded ->
            val read = assertIs<CandidateRead.Readable>(
                seeded.port.candidates(policy("NON_CONTRIBUTING_POLICY_READS_NOTHING", contributes = false)),
                "a membership that shares nothing reads a counted zero",
            )
            assertTrue(
                read.candidates.isEmpty(),
                "a deny-everything policy must narrow to nothing; ${read.candidates.size} came back",
            )
        }

        clause("CANDIDATE_RESOURCES_CARRY_UPLOAD_KEYS", CandidateSourceState.GRANTED_SEEDED) { seeded ->
            val read = assertIs<CandidateRead.Readable>(
                seeded.port.candidates(policy("CANDIDATE_RESOURCES_CARRY_UPLOAD_KEYS")),
            )
            val mine = read.candidates.filter { it.facts.assetId in seeded.ids }
            assertEquals(seeded.ids, mine.mapTo(mutableSetOf()) { it.facts.assetId })
            for (candidate in mine) {
                val resources = candidate.resources()
                assertTrue(resources.isNotEmpty(), "a seeded photo has an original to upload")
                for (resource in resources) {
                    assertEquals(candidate.facts.assetId, resource.assetId)
                    assertTrue(
                        resource.filename.startsWith("${candidate.facts.assetId}-"),
                        "an upload key is <assetId>-<role>.<ext>, got ${resource.filename}",
                    )
                }
            }
        }
    }
}
