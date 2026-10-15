package app.snapsync.services.membership

import kotlinx.coroutines.sync.Mutex

/**
 * A join in progress, from the moment it keeps anything of the new membership on this device — its key — until it has
 * entered it — MIGRATING, with the leftovers it guards (`PENDING_CLEANUPS.md`). A sweep of what a failed leave left
 * behind ([unlessJoining]) never runs inside that window: what it would sweep is the join's own.
 */
class MembershipTransition {
    private val joining = Mutex()

    @kotlin.concurrent.Volatile
    private var begun = false

    /** A join begins keeping its membership. Waits for a sweep already running. */
    suspend fun begin() {
        joining.lock()
        begun = true
    }

    /** The join ended, however it did. Nothing when it never began. */
    fun end() {
        if (begun) {
            begun = false
            joining.unlock()
        }
    }

    /** [sweep], unless a join is in progress — then nothing: the next opening sweeps again. */
    suspend fun unlessJoining(sweep: suspend () -> Unit) {
        if (!joining.tryLock()) return
        try {
            sweep()
        } finally {
            joining.unlock()
        }
    }
}
