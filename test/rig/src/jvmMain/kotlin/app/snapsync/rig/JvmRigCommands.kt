package app.snapsync.rig

import app.snapsync.feature.upload.TailTrigger
import app.snapsync.mock.DownloadSessionMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.ConfigRead
import app.snapsync.model.FileArea
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Layer
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadError
import app.snapsync.rig.gallery.GalleryReport
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.SeedOutcome
import app.snapsync.services.config.CONFIG_FILE_NAME
import app.snapsync.services.config.ConfigService
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The JVM host's `/device` writes, its gallery read, and what it refuses of the shared vocabulary (`docs/testing.md`,
// "One control protocol, served by two hosts"). The shared commands take the same parameters and answer the same shape
// as on the app host — their parsing and rendering are `commonMain`'s. Every lever and read here is a mock's operator
// face, save the four that still reach the composed core — named where they are defined, and rewritten with the tests
// that use them (11g2b): `downloads/stage`'s await, `downloads/reconcile`, `status/refresh` and the gallery read.

private val json = Json { encodeDefaults = true; prettyPrint = true }

/** The JVM host's `/device` write commands. */
internal fun jvmDeviceCommands(rig: JvmRig): Map<String, RigCommand> =
    inspectorLevers(rig) + integrationCommands(rig)

/** The desktop inspector's levers, over the operator faces. */
private fun inspectorLevers(rig: JvmRig): Map<String, RigCommand> = mapOf(
    "reset" to resetCommand(reset = { rig.mocks.devControls.operator.reset() }),
    "gallery/seed" to seedCommand { n, kind -> seedMocks(rig, n, kind) },
    "backend/offline" to rig.onOperator("backend-offline") { operator, params ->
        operator.offline = flag(params, "on")
        CommandResult.ok("""{"ok":true}""")
    },
    "jobs" to RigCommand { _, _ ->
        val queue = rig.mocks.uploadQueue.operator
        CommandResult.ok("""{"live":${jsonList(queue.liveJobKeys())},"created":${queue.created.size}}""")
    },
    "jobs/limit" to RigCommand { params, _ ->
        val n = params["n"]?.toIntOrNull()
        if (n == null || n < 0) {
            CommandResult.badRequest("n must be a non-negative integer, was '${params["n"]}'")
        } else {
            rig.mocks.uploadQueue.operator.jobLimit = n
            CommandResult.ok("""{"jobLimit":$n}""")
        }
    },
    // Without `key`, every live job — what a caller that just ran a cycle usually means.
    "jobs/complete" to RigCommand { params, _ ->
        val queue = rig.mocks.uploadQueue.operator
        val keys = params["key"]?.let(::listOf) ?: queue.liveJobKeys()
        keys.forEach { queue.completeJob(it) }
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
                rig.mocks.uploadQueue.operator.failJob(key, error)
                CommandResult.ok("""{"failed":${jsonString(key)},"error":${jsonString(error.toString())}}""")
            }
        }
    },
    "import/fail-next" to RigCommand { _, _ ->
        rig.mocks.library.operator.imports.failNextImport = true
        CommandResult.ok("""{"armed":true}""")
    },
    // The membership file made unreadable on the device's disk — a device before its first unlock after a boot.
    "membership/unreadable" to RigCommand { params, _ ->
        val on = flag(params, "on")
        rig.mocks.disk.operator.deny(FileArea.SHARED, CONFIG_FILE_NAME, on)
        CommandResult.ok("""{"membershipUnreadable":$on}""")
    },
    "permission" to RigCommand { params, _ ->
        val status = GalleryAccess.entries.firstOrNull { it.name.equals(params["status"], ignoreCase = true) }
        if (status == null) {
            CommandResult.badRequest(
                "status must be one of ${GalleryAccess.entries.joinToString("|")}, was '${params["status"]}'",
            )
        } else {
            rig.mocks.library.operator.access = status
            CommandResult.ok("""{"permission":"${status.name}"}""")
        }
    },
    // `wait=false` returns at once, so a caller can drive other triggers while an import it started is parked.
    "downloads/stage" to RigCommand { params, _ ->
        if (params["wait"]?.toBoolean() == false) {
            rig.scope.launch { stageAllDownloads(rig) }
            CommandResult.ok("""{"staged":true,"waited":false}""")
        } else {
            stageAllDownloads(rig)
            CommandResult.ok("""{"staged":true}""")
        }
    },
    // Reaches the composed core: the foreground's download reconcile, as the operator plays it (see the header).
    "downloads/reconcile" to RigCommand { _, _ ->
        val eventId = joinedEventId(rig)
        if (eventId == null) {
            CommandResult.badRequest("no membership to reconcile downloads for")
        } else {
            rig.app.core.downloadController.reconcile(eventId)
            CommandResult.ok("""{"reconciled":${jsonString(eventId)}}""")
        }
    },
    "album/place" to RigCommand { params, _ ->
        val album = params["album"]
        val asset = params["asset"]
        if (album == null || asset == null) {
            CommandResult.badRequest("album and asset are both required")
        } else {
            rig.mocks.library.operator.placeIn(album, asset)
            CommandResult.ok("""{"album":${jsonString(album)},"asset":${jsonString(asset)}}""")
        }
    },
    // A fellow member with complete photos, through the backend's public surface. `event` defaults to the joined
    // one; `assets` is a comma-separated list of asset ids.
    "foreign-device" to RigCommand { params, _ ->
        val device = params["device"]
        val assets = params["assets"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val event = params["event"] ?: joinedEventId(rig)
        // The capturing device's own file name for every asset — what an import names its photo after.
        val filename = params["filename"]
        if (device == null || assets.isEmpty()) {
            CommandResult.badRequest("device and a non-empty comma-separated assets are required")
        } else {
            val manifest = assets.map { if (filename != null) foreignAsset(it, filename) else foreignAsset(it) }
            val eventId = addForeignDevice(rig, device, manifest, event)
            CommandResult.ok("""{"device":${jsonString(device)},"eventId":${jsonString(eventId)}}""")
        }
    },
    // What the backend lists for a device — over its public surface, on either backend. `device` defaults to this one.
    "backend/objects" to RigCommand { params, _ ->
        val device = params["device"] ?: rig.mocks.ownDeviceId
        CommandResult.ok("""{"device":${jsonString(device)},"objects":${jsonList(rig.reach.objectsOf(device).sorted())}}""")
    },
    // Reaches the composed core: the foreground's status read, as the operator plays it (see the header).
    "status/refresh" to RigCommand { _, _ ->
        rig.app.core.refreshStatusSources()
        CommandResult.ok("""{"refreshed":true}""")
    },
)

