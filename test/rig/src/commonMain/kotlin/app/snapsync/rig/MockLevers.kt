package app.snapsync.rig

import app.snapsync.model.NetworkAccess
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.mock.BackendCall
import app.snapsync.mock.DownloadSessionMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.MockedSystem
import app.snapsync.model.AlbumKind
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.CrashEvent
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import app.snapsync.model.GalleryAccess
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.ManifestResource
import app.snapsync.model.PlannedResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.encodeToJson
import app.snapsync.model.resourcesFrom
import app.snapsync.model.uploadKey
import app.snapsync.rig.gallery.GalleryReport
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.SeedOutcome
import app.snapsync.services.config.CONFIG_FILE_NAME
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.services.gallery.PermissionAwareCandidateSource
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The operator levers and reads over the mocked systems (`docs/testing.md`, "The seam-to-UI-state integration surface"
// and "Launch-time adapters"): what the backend, the crash reporter's backend, the push service, the staging
// directory and the photo library recorded, and the levers that put them, the operating system and the photo library
// into the states a test starts from. Each is a mock's operator face, and each names what it needs — a lever over a
// system this host runs REAL is refused, with that reason, in the advertisement and at the call.

/** What a lever needs before it can be honoured. */
internal sealed interface Need {
    /** The system must be one this host's app runs over as a mock. */
    data class Mocked(val system: MockedSystem) : Need

    /** The backend's public surface must be reachable from the channel. */
    data object Reach : Need

    /** The backend must be the mock, whose operator this lever is. */
    data object Operator : Need

    /** The build's declared version must be a cell the channel may change. */
    data object Version : Need
}

/** A lever: what it [needs], and what it does. */
internal class Lever(val needs: List<Need>, val command: RigCommand)

/** Why [name] cannot be honoured in this world, or `null` when every need is met. */
private fun MockWorld.refusalOf(name: String, lever: Lever): String? = lever.needs.firstNotNullOfOrNull { need ->
    when (need) {
        is Need.Mocked -> refusalFor(need.system)
        Need.Reach -> reachRefusal.takeIf { reach == null }
        Need.Operator -> operatorRefusal(name).takeIf { !isMocked(MockedSystem.BACKEND) }
        Need.Version -> (
            "this build's declared version is baked, not a cell the channel may change; it is one only where the " +
                "backend is the mock, which reads it"
            ).takeIf { version == null }
    }
}

/** The levers this world honours, by `/device` name. */
fun MockWorld.honouredLevers(): Map<String, RigCommand> =
    levers().filter { (name, lever) -> refusalOf(name, lever) == null }.mapValues { it.value.command }

/** The levers this world refuses, by vocabulary entry (`device/<name>`), each with its reason. */
fun MockWorld.leverRefusals(): Map<String, String> =
    levers().mapNotNull { (name, lever) -> refusalOf(name, lever)?.let { "device/$name" to it } }.toMap()

private fun needs(vararg needs: Need, command: RigCommand) = Lever(needs.toList(), command)

private fun mocked(system: MockedSystem, command: RigCommand) = Lever(listOf(Need.Mocked(system)), command)

/** The whole table, bound to this world. */
internal fun MockWorld.levers(): Map<String, Lever> {
    val world = this
    fun op(body: suspend MockWorld.(Map<String, String>) -> CommandResult) =
        Lever(listOf(Need.Operator), RigCommand { params, _ -> world.body(params) })
    return backendReads(::op) + backendLevers(::op) + transferLevers() + libraryLevers() + deviceLevers() + deviceFacts()
}

private fun MockWorld.backendReads(op: (suspend MockWorld.(Map<String, String>) -> CommandResult) -> Lever): Map<String, Lever> = mapOf(
    "backend/union" to needs(Need.Reach, command = RigCommand { params, _ ->
        withEvent(params) { event ->
            val union = reach!!.unionOf(event)
            CommandResult.ok(
                buildJsonObject {
                    put("event", event)
                    putJsonArray("assets") {
                        union.forEach { a ->
                            add(
                                buildJsonObject {
                                    put("device", a.deviceId); put("asset", a.assetId.value)
                                    putJsonArray("roles") { a.resources.forEach { add(JsonPrimitive(it.role)) } }
                                },
                            )
                        }
                    }
                }.toString(),
            )
        }
    }),
    "backend/manifest" to op { params ->
        withEvent(params) { event ->
            val who = params["device"] ?: ownDeviceId()
            CommandResult.ok(manifestJson(event, who, device.backend.operator.manifestOf(event, who)))
        }
    },
    // What the device registered for pushes, as the backend stored it: the token and its environment, and how many
    // registrations it stored.
    "backend/device-config" to op { params ->
        val who = params["device"] ?: ownDeviceId()
        val config = device.backend.operator.deviceConfigOf(who)
        CommandResult.ok(
            buildJsonObject {
                put("device", who)
                put("token", config?.token)
                put("env", config?.env)
                put("writes", device.backend.operator.deviceConfigWritesOf(who))
            }.toString(),
        )
    },
    "backend/event" to needs(Need.Reach, command = RigCommand { params, _ ->
        withEvent(params) { event ->
            val (registered, name) = reach!!.eventOf(event)
            CommandResult.ok(buildJsonObject { put("event", event); put("registered", registered); put("name", name) }.toString())
        }
    }),
    "backend/departed" to op { params ->
        withEvent(params) { event ->
            val who = params["device"] ?: ownDeviceId()
            CommandResult.ok(
                buildJsonObject { put("event", event); put("device", who); put("departed", device.backend.operator.isDeparted(event, who)) }
                    .toString(),
            )
        }
    },
    "backend/publishes" to op { params ->
        withEvent(params) { event ->
            val who = params["device"] ?: ownDeviceId()
            CommandResult.ok(
                buildJsonObject {
                    put("event", event); put("device", who); put("applied", device.backend.operator.publishesOf(event, who))
                }.toString(),
            )
        }
    },
    "backend/reads" to op { params ->
        withEvent(params) { event ->
            val backend = device.backend.operator
            val (union, details) = (backend.unionReads[event] ?: 0) to (backend.eventReads[event] ?: 0)
            CommandResult.ok(
                buildJsonObject { put("event", event); put("union", union); put("event-details", details) }.toString(),
            )
        }
    },
    "backend/pushes" to op { _ ->
        CommandResult.ok(
            buildJsonObject {
                putJsonArray("pushes") {
                    device.backend.operator.pushesSent().forEach { p ->
                        add(buildJsonObject { put("event", p.eventId); put("device", p.deviceId); put("token", p.token) })
                    }
                }
            }.toString(),
        )
    },
    // What the backend lists for a device — over its public surface. `device` defaults to this one.
    "backend/objects" to needs(Need.Reach, command = RigCommand { params, _ ->
        val who = params["device"] ?: ownDeviceId()
        CommandResult.ok("""{"device":${jsonString(who)},"objects":${jsonList(reach!!.objectsOf(who).sorted())}}""")
    }),
    "diagnostics/sent" to mocked(MockedSystem.CRASH_REPORTER, RigCommand { _, _ ->
        CommandResult.ok(buildJsonObject { put("dumps", JsonArray(device.crashReporter.operator.sent.value.map(::dumpJson))) }.toString())
    }),
)

private fun MockWorld.backendLevers(op: (suspend MockWorld.(Map<String, String>) -> CommandResult) -> Lever): Map<String, Lever> = mapOf(
    "backend/offline" to op { params ->
        device.backend.operator.offline = flag(params, "on")
        CommandResult.ok("""{"ok":true}""")
    },
    // Without `minimum`, the gate is turned off.
    "backend/min-app-version" to op { params ->
        device.backend.operator.minAppVersion = params["minimum"]
        OK
    },
    // The id the next created event is minted with, once — so a screen that renders it renders the same every run.
    "backend/next-event-id" to op { params ->
        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return@op CommandResult.badRequest("id is required")
        device.backend.operator.nextEventId = id
        CommandResult.ok(buildJsonObject { put("next", id) }.toString())
    },
    "backend/sweep" to op { params -> withEvent(params) { event -> device.backend.operator.sweepEvent(event); OK } },
    // Every `call` (event|create|join|leave) waits until released (`on=false`) — the backend that has not answered yet,
    // which is the only time the app shows the screen that waits on it.
    "backend/hold" to op { params ->
        val call = BackendCall.ofKey(params["call"])
            ?: return@op CommandResult.badRequest(
                "call must be one of ${BackendCall.entries.joinToString("|") { it.key }}, was '${params["call"]}'",
            )
        val on = flag(params, "on")
        if (on) device.backend.operator.hold(call) else device.backend.operator.release(call)
        CommandResult.ok("""{"call":"${call.key}","held":$on}""")
    },
    // The sweep's early completion (capability `event-lifetime`): memberships and photos gone, the record kept.
    "backend/complete" to op { params -> withEvent(params) { event -> device.backend.operator.complete(event); OK } },
    "backend/fail-listing" to op { params ->
        device.backend.operator.failDeviceListing = flag(params, "on")
        OK
    },
    // An own photo's bytes land on the backend with NO acknowledgement reaching the app — the upload the OS completed
    // while the process was gone. Through the backend's public byte route, so on either backend.
    "backend/deposit" to needs(Need.Reach, Need.Mocked(MockedSystem.LIBRARY), command = RigCommand { params, _ ->
        val asset = params["asset"] ?: return@RigCommand CommandResult.badRequest("asset is required")
        landBytesWithoutAck(asset)
        CommandResult.ok(buildJsonObject { put("landed", asset) }.toString())
    }),
    "backend/legacy-event" to op { params ->
        val event = device.backend.operator.registerLegacyEvent(params["name"] ?: "Legacy")
        CommandResult.ok(buildJsonObject { put("event", event) }.toString())
    },
    // The byte partition of a device is gone — an operator's storage wipe — while every record that names it stays.
    "backend/wipe-bytes" to op { params ->
        device.backend.operator.wipeBytes(params["device"] ?: ownDeviceId())
        OK
    },
    "backend/refuse-credential" to op { _ ->
        device.backend.operator.refuseNextCredential()
        OK
    },
    // A fellow member with complete photos, through the backend's public surface. `event` defaults to the joined
    // one; `assets` is a comma-separated list of asset ids. `kind=motion-photo` makes each a real Google motion photo
    // (`PhotoLibrary.motionPhoto`), as an Android member's camera shares it; otherwise each is a placeholder photo.
    "foreign-device" to needs(Need.Reach, command = RigCommand { params, _ ->
        val who = params["device"]
        val assets = params["assets"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val event = params["event"] ?: joinedEventId()
        val motion = when (params["kind"]) {
            null, "photo" -> false
            "motion-photo" -> true
            else -> return@RigCommand CommandResult.badRequest("kind is photo or motion-photo, was '${params["kind"]}'")
        }
        // The capturing device's own file name for every asset — what an import names its photo after.
        val filename = params["filename"] ?: if (motion) "PXL_MOTION.MP.jpg" else null
        if (who == null || assets.isEmpty()) {
            CommandResult.badRequest("device and a non-empty comma-separated assets are required")
        } else {
            val manifest = assets.map {
                when {
                    motion -> foreignAsset(it, filename!!, contentType = "image/jpeg")
                    filename != null -> foreignAsset(it, filename)
                    else -> foreignAsset(it)
                }
            }
            val eventId = addForeignDevice(who, manifest, event, if (motion) PhotoLibrary.motionPhoto else null)
            CommandResult.ok("""{"device":${jsonString(who)},"eventId":${jsonString(eventId)}}""")
        }
    }),
    // The marketing version this build declares on every request — so a test can play an old build, or an update.
    "app-version" to needs(Need.Version, command = RigCommand { params, _ ->
        val version = params["version"] ?: return@RigCommand CommandResult.badRequest("version is required")
        this.version!!.value = version
        CommandResult.ok(buildJsonObject { put("appVersion", version) }.toString())
    }),
)

private fun MockWorld.transferLevers(): Map<String, Lever> = mapOf(
    "jobs" to mocked(MockedSystem.UPLOAD_QUEUE, RigCommand { _, _ ->
        val queue = device.uploadQueue.operator
        CommandResult.ok("""{"live":${jsonList(queue.liveJobKeys())},"created":${queue.created.size}}""")
    }),
    "jobs/limit" to mocked(MockedSystem.UPLOAD_QUEUE, RigCommand { params, _ ->
        val n = params["n"]?.toIntOrNull()
        if (n == null || n < 0) {
            CommandResult.badRequest("n must be a non-negative integer, was '${params["n"]}'")
        } else {
            device.uploadQueue.operator.jobLimit = n
            CommandResult.ok("""{"jobLimit":$n}""")
        }
    }),
    // Without `key`, every live job — what a caller that just ran a cycle usually means.
    "jobs/complete" to mocked(MockedSystem.UPLOAD_QUEUE, RigCommand { params, _ ->
        val queue = device.uploadQueue.operator
        val keys = params["key"]?.let(::listOf) ?: queue.liveJobKeys()
        keys.forEach { queue.completeJob(it) }
        CommandResult.ok("""{"completed":${jsonList(keys)}}""")
    }),
    // The app uploader's transfer session: its live transfers and how many it was ever handed.
    "uploads" to mocked(MockedSystem.UPLOAD_SESSION, RigCommand { _, _ ->
        val session = device.uploadSession.operator
        CommandResult.ok("""{"live":${jsonList(session.liveKeys())},"created":${session.created.size}}""")
    }),
    // The OS lands every live app-uploader transfer (or the one `key` names), each reported to the app as it ends.
    "uploads/complete" to mocked(MockedSystem.UPLOAD_SESSION, RigCommand { params, _ ->
        val session = device.uploadSession.operator
        val keys = params["key"]?.let(::listOf) ?: session.liveKeys()
        keys.forEach { session.complete(it) }
        CommandResult.ok("""{"completed":${jsonList(keys)}}""")
    }),
    "jobs/fail" to mocked(MockedSystem.UPLOAD_QUEUE, RigCommand { params, _ ->
        val key = params["key"]
        val error = uploadError(params["error"])
        when {
            key == null -> CommandResult.badRequest("key is required")
            error == null -> CommandResult.badRequest(
                "error must be network|cancelled|unknown|http:<status>, was '${params["error"]}'",
            )
            else -> {
                device.uploadQueue.operator.failJob(key, error)
                CommandResult.ok("""{"failed":${jsonString(key)},"error":${jsonString(error.toString())}}""")
            }
        }
    }),
    // The operating system finishes every in-flight download — healthy, or answered `status` with `received` bytes of
    // an error body — and, with `drained=true`, then reports the session's events delivered (`urlSessionDidFinishEvents`).
    // Answers at once: what the app makes of the transfers (staging, the import its tail runs) is observed, not
    // awaited. Nothing is delivered while this launch has not brought its download session up.
    "downloads/stage" to mocked(MockedSystem.DOWNLOADS, RigCommand { params, _ ->
        val status = params["status"]?.toIntOrNull()
        val outcome = if (status == null) {
            DownloadSessionMock.HEALTHY
        } else {
            TransferOutcome(statusCode = status, expectedBytes = -1L, receivedBytes = params["received"]?.toLongOrNull() ?: 0L)
        }
        // `bytes=motion-photo`: what each transfer brought is a real Google motion photo (`PhotoLibrary.motionPhoto`).
        val bytes = when (params["bytes"]) {
            null -> null
            "motion-photo" -> PhotoLibrary.motionPhoto
            else -> return@RigCommand CommandResult.badRequest("bytes is motion-photo or absent, was '${params["bytes"]}'")
        }
        val finished = finishDownloads(outcome, bytes)
        if (params["drained"]?.toBoolean() == true && device.downloads.operator.realized) {
            device.downloads.operator.reportEventsDrained()
        }
        CommandResult.ok("""{"finished":${jsonList(finished)}}""")
    }),
)

private fun MockWorld.libraryLevers(): Map<String, Lever> = mapOf(
    "import/fail-next" to mocked(MockedSystem.LIBRARY, RigCommand { _, _ ->
        device.library.operator.imports.failNextImport = true
        CommandResult.ok("""{"armed":true}""")
    }),
    "permission" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val status = GalleryAccess.entries.firstOrNull { it.name.equals(params["status"], ignoreCase = true) }
        if (status == null) {
            CommandResult.badRequest("status must be one of ${GalleryAccess.entries.joinToString("|")}, was '${params["status"]}'")
        } else {
            device.library.operator.access = status
            CommandResult.ok("""{"permission":"${status.name}"}""")
        }
    }),
    // How the library holds an album (`kind=folder`: an Android phone's; `collection`: an iPhone's). The composition
    // reads it once, at host assembly, so a test sets it and then relaunches.
    "album/kind" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val kind = AlbumKind.entries.firstOrNull { it.name.equals(params["kind"], ignoreCase = true) }
        if (kind == null) {
            CommandResult.badRequest("kind is one of ${AlbumKind.entries.joinToString { it.name.lowercase() }}")
        } else {
            device.library.operator.albumKind = kind
            CommandResult.ok("""{"albumKind":"${kind.name.lowercase()}"}""")
        }
    }),
    // The person deletes an album the app made — under `folder`, with the photos in it.
    "album/delete" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val album = params["album"] ?: return@RigCommand CommandResult.badRequest("album is required")
        device.library.operator.delete(album)
        CommandResult.ok("""{"deleted":${jsonString(album)}}""")
    }),
    // Every add to an album waits until released (`on=false`) — the photo library's change blocks held.
    "album/hold-adds" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val on = flag(params, "on")
        if (on) device.library.operator.holdAdds() else device.library.operator.releaseAdds()
        CommandResult.ok("""{"held":$on}""")
    }),
    "album/place" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val album = params["album"]
        val asset = params["asset"]
        if (album == null || asset == null) {
            CommandResult.badRequest("album and asset are both required")
        } else {
            device.library.operator.placeIn(album, asset)
            CommandResult.ok("""{"album":${jsonString(album)},"asset":${jsonString(asset)}}""")
        }
    }),
    // The limited selection, as the picker's outcome delivers it: exactly `assets` (comma-separated; empty allowed).
    "selection/change" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val library = device.library.operator
        if (!library.observing) {
            return@RigCommand CommandResult.refused("no selection observer is open — the host was never assembled, so nothing would hear it")
        }
        val assets = params["assets"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val wanted = assets.mapTo(mutableSetOf(), ::AssetId)
        library.changeSelection(library.current().filter { it.assetId in wanted })
        CommandResult.ok(buildJsonObject { putJsonArray("selected") { assets.forEach { add(JsonPrimitive(it)) } } }.toString())
    }),
    // One own photo of a chosen id, capture date and kind — what `gallery/seed`'s random batch cannot say.
    "gallery/add" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val id = params["id"] ?: return@RigCommand CommandResult.badRequest("id is required")
        val date = params["date"] ?: LibraryAssets.DEFAULT_DATE
        val asset = when (params["kind"] ?: "photo") {
            "photo" -> LibraryAssets.photo(id, creationDate = date)
            "low-res" -> LibraryAssets.lowResPhoto(id, date)
            "screenshot" -> LibraryAssets.screenshot(id, date)
            "screen-recording" -> LibraryAssets.screenRecording(id, date)
            "hd-video" -> LibraryAssets.hdVideo(id, date)
            "live-photo" -> LibraryAssets.livePhoto(id, date)
            "gif" -> LibraryAssets.gif(id, date)
            else -> return@RigCommand CommandResult.badRequest(
                "kind must be photo|low-res|screenshot|screen-recording|hd-video|live-photo|gif, was '${params["kind"]}'",
            )
        }
        device.library.operator.add(asset)
        CommandResult.ok(buildJsonObject { put("added", id); put("date", date) }.toString())
    }),
    // The person deletes an own photo from the library.
    "gallery/remove" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val id = params["id"] ?: return@RigCommand CommandResult.badRequest("id is required")
        device.library.operator.remove(AssetId(id))
        CommandResult.ok(buildJsonObject { put("removed", id) }.toString())
    }),
    // Every walk of the library waits until released (`on=false`) — a library not yet enumerated, so nothing is counted.
    "gallery/hold-enumeration" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val on = flag(params, "on")
        if (on) device.library.operator.holdEnumeration() else device.library.operator.releaseEnumeration()
        CommandResult.ok("""{"held":$on}""")
    }),
    "gallery/fail-next-enumeration" to mocked(MockedSystem.LIBRARY, RigCommand { _, _ ->
        device.library.operator.failNextEnumeration = true
        CommandResult.ok("""{"armed":true}""")
    }),
    // `afterCommit=true`: the asset is created and the report never comes (a process death mid-import); otherwise the
    // transaction is held open before its commit lands.
    "import/suspend-next" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        val imports = device.library.operator.imports
        if (params["afterCommit"]?.toBoolean() == true) imports.suspendNextImportAfterCommit = true else imports.suspendNextImport = true
        CommandResult.ok("""{"armed":true}""")
    }),
    "import/await-parked" to mocked(MockedSystem.LIBRARY, RigCommand { _, _ ->
        val ref = withTimeoutOrNull(AWAIT) { device.library.operator.imports.suspendedImport.await() }
            ?: return@RigCommand CommandResult.refused("no import parked within $AWAIT")
        CommandResult.ok(buildJsonObject { put("device", ref.sourceDeviceId); put("asset", ref.sourceAssetId.value) }.toString())
    }),
    "import/resume" to mocked(MockedSystem.LIBRARY, RigCommand { params, _ ->
        device.library.operator.imports.resumeSuspendedImport(succeeded = params["succeeded"]?.toBoolean() ?: true)
        CommandResult.ok("""{"resumed":true}""")
    }),
)

