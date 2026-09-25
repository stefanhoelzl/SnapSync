package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.model.ProcessMetricReport
import app.snapsync.model.processMetricEmissions
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.UploadExtensionRegistry
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import app.snapsync.model.Layer
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rig.gallery.GalleryReader
import app.snapsync.rig.gallery.photoKitCensus
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.WipeScope
import app.snapsync.rig.gallery.WipeWindow
import app.snapsync.rig.gallery.seedPhotos
import app.snapsync.rig.gallery.wipeGallery
import co.touchlab.kermit.Logger
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything the hook would otherwise have to decide.
 *
 * The hook file lives in this module's tree but is compiled INTO `:app:ios`, so it is scanned by the shell
 * gate and may hold **no** decisions — not a default, not a cast, not an elvis. Every `when`, every parse,
 * every fallback that the commands need therefore lives here, one module across the seam, where it is
 * ordinary ungated code. That split is the same one `rigPort` already made for a single env-var parse; the
 * command maps just make it carry more.
 */

private val json = Json { encodeDefaults = true; prettyPrint = true }
private val log = Logger.withTag("rig")

/**
 * The `/device` verbs. Blocking, each answering with what it did.
 *
 * Hand-listed, because there is no population to derive one from: nothing in production seeds a photo
 * library, empties one, or voids durable sync state, so this set exists only because a test rig exists.
 */
fun deviceCommands(
    core: () -> AppCore,
    photoAccess: PhotoLibraryPermission,
    osSupportsOsDrivenUpload: Boolean,
    /** The app's OWN process-metric handler, so a synthetic report drives the path the OS drives. */
    handleReport: (ProcessMetricReport) -> Unit,
): Map<String, RigCommand> = uploadJobDeviceCommands() + mapOf(
    // The development switch per uploader (capability `background-upload`). Reports the switch AND the
    // registration fact it produces, because the extension is never registrable below 26.1 or without a full
    // grant, whatever the switch says.
    "uploaders" to uploadersCommand(
        osSupportsOsDrivenUpload = { osSupportsOsDrivenUpload },
        permission = { photoAccess.permission.value },
        reconcile = { core().uploadTransitions.onOverrideChanged() },
    ),
    "reset" to resetCommand(core),
    "gallery/seed" to seedCommand { n, kind -> seedPhotos(log, n, kind) },
    "gallery/wipe" to RigCommand { params, _ ->
        // A VALUE, not presence, and the only command here that refuses on one — because a wipe cannot be
        // undone, so a stale or mistyped scope must refuse rather than delete something. `limit`/`offset`
        // are held to the same standard for the same reason: a mistyped `limit=al` must NOT fall back to
        // "no window" and delete the whole library, which is what a plain `toLongOrNull()` would do.
        val scope = WipeScope.parse(params["scope"])
        val limitRaw = params["limit"]
        val offsetRaw = params["offset"]
        val limit = limitRaw?.toLongOrNull()?.takeIf { it >= 0L }
        val offset = offsetRaw?.toLongOrNull()?.takeIf { it >= 0L }
        when {
            scope == null -> CommandResult.badRequest(
                "scope must be one of ${WipeScope.entries.joinToString("|") { it.name.lowercase() }}, " +
                    "was '${params["scope"]}' — refusing rather than guessing, because this cannot be undone",
            )
            limitRaw != null && limit == null ->
                CommandResult.badRequest("limit must be a non-negative integer, was '$limitRaw'")
            offsetRaw != null && offset == null ->
                CommandResult.badRequest("offset must be a non-negative integer, was '$offsetRaw'")
            else -> {
                val window =
                    if (limit == null && offset == null) null else WipeWindow(offset ?: 0L, limit)
                val o = wipeGallery(log, scope, photoAccess, photoAccess, window = window)
                val windowJson =
                    o.window?.let { """{"offset":${it.offset},"limit":${it.limit}}""" } ?: "null"
                CommandResult.ok(
                    """{"scope":"${o.scope.name.lowercase()}","grant":"${o.grant}",""" +
                        """"matched":{"assets":${o.matchedAssets},"albums":${o.matchedAlbums},""" +
                        """"folders":${o.matchedFolders}},"deletable":${o.deletable},""" +
                        """"bySource":${jsonMap(o.bySource)},"selected":${o.selected},""" +
                        """"window":$windowJson,""" +
                        """"committed":${o.committed},"errorCode":${o.errorCode},""" +
                        """"errorDescription":${quoted(o.errorDescription)}}""",
                )
            }
        }
    },
    // Drive a synthetic process-metric report through the app's OWN handler (capability
    // `privacy-security`). Real reports arrive on the OS's cadence — roughly daily, and only after a
    // period has closed — so without this the only way to exercise the three channels is to wait a
    // day. The report is an open key/value bag by design, so a synthetic one needs no MetricKit types
    // and this route stays honest: it feeds the same rule and the same channels the OS feeds.
    "process-metrics" to RigCommand { _, body ->
        val fields = parseFields(body)
        when (fields) {
            null -> CommandResult.badRequest(
                "body must be a JSON object of string keys to scalar values, e.g. " +
                    """{"applicationExitMetrics.backgroundExitData.cumulativeAppWatchdogExitCount":"1"}""",
            )
            else -> {
                val report = ProcessMetricReport(fields)
                handleReport(report)
                val reasons = processMetricEmissions(report).flatMap { it.reasons }
                CommandResult.ok(
                    """{"fields":${fields.size},"crossed":${reasons.isNotEmpty()},""" +
                        """"reasons":${jsonArray(reasons)}}""",
                )
            }
        }
    },
)

