package app.snapsync.ios.upload

/**
 * The persisted discovery cursor — opaque archived change-token bytes. A `commonMain` port so the
 * cycle's *advance-only-when-fully-drained* orchestration is testable with a fake; the iOS impl
 * ([IosDiscoveryStore]) persists the bytes in the shared App-Group `NSUserDefaults`. The platform
 * owns archiving the `PHPersistentChangeToken` to/from these bytes (in [UploadJobPlatform]); the
 * store only stores them.
 *
 * Persistence is an efficiency optimization only: a cold start with no stored token re-enumerates
 * the whole library, which the ledger makes harmless (everything in flight answers `AlreadyUploaded`).
 */
interface DiscoveryStore {
    fun loadToken(): ByteArray?
    fun saveToken(token: ByteArray)

    /**
     * Drop the stored cursor so the next cycle re-enumerates the whole library. The re-join
     * reconciliation calls this after seeding, so the producer re-derives every resource against the
     * freshly-seeded ledger (already-stored ones answer `AlreadyUploaded`).
     */
    fun clearToken()
}