private fun MockWorld.deviceLevers(): Map<String, Lever> = mapOf(
    // The membership file made unreadable on the device's disk — a device before its first unlock after a boot.
    "membership/unreadable" to mocked(MockedSystem.FILES, RigCommand { params, _ ->
        val on = flag(params, "on")
        device.disk.operator.deny(FileArea.SHARED, CONFIG_FILE_NAME, on)
        CommandResult.ok("""{"membershipUnreadable":$on}""")
    }),
    // The operating system's wall clock, as the core reads it. `to` is an ISO instant.
    "clock/advance" to mocked(MockedSystem.CLOCK, RigCommand { params, _ ->
        val to = params["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return@RigCommand CommandResult.badRequest("to must be an ISO instant, was '${params["to"]}'")
        device.clock.operator.now = to
        CommandResult.ok(buildJsonObject { put("now", to.toString()) }.toString())
    }),
    // The device's network as the operating system reports it to the app (capability `sync-status`): `access` is
    // online|offline|blocked — blocked is the network withheld from this app, offline none at all.
    "network" to mocked(MockedSystem.NETWORK, RigCommand { params, _ ->
        val access = NetworkAccess.entries.firstOrNull { it.name.equals(params["access"], ignoreCase = true) }
        if (access == null) {
            CommandResult.badRequest("access must be one of ${NetworkAccess.entries.joinToString("|")}, was '${params["access"]}'")
        } else {
            device.connectivity.operator.access = access
            CommandResult.ok("""{"network":"${access.name}"}""")
        }
    }),
    // How this build answers an invite link's dev/test hints: `honoured=false` plays a shipped build, which ignores
    // them; a host starts as a rig build, which honours them. The build's own controls, so on every host.
    "invite-link-hints" to Lever(emptyList(), RigCommand { params, _ ->
        val honoured = flag(params, "honoured")
        setInviteLinkHints(if (honoured) InviteLinkHints.Honoured else InviteLinkHints.Ignored)
        CommandResult.ok("""{"honoured":$honoured}""")
    }),
    // Text a process's device log carries — what a diagnostic dump reads back. `process` is app|extension.
    "logs/append" to mocked(MockedSystem.FILES, RigCommand { params, body ->
        val (process, area, file) = when (params["process"] ?: "app") {
            "app" -> Triple(LogTailService.Process.APP, FileArea.PRIVATE, APP_LOG_FILE_NAME)
            "extension" -> Triple(LogTailService.Process.EXTENSION, FileArea.SHARED, EXTENSION_LOG_FILE_NAME)
            else -> return@RigCommand CommandResult.badRequest("process must be app|extension")
        }
        device.disk.operator.append(area, file, body ?: params["text"].orEmpty())
        CommandResult.ok(buildJsonObject { put("appended", process.name.lowercase()) }.toString())
    }),
    "staging/seed-legacy-backlog" to needs(
        Need.Mocked(MockedSystem.DATABASES), Need.Mocked(MockedSystem.FILES),
        command = RigCommand { params, _ ->
            val who = params["device"] ?: "DEV-LEGACY"
            val asset = params["asset"] ?: "LEGACY"
            val paths = seedLegacyStagedBacklog(AssetRef(who, AssetId(asset)))
            CommandResult.ok(buildJsonObject { putJsonArray("staged") { paths.sorted().forEach { add(JsonPrimitive(it)) } } }.toString())
        },
    ),
    // What the operating system recorded of the app, for every system it plays here.
    "os-record" to Lever(emptyList(), RigCommand { _, _ -> CommandResult.ok(os.record()) }),
)

private fun MockWorld.deviceFacts(): Map<String, Lever> = mapOf(
    // The files in the download staging directory — what a person with the container open would see.
    "staging" to mocked(MockedSystem.FILES, RigCommand { _, _ ->
        val staged = device.disk.operator.paths(FileArea.SHARED).filter { it.startsWith("$DOWNLOAD_STAGING_DIR/") }
        CommandResult.ok(buildJsonObject { putJsonArray("files") { staged.sorted().forEach { add(JsonPrimitive(it)) } } }.toString())
    }),
    // Every album this app created, with the assets in it: under `collection` those placed, in order; under `folder`
    // the photos its folder holds now.
    "album/contents" to mocked(MockedSystem.LIBRARY, RigCommand { _, _ ->
        val library = device.library.operator
        CommandResult.ok(
            buildJsonObject {
                putJsonArray("albums") {
                    library.created.forEach { (id, name) ->
                        add(
                            buildJsonObject {
                                put("id", id); put("name", name)
                                putJsonArray("assets") { library.contentsOf(id).forEach { add(JsonPrimitive(it.value)) } }
                            },
                        )
                    }
                }
            }.toString(),
        )
    }),
)

/**
 * `POST /device/gallery/seed` over the photo library's mock: [n] photos of the seed kind, dated as the app host dates
 * its PhotoKit seed relative to its event window. `BULK` is dated long before any event (the walk-cost seed); `POLICY`
 * alternates above and below the image floor inside the default event window, so only the floor separates them.
 * `NOISE` exists to put bytes on the wire, which the mock does not carry, so it is refused rather than silently seeded
 * as something else.
 */
@OptIn(ExperimentalUuidApi::class)
fun MockWorld.seedMockLibrary(n: Int, kind: SeedKind): SeedOutcome {
    if (kind == SeedKind.NOISE) {
        throw SeedRefused("the mocked photos carry no bytes, so a seed for bytes on the wire would measure nothing")
    }
    val library = device.library.operator
    repeat(n) { i ->
        val id = Uuid.random().toString()
        library.add(
            when {
                kind == SeedKind.BULK -> LibraryAssets.photo(id, creationDate = BULK_DATE)
                i % 2 == 0 -> LibraryAssets.photo(id)
                else -> LibraryAssets.lowResPhoto(id)
            },
        )
    }
    return SeedOutcome(requested = n, created = n, kind = kind, failedAtChunk = null)
}

/**
 * The gallery read over the photo library's mock — what it answers a reader under the person's grant, through the
 * selection policy: the library under a full grant, the person's selection under a partial one, nothing without a
 * grant. Read off the mock, never the composed app — the same candidate reads and policy the app uses, over the
 * operating system's own state.
 */
fun MockWorld.mockGalleryReader(): suspend (String?, Boolean, Boolean) -> String = { cutoff, resources, includesUpload ->
    val library = device.library
    val reader = GalleryReport(
        candidates = PermissionAwareCandidateSource(
            permission = library.operator.grant,
            walk = GalleryCandidateSource(library.port()),
            // Read per request, as the rest of this reader is.
            selection = MutableStateFlow(library.operator.selection.value?.let(::resourcesFrom)),
        ),
        grant = { library.operator.access.name },
        census = {
            val facts = library.operator.current().map { it.facts }
            CensusView(
                total = facts.size.toLong(),
                screenshots = facts.count { it.isScreenshot }.toLong(),
                screenRecordings = facts.count { it.isScreenRecording }.toLong(),
            )
        },
    )
    leverJson.encodeToString(GalleryView.serializer(), reader.read(cutoff, resources, includesUpload))
}

/** The operating system finishes every in-flight download with [outcome]; answers their descriptions. */
private fun MockWorld.finishDownloads(outcome: TransferOutcome, bytes: ByteArray? = null): List<String> {
    val session = device.downloads.operator
    if (!session.realized) return emptyList()
    return session.inFlight().map { it.description }.onEach { session.finish(it, outcome, bytes) }
}

/** A fellow member with a primary resource per asset, captured on [filename]. */
internal fun foreignAsset(assetId: String, filename: String = "IMG.HEIC", contentType: String = "image/heic"): DeviceManifestAsset {
    val key = uploadKey(AssetId(assetId), ResourceRole.PRIMARY, filename)
    return DeviceManifestAsset(
        assetId = AssetId(assetId),
        creationDate = LibraryAssets.DEFAULT_DATE,
        resources = listOf(ManifestResource(ResourceRole.PRIMARY, contentType, key, filename)),
    )
}

/**
 * A fellow member through the backend's public surface: [device] joins [event] — or an event the backend mints for it —
 * uploads every resource's bytes where the app's uploader addresses them, and publishes its manifest. Returns the event.
 */
private suspend fun MockWorld.addForeignDevice(
    who: String,
    assets: List<DeviceManifestAsset>,
    event: String?,
    bytes: ByteArray? = null,
): String {
    val reach = reach!!
    val eventId = event ?: reach.createEvent(FOREIGN_EVENT_NAME, FOREIGN_EVENT_START)
    reach.join(eventId, who)
    assets.forEach { asset ->
        asset.resources.forEach { if (bytes != null) reach.upload(who, asset.assetId, it, bytes) else reach.upload(who, asset.assetId, it) }
    }
    reach.publish(eventId, DeviceManifest(deviceId = who, assets = assets))
    return eventId
}

/** Bytes of an own photo's resources land on the backend, and no acknowledgement reaches the app. */
private suspend fun MockWorld.landBytesWithoutAck(assetId: String) {
    val asset = device.library.operator.current().single { it.assetId == AssetId(assetId) }
    asset.rawResources.forEach { raw ->
        // Only a resource with an upload role is ever sent.
        val role = raw.role ?: return@forEach
        val key = uploadKey(asset.assetId, role, raw.originalFilename)
        reach!!.upload(ownDeviceId(), asset.assetId, ManifestResource(role, raw.mimeContentType, key, raw.originalFilename))
    }
}

/**
 * **An install upgraded from a build that predates per-asset byte release** (capability `receiving-photos`): a
 * confirmed import of [ref] whose resource rows, with their staged paths, survive, and whose files are still on the
 * staging "disk". The one lever that writes app-private state, deliberately: no path in the current app produces it,
 * so the honest way to reach it is to write, over the device's own database and disk, what the older build left.
 */
private suspend fun MockWorld.seedLegacyStagedBacklog(ref: AssetRef): Set<String> {
    val downloads = DownloadService(device.databases.port())
    val root = StagingService(device.disk.port()).stagingRoot()
    val primaryKey = "${ref.sourceAssetId}-primary.heic"
    val liveKey = "${ref.sourceAssetId}-live.mov"
    val paths = listOf("$root/$primaryKey", "$root/$liveKey")
    downloads.plan(
        ref,
        LibraryAssets.DEFAULT_DATE,
        listOf(
            PlannedResource(primaryKey, "https://mock.edge/p", "primary", "image/heic", "IMG.HEIC"),
            PlannedResource(liveKey, "https://mock.edge/l", "live", "video/quicktime", "IMG.MOV"),
        ),
    )
    downloads.markStaged(ref, primaryKey, paths[0])
    downloads.markStaged(ref, liveKey, paths[1])
    downloads.markImported(ref, AssetId("LOCAL-${ref.sourceAssetId}"))
    paths.forEach { device.disk.operator.write(FileArea.SHARED, it, LEGACY_BYTES) }
    return paths.toSet()
}

private suspend fun MockWorld.withEvent(params: Map<String, String>, block: suspend (String) -> CommandResult): CommandResult {
    val event = params["event"] ?: joinedEventId() ?: return CommandResult.badRequest("no joined event, and no `event` was named")
    return block(event)
}

private fun manifestJson(event: String, device: String, manifest: DeviceManifest?): String =
    """{"event":${JsonPrimitive(event)},"device":${JsonPrimitive(device)},"manifest":${manifest?.encodeToJson() ?: "null"}}"""

/** A dump as it left the process, read back into its five sections (`model/diagnosticDumpEvent` wrote them). */
private fun dumpJson(dump: CrashEvent) = buildJsonObject {
    val section = { name: String -> dump.contexts[name].orEmpty() }
    val appLog = section("app_log")["text"].orEmpty()
    val extensionLog = section("ext_log")["text"].orEmpty()
    put("note", section("note")["text"].orEmpty())
    putJsonObject("state") { section("state").forEach { (k, v) -> put(k, v) } }
    putJsonObject("ledger") { section("ledger").forEach { (k, v) -> put(k, v) } }
    put("appLog", appLog)
    put("extensionLog", extensionLog)
    put("logBytes", appLog.encodeToByteArray().size + extensionLog.encodeToByteArray().size)
}

internal fun flag(params: Map<String, String>, name: String): Boolean = params[name]?.toBoolean() ?: true

private fun uploadError(raw: String?): UploadError? = when {
    raw == null -> null
    raw.equals("network", ignoreCase = true) -> UploadError.Network
    raw.equals("cancelled", ignoreCase = true) -> UploadError.Cancelled
    raw.equals("unknown", ignoreCase = true) -> UploadError.Unknown("rig")
    raw.startsWith("http:") -> raw.removePrefix("http:").toIntOrNull()?.let { UploadError.Http(it) }
    else -> null
}

internal fun jsonList(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

private val leverJson = Json { encodeDefaults = true; prettyPrint = true }
private val OK = CommandResult.ok("""{"ok":true}""")
private val AWAIT = 10.seconds
private val LEGACY_BYTES = "staged".encodeToByteArray()
private const val FOREIGN_EVENT_NAME = "Anna's Birthday"
private const val FOREIGN_EVENT_START = "2026-01-01T00:00:00Z"

/** The app host's bulk-seed date: 2001-09-09, before any event can start. */
private const val BULK_DATE = "2001-09-09T01:46:40Z"
