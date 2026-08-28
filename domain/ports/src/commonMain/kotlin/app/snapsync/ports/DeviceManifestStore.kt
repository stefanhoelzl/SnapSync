package app.snapsync.ports

/**
 * The App-Group persistence seam for the device manifest (capability `device-manifest`): the JSON of
 * the **last successfully-uploaded** projection, for skip-if-unchanged. iOS backs it with a file in the
 * shared container; tests use an in-memory fake.
 *
 * It used to hold a device-global **accumulator** as well — every discovered, not-deleted asset's
 * manifest detail — which the manifest was projected from. That is gone: the manifest is now projected
 * from the upload ledger's COMPLETED rows (capability `sync-ledger`), which already had to maintain the
 * same deletion-aware asset set, and had to be right about it under pain of re-uploading a whole
 * library. Two durable structures tracking one set could only ever disagree.
 */
interface DeviceManifestStore {
    /**
     * Absence: null covers "nothing uploaded yet" and "could not read the record" alike. Both skip
     * the skip-if-unchanged optimisation and re-write the manifest — an idempotent PUT — so the
     * collapse costs one redundant upload of a small JSON and never a wrong belief. (The dangerous
     * direction here is the opposite one, a STALE non-null: that once suppressed the rewrite
     * forever. Staleness is outside this law; see `LeaveEvent`'s note.)
     */
    fun loadLastUploaded(): String?
    fun saveLastUploaded(json: String)

    /**
     * Stop believing the server holds the recorded projection.
     *
     * The recorded value is a belief about a **remote** resource, and the producer is not its only
     * writer: enrollment PUTs a register-only empty manifest to the same path (capability `join-event`).
     * When that happens the record is false in the direction that loses data — the producer computes the
     * same projection, matches the stale record, and skips, leaving the empty manifest standing. Clearing
     * is how the falsifying writer says so.
     */
    fun clearLastUploaded()
}
