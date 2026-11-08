package app.snapsync.scheduling

import app.snapsync.inbound.Trigger
import kotlinx.coroutines.currentCoroutineContext

/**
 * The run a piece of work belongs to (`docs/patterns.md`, "Runs, steps and expiry"): the [Trigger] it runs for and the
 * process's [Runtime]. Only the runtime makes one — for an entry point's run, a sequence's pass and a detached piece of
 * work — and hands it to that work's body, so [steps], a member of a run, cannot be written outside one: there is
 * nothing to check at run time.
 */
class Run internal constructor(internal val runtime: Runtime, internal val trigger: Trigger) {
    /**
     * Runs [block] as a list of steps (`docs/patterns.md`, "Steps"), under this run. Each step block is written inline
     * and makes exactly one call (`OneCallPerStep`); what a step does is that call's.
     */
    suspend fun <T> steps(block: suspend Steps.() -> T): T {
        // The entry point's work this run is part of — which what it detaches joins — rides in its context.
        val steps = Steps(runtime, trigger, currentCoroutineContext()[WorkElement]?.work)
        try {
            return steps.block()
        } finally {
            steps.ensured()
        }
    }
}
