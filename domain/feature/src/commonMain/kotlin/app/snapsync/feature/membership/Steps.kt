package app.snapsync.feature.membership

import app.snapsync.model.runCatchingCancellable
import co.touchlab.kermit.Logger

/**
 * The steps of a multi-step membership use case, each declared **required** or **best-effort** (law "A multi-step
 * use case declares which steps are required", capability `module-architecture`).
 *
 * - [required]: a failure stops the sequence — the caller returns as soon as it answers `false`, and reports it.
 *   A step whose failure would leave a later step acting on state that was never persisted is required: the
 *   reconfigure's config save is the one that motivated this, having been swallowed while the album was created,
 *   downloads cancelled and the uploads re-armed against settings that never landed (B5).
 * - [bestEffort]: a failure is logged and the sequence continues.
 *
 * Either way a failure is reported at `Error` (a crash-reporting event), and a cancelled step is not a failure: it
 * propagates unreported (law "Catch sites keep cancellation").
 */
internal class Steps(internal val log: Logger, internal val useCase: String) {

    /** Run a required step; `false` means it failed and the use case must stop. */
    internal inline fun required(name: String, block: () -> Unit): Boolean =
        runCatchingCancellable(block)
            .onFailure { log.e(it) { "$useCase step failed: $name — stopping" } }
            .isSuccess

    /** Run a best-effort step; a failure is logged and the use case carries on. */
    internal inline fun bestEffort(name: String, block: () -> Unit) {
        runCatchingCancellable(block).onFailure { log.e(it) { "$useCase step failed: $name" } }
    }
}
