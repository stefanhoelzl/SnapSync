package app.snapsync.services.backend

/**
 * The backend's HTTP statuses the backend services read off a `Reply.Refused` — named once. What each means is the
 * reading service's to decide (a `404` is "no such event" to the directory and "nothing left to leave" to the leave).
 */
internal object HttpStatus {
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val GONE = 410
    const val UPGRADE_REQUIRED = 426
}
