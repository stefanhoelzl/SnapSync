package app.snapsync.domain.trust

/**
 * Why the service refused this phone as not genuine, as the user is told it: a refused phone is told why. A CLOSED set,
 * coarser than the backend's checks on purpose: the precise check stays in its log. A refusal that names no reason the
 * app knows is [DEVICE_UNVERIFIABLE], the one reason that never accuses the user.
 */
enum class DeviceRefusal(val wireName: String) {
    /** An unlocked bootloader, a system its maker did not ship, or a key held only in software. */
    DEVICE_MODIFIED("device-modified"),

    /** The proof the phone's hardware gives is not one the service recognises — and the default. */
    DEVICE_UNVERIFIABLE("device-unverifiable"),

    /** This copy of the app is not the official one: another package, or another signing certificate. */
    APP_NOT_GENUINE("app-not-genuine"),
}
