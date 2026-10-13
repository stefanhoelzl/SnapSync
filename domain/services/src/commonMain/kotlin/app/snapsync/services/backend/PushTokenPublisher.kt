package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.model.PushEndpoint
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.identity.requireDeviceId
import app.snapsync.wire.api.PutDeviceReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.PushRegistration

/**
 * Where this device's push token is published so the backend can wake it. The
 * address and the device id are this service's, so the feature above it holds only the policy (absorb, publish only
 * on a change, retry on the next trigger).
 */
fun interface PushTokenPublisher {

    /**
     * Publish [token] for this device, replacing whatever was published before (last-write-wins).
     *
     * Absence: a failed [Result] covers every cause — a refused credential, a non-2xx answer, a transport failure —
     * and never a throw, because all of them mean the same thing to the only caller: not registered this time, retry
     * at the next trigger.
     */
    suspend fun publish(token: PushEndpoint): Result<Unit>
}

/**
 * [PushTokenPublisher] over the backend's device config, for the device [identity] names — read per call, so an
 * identity the secure store could not yet serve is retried rather than fixed at construction.
 */
class BackendPushTokenPublisher(
    private val gateway: BackendGateway,
    private val identity: DeviceIdentity,
) : PushTokenPublisher {

    override suspend fun publish(token: PushEndpoint): Result<Unit> {
        val id = runCatchingCancellable { identity.requireDeviceId() }.getOrElse { return Result.failure(it) }
        val config = DeviceConfig(PushRegistration(kind = token.kind, token = token.token, env = token.env))
        // Never token-less: a fresh install's push token can arrive before it attested, and the PUT would only be refused.
        return when (val reply = gateway.sendObtainingFirst(Routes.PutDevice.request(id.value, config))) {
            PutDeviceReply.Recorded -> Result.success(Unit)
            else -> Result.failure(IllegalStateException("config PUT $id: $reply"))
        }
    }
}
