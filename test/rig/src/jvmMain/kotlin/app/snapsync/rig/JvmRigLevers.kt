package app.snapsync.rig

import app.snapsync.mock.BackendOperator
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.CrashEvent
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import app.snapsync.model.ManifestResource
import app.snapsync.model.PlannedResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.model.uploadKey
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The JVM host's integration-surface verbs (`docs/testing.md`, "The seam-to-UI-state integration surface"): the
// observable reads of the mocked systems — the backend, the crash reporter's backend, the push service, the staging
// directory, the photo library's albums — and the levers that put them, the operating system and the photo library
// into the states a test starts from. Each is a mock's operator face. Over the real backend a read or lever only the
// backend mock's operator has answers `409` with the reason, never an empty value.

/** The integration surface's `/device` verbs. */
internal fun integrationCommands(rig: JvmRig): Map<String, RigCommand> =
    backendReads(rig) + backendLevers(rig) + osAndLibraryLevers(rig) + deviceFacts(rig)

private fun backendReads(rig: JvmRig): Map<String, RigCommand> = mapOf(
    "backend/union" to RigCommand { params, _ ->
        withEvent(rig, params) { event ->
            val union = rig.reach.unionOf(event)
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
    },
    "backend/manifest" to rig.onOperator("the manifest read") { operator, params ->
        withEvent(rig, params) { event ->
            val device = params["device"] ?: rig.mocks.ownDeviceId
            CommandResult.ok(manifestJson(event, device, operator.manifestOf(event, device)))
        }
    },
    // What the device registered for pushes, as the backend stored it: the token and its environment, and how many
    // registrations it stored.
    "backend/device-config" to rig.onOperator("the device-config read") { operator, params ->
        val device = params["device"] ?: rig.mocks.ownDeviceId
        val config = operator.deviceConfigOf(device)
        CommandResult.ok(
            buildJsonObject {
                put("device", device)
                put("token", config?.token)
                put("env", config?.env)
                put("writes", operator.deviceConfigWritesOf(device))
            }.toString(),
        )
    },
    "backend/event" to RigCommand { params, _ ->
        withEvent(rig, params) { event ->
            val (registered, name) = rig.reach.eventOf(event)
            CommandResult.ok(buildJsonObject { put("event", event); put("registered", registered); put("name", name) }.toString())
        }
    },
    "backend/departed" to rig.onOperator("the departed read") { operator, params ->
        withEvent(rig, params) { event ->
            val device = params["device"] ?: rig.mocks.ownDeviceId
            CommandResult.ok(
                buildJsonObject { put("event", event); put("device", device); put("departed", operator.isDeparted(event, device)) }
                    .toString(),
            )
        }
    },
    "backend/publishes" to rig.onOperator("the publish counter") { operator, params ->
        withEvent(rig, params) { event ->
            val device = params["device"] ?: rig.mocks.ownDeviceId
            CommandResult.ok(
                buildJsonObject {
                    put("event", event); put("device", device); put("applied", operator.publishesOf(event, device))
                }.toString(),
            )
        }
    },
    "backend/pushes" to rig.onOperator("the pushes read") { operator, _ ->
        CommandResult.ok(
            buildJsonObject {
                putJsonArray("pushes") {
                    operator.pushesSent().forEach { p ->
                        add(buildJsonObject { put("event", p.eventId); put("device", p.deviceId); put("token", p.token) })
                    }
                }
            }.toString(),
        )
    },
    "diagnostics/sent" to RigCommand { _, _ ->
        CommandResult.ok(
            buildJsonObject {
                put("dumps", JsonArray(rig.mocks.crashReporter.operator.sent.value.map(::dumpJson)))
            }.toString(),
        )
    },
)

private fun backendLevers(rig: JvmRig): Map<String, RigCommand> = mapOf(
    // Without `minimum`, the gate is turned off.
    "backend/min-app-version" to rig.onOperator("the minimum-app-version lever") { operator, params ->
        operator.minAppVersion = params["minimum"]
        OK
    },
    "backend/sweep" to rig.onOperator("the event sweep") { operator, params ->
        withEvent(rig, params) { event -> operator.sweepEvent(event); OK }
    },
    "backend/hold-leave" to rig.onOperator("the leave hold") { operator, _ -> operator.holdLeave(); OK },
    "backend/release-leave" to rig.onOperator("the leave release") { operator, _ -> operator.releaseLeave(); OK },
    "backend/fail-listing" to rig.onOperator("the listing-failure lever") { operator, params ->
        operator.failDeviceListing = flag(params, "on")
        OK
    },
    // An own photo's bytes land on the backend with NO acknowledgement reaching the app — the upload the OS completed
    // while the process was gone. Through the backend's public byte route, so on either backend.
    "backend/deposit" to RigCommand { params, _ ->
        val asset = params["asset"] ?: return@RigCommand CommandResult.badRequest("asset is required")
        landBytesWithoutAck(rig, asset)
        CommandResult.ok(buildJsonObject { put("landed", asset) }.toString())
    },
    "backend/legacy-event" to rig.onOperator("the legacy-event lever") { operator, params ->
        val event = operator.registerLegacyEvent(params["name"] ?: "Legacy")
        CommandResult.ok(buildJsonObject { put("event", event) }.toString())
    },
    "backend/refuse-credential" to rig.onOperator("the credential-refusal lever") { operator, _ ->
        operator.refuseNextCredential()
        OK
    },
)

private fun osAndLibraryLevers(rig: JvmRig): Map<String, RigCommand> = mapOf(
    // The operating system's wall clock, as the core reads it. `to` is an ISO instant.
    "clock/advance" to RigCommand { params, _ ->
        val to = params["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return@RigCommand CommandResult.badRequest("to must be an ISO instant, was '${params["to"]}'")
        rig.mocks.clock.operator.now = to
        CommandResult.ok(buildJsonObject { put("now", to.toString()) }.toString())
    },
    // The marketing version this build declares on every request — so a test can play an old build, or an update.
    "app-version" to RigCommand { params, _ ->
        val version = params["version"] ?: return@RigCommand CommandResult.badRequest("version is required")
        rig.version.value = version
        CommandResult.ok(buildJsonObject { put("appVersion", version) }.toString())
    },
    // Process death and a cold foreground launch: the new app's host is assembled — its subscriptions installed, the
    // startup sweep among them — and shown, as a phone brings a scene up. `scene=false` is a cold BACKGROUND launch
    // instead — the operating system starting the process for a wake, with no scene: nothing builds a screen, and
    // nothing that reads the screen (`/device/state`, `/user`) may be asked before an `onForeground` brings one up.
    "relaunch" to RigCommand { params, _ ->
        val scene = params["scene"]?.toBoolean() ?: true
        rig.app.relaunch()
        if (scene) rig.showScreen()
        CommandResult.ok("""{"relaunched":true,"scene":$scene}""")
    },
    // The limited selection, as the picker's outcome delivers it: exactly `assets` (comma-separated; empty allowed).
    "selection/change" to RigCommand { params, _ ->
        val library = rig.mocks.library.operator
        if (!library.observing) {
            return@RigCommand CommandResult.refused(
                "no selection observer is open — the host was never assembled, so nothing would hear it",
            )
        }
        val assets = params["assets"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val wanted = assets.mapTo(mutableSetOf(), ::AssetId)
        library.changeSelection(library.current().filter { it.assetId in wanted })
        CommandResult.ok(buildJsonObject { putJsonArray("selected") { assets.forEach { add(JsonPrimitive(it)) } } }.toString())
    },
    // One own photo of a chosen id, capture date and kind — what `gallery/seed`'s random batch cannot say.
    "gallery/add" to RigCommand { params, _ ->
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
        rig.mocks.library.operator.add(asset)
        CommandResult.ok(buildJsonObject { put("added", id); put("date", date) }.toString())
    },
    "gallery/fail-next-enumeration" to RigCommand { _, _ ->
        rig.mocks.library.operator.failNextEnumeration = true
        CommandResult.ok("""{"armed":true}""")
    },
    // `afterCommit=true`: the asset is created and the report never comes (a process death mid-import); otherwise the
    // transaction is held open before its commit lands.
    "import/suspend-next" to RigCommand { params, _ ->
        val imports = rig.mocks.library.operator.imports
        if (params["afterCommit"]?.toBoolean() == true) imports.suspendNextImportAfterCommit = true else imports.suspendNextImport = true
        CommandResult.ok("""{"armed":true}""")
    },
    "import/await-parked" to RigCommand { _, _ ->
        val ref = withTimeoutOrNull(AWAIT) { rig.mocks.library.operator.imports.suspendedImport.await() }
            ?: return@RigCommand CommandResult.refused("no import parked within $AWAIT")
        CommandResult.ok(buildJsonObject { put("device", ref.sourceDeviceId); put("asset", ref.sourceAssetId.value) }.toString())
    },
    "import/resume" to RigCommand { params, _ ->
        rig.mocks.library.operator.imports.resumeSuspendedImport(succeeded = params["succeeded"]?.toBoolean() ?: true)
        CommandResult.ok("""{"resumed":true}""")
    },
    // Text a process's device log carries — what a diagnostic dump reads back. `process` is app|extension.
    "logs/append" to RigCommand { params, body ->
        val (process, area, file) = when (params["process"] ?: "app") {
            "app" -> Triple(LogTailService.Process.APP, FileArea.PRIVATE, APP_LOG_FILE_NAME)
            "extension" -> Triple(LogTailService.Process.EXTENSION, FileArea.SHARED, EXTENSION_LOG_FILE_NAME)
            else -> return@RigCommand CommandResult.badRequest("process must be app|extension")
        }
        rig.mocks.disk.operator.append(area, file, body ?: params["text"].orEmpty())
        CommandResult.ok(buildJsonObject { put("appended", process.name.lowercase()) }.toString())
    },
    "staging/seed-legacy-backlog" to RigCommand { params, _ ->
        val device = params["device"] ?: "DEV-LEGACY"
        val asset = params["asset"] ?: "LEGACY"
        val paths = seedLegacyStagedBacklog(rig, AssetRef(device, AssetId(asset)))
        CommandResult.ok(buildJsonObject { putJsonArray("staged") { paths.sorted().forEach { add(JsonPrimitive(it)) } } }.toString())
    },
)

private fun deviceFacts(rig: JvmRig): Map<String, RigCommand> = mapOf(
    // The files in the download staging directory — what a person with the container open would see.
    "staging" to RigCommand { _, _ ->
        val staged = rig.mocks.disk.operator.area(FileArea.SHARED).keys.filter { it.startsWith("$DOWNLOAD_STAGING_DIR/") }
        CommandResult.ok(buildJsonObject { putJsonArray("files") { staged.sorted().forEach { add(JsonPrimitive(it)) } } }.toString())
    },
    // Every album this app created, with the assets placed in it, in order.
    "album/contents" to RigCommand { _, _ ->
        val library = rig.mocks.library.operator
        CommandResult.ok(
            buildJsonObject {
                putJsonArray("albums") {
                    library.created.forEach { (id, name) ->
                        add(
                            buildJsonObject {
                                put("id", id); put("name", name)
                                putJsonArray("assets") { library.assetsIn(id).forEach { add(JsonPrimitive(it.value)) } }
                            },
                        )
                    }
                }
            }.toString(),
        )
    },
)

/**
 * A verb only the backend mock's operator can serve: on the real backend it answers `409` with the reason, never an
 * empty value or a silent success.
 */
internal fun JvmRig.onOperator(
    operation: String,
    body: suspend (BackendOperator, Map<String, String>) -> CommandResult,
): RigCommand = RigCommand { params, _ ->
    backend.operator(mocks)?.let { body(it, params) } ?: CommandResult.refused(backend.unavailable(operation))
}

/** A fellow member with a primary resource per asset, captured on [filename]. */
internal fun foreignAsset(assetId: String, filename: String = "IMG.HEIC"): DeviceManifestAsset {
    val key = uploadKey(AssetId(assetId), ResourceRole.PRIMARY, filename)
    return DeviceManifestAsset(
        assetId = AssetId(assetId),
        creationDate = LibraryAssets.DEFAULT_DATE,
        resources = listOf(ManifestResource(ResourceRole.PRIMARY, "image/heic", key, filename)),
    )
}

/**
 * A fellow member through the backend's public surface: [device] joins [event] — or an event the backend mints for it —
 * uploads every resource's bytes where the app's uploader addresses them, and publishes its manifest. Returns the event.
 */
internal suspend fun addForeignDevice(rig: JvmRig, device: String, assets: List<DeviceManifestAsset>, event: String?): String {
    val eventId = event ?: rig.reach.createEvent(FOREIGN_EVENT_NAME, FOREIGN_EVENT_START)
    rig.reach.join(eventId, device)
    assets.forEach { asset -> asset.resources.forEach { rig.reach.upload(device, asset.assetId, it) } }
    rig.reach.publish(eventId, DeviceManifest(deviceId = device, assets = assets))
    return eventId
}

/** Bytes of an own photo's resources land on the backend, and no acknowledgement reaches the app. */
private suspend fun landBytesWithoutAck(rig: JvmRig, assetId: String) {
    val asset = rig.mocks.library.operator.current().single { it.assetId == AssetId(assetId) }
    asset.rawResources.forEach { raw ->
        // Only a resource with an upload role is ever sent.
        val role = raw.role ?: return@forEach
        val key = uploadKey(asset.assetId, role, raw.originalFilename)
        rig.reach.upload(rig.mocks.ownDeviceId, asset.assetId, ManifestResource(role, raw.mimeContentType, key, raw.originalFilename))
    }
}

/**
 * **An install upgraded from a build that predates per-asset byte release** (capability `receiving-photos`): a
 * confirmed import of [ref] whose resource rows, with their staged paths, survive, and whose files are still on the
 * staging "disk". The one lever that writes app-private state, deliberately: no path in the current app produces it,
 * so the honest way to reach it is to write, over the device's own database and disk, what the older build left.
 */
private suspend fun seedLegacyStagedBacklog(rig: JvmRig, ref: AssetRef): Set<String> {
    val downloads = DownloadService(rig.mocks.databases.port())
    val root = StagingService(rig.mocks.disk.port()).stagingRoot()
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
    paths.forEach { rig.mocks.disk.operator.write(FileArea.SHARED, it, LEGACY_BYTES) }
    return paths.toSet()
}

private suspend fun withEvent(
    rig: JvmRig,
    params: Map<String, String>,
    block: suspend (String) -> CommandResult,
): CommandResult {
    val event = params["event"] ?: joinedEventId(rig)
        ?: return CommandResult.badRequest("no joined event, and no `event` was named")
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

private val OK = CommandResult.ok("""{"ok":true}""")
private val AWAIT = 10.seconds
private val LEGACY_BYTES = "staged".encodeToByteArray()
private const val FOREIGN_EVENT_NAME = "Anna's Birthday"
private const val FOREIGN_EVENT_START = "2026-01-01T00:00:00Z"
