package app.snapsync.feature.membership

import app.snapsync.model.EventConfig
import app.snapsync.model.EventLookup
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.config.ConfigService
import co.touchlab.kermit.Logger

/**
 * Whether opening another event's invite may leave the joined one without asking (decision record
 * `changes/back-dated-events`, D8): only when nothing can be lost by it.
 *
 * That is so when the joined event's photos were deleted, or when its range has ended, the event read answers that
 * every member still in it has shared everything, and this device holds every photo of the others it receives. One
 * read of the event, made when the invite is opened. Every doubt — no membership, a failed or missing read, a count
 * the server did not serve, a union that cannot be read — answers `false`, and the question is asked as before.
 */
class QuietSwitch(
    private val config: ConfigService,
    private val directory: EventDirectory,
    /** Whether this device holds every photo of the others in [EventConfig]'s membership — the download side's answer. */
    private val holdsEverything: suspend (EventConfig) -> Boolean,
    private val log: Logger = Logger.withTag("QuietSwitch"),
) {

    /** `true` when leaving the joined event now loses nothing for anyone. Never throws but for cancellation. */
    suspend fun losesNothing(): Boolean {
        val current = config.config.value ?: return false
        if (current.deleted) return true.also { log.i { "the joined event's photos are deleted — no question" } }
        if (!config.hasEnded(current)) return false
        val found = runCatchingCancellable { directory.fetch(current.eventId) }.getOrNull() as? EventLookup.Found
            ?: return false.also { log.i { "event ${current.eventId}: no answer from the server — asking" } }
        // Confirmed deleted on this very read: nothing is left on the server to lose, whatever this device holds.
        if (found.completion.completed) {
            log.i { "the joined event was just deleted — no question" }
            return true
        }
        val everyoneShared = found.completion.members?.waiting == 0
        val quiet = everyoneShared && runCatchingCancellable { holdsEverything(current) }.getOrDefault(false)
        log.i { "event ${current.eventId}: everyone shared=$everyoneShared — ${if (quiet) "no question" else "asking"}" }
        return quiet
    }
}
