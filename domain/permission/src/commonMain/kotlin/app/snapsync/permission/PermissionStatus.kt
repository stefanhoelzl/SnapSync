package app.snapsync.permission

/**
 * Photo-library permission as the app understands it. v1 requires a FULL library grant:
 * platform adapters map partial or unchangeable grants (iOS `.limited`, `.restricted`)
 * to [DENIED] — the screen must never report a complete sync over a partially-granted
 * library, and the Denied gate's Settings path is exactly where a partial grant becomes
 * a full one.
 */
enum class PermissionStatus {
    NOT_DETERMINED,
    DENIED,
    GRANTED,
}
