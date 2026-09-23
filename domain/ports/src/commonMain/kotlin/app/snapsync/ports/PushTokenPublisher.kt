package app.snapsync.ports

import app.snapsync.model.ApnsPushToken

/**
 * Where this device's push token is published so the backend can wake it (capability `push-registration`).
 * Named for the need: the HTTP binding `PUT`s the device config; the address, the body and the device id
 * are the adapter's, so the feature above it holds only the policy (absorb, retry on the next trigger).
 *
 * Its promises are the port contract `PushTokenPublisherContract` (capability `port-contracts`), run
 * against the real edge and the world's mini-edge.
 */
fun interface PushTokenPublisher {

    /**
     * Publish [token] for this device, replacing whatever was published before (last-write-wins).
     *
     * Absence: a failed [Result] covers every cause — a refused credential, a non-2xx answer, a transport
     * failure — and never a throw, because all of them mean the same thing to the only caller: not
     * registered this time, retry at the next trigger.
     */
    suspend fun publish(token: ApnsPushToken): Result<Unit>
}
