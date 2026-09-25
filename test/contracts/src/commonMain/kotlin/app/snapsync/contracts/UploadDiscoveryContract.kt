package app.snapsync.contracts

import app.snapsync.model.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.ports.UploadDiscovery
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The states an [UploadDiscovery]'s library can be found in, as far as a clause cares. */
enum class UploadDiscoveryState {
    /** The process holds no photo grant (undetermined or denied). */
    NO_GRANT,

    /** A full grant, and [SEED_COUNT] assets seeded in the clause's window. */
    GRANTED_SEEDED,
}

/**
 * What every [UploadDiscovery] promises (`docs/architecture.md` — this list IS the specification of the
 * port's obligations).
 *
 * The walk's `fullEnumeration` is a **deletion authority**: the cycle deletes the in-window ledger rows of every
 * asset an authoritative walk did not return (capability `photo-sharing`). So a walk over a library the process
 * could not read must say it is not authoritative. An empty authoritative walk deletes every in-window row.
 */
object UploadDiscoveryContract : Contract<UploadDiscoveryState, SeededLibrary<UploadDiscovery>>("UploadDiscovery") {

    private suspend fun policy(clauseId: String) = PhotoLibrary.policy(name, clauseId)

    override val clauses = clauses {

        clause("NO_GRANT_WALK_IS_NOT_AUTHORITATIVE", UploadDiscoveryState.NO_GRANT) { seeded ->
            val walk = seeded.port.discover(policy("NO_GRANT_WALK_IS_NOT_AUTHORITATIVE"))
            assertFalse(
                walk.fullEnumeration,
                "a library the process may not read is no evidence that anything left it; an authoritative " +
                    "empty walk deletes every in-window row",
            )
            assertTrue(walk.candidates.isEmpty())
        }

        clause("GRANTED_WALK_IS_AUTHORITATIVE_AND_COMPLETE", UploadDiscoveryState.GRANTED_SEEDED) { seeded ->
            val walk = seeded.port.discover(policy("GRANTED_WALK_IS_AUTHORITATIVE_AND_COMPLETE"))
            assertTrue(walk.fullEnumeration, "a full grant's walk read the library itself")
            val returned = walk.candidates.mapTo(mutableSetOf()) { it.facts.assetId }
            assertEquals(SEED_COUNT, seeded.ids.size, "the binding seeded what the state promises")
            assertTrue(
                returned.containsAll(seeded.ids),
                "an in-window asset the walk omits has its rows deleted as departed: missing ${seeded.ids - returned}",
            )
        }

        clause("RESOLVE_RETURNS_EXACTLY_THE_KEYS_STILL_THERE", UploadDiscoveryState.GRANTED_SEEDED) { seeded ->
            val clauseId = "RESOLVE_RETURNS_EXACTLY_THE_KEYS_STILL_THERE"
            val walk = seeded.port.discover(policy(clauseId))
            val present = walk.candidates.filter { it.facts.assetId in seeded.ids }
                .flatMap { it.resources() }.mapTo(mutableSetOf()) { it.filename }
            assertTrue(present.isNotEmpty(), "the seeded assets have resources to resolve")
            val departed = uploadKey(absentAssetId(clauseId), ResourceRole.PRIMARY, "IMG_0001.JPG")

            val resolved = seeded.port.resourcesFor(present + departed)

            assertEquals(
                present,
                resolved.mapTo(mutableSetOf()) { it.filename },
                "each present key resolves to its own resource, and a departed key to nothing, never to another",
            )
        }

        clause("RESOLVE_OF_NOTHING_IS_NOTHING", UploadDiscoveryState.GRANTED_SEEDED) { seeded ->
            assertTrue(seeded.port.resourcesFor(emptySet()).isEmpty())
        }
    }
}
