package app.snapsync.ports

/** The operating system's own view of the upload extension's registration, as the [ExtensionRegistry] port reads it. */
enum class RegistrationState {
    /** A configuration record exists. */
    REGISTERED,

    /**
     * The OS reads no record. **Grant-dependent** on iOS: it reads this for a live record while photo access is
     * `NOT_DETERMINED` (measured SE2 / iOS 26.6), so a caller trusts it only under a full grant.
     */
    NOT_REGISTERED,

    /** This platform has no such registration (`RegistrationAnswer.Unsupported`). */
    UNSUPPORTED,
}