/**
 * A JSON object of scalars as the flat field map a report carries, or `null` when it is not one.
 *
 * Refusing rather than guessing: a mistyped body that silently became an empty report would exercise
 * the channels with nothing in them and look like a pass.
 */
private fun parseFields(body: String?): Map<String, String>? {
    val text = body?.takeIf { it.isNotBlank() } ?: return null
    val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    return root.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: return null }
}

private fun jsonArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

/** The gallery read, bound to the app's own permission-aware candidate seam rather than a second walk. */
fun galleryReader(core: () -> AppCore): suspend (String?, Boolean, Boolean) -> String =
    { cutoff, resources, includesUpload ->
    val reader = GalleryReader(
        candidates = core().candidates,
        grant = { core().photoPermission.value.name },
        census = ::photoKitCensus,
    )
    json.encodeToString(GalleryView.serializer(), reader.read(cutoff, resources, includesUpload))
}

/**
 * The OS's view of the extension registration — `null` anywhere it cannot be asked.
 *
 * `isUploadJobExtensionEnabled` is a **26.1 selector** and this app deploys to min iOS 18, so calling it
 * unconditionally traps as an unrecognized selector. [osSupportsOsDrivenUpload] is the capability check
 * that gates the selector's existence — asked directly now, rather than through the resolved tier, since
 * which mechanism *runs* is a runtime fact and this question is about what the OS *has*.
 *
 * It is also grant-dependent in a way the name does not admit: measured on device (SE2, iOS 26.6), the
 * read returns `false` for a live configuration record whenever the app does not hold photo access, so a
 * `false` here means "no record **or** not allowed to look". Read it beside the reported permission.
 */
fun osExtensionEnabled(registry: () -> UploadExtensionRegistry?): () -> Boolean? = {
    // Read through the PORT, never through PhotoKit directly. The adapter behind it is the repo's sole
    // caller of `isUploadJobExtensionEnabled`, and on a target whose host cannot hold a record it is the
    // substitute — so this answers what the app itself would read rather than a second opinion.
    //
    // A null registry is the OS having no such notion at all: below 26.1 the selector does not exist, so
    // the app composes no registry and `notApplicable` is the honest answer.
    registry()?.isEnabled()
}

/** A tiny object renderer for the source census — the only map this file emits. */
private fun jsonMap(m: Map<String, Long>): String =
    m.entries.joinToString(prefix = "{", postfix = "}") { """"${it.key}":${it.value}""" }

private fun quoted(value: String?): String = value?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"

/**
 * The precondition a contract run that rewrites the extension registration needs: **no membership**
 * (`docs/architecture.md`). Re-registering wipes every in-flight upload job, and an automatic leave would
 * destroy a real membership on a shared phone, so the run is refused while the screen shows one, naming the
 * reset the operator runs deliberately. `null` means proceed.
 */
fun noMembershipRefusal(host: () -> StatusContainerHost): () -> String? = {
    (host().container.stateFlow.value as? Layer.Joined)?.let { joined ->
        "this device is a member of event ${joined.membership.eventId}, and the run re-registers the extension, " +
            "which wipes its in-flight upload jobs. Reset deliberately first (POST /device/reset), then re-run."
    }
}

