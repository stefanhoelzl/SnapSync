package app.snapsync.domain.foreground

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The app's latest return to the foreground: the [count]th since launch, [awayFor] after it last left (`null` when it
 * had not left — the cold launch's first activation). The create screen reads it to move an untouched start to now, or
 * to start a fresh draft after a long absence. [NONE] is before the first activation.
 */
class ForegroundReturn(val count: Int, val awayFor: Duration?) {

    /** The next return, at [now], after leaving at [leftAt] — or never having left, on a cold launch. */
    fun next(leftAt: Instant?, now: Instant): ForegroundReturn = ForegroundReturn(count + 1, leftAt?.let { now - it })

    companion object {
        val NONE = ForegroundReturn(count = 0, awayFor = null)
    }
}

/** Whether the app is in front, where a person can see it, or away. */
enum class InFront { IN_FRONT, AWAY }
