package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceManifest
import app.snapsync.model.MintRequest
import app.snapsync.model.PushEndpoint
import app.snapsync.model.RenewRequest
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.Backend

/** [Backend] as its clause's [CallLog] sees it. */
fun Backend.recorded(log: CallLog): Backend = BackendProxy(this, log)

internal class BackendProxy(private val inner: Backend, log: CallLog) : Backend {
    private val r = log.recorder("Backend")

    override suspend fun challenge() = r.answer("challenge", inner.challenge())
    override suspend fun mintToken(req: MintRequest) = r.answer("mintToken", inner.mintToken(req))
    override suspend fun renewToken(req: RenewRequest) = r.answer("renewToken", inner.renewToken(req))
    override suspend fun createEvent(token: String?, req: CreateEventRequest) =
        r.answer("createEvent", inner.createEvent(token, req))
    override suspend fun getEvent(
        token: String?,
        eventId: String,
    ) = r.answer("getEvent", inner.getEvent(token, eventId))
    override suspend fun renameEvent(token: String?, eventId: String, name: String) =
        r.answer("renameEvent", inner.renameEvent(token, eventId, name))
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String) =
        r.answer("joinEvent", inner.joinEvent(token, eventId, deviceId))
    override suspend fun publishManifest(token: String?, eventId: String, deviceId: String, manifest: DeviceManifest) =
        r.answer("publishManifest", inner.publishManifest(token, eventId, deviceId, manifest))
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String, received: Boolean) =
        r.answer("leaveEvent", inner.leaveEvent(token, eventId, deviceId, received))
    override suspend fun eventFiles(token: String?, eventId: String, cursor: Long?, trigger: UnionTrigger) =
        r.answer("eventFiles", inner.eventFiles(token, eventId, cursor, trigger))
    override suspend fun deviceFiles(token: String?, eventId: String, deviceId: String) =
        r.answer("deviceFiles", inner.deviceFiles(token, eventId, deviceId))
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: PushEndpoint) =
        r.answer("putDeviceConfig", inner.putDeviceConfig(token, deviceId, push))
}
