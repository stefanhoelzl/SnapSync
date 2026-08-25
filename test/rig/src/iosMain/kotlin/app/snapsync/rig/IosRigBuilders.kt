package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rig.gallery.GalleryReader
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.WipeScope
import app.snapsync.rig.gallery.WipeWindow
import app.snapsync.rig.gallery.plantFallbackIdentity
import app.snapsync.rig.gallery.seedPhotos
import app.snapsync.rig.gallery.wipeGallery
import co.touchlab.kermit.Logger
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import platform.Photos.PHPhotoLibrary

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
 * The `/user` surface: real members of [StatusContainerHost], invoked exactly as a tap invokes them.
 *
 * Four are wired because four are what an operator needs to reach an event end-to-end without a finger:
 * create it, confirm the join it opens, abandon that join, and leave afterwards. The rest are excluded with
 * the reason that makes each omission safe, and the coverage guard holds this map against the host's own
 * public surface — so a new command on the host fails the build until someone says which of the two it is.
 */
fun userCommands(host: () -> StatusContainerHost): Map<String, RigUserCommand> = mapOf(
    "leave" to RigUserCommand { host().onLeaveEvent() },
    "create" to RigUserCommand { params ->
        host().onCreateEvent(
            name = params["name"].orEmpty(),
            startsAt = localDateTime(params["startsAt"]),
            endsAt = localDateTime(params["endsAt"]),
        )
    },
    "confirmJoin" to RigUserCommand { params ->
        host().onConfirmJoin(
            cutoff = captureCutoff(params["cutoff"]),
            until = captureCeiling(params["until"]),
            direction = direction(params["direction"]),
            saveToAlbum = params["saveToAlbum"].toBoolean(),
        )
    },
    "cancelJoin" to RigUserCommand { host().onCancelJoin() },
)

/**
 * `/user` members deliberately NOT wired, each with the consequence that makes the omission safe.
 *
 * The guard asserts wired + excluded equals the host's public command surface, exactly — so this list is
 * accounted-for, never curated.
 */
