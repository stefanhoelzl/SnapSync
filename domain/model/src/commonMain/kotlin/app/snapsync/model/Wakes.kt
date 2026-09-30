package app.snapsync.model

import kotlin.time.Duration

/**
 * The wakes the app asks the operating system for — the vocabulary of the `Wake` port. Named for what the core wants
 * woken for, never for a platform's task identifier, which stays in the adapter.
 */
enum class WakeId {
    /** The app uploader's heartbeat: a timed wake whose work is the process tail (capability `background-upload`). */
    Heartbeat,

    /**
     * A wake when the photo library changes. Android only (a content-URI trigger); iOS has no such wake for an app
     * and answers [ScheduleResult.Unsupported] — its library-change wake is the upload extension, which the operating
     * system invokes on its own.
     */
    LibraryChanged,
}

/**
 * How urgently a timed wake is wanted — the core's word; which task kind carries it is the adapter's (capability
 * `receiving-photos`; decision record `changes/timely-background-receiving`, D1–D2).
 */
enum class WakeCadence {
    /** Work remains, or a new photo can only be noticed by looking: wake again soon. */
    BUSY,

    /** Nothing is left to do: look in now and then — for others' photos and the event's close — and no more. */
    IDLE,
}

/** When the operating system may deliver a scheduled wake. */
sealed interface WakeTrigger {
    /**
     * No sooner than [earliest] from now, and only with a network connection when [requiresNetwork]. [cadence] says
     * how urgently: an adapter may carry the two on different task kinds, and a request of one replaces a pending one
     * of the other — one timed wake is pending at a time.
     */
    data class After(
        val earliest: Duration,
        val requiresNetwork: Boolean,
        val cadence: WakeCadence = WakeCadence.BUSY,
    ) : WakeTrigger

    /** When the photo library changes, delivered no later than [maxDelay] after the change. */
    data class LibraryChange(val maxDelay: Duration) : WakeTrigger
}

/** What the operating system said to a wake request. */
sealed interface ScheduleResult {
    /** The request is pending. */
    data object Scheduled : ScheduleResult

    /** The operating system refused it; [detail] is its reason, for the diagnostic line. */
    data class Refused(val detail: String) : ScheduleResult

    /** This platform has no such wake. Not a failure: the services arm every wake on every platform. */
    data object Unsupported : ScheduleResult
}
