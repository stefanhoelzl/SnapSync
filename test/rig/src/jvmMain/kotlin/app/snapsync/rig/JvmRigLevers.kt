package app.snapsync.rig

import app.snapsync.model.DiagnosticDump
import app.snapsync.model.DeviceManifest
import app.snapsync.model.encodeToJson
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.UnionAsset
import app.snapsync.world.Answer
import app.snapsync.world.World
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds

// The JVM host's integration-surface verbs (`docs/testing.md`, "The seam-to-UI-state integration
// surface"): the observable reads of the world's simulated systems — the backend, the crash reporter, the push
// service, the staging directory, the photo library's albums — and the levers that put them, the operating system
// and the photo library into the states a test starts from. Each is a world lever or a device fact the app host
// refuses with its reason (`RigVocabulary`). Backend reads go through the world's backend-neutral inspection, so
// over the real backend a read it cannot serve answers `409` with the reason rather than an empty value.

/** The integration surface's `/device` verbs over [world]. */
internal fun worldIntegrationCommands(world: World, afterRelaunch: () -> Unit): Map<String, RigCommand> =
    backendReads(world) + backendLevers(world) + osAndLibraryLevers(world, afterRelaunch) + deviceFacts(world)

private fun backendReads(world: World): Map<String, RigCommand> = mapOf(
    "backend/union" to RigCommand { params, _ ->
        withEvent(world, params) { event -> answered(world.neutral.unionOf(event)) { unionJson(event, it) } }
    },
    "backend/manifest" to RigCommand { params, _ ->
        withEvent(world, params) { event ->
            val device = params["device"] ?: world.ownDeviceId
            answered(world.neutral.manifestOf(event, device)) { manifestJson(event, device, it) }
        }
    },
    "backend/device-config" to RigCommand { params, _ ->
        val device = params["device"] ?: world.ownDeviceId
        val writes = (world.neutral.deviceConfigWritesOf(device) as? Answer.Available)?.value
        answered(world.neutral.deviceConfigOf(device)) { config ->
            buildJsonObject { put("device", device); put("config", config); put("writes", writes) }.toString()
        }
    },
    "backend/event" to RigCommand { params, _ ->
        withEvent(world, params) { event ->
            val registered = world.neutral.isRegistered(event)
            val name = world.neutral.eventNameOf(event)
            if (registered is Answer.Available && name is Answer.Available) {
                CommandResult.ok(
                    buildJsonObject {
                        put("event", event); put("registered", registered.value); put("name", name.value)
                    }.toString(),
                )
            } else {
                CommandResult.refused((registered as? Answer.Unavailable)?.reason ?: (name as Answer.Unavailable).reason)
            }
        }
    },
    "backend/departed" to RigCommand { params, _ ->
        withEvent(world, params) { event ->
            val device = params["device"] ?: world.ownDeviceId
            answered(world.neutral.isDeparted(event, device)) { departed ->
                buildJsonObject { put("event", event); put("device", device); put("departed", departed) }.toString()
            }
        }
    },
    "backend/publishes" to RigCommand { params, _ ->
        withEvent(world, params) { event ->
            val device = params["device"] ?: world.ownDeviceId
            answered(world.neutral.publishesOf(event, device)) { applied ->
                buildJsonObject { put("event", event); put("device", device); put("applied", applied) }.toString()
            }
        }
    },
    "backend/pushes" to RigCommand { _, _ ->
        answered(world.neutral.pushesSent()) { pushes ->
            buildJsonObject {
                putJsonArray("pushes") {
                    pushes.forEach { p ->
                        add(buildJsonObject { put("event", p.eventId); put("device", p.deviceId); put("token", p.token) })
                    }
                }
            }.toString()
        }
    },
    "diagnostics/sent" to RigCommand { _, _ ->
        CommandResult.ok(
            buildJsonObject { put("dumps", JsonArray(world.diagnosticsSent.value.map(::dumpJson))) }.toString(),
        )
    },
)

