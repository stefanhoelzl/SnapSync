package app.snapsync.rig

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.MockedSystem
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.AssetRef
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import app.snapsync.model.ManifestResource
import app.snapsync.model.PlannedResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.rig.gallery.GalleryReport
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.SeedOutcome
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.services.gallery.PermissionAwareCandidateSource
import app.snapsync.services.gallery.assetId
import app.snapsync.services.gallery.resourcesFrom
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /** Nothing but a lever may move the clock: no loop owns it. */
    data object ClockUnowned : Need
}

/** A lever: what it [needs], and what it does. */
internal class Lever(val needs: List<Need>, val command: RigCommand)

/** Why [name] cannot be honoured in this world, or `null` when every need is met. */
private fun MockWorld.refusalOf(name: String, lever: Lever): String? = refusalFor(lever.needs, name)

/** Why something needing [needs] — named [name] in the refusal — cannot be honoured here, or `null`. */
internal fun MockWorld.refusalFor(needs: List<Need>, name: String): String? = needs.firstNotNullOfOrNull { need ->
    when (need) {
        is Need.Mocked -> refusalFor(need.system)
        Need.Reach -> reachRefusal.takeIf { reach == null }
        Need.Operator -> operatorRefusal(name).takeIf { !isMocked(MockedSystem.BACKEND) }
        Need.Version -> (
            "this build's declared version is baked, not a cell the channel may change; it is one only where the " +
                "backend is the mock, which reads it"
            ).takeIf { version == null }
        Need.ClockUnowned -> clockOwner
    }
}

/** The levers this world honours, by `/device` name. */
fun MockWorld.honouredLevers(): Map<String, RigCommand> =
    levers().filter { (name, lever) -> refusalOf(name, lever) == null }.mapValues { it.value.command }

/** The levers this world refuses, by vocabulary entry (`device/<name>`), each with its reason. */
fun MockWorld.leverRefusals(): Map<String, String> =
    levers().mapNotNull { (name, lever) ->
        refusalOf(name, lever)?.let {
            "device/$name" to it
        }
    }.toMap() + serviceRefusals()

private fun needs(vararg needs: Need, command: RigCommand) = Lever(needs.toList(), command)

private fun mocked(system: MockedSystem, command: RigCommand) = Lever(listOf(Need.Mocked(system)), command)

/** The whole table, bound to this world. */
internal fun MockWorld.levers(): Map<String, Lever> {
    return deviceLevers() + deviceFacts()
}

private fun MockWorld.deviceLevers(): Map<String, Lever> = mapOf(
    // Every database made unreadable — a device before its first unlock after a boot, when the operating system keeps
    // every App-Group file closed: opens fail, and so does every statement on a database a process already holds.
    "databases/locked" to mocked(
        MockedSystem.DATABASES,
        RigCommand { params, _ ->
            val on = flag(params, "on")
            device.databases.operator.lock(on)
            CommandResult.ok("""{"databasesLocked":$on}""")
        },
    ),
    // The membership stays readable and writable but cannot be deleted — a leave whose clear fails, so the app stays
    // joined to the event it tried to leave. A trigger in the shared database refuses the delete.
    "membership/undeletable" to mocked(
        MockedSystem.DATABASES,
        RigCommand { params, _ ->
            val on = flag(params, "on")
            device.databases.operator.execute(
                LEDGER_DB_NAME,
                if (on) {
                    "CREATE TRIGGER IF NOT EXISTS membership_undeletable BEFORE DELETE ON membership " +
                        "BEGIN SELECT RAISE(ABORT, 'the membership cannot be deleted'); END"
                } else {
                    "DROP TRIGGER IF EXISTS membership_undeletable"
                },
            )
            CommandResult.ok("""{"membershipUndeletable":$on}""")
        },
    ),
    // Text a process's device log carries — what a diagnostic dump reads back. `process` is app|extension.
    "logs/append" to mocked(
        MockedSystem.FILES,
        RigCommand { params, body ->
            val (process, area, file) = when (params["process"] ?: "app") {
                "app" -> Triple(LogTailService.Process.APP, FileArea.PRIVATE, APP_LOG_FILE_NAME)
                "extension" -> Triple(LogTailService.Process.EXTENSION, FileArea.SHARED, EXTENSION_LOG_FILE_NAME)
                else -> return@RigCommand CommandResult.badRequest("process must be app|extension")
            }
            device.disk.operator.append(area, file, body ?: params["text"].orEmpty())
            CommandResult.ok(buildJsonObject { put("appended", process.name.lowercase()) }.toString())
        },
    ),
    "staging/seed-legacy-backlog" to needs(
        Need.Mocked(MockedSystem.DATABASES), Need.Mocked(MockedSystem.FILES),
        command = RigCommand { params, _ ->
            val who = DeviceId.parse(params["device"] ?: LEGACY_DEVICE)
                ?: return@RigCommand CommandResult.badRequest("device is not a device id")
            val asset = params["asset"] ?: "LEGACY"
            val paths = seedLegacyStagedBacklog(AssetRef(who, AssetId(asset)))
            CommandResult.ok(
                buildJsonObject {
                    putJsonArray("staged") { paths.sorted().forEach { add(JsonPrimitive(it)) } }
                }.toString(),
            )
        },
    ),
    // What the operating system recorded of the app, for every system it plays here.
    "os-record" to Lever(emptyList(), RigCommand { _, _ -> CommandResult.ok(os.record()) }),
)

