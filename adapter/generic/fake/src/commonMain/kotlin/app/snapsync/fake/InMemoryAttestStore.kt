package app.snapsync.fake

import app.snapsync.ports.AttestStore

/** The honest in-memory [AttestStore] for tests and the world harness (no platform Keychain). */
internal class InMemoryAttestStore(
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
