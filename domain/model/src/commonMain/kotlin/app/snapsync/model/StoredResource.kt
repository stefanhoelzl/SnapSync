package app.snapsync.model

/**
 * One resource the backend holds for this device: its recomposed storage [key] and the [assetId] the
 * backend **reported** for it.
 *
 * The `assetId` travels beside the key rather than being parsed back out of it. The backend states
 * identity, so a caller seeding a ledger row takes that statement instead of recovering it from a string
 * the seam has just composed — the direction that cannot drift.
 */
data class StoredResource(val key: String, val assetId: AssetId)
