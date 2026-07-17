package app.snapsync.gallery

/**
 * The App-Group persistence seam for the device manifest (capability `device-manifest`). Holds the
 * device-global **accumulator** (every discovered, not-deleted asset's manifest detail) and the JSON
 * of the **last successfully-uploaded** projection (for skip-if-unchanged). iOS backs this with files
 * in the shared container; tests use an in-memory fake.
 */
interface DeviceManifestStore {
    fun loadAccumulator(): List<DeviceManifestAsset>
    fun saveAccumulator(assets: List<DeviceManifestAsset>)
    fun loadLastUploaded(): String?
    fun saveLastUploaded(json: String)
}
