package app.snapsync.feature.creation.readmodel

import kotlin.time.Duration

/**
 * The app's latest return to the foreground (capability `create-event`): the [count]th since launch, [awayFor]
 * after it last left (`null` when it had not left — the cold launch's first activation). The create screen
 * reads it to move an untouched start to now, or to start a fresh draft after a long absence. [NONE] is
 * before the first activation.
 */
class ForegroundReturn(val count: Int, val awayFor: Duration?) {
    companion object {
        val NONE = ForegroundReturn(count = 0, awayFor = null)
    }
}
