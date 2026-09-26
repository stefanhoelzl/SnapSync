package app.snapsync.ports

/**
 * **One operating-system completion handler**, as an event port hands it to the core (`docs/architecture.md`,
 * "Background execution": a `BGTask`'s `setTaskCompleted`, a background `URLSession`'s
 * `handleEventsForBackgroundURLSession` handler, an Android worker's result).
 *
 * Calling [complete] declares *"I am done"*, and the system may suspend the process on the strength of it. The core
 * never calls it directly: it hands the completion to `OsCompletions` (in `:domain:services`), the one holder, which
 * releases it after the wake's own work or at once on the operating system's expiry.
 *
 * **No success flag**, on purpose: nothing reads one, and on Android a failure or retry answer would make the OS
 * reschedule a wake the services already re-arm, which would double it (decision record of phase 11f, r12 D2).
 */
interface Completion {

    /**
     * Release the handler. The adapter answers the operating system **once** — a second call does nothing — and on
     * the thread the platform requires (a background `URLSession` handler is part of UIKit and must be called on the
     * main thread: the adapter hops there itself). Returns without waiting for that hop.
     */
    fun complete()

    /**
     * Run [action] when the operating system says this wake's time is up — a `BGTask`'s `expirationHandler`, an
     * Android `Worker.onStopped`. [action] runs **at most once**, on a thread the adapter does not choose, and must
     * only request a stop and return. Registered after the expiry already came, it runs at once.
     *
     * A completion whose wake has no such signal (a background `URLSession` relaunch, a silent push) never runs it:
     * those wakes' only "time is up" is the process's background time (`BackgroundTime`), which the core holds across
     * them.
     */
    fun onExpired(action: () -> Unit)
}
