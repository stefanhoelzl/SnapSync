package app.snapsync.world

import app.snapsync.http.HttpBackend
import app.snapsync.fake.inMemorySecureStore
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.backend.Credential
import app.snapsync.services.backend.CredentialedBackend

/**
 * The production backend services over the production [HttpBackend] in front of the mini-edge, for the member
 * [deviceId] names — how the mini-edge's own tests reach it through the code the app ships. No credential: the
 * mini-edge verifies none.
 */
internal fun miniEdgeServices(store: BackendStore, host: String, deviceId: String = "D"): BackendServices =
    BackendServices(
        CredentialedBackend(HttpBackend(miniEdgeClient(store), host, "99.0"), NoCredential, versionGate = null),
        PersistedDeviceIdentity(DeviceIdentityRole.MINTING, inMemorySecureStore(), PlatformDeviceId { deviceId }),
    )

private object NoCredential : Credential {
    override fun token(): String? = null
    override suspend fun rejected(sent: String): String? = null
}