private fun MockWorld.deviceFacts(): Map<String, Lever> = mapOf(
    // The files in the download staging directory — what a person with the container open would see.
    "staging" to mocked(
        MockedSystem.FILES,
        RigCommand { _, _ ->
            val staged = device.disk.operator.paths(FileArea.SHARED).filter { it.startsWith("$DOWNLOAD_STAGING_DIR/") }
            CommandResult.ok(
                buildJsonObject {
                    putJsonArray("files") { staged.sorted().forEach { add(JsonPrimitive(it)) } }
                }.toString(),
            )
        },
    ),
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
    val library = device.library
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
fun MockWorld.mockGalleryReader(): suspend (
    String?,
    Boolean,
    Boolean,
) -> String = { cutoff, resources, includesUpload ->
    val library = device.library
    val reader = GalleryReport(
        candidates = PermissionAwareCandidateSource(
            permission = GalleryAccessState(library.grant).grant,
            walk = GalleryCandidateSource(library.port()),
            // Read per request, as the rest of this reader is.
            selection = MutableStateFlow(library.selection.value?.let(::resourcesFrom)),
        ),
        grant = { library.grant.value.name },
        census = {
            val facts = library.contents.value.map { it.asset }
            CensusView(
                total = facts.size.toLong(),
                screenshots = facts.count { it.isScreenshot }.toLong(),
                screenRecordings = facts.count { it.isScreenRecording }.toLong(),
            )
        },
    )
    leverJson.encodeToString(GalleryView.serializer(), reader.read(cutoff, resources, includesUpload))
}

/** A fellow member with a primary resource per asset, captured on [filename]. */
internal fun foreignAsset(
    assetId: String,
    filename: String = "IMG.HEIC",
    contentType: String = "image/heic",
): DeviceManifestAsset {
    val key = uploadKey(AssetId(assetId), ResourceRole.PRIMARY, filename)
    return DeviceManifestAsset(
        assetId = AssetId(assetId),
        creationDate = LibraryAssets.DEFAULT_DATE,
        resources = listOf(ManifestResource(ResourceRole.PRIMARY, contentType, key, filename)),
    )
}

/**
 * **An install upgraded from a build that predates per-asset byte release**: a
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

/** A dump as it left the process, read back into its five sections (`model/diagnosticDumpEvent` wrote them). */
internal fun flag(params: Map<String, String>, name: String): Boolean = params[name]?.toBoolean() ?: true

private val leverJson = Json {
    encodeDefaults = true
    prettyPrint = true
}
private val LEGACY_BYTES = "staged".encodeToByteArray()

/** The app host's bulk-seed date: 2001-09-09, before any event can start. */
private const val BULK_DATE = "2001-09-09T01:46:40Z"

/** The `network` lever's accesses, by the name a caller passes. */
/** The device a legacy staged backlog is seeded for when the caller names none — a device id like every other. */
private const val LEGACY_DEVICE = "1e6ac700-0000-4000-8000-000000000001"
