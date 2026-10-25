package app.snapsync.presentation

import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.syntax.Syntax
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * One tap, as the work its handling starts belongs to it (`docs/patterns.md`, "Entry events"): an intent the tap
 * launches runs in [context], and is counted through [joining] as the tap's work until it ends — so the tap's `done`
 * waits for everything it caused. Presentation sees no runtime: the composition hands a tap over as these two.
 */
class TapRun(val context: CoroutineContext, val joining: (Job) -> Unit)

/**
 * A container whose intents, launched while a tap is handled ([onTap]), are that tap's work: each runs in the tap's
 * context and is counted until it ends, so the tap's `done` waits for it and for what it starts.
 */
abstract class TappedContainerHost : OrbitContainerHost<ScreenModel, ScreenModel, Nothing> {
    /** The tap being handled, whose work every intent it launches is. */
    internal val tapping = Tapping()

    /**
     * Runs [dispatch] as [run]'s handling. The container must have started first — the host subscribes to its screen at
     * assembly, before any tap can arrive: its own intents, the collectors it runs for its life, launched while a tap is
     * handled would be counted as that tap's work and keep its `done` from ever coming.
     */
    internal fun tapped(run: TapRun, dispatch: () -> Unit) = tapping.during(run, dispatch)

    override fun intent(registerIdling: Boolean, transformer: suspend Syntax<ScreenModel, Nothing>.() -> Unit): Job =
        tapping.launched { wrap -> super.intent(registerIdling) { wrap { transformer() }() } }
}

/**
 * The tap being handled right now, if any: set for the length of the table's dispatch, which launches the tap's intents
 * synchronously on the thread that delivered it. The platform hands taps over one at a time, on the screen's thread; an
 * intent another thread launches in that window — a link opened at that moment — is counted as the tap's too, which
 * only makes its `done` later.
 */
internal class Tapping {
    @Volatile private var current: TapRun? = null

    /** Runs [dispatch] as [run]'s handling. */
    fun during(run: TapRun, dispatch: () -> Unit) {
        val before = current
        current = run
        try {
            dispatch()
        } finally {
            current = before
        }
    }

    /** Launches an intent through [launch] as the current tap's, when one is being handled; plainly otherwise. */
    fun launched(launch: (wrap: (suspend () -> Unit) -> (suspend () -> Unit)) -> Job): Job {
        val tap = current ?: return launch { it }
        return launch { body -> { withContext(tap.context) { body() } } }.also(tap.joining)
    }

    /** [act], to be run later on a queue — as the current tap's work when one is being handled. */
    fun queued(act: suspend () -> Unit): suspend () -> Unit {
        val tap = current ?: return act
        val ran = Job()
        tap.joining(ran)
        return {
            try {
                withContext(tap.context) { act() }
            } finally {
                ran.complete()
            }
        }
    }
}