fun excludedUserCommands(): Map<String, String> = mapOf(
    "onRequestPermission" to
        "raises the system photo-access alert, which needs a tap on the device and cannot be answered " +
        "over the channel; grant access once by hand and every later run inherits it.",
    "onOpenSettings" to
        "sends the user to the Settings app, leaving the app under test backgrounded — the channel would " +
        "be driving a process that is no longer foreground, which is not the state a caller asked for.",
    "onChoosePhotos" to
        "presents the limited-library picker, a modal only a finger can answer. The selection it produces " +
        "arrives through the selection-change seam, which /device/state reports.",
    "onShareInvite" to
        "presents a UIActivityViewController and observes no result, so there is nothing to report and a " +
        "modal is left on screen. The invite URL itself is already in /device/state.",
    "onSendDiagnostics" to
        "sends a diagnostic dump to the operator's Bugsink instance. Driving it from a rig would fill the " +
        "issue with runs nobody triaged; /device/logs reaches the same content without the round trip.",
    "onEventCreated" to
        "an internal continuation of create, not a surface of its own — the create command already reaches " +
        "it, and calling it directly would open a join gate for an event nothing minted.",
    "onOpenUrl" to
        "the join-link entry, reachable with full fidelity as POST /os/onSceneContinueActivity, which " +
        "additionally exercises the real NSUserActivity decode and activity-type filter.",
    "onReconfigure" to
        "changes a live membership's settings; wired nowhere yet because no scenario needs it driven, and " +
        "an unexercised destructive command is worse than an absent one.",
    "onRenameEvent" to "same as onReconfigure: no scenario drives it yet.",
    "onRenameStatusConsumed" to
        "a latch reset the screen fires after acting on a terminal value — driving it would desynchronise " +
        "the container from the UI that owns the latch.",
    "onRetryLoad" to "a retry of a failed details load; reachable by re-issuing the command that failed.",
    "onRetryJoin" to "a retry of a failed commit; reachable by re-issuing confirmJoin.",
    "onAcknowledgeAccess" to
        "dismisses the access explainer, a purely presentational transition with no effect outside the " +
        "container.",
    "onConfirmSwitch" to
        "confirms leaving one event for another. Reachable as leave + confirmJoin, which is the same two " +
        "steps with the state visible between them.",
    "onCancelSwitch" to "the presentational half of the switch dialog; see onConfirmSwitch.",
)

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
): Map<String, RigCommand> = mapOf(
    // The development pin on the upload mechanism — the channel's replacement for the deleted
    // `SNAPSYNC_FORCE_URLSESSION_UPLOAD` (capability `upload-lifecycle`). Reports the pin AND what the
    // app resolves with it, because a pin naming a mechanism this OS cannot run is clamped by the
    // resolver and a pin is ignored entirely without usable photo access — so the two can disagree, and
    // only one of them is what the app will actually do.
    "upload-mechanism" to uploadMechanismCommand(
        osSupportsOsDrivenUpload = { osSupportsOsDrivenUpload },
        permission = { photoAccess.permission.value },
    ),
    "reset" to RigCommand {
        core().resetDeviceState.reset()
        // The counts AFTER the reset, so "it cleared" is verifiable rather than asserted. An in-flight
        // upload cycle can still write rows behind this read — stated in `device-state-reset` rather than
        // prevented, and visible right here when it happens.
        core().ledgerCounts.refresh()
        val counts = core().ledgerCounts.counts.value
        CommandResult.ok("""{"reset":true,"ledgerCompleted":${counts.completed},"ledgerPending":${counts.pending}}""")
    },
    "gallery/seed" to RigCommand { params ->
        val n = params["n"]?.toIntOrNull()
        val kind = SeedKind.entries.firstOrNull { it.name.equals(params["kind"], ignoreCase = true) }
        when {
            n == null || n <= 0 -> CommandResult.badRequest("n must be a positive integer, was '${params["n"]}'")
            kind == null -> CommandResult.badRequest(
                "kind must be one of ${SeedKind.entries.joinToString("|") { it.name.lowercase() }}, " +
                    "was '${params["kind"]}'",
            )
            else -> {
                val outcome = seedPhotos(log, n, kind)
                CommandResult.ok(
                    """{"requested":${outcome.requested},"created":${outcome.created},""" +
                        """"kind":"${outcome.kind.name.lowercase()}","failedAtChunk":${outcome.failedAtChunk}}""",
                )
            }
        }
    },
    "gallery/wipe" to RigCommand { params ->
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
    "identity" to RigCommand { params ->
        val id = params["id"]
        when {
            id.isNullOrBlank() -> CommandResult.badRequest("id is required")
            else -> {
                val outcome = plantFallbackIdentity(id)
                if (outcome.path == null) {
                    CommandResult.badRequest("could not plant the identity — ${outcome.reason}")
                } else {
                    // Deliberately does not claim the app WILL use it: a secure store that resolves an
                    // identity ignores this file, and saying otherwise here would be a promise this
                    // command cannot keep.
                    CommandResult.ok(
                        """{"planted":true,"path":"${outcome.path}","note":"fills an absence only; a """ +
                            """resolvable secure store still wins"}""",
                    )
                }
            }
        }
    },
)

/** The gallery read, bound to the app's own permission-aware candidate seam rather than a second walk. */
fun galleryReader(core: () -> AppCore): suspend (String?, Boolean) -> String = { cutoff, resources ->
    val reader = GalleryReader(
        candidates = core().candidates,
        grant = { core().photoPermission.value.name },
    )
    json.encodeToString(GalleryView.serializer(), reader.read(cutoff, resources))
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
fun osExtensionEnabled(osSupportsOsDrivenUpload: Boolean): () -> Boolean? = {
    // A branch, not a `takeIf`: the selector must not be *evaluated* where it does not exist.
    if (osSupportsOsDrivenUpload) PHPhotoLibrary.sharedPhotoLibrary().isUploadJobExtensionEnabled() else null
}

/**
 * Parse the join-surface arguments. Each is strict rather than defaulted: a cutoff that silently became
 * "now" would join at a scope the caller did not ask for, and on the upload direction that is the whole
 * camera roll. A malformed value raises, the request answers 500, and the caller sees which one.
 */
private fun localDateTime(raw: String?): LocalDateTime = LocalDateTime.parse(requireNotNull(raw) {
    "startsAt/endsAt are required, as ISO local date-times — there is no safe default for an event window"
})

private fun captureCutoff(raw: String?): CaptureCutoff = CaptureCutoff(CaptureDate(requireNotNull(raw) {
    "cutoff is required — defaulting it would share photos the caller did not choose to share"
}))

private fun captureCeiling(raw: String?): CaptureCeiling = CaptureCeiling(CaptureDate(requireNotNull(raw) {
    "until is required — it is the capture-date ceiling and bounds what may be uploaded"
}))

private fun direction(raw: String?): Direction = requireNotNull(Direction.entries.firstOrNull {
    it.name.equals(raw, ignoreCase = true) || it.wire.equals(raw, ignoreCase = true)
}) { "direction must be one of ${Direction.entries.joinToString("|") { it.wire }}, was '$raw'" }

/** A tiny object renderer for the source census — the only map this file emits. */
private fun jsonMap(m: Map<String, Long>): String =
    m.entries.joinToString(prefix = "{", postfix = "}") { """"${it.key}":${it.value}""" }

private fun quoted(value: String?): String = value?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"
