package app.snapsync.world

import app.snapsync.http.HttpBackend
import app.snapsync.model.ApnsPushToken
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.MintRequest
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.UnionAsset
import app.snapsync.ports.Backend
import io.ktor.client.HttpClient

/**
 * The world's [Backend] port: the production [HttpBackend] over the world's backend client, with the two things only
 * a test harness needs laid over it and nothing else.
 *
 * - **The declared build version is an operator lever** ([appVersion], read per call), so a test can be an old
 *   build against the version gate. A device's is a constant of its bundle, which is why `HttpBackend` takes a value:
 *   this builds one per call around the lever's current answer — it holds no state, so that is all it costs.
 * - **Push registrations are counted** ([onDeviceConfig], every attempt), because "how many times did the app publish
 *   its token" is the observable the push-registration tests assert, and the backend's own record says only the last.
 *
 * Every answer is `HttpBackend`'s, unchanged: the credential handling, the verdicts and every decision stay in the
 * composed services above this, as on the phone.
 */
internal class WorldBackendPort(
    private val client: HttpClient,
    private val base: String,
    private val appVersion: () -> String,
    private val onDeviceConfig: () -> Unit,
) : Backend {

    private fun http() = HttpBackend(client, base, appVersion())

    override suspend fun challenge(): Reply<String> = http().challenge()
    override suspend fun mintToken(req: MintRequest): Reply<String> = http().mintToken(req)
    override suspend fun renewToken(req: RenewRequest): Reply<String> = http().renewToken(req)
    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = http().createEvent(token, req)
    override suspend fun getEvent(eventId: String): Reply<EventMeta> = http().getEvent(eventId)
    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> =
        http().renameEvent(token, eventId, name)
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        http().joinEvent(token, eventId, deviceId)
    override suspend fun publishManifest(token: String?, eventId: String, deviceId: String, manifest: DeviceManifest): Reply<Unit> =
        http().publishManifest(token, eventId, deviceId, manifest)
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        http().leaveEvent(token, eventId, deviceId)
    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> = http().eventFiles(eventId)
    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> = http().deviceFiles(token, deviceId)
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: ApnsPushToken): Reply<Unit> =
        http().putDeviceConfig(token, deviceId, push).also { onDeviceConfig() }
}
