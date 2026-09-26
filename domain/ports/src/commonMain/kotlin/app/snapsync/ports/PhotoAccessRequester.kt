package app.snapsync.ports

/**
 * The platform's settings surface for this app's photo permission: fire-and-forget, no return value. Any
 * status change it leads to arrives exclusively via [PhotoAccessStatusSource] — permission also changes
 * without a request (the member flips it in system settings), so that path is the only one.
 *
 * Asking for access and revising a partial selection are the [Gallery]'s; this remains only until the system
 * surfaces move to their own port (11e).
 */
interface PhotoAccessRequester {
    /** Opens the platform's settings surface for this app's permissions. */
    fun openSettings()
}
