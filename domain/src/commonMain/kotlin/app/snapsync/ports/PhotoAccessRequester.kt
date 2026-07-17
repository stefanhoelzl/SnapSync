package app.snapsync.ports

/**
 * The command port for permission: fire-and-forget, no return values, no suspension.
 * Any status change a command causes arrives exclusively via [PhotoAccessStatusSource] —
 * permission also changes without a request (the user flips it in system settings), so
 * that path is the only one. Implementations typically also implement the source as one
 * platform adapter; consumers depend on each port separately.
 */
interface PhotoAccessRequester {
    /** Triggers the platform permission dialog. Duplicate calls are harmless. */
    fun request()

    /** Opens the platform's settings surface for this app's permissions. */
    fun openSettings()
}
