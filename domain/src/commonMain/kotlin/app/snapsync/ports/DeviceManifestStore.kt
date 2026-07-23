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
    fun loadLastUploaded(): String?
    fun saveLastUploaded(json: String)
}
