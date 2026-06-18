package app.snapsync.ios.upload

import platform.Photos.PHPersistentChangeToken

/**
 * The watcher's discovery cursor — the change [token] marking how far enumeration has progressed.
 *
 * **v1 simplification:** held **in-process only** (not persisted across extension process death).
 * A cold start therefore re-establishes the baseline via a full enumeration, which the engine's
 * ledger makes harmless (every already-recorded key answers `AlreadyUploaded`), exactly like the
 * routine `persistentChangeTokenExpired` path. Persisting the token **per change record** across
 * restarts (for incremental efficiency) is the follow-up; this seam is where it lands.
 */
class DiscoveryStore {

    private var token: PHPersistentChangeToken? = null

    fun loadToken(): PHPersistentChangeToken? = token

    fun saveToken(value: PHPersistentChangeToken) {
        token = value
    }
}
