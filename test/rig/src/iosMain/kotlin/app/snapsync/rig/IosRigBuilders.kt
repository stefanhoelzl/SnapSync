package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.UploadExtensionRegistry
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rig.gallery.GalleryReader
import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.WipeScope
import app.snapsync.rig.gallery.WipeWindow
import app.snapsync.rig.gallery.seedPhotos
import app.snapsync.rig.gallery.wipeGallery
import co.touchlab.kermit.Logger
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json

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
    // The commit carries nothing now: what is committed is what the reduction resolved from the form
    // (capability `sync-status-screen`). So the channel does what a member does — set the choices, then
    // confirm — rather than handing the container a pre-resolved answer it would have to trust.
    "confirmJoin" to RigUserCommand { params ->
        host().applyRangeChoices(params)
        host().onConfirmJoin()
    },
    "cancelJoin" to RigUserCommand { host().onCancelJoin() },
    // The membership change this channel could not previously express. Narrowing a scope — raising the
    // cutoff, or turning the share direction off — is what re-projects the device manifest (capability
    // `reconfigure-membership`), so without this the one behaviour that change turns on is undriveable
    // on a device.
    "reconfigure" to RigUserCommand { params ->
        // Open first: opening seeds the form from the persisted membership, exactly as the settings gear
        // does, so an unspecified field keeps the membership's current value rather than a default.
        host().surfaces.onOpenReconfigure()
        host().applyRangeChoices(params)
        host().onReconfigure()
    },
)

/**
 * Drive the range form from the channel's committed-shaped parameters.
 *
 * The channel speaks in canonical `…Z` instants because that is what a caller can write down; the form
 * speaks in presets plus a picked wall-clock value. The conversion lives HERE, in test-only code, rather
 * than as a rig-shaped intent on the container — production has no caller that needs it.
 */
private fun StatusContainerHost.applyRangeChoices(params: Map<String, String>) {
    params["direction"]?.let {
        val d = direction(it)
        form.onShareOn(d.includesUpload)
        form.onReceiveOn(d.includesDownload)
    }
    params["saveToAlbum"]?.let { form.onSaveToAlbum(it.toBoolean()) }
    params["cutoff"]?.let { form.onFromCustom(toLocalWallClock(it)) }
    params["until"]?.let { form.onUntilCustom(toLocalWallClock(it)) }
}

/** A canonical `…Z` instant as the device's wall clock — the form's own vocabulary. */
private fun toLocalWallClock(iso: String): LocalDateTime =
    Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())

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
    // ---- the range form (capability `photo-selection-policy`) ------------------------------------
    //
    // The channel drives the form through `confirmJoin`/`reconfigure`, which set the values a caller
    // names and then commit. The PRESET taps are the two it does not need: a preset is a shorthand for a
    // bound the caller can state outright, and `applyRangeChoices` states it — offering both would give
    // the channel two ways to say one thing, and they could disagree.
    "onFromPreset" to
        "a shorthand for a bound the channel already sets outright via the `cutoff` parameter; offering " +
        "both would let one caller say the same thing two ways, which could then disagree.",
    "onUntilPreset" to
        "the same, for the upper bound the `until` parameter sets outright.",
    // ---- what is drawn OVER the layer (capability `sync-status-screen`) ---------------------------
    //
    // Every one of these opens or dismisses a confirmation. None reaches a port, so driving them would
    // change what a screenshot shows and nothing else — and what the app DOES is what this channel is
    // for. The commit behind each confirmation IS wired: `leave`, `rename`, `reconfigure`.
    "onConfirmLeaveOpen" to
        "opens the leave confirmation and touches no port; the leave itself is wired as `/user/leave`.",
    "onConfirmLeaveDismiss" to
        "dismisses that confirmation, which the channel never opened.",
    "onRenameOpen" to
        "opens the rename sheet and touches no port; the rename itself is wired as `/user/rename`.",
    "onRenameDismiss" to
        "dismisses that sheet, which the channel never opened.",
    "onCancelReconfigure" to
        "discards the settings surface without writing; `/user/reconfigure` opens, sets and commits it in " +
        "one call, so there is no half-open surface for the channel to cancel.",
    "onReportBugOpen" to
        "opens the diagnostic sheet, whose SEND is already excluded below for the same reason — the dump " +
        "leaves the device, and a channel-triggered one would be indistinguishable from a real report.",
    "onReportBugDismiss" to
        "dismisses that sheet, which the channel never opens.",
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
    "onRenameEvent" to
        "changes a live membership's name; no scenario drives it yet, and an unexercised destructive " +
        "command is worse than an absent one.",
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
): Map<String, RigCommand> = uploadJobDeviceCommands() + mapOf(
    // The development pin on the upload mechanism — the channel's replacement for the deleted
    // `SNAPSYNC_FORCE_URLSESSION_UPLOAD` (capability `upload-lifecycle`). Reports the pin AND what the
    // app resolves with it, because a pin naming a mechanism this OS cannot run is clamped by the
    // resolver and a pin is ignored entirely without usable photo access — so the two can disagree, and
    // only one of them is what the app will actually do.
    "upload-mechanism" to uploadMechanismCommand(
        osSupportsOsDrivenUpload = { osSupportsOsDrivenUpload },
        permission = { photoAccess.permission.value },
    ),
    "reset" to RigCommand { _, _ ->
        core().resetDeviceState.reset()
        // The counts AFTER the reset, so "it cleared" is verifiable rather than asserted. An in-flight
        // upload cycle can still write rows behind this read — stated in `device-state-reset` rather than
        // prevented, and visible right here when it happens.
        core().ledgerCounts.refresh()
        val counts = core().ledgerCounts.counts.value
        CommandResult.ok("""{"reset":true,"ledgerCompleted":${counts.completed},"ledgerPending":${counts.pending}}""")
    },
    "gallery/seed" to RigCommand { params, _ ->
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
)

/** The gallery read, bound to the app's own permission-aware candidate seam rather than a second walk. */
fun galleryReader(core: () -> AppCore): suspend (String?, Boolean, Boolean) -> String =
    { cutoff, resources, includesUpload ->
    val reader = GalleryReader(
        candidates = core().candidates,
        grant = { core().photoPermission.value.name },
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

/**
 * Parse the join-surface arguments. Each is strict rather than defaulted: a cutoff that silently became
 * "now" would join at a scope the caller did not ask for, and on the upload direction that is the whole
 * camera roll. A malformed value raises, the request answers 500, and the caller sees which one.
 */
private fun localDateTime(raw: String?): LocalDateTime = LocalDateTime.parse(requireNotNull(raw) {
    "startsAt/endsAt are required, as ISO local date-times — there is no safe default for an event window"
})


private fun direction(raw: String?): Direction = requireNotNull(Direction.entries.firstOrNull {
    it.name.equals(raw, ignoreCase = true) || it.wire.equals(raw, ignoreCase = true)
}) { "direction must be one of ${Direction.entries.joinToString("|") { it.wire }}, was '$raw'" }

/** A tiny object renderer for the source census — the only map this file emits. */
private fun jsonMap(m: Map<String, Long>): String =
    m.entries.joinToString(prefix = "{", postfix = "}") { """"${it.key}":${it.value}""" }

private fun quoted(value: String?): String = value?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"
