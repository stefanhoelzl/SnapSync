package app.snapsync.rig

import app.snapsync.http.HttpBackend
import app.snapsync.mock.DeclaredVersion
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.MintRequest
import app.snapsync.model.PushEndpoint
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.Backend
import io.ktor.client.HttpClient

/**
 * The backend port over HTTP for a build whose declared version an operator may change in place: the production
 * [HttpBackend] over [client], rebuilt per call around the version [declared] holds now. It holds no state, so that is
 * all it costs; every answer is `HttpBackend`'s.
 */
class VersionedHttpBackend(
    private val client: HttpClient,
    private val base: String,
    private val declared: DeclaredVersion,
) : Backend {
    private fun http() = HttpBackend(client, base, declared.value.orEmpty())

    override suspend fun challenge(): Reply<String> = http().challenge()
    override suspend fun mintToken(req: MintRequest): Reply<String> = http().mintToken(req)
    override suspend fun renewToken(req: RenewRequest): Reply<String> = http().renewToken(req)
    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = http().createEvent(
        token,
        req,
    )
    override suspend fun getEvent(token: String?, eventId: String): Reply<EventMeta> = http().getEvent(token, eventId)
    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> =
        http().renameEvent(token, eventId, name)
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        http().joinEvent(token, eventId, deviceId)
    override suspend fun publishManifest(
        token: String?,
        eventId: String,
        deviceId: String,
        manifest: DeviceManifest,
    ): Reply<Unit> = http().publishManifest(token, eventId, deviceId, manifest)
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String, received: Boolean): Reply<Unit> =
        http().leaveEvent(token, eventId, deviceId, received)
    override suspend fun eventFiles(
        token: String?,
        eventId: String,
        cursor: Long?,
        trigger: UnionTrigger,
    ): Reply<UnionPage> =
        http().eventFiles(token, eventId, cursor, trigger)
    override suspend fun deviceFiles(token: String?, eventId: String, deviceId: String): Reply<List<DeviceFile>> =
        http().deviceFiles(token, eventId, deviceId)
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: PushEndpoint): Reply<Unit> =
        http().putDeviceConfig(token, deviceId, push)
}
