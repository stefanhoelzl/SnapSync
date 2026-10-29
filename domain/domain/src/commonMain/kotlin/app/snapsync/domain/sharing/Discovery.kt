package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline

/** The photo grant, as the selection speaks it. [NONE] is refused or not yet decided: there is nothing to read. */
enum class PhotoAccess { FULL, LIMITED, NONE }

/**
 * What may be read for a member's photos right now — decided before anything is read, so a read can only be the one
 * the grant allows.
 *
 * Under a full grant the library is walked. Under a partial grant the hand-picked selection IS the gallery, read from
 * the snapshot the app already holds — never a walk; and until that snapshot has been read there is no answer at all:
 * an unread selection is not an empty one (an empty one would withdraw every photo). The plan to read a selection
 * carries the selection it reads, [S] — whatever the reader holds it as — so no reader can plan a selection it has not
 * got.
 */
sealed interface ReadPlan<out S> {
    /**
     * Whether a membership of [direction] needs the album denylist read for this plan: only when the library itself is
     * walked for photos it shares. A selection is not judged by it, and under no grant or an undecided one the album
     * structure must not be touched (on iOS a fetch there raises the permission dialog).
     */
    fun readsAlbums(direction: Direction): Boolean

    data object Walk : ReadPlan<Nothing> {
        override fun readsAlbums(direction: Direction): Boolean = direction.includesUpload
    }

    class Snapshot<out S>(val selection: S) : ReadPlan<S> {
        override fun readsAlbums(direction: Direction): Boolean = false
    }

    data object NotReadable : ReadPlan<Nothing> {
        override fun readsAlbums(direction: Direction): Boolean = false
    }

    companion object {
        /** The read [access] allows, with the [selection] as last read — `null` while none has been. */
        fun <S : Any> of(access: PhotoAccess, selection: S?): ReadPlan<S> = when (access) {
            PhotoAccess.FULL -> Walk
            PhotoAccess.LIMITED -> when (selection) {
                null -> NotReadable
                else -> Snapshot(selection)
            }
            PhotoAccess.NONE -> NotReadable
        }
    }
}

/** A read's candidates, before admission — as the [ReadPlan] produced them, with the moment the read began. */
sealed interface CandidateSet {
    /** The library, walked within the policy's capture dates. */
    class Walked(val assets: List<AssetFacts>, val takenAt: Timestamp) : CandidateSet

    /** The member's hand-picked selection. */
    class Selected(val assets: List<AssetFacts>, val takenAt: Timestamp) : CandidateSet

    /** Nothing could be read; no admitted set can be stated. */
    data object NotReadable : CandidateSet
}

/** Where a [Discovery]'s photos came from. */
enum class DiscoverySource { LIBRARY, SELECTION }

/** [SelectionPolicy.admit]'s answer. */
sealed interface AdmissionVerdict {
    class Discovered(val discovery: Discovery) : AdmissionVerdict

    /** No admitted set can be stated — never the same as an empty one, which settles the status screen. */
    data object NotReadable : AdmissionVerdict
}

/**
 * One read of the member's photos, admitted.
 *
 * It exists only for a read that saw the whole of what this member shares from — the library walked under a full
 * grant, or the read selection under a partial one — so a photo inside the range that it does not hold has left, and
 * its rows may go. A read that could not be made produces no discovery at all.
 */
class Discovery internal constructor(
    val admitted: List<AdmittedAsset>,
    /** When the read began: everything captured before then that it does not hold was not there to be shared. */
    val takenAt: Timestamp,
    val source: DiscoverySource,
    /** How many candidates the read offered before admission. */
    val considered: Int,
)

// The selection's evidence (`docs/patterns.md`, "Evidence types"): a step that needs the policy to have admitted a
// photo requires its evidence, and each has one mint, its constructor's opt-in pinned to that site (`OptInPinsTest`).
// Never stored, never serialized, never a data class.

@RequiresOptIn("Only SelectionPolicy.admit admits an asset.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsAdmittedAsset

@RequiresOptIn("Only SelectionPolicy.admitRecorded admits a ledger row.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsAdmittedRow

/** An asset the policy admitted, with the facts it was admitted on. */
class AdmittedAsset @MintsAdmittedAsset constructor(val facts: AssetFacts)

/** A ledger row's asset the policy admitted. */
@JvmInline
value class AdmittedRow @MintsAdmittedRow constructor(val assetId: AssetId)
