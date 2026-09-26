package app.snapsync.services.backend

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

/**
 * A [Backend] whose every route answers from [answer], recording each call as `"<route> <token>"` — enough to
 * assert which token a call carried and how many times it was sent. Hand-written here because a `:domain:*` build
 * file names no module (the in-memory backend lives beside the fakes).
 */
internal class ScriptedBackend(var answer: (route: String, token: String?) -> Reply<*> = { _, _ -> Reply.Ok(Unit) }) : Backend {
    val calls = mutableListOf<String>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(route: String, token: String?): Reply<T> {
        calls += "$route $token"
        return answer(route, token) as Reply<T>
    }

    override suspend fun challenge(): Reply<String> = call("challenge", null)
    override suspend fun mintToken(req: MintRequest): Reply<String> = call("mint", null)
    override suspend fun renewToken(req: RenewRequest): Reply<String> = call("renew", null)
    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = call("create", token)
    override suspend fun getEvent(eventId: String): Reply<EventMeta> = call("get", null)
    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> = call("rename", token)
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> = call("join", token)
    override suspend fun publishManifest(token: String?, eventId: String, deviceId: String, manifest: DeviceManifest): Reply<Unit> =
        call("manifest", token)
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> = call("leave", token)
    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> = call("union", null)
    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> = call("files", token)
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: ApnsPushToken): Reply<Unit> = call("config", token)
}

/** A [Credential] holding [current], which a rejection replaces with [recovered] (or keeps, for `null`). */
internal class ScriptedCredential(var current: String?, private val recovered: String? = null) : Credential {
    val rejections = mutableListOf<String>()
    var reads = 0

    override fun token(): String? = current.also { reads++ }

    override suspend fun rejected(sent: String): String? {
        rejections += sent
        if (recovered != null) current = recovered
        return recovered
    }
}

/** Services over an authenticated backend that answers every call with [reply], for the need-shaped mappings. */
internal fun servicesAnswering(reply: Reply<*>, deviceId: () -> String = { "D" }): BackendServices =
    BackendServices(CredentialedBackend(ScriptedBackend { _, _ -> reply }, ScriptedCredential(null), versionGate = null)) { deviceId() }