/**
 * The operating system delivers every in-flight download ([outcome] each), then — reaching the composed core, as the
 * world did before this host — awaits the stagings the jobs launched and the import the tail runs for them, so the verb
 * is complete on return. Nothing happens while this launch has not brought its download session up.
 */
internal suspend fun stageAllDownloads(rig: JvmRig, outcome: TransferOutcome = DownloadSessionMock.HEALTHY) {
    val session = rig.mocks.downloads.operator
    if (!session.realized) return
    session.inFlight().forEach { session.finish(it.description, outcome) }
    rig.app.core.downloadJobs.awaitOutstandingStagings()
    rig.app.core.tail.runner.request(TailTrigger.DOWNLOAD_STAGED)
}

/**
 * The joined event: the one the screen shows, or — before a screen shows it — the one the membership file on the
 * device's disk names. `null` when neither does.
 */
internal fun joinedEventId(rig: JvmRig): String? =
    (rig.app.host.container.stateFlow.value.layer as? Layer.Joined)?.membership?.eventId
        ?: (ConfigService(rig.mocks.disk.port(), rig.mocks.clock.port()).read() as? ConfigRead.Joined)?.config?.eventId

/**
 * The gallery read — the photo library through the app's own candidate seam and policy, as on the app host. It reaches
 * the composed core (see the header); the census is the library mock's.
 */
internal fun jvmGalleryReader(rig: JvmRig): suspend (String?, Boolean, Boolean) -> String =
    { cutoff, resources, includesUpload ->
        val reader = GalleryReport(
            candidates = rig.app.core.candidates,
            grant = { rig.app.core.photoPermission.value.name },
            census = {
                val facts = rig.mocks.library.operator.current().map { it.facts }
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
        "device/gallery/wipe",
        "a mocked photo library is fresh for every host, so there is nothing to wipe; the wipe's answer is PhotoKit's " +
            "source, album and folder census, which a mocked library has no counterpart for",
    )
    put("device/uploaders", "the JVM root composes no OS-driven upload mechanism for a switch to choose between")
    put(
        "device/process-metrics",
        "process-metric reports are MetricKit's; the JVM runs no process the operating system measures",
    )
    RigVocabulary.appHostCommands.filter { it.startsWith("device/upload-") }.forEach {
        put(it, "the mocked upload-job queue is operated by the jobs verbs, not by an operating system to play")
    }
    put(
        RigVocabulary.CONTRACT,
        "host JVM runs no contract in-process: JVM contract bindings run under Gradle in the canonical check",
    )
}

/**
 * Seed [n] photos of [kind] into the library mock, dated as the app host dates them relative to its event window.
 *
 * `BULK` is dated long before any event (the walk-cost seed); `POLICY` alternates above and below the image floor
 * inside the default event window, so only the floor separates them. `NOISE` exists to put bytes on the wire, which
 * the mock does not carry, so it is refused rather than silently seeded as something else.
 */
@OptIn(ExperimentalUuidApi::class)
private fun seedMocks(rig: JvmRig, n: Int, kind: SeedKind): SeedOutcome {
    if (kind == SeedKind.NOISE) {
        throw SeedRefused("the mocked photos carry no bytes, so a seed for bytes on the wire would measure nothing")
    }
    val library = rig.mocks.library.operator
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

/** The app host's bulk-seed date: 2001-09-09, before any event can start. */
private const val BULK_DATE = "2001-09-09T01:46:40Z"

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
