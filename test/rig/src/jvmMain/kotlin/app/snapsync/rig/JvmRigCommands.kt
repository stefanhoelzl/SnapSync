package app.snapsync.rig

import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploadError
import app.snapsync.rig.gallery.GalleryReader
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.SeedOutcome
import app.snapsync.world.Answer
import app.snapsync.world.World
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The JVM host's `/device` writes, its gallery read, and what it refuses of the shared vocabulary (capability
// `testing-architecture`, "One control protocol, served by two hosts"). The shared commands take the same
// parameters and answer the same shape as on the app host — their parsing and rendering are `commonMain`'s — and
// the world levers are the full-stack inspector's set (capability `full-stack-harness`).

private val json = Json { encodeDefaults = true; prettyPrint = true }

/** The JVM host's `/device` write commands. */
internal fun worldDeviceCommands(world: World, afterRelaunch: () -> Unit): Map<String, RigCommand> =
    inspectorLevers(world) + worldIntegrationCommands(world, afterRelaunch)

/** The full-stack world inspector's levers (capability `full-stack-harness`). */
private fun inspectorLevers(world: World): Map<String, RigCommand> = mapOf(
    "reset" to resetCommand { world.core },
    "gallery/seed" to seedCommand { n, kind -> seedWorld(world, n, kind) },
    "backend/offline" to RigCommand { params, _ -> answered(world.neutral.setOffline(flag(params, "on"))) },
    "jobs" to RigCommand { _, _ ->
        CommandResult.ok(
            """{"live":${jsonList(world.platform.liveJobKeys())},"created":${world.platform.created.size}}""",
        )
    },
    "jobs/limit" to RigCommand { params, _ ->
        val n = params["n"]?.toIntOrNull()
        if (n == null || n < 0) {
            CommandResult.badRequest("n must be a non-negative integer, was '${params["n"]}'")
        } else {
            world.jobLimit = n
            CommandResult.ok("""{"jobLimit":$n}""")
        }
    },
    // Without `key`, every live job — what a caller that just ran a cycle usually means.
    "jobs/complete" to RigCommand { params, _ ->
        val keys = params["key"]?.let(::listOf) ?: world.platform.liveJobKeys()
        keys.forEach { world.platform.completeJob(it) }
        CommandResult.ok("""{"completed":${jsonList(keys)}}""")
    },
    "jobs/fail" to RigCommand { params, _ ->
        val key = params["key"]
        val error = uploadError(params["error"])
        when {
            key == null -> CommandResult.badRequest("key is required")
            error == null -> CommandResult.badRequest(
                "error must be network|cancelled|unknown|http:<status>, was '${params["error"]}'",
            )
            else -> {
                world.platform.failJob(key, error)
                CommandResult.ok("""{"failed":${jsonString(key)},"error":${jsonString(error.toString())}}""")
            }
        }
    },
    "import/fail-next" to RigCommand { _, _ ->
        world.failNextImport()
        CommandResult.ok("""{"armed":true}""")
    },
    "membership/unreadable" to RigCommand { params, _ ->
        world.membershipUnreadable = flag(params, "on")
        CommandResult.ok("""{"membershipUnreadable":${world.membershipUnreadable}}""")
    },
    "permission" to RigCommand { params, _ ->
        val status = PermissionStatus.entries.firstOrNull { it.name.equals(params["status"], ignoreCase = true) }
        if (status == null) {
            CommandResult.badRequest(
                "status must be one of ${PermissionStatus.entries.joinToString("|")}, was '${params["status"]}'",
            )
        } else {
            world.permission.set(status)
            CommandResult.ok("""{"permission":"${status.name}"}""")
        }
    },
    // `wait=false` returns at once, so a caller can drive other triggers while an import it started is parked.
    "downloads/stage" to RigCommand { params, _ ->
        if (params["wait"]?.toBoolean() == false) {
            stageWithoutWaiting(world)
        } else {
            world.stageAllDownloads()
            CommandResult.ok("""{"staged":true}""")
        }
    },
    "downloads/reconcile" to RigCommand { _, _ ->
        val eventId = world.configSource.config.value?.eventId
        if (eventId == null) {
            CommandResult.badRequest("no membership to reconcile downloads for")
        } else {
            world.downloadController.reconcile(eventId)
            CommandResult.ok("""{"reconciled":${jsonString(eventId)}}""")
        }
    },
    "album/place" to RigCommand { params, _ ->
        val album = params["album"]
        val asset = params["asset"]
        if (album == null || asset == null) {
            CommandResult.badRequest("album and asset are both required")
        } else {
            world.placeInAlbum(album, asset)
            CommandResult.ok("""{"album":${jsonString(album)},"asset":${jsonString(asset)}}""")
        }
    },
    // A fellow member with complete photos, through the backend's public surface. `event` defaults to the joined
    // one; `assets` is a comma-separated list of asset ids.
    "foreign-device" to RigCommand { params, _ ->
        val device = params["device"]
        val assets = params["assets"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val event = params["event"] ?: world.configSource.config.value?.eventId
        // The capturing device's own file name for every asset — what an import names its photo after.
        val filename = params["filename"]
        if (device == null || assets.isEmpty()) {
            CommandResult.badRequest("device and a non-empty comma-separated assets are required")
        } else {
            val manifest = assets.map { if (filename != null) World.foreignAsset(it, filename) else World.foreignAsset(it) }
            val eventId = world.addForeignDeviceMinted(device, manifest, event)
            CommandResult.ok("""{"device":${jsonString(device)},"eventId":${jsonString(eventId)}}""")
        }
    },
    // What the backend lists for a device — the neutral read, over its public HTTP surface, on either backend.
    // `device` defaults to this host's own.
    "backend/objects" to RigCommand { params, _ ->
        val device = params["device"] ?: world.ownDeviceId
        when (val objects = world.neutral.objectsOf(device)) {
            is Answer.Available ->
                CommandResult.ok("""{"device":${jsonString(device)},"objects":${jsonList(objects.value.sorted())}}""")
            is Answer.Unavailable -> CommandResult.refused(objects.reason)
        }
    },
    "status/refresh" to RigCommand { _, _ ->
        world.refreshStatus()
        CommandResult.ok("""{"refreshed":true}""")
    },
)

/** The gallery read over the world: the app's own candidate seam and policy, with the world gallery's census. */
internal fun worldGalleryReader(world: World): suspend (String?, Boolean, Boolean) -> String =
    { cutoff, resources, includesUpload ->
        val reader = GalleryReader(
            candidates = world.core.candidates,
            grant = { world.core.photoPermission.value.name },
            census = {
                val facts = world.gallery.current().map { it.facts }
                CensusView(
                    total = facts.size.toLong(),
                    screenshots = facts.count { it.isScreenshot }.toLong(),
                    screenRecordings = facts.count { it.isScreenRecording }.toLong(),
                )
            },
        )
        json.encodeToString(GalleryView.serializer(), reader.read(cutoff, resources, includesUpload))
    }

/** What the JVM host refuses of the shared vocabulary, each with its reason. */
internal fun jvmRefusals(): Map<String, String> = buildMap {
    put(
        "os/app/onPushTokenFailure",
        "the inbound port has no push-registration-failure entry: on iOS this callback only logs the platform's " +
            "error, and the world's platform never fails to register",
    )
    put(
        "device/gallery/wipe",
        "a world's gallery is fresh for every host, so there is nothing to wipe; the wipe's answer is PhotoKit's " +
            "source, album and folder census, which a world gallery has no counterpart for",
    )
    put("device/uploaders", "the world composes no OS-driven upload mechanism for a switch to choose between")
    put(
        "device/process-metrics",
        "process-metric reports are MetricKit's; the world runs no process the operating system measures",
    )
    RigVocabulary.appHostCommands.filter { it.startsWith("device/upload-") }.forEach {
        put(it, "the world's upload-job queue is operated by the jobs verbs, not by an operating system to play")
    }
    put(
        RigVocabulary.CONTRACT,
        "host JVM runs no contract in-process: JVM contract bindings run under Gradle in the canonical check",
    )
}

/**
 * Seed [n] world photos of [kind], dated as the app host dates them relative to its event window.
 *
 * `BULK` is dated long before any event (the walk-cost seed); `POLICY` alternates above and below the image floor
 * inside the default event window, so only the floor separates them. `NOISE` exists to put bytes on the wire,
 * which the world does not carry, so it is refused rather than silently seeded as something else.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun seedWorld(world: World, n: Int, kind: SeedKind): SeedOutcome {
    if (kind == SeedKind.NOISE) {
        throw SeedRefused("the world's photos carry no bytes, so a seed for bytes on the wire would measure nothing")
    }
    repeat(n) { i ->
        val id = Uuid.random().toString()
        when {
            kind == SeedKind.BULK -> world.addOwnAsset(id, creationDate = BULK_DATE)
            i % 2 == 0 -> world.addOwnAsset(id)
            else -> world.addLowResPhoto(id)
        }
    }
    return SeedOutcome(requested = n, created = n, kind = kind, failedAtChunk = null)
}

/** The app host's bulk-seed date: 2001-09-09, before any event can start. */
private const val BULK_DATE = "2001-09-09T01:46:40Z"

private fun flag(params: Map<String, String>, name: String): Boolean = params[name]?.toBoolean() ?: true

private fun uploadError(raw: String?): UploadError? = when {
    raw == null -> null
    raw.equals("network", ignoreCase = true) -> UploadError.Network
    raw.equals("cancelled", ignoreCase = true) -> UploadError.Cancelled
    raw.equals("unknown", ignoreCase = true) -> UploadError.Unknown("rig")
    raw.startsWith("http:") -> raw.removePrefix("http:").toIntOrNull()?.let { UploadError.Http(it) }
    else -> null
}

private fun answered(answer: Answer<Unit>): CommandResult = when (answer) {
    is Answer.Available -> CommandResult.ok("""{"ok":true}""")
    is Answer.Unavailable -> CommandResult.refused(answer.reason)
}

private fun jsonList(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }
