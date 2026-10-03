package app.snapsync.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable

/**
 * How long the app may stay in the background before the create screen starts a fresh draft (capability
 * `create-event`, "A long absence starts a fresh draft").
 */
val CREATE_DRAFT_ABSENCE_LIMIT: Duration = 15.minutes

/**
 * Where the create screen's draft stands against the app's foreground life (capability `create-event`).
 * [activation] changes on every return to the foreground — an untouched start moves to now; [epoch] changes
 * only on a return after [CREATE_DRAFT_ABSENCE_LIMIT] or more away — the whole draft starts over.
 */
@Serializable
data class CreateDraftSession(val activation: Int = 0, val epoch: Int = 0) {
    /**
     * The session after the app's [count]th return to the foreground, [awayFor] after it left (`null`: it had
     * not left — a cold launch). A count already seen changes nothing.
     */
    fun afterReturn(count: Int, awayFor: Duration?): CreateDraftSession = when {
        count == activation -> this
        awayFor != null && awayFor >= CREATE_DRAFT_ABSENCE_LIMIT -> CreateDraftSession(count, epoch + 1)
        else -> copy(activation = count)
    }
}