private fun backendLevers(world: World): Map<String, RigCommand> = mapOf(
    // Without `minimum`, the gate is turned off.
    "backend/min-app-version" to RigCommand { params, _ -> done(world.neutral.setMinAppVersion(params["minimum"])) },
    "backend/sweep" to RigCommand { params, _ ->
        withEvent(world, params) { event -> done(world.neutral.sweepEvent(event)) }
    },
    "backend/hold-leave" to RigCommand { _, _ -> done(world.neutral.holdLeave()) },
    "backend/release-leave" to RigCommand { _, _ -> done(world.neutral.releaseLeave()) },
    "backend/fail-listing" to RigCommand { params, _ ->
        done(world.neutral.setDeviceListingFails(params["on"]?.toBoolean() ?: true))
    },
    "backend/deposit" to RigCommand { params, _ ->
        val asset = params["asset"] ?: return@RigCommand CommandResult.badRequest("asset is required")
        world.landBytesWithoutAck(asset)
        CommandResult.ok(buildJsonObject { put("landed", asset) }.toString())
    },
    "backend/legacy-event" to RigCommand { params, _ ->
        answered(world.neutral.registerLegacyEvent(params["name"] ?: "Legacy")) { event ->
            buildJsonObject { put("event", event) }.toString()
        }
    },
    "backend/refuse-credential" to RigCommand { _, _ -> done(world.neutral.refuseNextCredential()) },
)

private fun osAndLibraryLevers(world: World, afterRelaunch: () -> Unit): Map<String, RigCommand> = mapOf(
    // The operating system's wall clock, as the core reads it. `to` is an ISO instant.
    "clock/advance" to RigCommand { params, _ ->
        val to = params["to"]?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() }
            ?: return@RigCommand CommandResult.badRequest("to must be an ISO instant, was '${params["to"]}'")
        world.nowMillis = to.toEpochMilliseconds()
        CommandResult.ok(buildJsonObject { put("now", to.toString()) }.toString())
    },
    // The marketing version this build declares on every request — so a test can play an old build.
    "app-version" to RigCommand { params, _ ->
        val version = params["version"] ?: return@RigCommand CommandResult.badRequest("version is required")
        world.appVersion = version
        CommandResult.ok(buildJsonObject { put("appVersion", version) }.toString())
    },
    // Process death and a cold foreground launch: the new app's host is assembled — its subscriptions installed,
    // the startup sweep among them — and shown, as a phone brings a scene up.
    "relaunch" to RigCommand { _, _ ->
        world.relaunch()
        afterRelaunch()
        CommandResult.ok("""{"relaunched":true}""")
    },
    // The limited selection, as the picker's outcome delivers it: exactly `assets` (comma-separated; empty allowed).
    "selection/change" to RigCommand { params, _ ->
        val assets = params["assets"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        withTimeoutOrNull(AWAIT) { world.changeSelection(*assets.toTypedArray()) }
            ?: return@RigCommand CommandResult.refused(
                "no selection observer is installed — the host was never assembled, so nothing would hear it",
            )
        CommandResult.ok(buildJsonObject { putJsonArray("selected") { assets.forEach { add(JsonPrimitive(it)) } } }.toString())
    },
    // One own photo of a chosen id, capture date and kind — what `gallery/seed`'s random batch cannot say.
    "gallery/add" to RigCommand { params, _ ->
        val id = params["id"] ?: return@RigCommand CommandResult.badRequest("id is required")
        val date = params["date"] ?: World.DEFAULT_DATE
        when (params["kind"] ?: "photo") {
            "photo" -> world.addOwnAsset(id, creationDate = date)
            "low-res" -> world.addLowResPhoto(id, date)
            "screenshot" -> world.addScreenshot(id, date)
            "screen-recording" -> world.addScreenRecording(id, date)
            "hd-video" -> world.addHdVideo(id, date)
            "live-photo" -> world.addLivePhoto(id, date)
            "gif" -> world.addGif(id, date)
            else -> return@RigCommand CommandResult.badRequest(
                "kind must be photo|low-res|screenshot|screen-recording|hd-video|live-photo|gif, was '${params["kind"]}'",
            )
        }
        CommandResult.ok(buildJsonObject { put("added", id); put("date", date) }.toString())
    },
    "gallery/fail-next-enumeration" to RigCommand { _, _ ->
        world.failNextEnumeration()
        CommandResult.ok("""{"armed":true}""")
    },
    // `afterCommit=true`: the asset is created and the report never comes (a process death mid-import); otherwise
    // the transaction is held open before its commit lands.
    "import/suspend-next" to RigCommand { params, _ ->
        if (params["afterCommit"]?.toBoolean() == true) world.suspendNextImportAfterCommit() else world.suspendNextImport()
        CommandResult.ok("""{"armed":true}""")
    },
    "import/await-parked" to RigCommand { _, _ ->
        val ref = withTimeoutOrNull(AWAIT) { world.importerSuspended.await() }
            ?: return@RigCommand CommandResult.refused("no import parked within $AWAIT")
        CommandResult.ok(buildJsonObject { put("device", ref.sourceDeviceId); put("asset", ref.sourceAssetId) }.toString())
    },
    "import/resume" to RigCommand { params, _ ->
        world.resumeSuspendedImport(succeeded = params["succeeded"]?.toBoolean() ?: true)
        CommandResult.ok("""{"resumed":true}""")
    },
    // Text a process's device log carries — what a diagnostic dump reads back. `process` is app|extension.
    "logs/append" to RigCommand { params, body ->
        val process = when (params["process"] ?: "app") {
            "app" -> DeviceLogSource.Process.APP
            "extension" -> DeviceLogSource.Process.EXTENSION
            else -> return@RigCommand CommandResult.badRequest("process must be app|extension")
        }
        world.appendDeviceLog(process, body ?: params["text"].orEmpty())
        CommandResult.ok(buildJsonObject { put("appended", process.name.lowercase()) }.toString())
    },
    "staging/seed-legacy-backlog" to RigCommand { params, _ ->
        val device = params["device"] ?: "DEV-LEGACY"
        val asset = params["asset"] ?: "LEGACY"
        val paths = world.seedLegacyStagedBacklog(app.snapsync.model.AssetRef(device, asset))
        CommandResult.ok(buildJsonObject { putJsonArray("staged") { paths.sorted().forEach { add(JsonPrimitive(it)) } } }.toString())
    },
)

