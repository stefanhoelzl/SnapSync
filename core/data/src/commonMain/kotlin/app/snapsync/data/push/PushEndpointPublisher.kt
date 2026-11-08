package app.snapsync.data.push

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.device.DeviceId
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.PutDeviceReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.PushRegistration

/**
 * Publishes where the backend sends this device's silent pushes — its device config, replacing whatever was published
 * before (last-write-wins, so a redundant `PUT` is safe — never a reason to send one). **No event**: the token is
 * device-scoped. The device is the one [identity] names, read per call: before the first unlock it cannot be, and the
 * registration then waits for a later trigger.
 */
class PushEndpointPublisher(private val gateway: BackendGateway, private val identity: DeviceIdentity) {

    /** Publish [endpoint] for this device. */
    suspend fun publish(endpoint: PushEndpoint): PushPublishReply = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> sent(endpoint, id.value)
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> PushPublishReply.NoIdentity
    }

    private suspend fun sent(endpoint: PushEndpoint, device: DeviceId): PushPublishReply {
        val config = DeviceConfig(PushRegistration(kind = endpoint.kind, token = endpoint.token, env = endpoint.env))
        // Never token-less: a fresh install's push token can arrive before it attested, and the PUT would be refused.
        return when (val reply = gateway.sendObtainingFirst(Routes.PutDevice.request(device.value, config))) {
            PutDeviceReply.Recorded -> PushPublishReply.Accepted
            is ClientError, is ServerIssue, is NoAnswer -> PushPublishReply.Refused(reply.toString())
        }
    }
}

/** What the backend answered a registration. */
sealed interface PushPublishReply {
    /** Recorded: the backend pushes this device through it. */
    data object Accepted : PushPublishReply

    /** A refused credential, another answer, or none at all: not registered this time. [detail] is diagnostics only. */
    data class Refused(val detail: String) : PushPublishReply

    /** The device id cannot be read now, so nothing could address this device. */
    data object NoIdentity : PushPublishReply
}
