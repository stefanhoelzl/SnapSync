package app.snapsync.services.backend

import app.snapsync.model.ApnsPushToken
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.toResult
import app.snapsync.ports.DeviceIdentity

/**
 * Where this device's push token is published so the backend can wake it (capability `receiving-photos`). The
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
    suspend fun publish(token: ApnsPushToken): Result<Unit>
}

/**
 * [PushTokenPublisher] over the backend's device config, for the device [identity] names — read per call, so an
 * identity the secure store could not yet serve is retried rather than fixed at construction.
 */
class BackendPushTokenPublisher(
    private val backend: AuthenticatedBackend,
    private val identity: DeviceIdentity,
) : PushTokenPublisher {

    override suspend fun publish(token: ApnsPushToken): Result<Unit> {
        val id = runCatchingCancellable { identity.deviceId() }.getOrElse { return Result.failure(it) }
        return backend.putDeviceConfig(id, token).toResult("config PUT $id")
    }
}
