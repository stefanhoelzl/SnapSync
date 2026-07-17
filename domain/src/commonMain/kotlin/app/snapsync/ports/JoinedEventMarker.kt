package app.snapsync.ports

/**
 * The persisted join marker: the last `eventId` the extension reconciled, surviving the extension's
 * short-lived per-cycle process. It — **not** ledger-emptiness — is the join signal: a fresh join
 * that seeds zero rows still sets it (so there is no re-seed loop on an empty/large library), an
 * event switch is a marker mismatch, and a reinstall is an absent marker. Backed by the App-Group
 * `NSUserDefaults` on iOS, exactly as the discovery cursor is.
 */
interface JoinedEventMarker {
    fun read(): String?
    fun set(eventId: String)
    fun clear()
}
