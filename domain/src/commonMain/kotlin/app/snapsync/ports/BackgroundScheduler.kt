package app.snapsync.ports

/**
 * Re-arm scheduling for the app-driven (iOS 18–26.0) upload tier — a platform-free seam so the
 * [BackgroundUploadPump]'s re-arm logic is JVM/simulator-testable against a fake.
 *
 * On the iOS ≥26.1 PhotoKit tier the OS owns re-invocation (`process()` is scheduled by the system),
 * so there is no scheduler. On <26.1 the app is the scheduler: after a cycle leaves work outstanding
 * (or on every `BGProcessingTask` handler, as the new-photo heartbeat), the pump asks this seam to
 * ensure the next background wake exists. The iOS implementation (`IosBackgroundScheduler`, in
 * `:app:ios:url-session-upload`) backs it with `BGTaskScheduler`; the genuinely OS-bound wiring
 * (registration, the `URLSession` delegate, `handleEventsForBackgroundURLSession`) stays in the thin
 * Swift shell.
 */
interface BackgroundScheduler {

    /**
     * Ensure a background wake is scheduled. `BGProcessingTask` requests are one-shot, so this is
     * called to (re)submit the next one; implementations SHOULD treat repeated calls as idempotent
     * (a pending request is replaced, not duplicated).
     */
    fun scheduleNext()

    /** Cancel any pending scheduled wake (on leave / disable). */
    fun cancel()
}
