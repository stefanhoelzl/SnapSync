package app.snapsync.query.sharing

import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.store.StoreChanges
import app.snapsync.data.upload.ProgressRead
import app.snapsync.data.upload.UploadProgress
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.upload.AssetProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * **How far the member's own photos are shared**, as the joined screen shows it: the upload rows re-read each time the
 * shared database changes — this process's commit or the upload extension's — and set against the admitted total.
 *
 * Read only while the app is in front ([AppForeground.whileInFront]) and only while a screen follows it: away, a commit
 * reads nothing, and nothing is read before a screen asks. A read the store refuses keeps the last one. [scope] is the
 * process's, which the reads run on.
 */
class SharingProgressQuery(
    foreground: AppForeground,
    private val changes: StoreChanges,
    private val membership: MembershipRepository,
    private val rows: UploadProgress,
    admitted: AdmittedPhotos,
    scope: CoroutineScope,
) {
    /**
     * Never back to [SharingProgressRead.NotYetRead] once read, by construction: the rows held are the last read (a
     * refused one keeps it), and the total, once counted, is never uncounted.
     */
    val state: StateFlow<SharingProgressRead> =
        combine(
            foreground.whileInFront(rowsLooks()).scan<RowsLook, Map<AssetId, AssetProgress>?>(null, ::keptOnFailure),
            admitted.state,
            ::readOf,
        )
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), SharingProgressRead.NotYetRead)

    /**
     * One read of the rows per change of the store or of the membership whose rows they are. Deferred into the flow:
     * the membership's first read opens the shared database, which building the query must not.
     */
    private fun rowsLooks(): Flow<RowsLook> = flow {
        emitAll(combine(membership.state, changes.looks) { held, _ -> held }.map(::rowsOf))
    }

    private fun rowsOf(held: MembershipRead): RowsLook = when (held) {
        is MembershipRead.Joined -> when (val read = rows.progress(held.membership.event.id)) {
            is ProgressRead.Read -> RowsLook.Read(read.photos)
            is ProgressRead.Unavailable -> RowsLook.Refused
        }
        // No membership held, or none readable: nothing of anyone's is counted — a counted zero, not an unread one.
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> RowsLook.Read(emptyMap())
    }

    /** The rows held: the last read, or `null` before the first; a refused look keeps them. */
    private fun keptOnFailure(last: Map<AssetId, AssetProgress>?, next: RowsLook): Map<AssetId, AssetProgress>? =
        when (next) {
            is RowsLook.Read -> next.photos
            RowsLook.Refused -> last
        }

    /** Read once both halves are: the rows, and the total. */
    private fun readOf(photos: Map<AssetId, AssetProgress>?, admitted: Set<AssetId>?): SharingProgressRead =
        photos?.let { read -> admitted?.let { SharingProgress.of(it, read) } }
            ?.let(SharingProgressRead::Read)
            ?: SharingProgressRead.NotYetRead

    /** One look at the upload rows: how far each photo they hold has got, or refused by the store. */
    private sealed interface RowsLook {
        data class Read(val photos: Map<AssetId, AssetProgress>) : RowsLook

        data object Refused : RowsLook
    }
}
