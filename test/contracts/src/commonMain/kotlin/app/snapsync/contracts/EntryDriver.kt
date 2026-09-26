package app.snapsync.contracts

/**
 * **The operating system's side of the app's entry ports** — what a test plays to make the platform deliver
 * (`docs/testing.md`, "The control channel"). Each platform implements it over its own adapters' deliveries: the iOS
 * rig over the iOS adapters' `deliver…` methods, the JVM host over the world's entry doubles. The control channel's
 * `/os` verbs are this interface under their historical names, so a verb means the same delivery on every host.
 *
 * [done] is the operating system's completion block where the platform hands one over; it is released once.
 */
interface EntryDriver {
    /** The app became active. */
    fun foreground()

    /** The app is leaving the active state. */
    fun background()

    /** The push service issued this device [hex] as its token. */
    fun pushToken(hex: String)

    /** The push service could not issue a token. */
    fun pushTokenFailure(description: String)

    /** A silent push naming [eventId] (or none) arrived. */
    fun silentPush(eventId: String?, done: () -> Unit)

    /** A running app was handed [url] as a Universal Link. */
    fun continueLink(url: String)

    /** The operating system launched the scheduled background task [identifier]. */
    fun backgroundTask(identifier: String, done: () -> Unit)

    /** The operating system relaunched the app for the background transfer session [identifier]'s events. */
    fun backgroundTransfers(identifier: String, done: () -> Unit)
}
