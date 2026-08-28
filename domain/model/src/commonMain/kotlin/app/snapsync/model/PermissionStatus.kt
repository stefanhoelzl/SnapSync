package app.snapsync.model

/**
 * Photo-library permission as the app understands it (capability `permission-gate`).
 *
 * [GRANTED] is a FULL library grant and nothing less. [LIMITED] is a PARTIAL grant (iOS `.limited`):
 * the platform scopes reads to a user-picked selection, which the app treats as the membership's
 * own-photo scope — the selection defines "everything", so "In sync" over the selected set is true
 * (capability `limited-photo-access`). Unchangeable or refused grants (iOS `.denied`, `.restricted`)
 * map to [DENIED].
 *
 * Consumers gating on "may the app read photos at all" (is syncing operational?) treat [GRANTED] and
 * [LIMITED] alike; consumers gating on "may the app read the WHOLE library" (the autonomous walk
 * paths — capability `limited-photo-access` forbids autonomous reads under a partial grant) require
 * [GRANTED] exactly. A bare `!= GRANTED` comparison is no longer self-evidently correct: every such
 * site states which reading it intends.
 */
enum class PermissionStatus {
    NOT_DETERMINED,
    DENIED,
    LIMITED,
    GRANTED,
}

/**
 * The "may the app read photos at all" reading — [PermissionStatus.GRANTED] or
 * [PermissionStatus.LIMITED]. This is the gate for work that operates on whatever the platform lets
 * the app see (album creation, imports, the sync-active signal). It is deliberately NOT the gate for
 * the autonomous library walks, which require [PermissionStatus.GRANTED] exactly — under a partial
 * grant those reads are selection-driven instead (capability `limited-photo-access`).
 */
val PermissionStatus.grantsPhotoAccess: Boolean
    get() = this == PermissionStatus.GRANTED || this == PermissionStatus.LIMITED