private fun deviceFacts(world: World): Map<String, RigCommand> = mapOf(
    // The files in the download staging directory — what a person with the container open would see.
    "staging" to RigCommand { _, _ ->
        CommandResult.ok(
            buildJsonObject { putJsonArray("files") { world.stagedFiles.sorted().forEach { add(JsonPrimitive(it)) } } }
                .toString(),
        )
    },
    // Every album this app created, with the assets placed in it, in order.
    "album/contents" to RigCommand { _, _ ->
        CommandResult.ok(
            buildJsonObject {
                putJsonArray("albums") {
                    world.gallery.created.forEach { (id, name) ->
                        add(
                            buildJsonObject {
                                put("id", id); put("name", name)
                                putJsonArray("assets") { world.gallery.assetsIn(id).forEach { add(JsonPrimitive(it)) } }
                            },
                        )
                    }
                }
            }.toString(),
        )
    },
)

/** A downloads stage that returns while an import it started is parked — the import completes in the world's scope. */
internal fun stageWithoutWaiting(world: World): CommandResult {
    world.scope.launch { world.stageAllDownloads() }
    return CommandResult.ok("""{"staged":true,"waited":false}""")
}

private val AWAIT = 10.seconds

private suspend fun withEvent(
    world: World,
    params: Map<String, String>,
    block: suspend (String) -> CommandResult,
): CommandResult {
    val event = params["event"] ?: world.configSource.config.value?.eventId
        ?: return CommandResult.badRequest("no joined event, and no `event` was named")
    return block(event)
}

private inline fun <T> answered(answer: Answer<T>, render: (T) -> String): CommandResult = when (answer) {
    is Answer.Available -> CommandResult.ok(render(answer.value))
    is Answer.Unavailable -> CommandResult.refused(answer.reason)
}

private fun done(answer: Answer<Unit>): CommandResult = answered(answer) { """{"ok":true}""" }

private fun unionJson(event: String, union: List<UnionAsset>): String = buildJsonObject {
    put("event", event)
    putJsonArray("assets") {
        union.forEach { a ->
            add(
                buildJsonObject {
                    put("device", a.deviceId); put("asset", a.assetId)
                    putJsonArray("roles") { a.resources.forEach { add(JsonPrimitive(it.role)) } }
                },
            )
        }
    }
}.toString()

private fun manifestJson(event: String, device: String, manifest: DeviceManifest?): String =
    """{"event":${JsonPrimitive(event)},"device":${JsonPrimitive(device)},"manifest":${manifest?.encodeToJson() ?: "null"}}"""

private fun dumpJson(dump: DiagnosticDump) = buildJsonObject {
    put("note", dump.note)
    putJsonObject("state") { dump.state.forEach { (k, v) -> put(k, v) } }
    putJsonObject("ledger") { dump.ledger.forEach { (k, v) -> put(k, v) } }
    put("appLog", dump.appLog)
    put("extensionLog", dump.extensionLog)
    put("logBytes", dump.logBytes)
}
