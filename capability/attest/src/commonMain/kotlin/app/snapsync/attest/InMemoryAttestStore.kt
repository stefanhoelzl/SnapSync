package app.snapsync.attest

import app.snapsync.ports.AttestStore

/** An in-memory store for tests and the desktop harness (no platform Keychain). */
class InMemoryAttestStore(
    private var token: String? = null,
    private var keyId: String? = null,
) : AttestStore {
    override fun token(): String? = token
    override fun setToken(token: String) {
        this.token = token
    }

    override fun keyId(): String? = keyId
    override fun setKeyId(keyId: String) {
        this.keyId = keyId
    }

    override fun clearToken() {
        token = null
    }
}
