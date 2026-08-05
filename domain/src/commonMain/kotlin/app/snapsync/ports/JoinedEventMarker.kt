package app.snapsync.ports

/**
 * The persisted join marker: the last `eventId` the extension reconciled, surviving the extension's
 * short-lived per-cycle process. It — **not** ledger-emptiness — is the join signal: a fresh join
 * that seeds zero rows still sets it (so there is no re-seed loop on an empty/large library), an
 * event switch is a marker mismatch, and a reinstall is an absent marker. Backed by the App-Group
 * `NSUserDefaults` on iOS, exactly as the discovery cursor is.
 */
interface JoinedEventMarker {
    /**
     * Absence: null means "no marker" — which this capability reads as **a reinstall**, a genuinely
     * load-bearing meaning. The collapse is nonetheless **forced, not chosen**: the marker lives in
     * a shared `NSUserDefaults` suite, and that API has no error channel at all — `stringForKey`
     * answers nil for an absent key and offers no way to report a failed read, so there is no third
     * state available to encode.
     *
     * What makes it safe is the bound on being wrong: a forged "reinstall" costs exactly one
     * reconcile, and a reconcile seeds already-stored photos as `COMPLETED` rather than re-uploading
     * them (capability `event-rejoin-reconciliation`). Expiry: if reconciliation ever stops being
     * cheap and idempotent, this marker needs a store that can distinguish the two.
     */
    fun read(): String?
    fun set(eventId: String)
    fun clear()
}
