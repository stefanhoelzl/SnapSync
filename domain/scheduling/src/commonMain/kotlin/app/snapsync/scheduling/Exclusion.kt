package app.snapsync.scheduling

import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.sync.Mutex

/**
 * **Two runs that must not overlap where one of them says so** (`docs/patterns.md`, "Runs, steps and expiry"): a run
 * that [hold]s it keeps it until it [release]s it — in an `ensure` step, so however the run ends — waiting first for
 * a holder already in it; and work another run does [unlessHeld] is skipped meanwhile. When work runs, never what the
 * work is: the entry points name what each holds or yields to.
 *
 * The one today is the membership change: a join keeps what it adopts from its key on, and a sweep of what a failed
 * leave left behind never takes it.
 */
class Exclusion(private val name: String) {
    private val log = Log("Exclusion")
    private val held = Mutex()

    @kotlin.concurrent.Volatile
    private var holding = false

    /** Hold this until [release] — waiting for a holder already in it. */
    suspend fun hold() {
        held.lock()
        holding = true
    }

    /** End a [hold]: nothing when none was taken, so an `ensure` step may release a hold its run never reached. */
    fun release() {
        when (holding) {
            true -> {
                holding = false
                held.unlock()
            }
            false -> Unit
        }
    }

    /** [work], unless another run holds this — then nothing, and it says so; a [hold] waits for it while it runs. */
    suspend fun unlessHeld(what: String, work: suspend () -> Unit) {
        when (held.tryLock()) {
            true -> try {
                work()
            } finally {
                held.unlock()
            }
            false -> log.i("skipped: held elsewhere", "work" of what, "exclusion" of name)
        }
    }
}
