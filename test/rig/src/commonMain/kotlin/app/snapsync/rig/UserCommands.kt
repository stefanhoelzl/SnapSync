package app.snapsync.rig

import app.snapsync.model.Direction
import app.snapsync.presentation.StatusContainerHost
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// The `/user` surface, shared by both hosts: the same real members of `StatusContainerHost`, invoked exactly
// as a tap invokes them, whichever application is behind the channel. Moved here verbatim from the iOS
// builders — none of it names a platform API, and a host with its own table would be a second way to drive.

/**
 * The `/user` surface: real members of [StatusContainerHost], invoked exactly as a tap invokes them.
 *
 * Four are wired because four are what an operator needs to reach an event end-to-end without a finger:
 * create it, confirm the join it opens, abandon that join, and leave afterwards. The rest are excluded with
 * the reason that makes each omission safe. A guard once held this map against the host's own public surface,
 * so a new command failed the build until someone said which of the two it was; it was retired in `74302d2b`,
 * and a new command is now classified by review.
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
    "onOpenAppStore" to
        "leaves the app for the App Store, backgrounding the process under test — the same reason " +
        "onOpenSettings is excluded, and worse here: the channel could not drive what it switched to. " +
        "The state that offers it IS reachable over the channel — /device/state reports the " +
        "update-required screen and the URL it carries — so what is untestable here is only the " +
        "hand-off itself.",
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
        "presents a UIActivityViewController and leaves the modal on screen for a finger to dismiss. " +
        "The presentation itself is SharePresenterContract, run live by POST /contract/SharePresenter; the " +
        "invite URL is already in /device/state.",
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
