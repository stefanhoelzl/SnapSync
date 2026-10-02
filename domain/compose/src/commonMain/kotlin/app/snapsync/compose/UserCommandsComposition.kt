package app.snapsync.compose

import app.snapsync.feature.membership.toCommit
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.model.Handoff
import app.snapsync.model.JoinCommit
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The user-tap command bundle and the user-query bundle (`docs/architecture.md`, "Commands cross one door" and
// "Queries cross a lane-gated door"): built and decorated only here in `compose/`, injected into
// `StatusContainerHost` by constructor — so presentation never references a feature command directly.
//
// Every field is built through one of the two lane decorators below, which `CommandLaneTest` holds this file to.
// Top-level builders rather than `AppCore` bodies because `AppCore` is measured (see `shareSetLoadFor`).

/**
 * Wrap a user tap as a **platform entry point** (spec `privacy-security`; `docs/architecture.md`, "Absence is never
 * silent"). `compose/` is where this must live: it is where the door law says command instances are decorated, and
 * the only place that *can* — `:domain:presentation` may not reference `ports/`, so it cannot reach an `EntryContext`.
 *
 * The `tap.` namespace is load-bearing, not cosmetic. Without it a device log cannot say whether work was started by
 * the platform or by the person holding the phone: on Bugsink `SNAPSYNC-3`, proving that a leave was a manual tap
 * rather than the switch path's backend notify took reading two source files, because both produce the same
 * downstream lines.
 */
private val tapLog = Logger.withTag("userTap")

/**
 * A command the caller waits on, run on the composition lane ([AppCore.coreLane]). Used where the screen needs the
 * outcome in hand — the join gate's `commitJoin` returns whether it joined — and for every query.
 */
internal suspend fun <T> AppCore.awaitingOnCoreLane(
    name: String,
    params: String = "",
    result: (T) -> String = { "" },
    block: suspend () -> T,
): T = withContext(coreLane) {
    tapLog.invocation(process.entryContext, name, params, result = result) { block() }
}

/**
 * A fire-and-forget command, run on the composition lane ([AppCore.coreLane]). The tap returns at once and the
 * outcome rides a read-model. That includes a hand-off to the platform's UI — a sheet, a prompt, the Settings page:
 * the main thread is the ADAPTER's to reach, each platform-UI adapter hopping there itself (`docs/architecture.md`,
 * "Dispatcher lanes are fixed by the composition"), so this graph names no main lane at all. A hand-off's [Handoff]
 * is the one return value, and it is only rendered onto the tap's line by [result] — nothing acts on it.
 *
 * The `invocation` wrap sits INSIDE the launch deliberately: wrapping the launcher instead would time the hand-off
 * rather than the work, which is how `← tap.create (1ms)` came to be logged against a multi-second backend mint.
 */
private fun <T> AppCore.detachedOnCoreLane(
    name: String,
    params: String = "",
    result: (T) -> String = { "" },
    block: suspend () -> T,
) {
    scope.launch(coreLane) { tapLog.invocation(process.entryContext, name, params, result = result) { block() } }
}

/** The user-tap command bundle, each command's lane declared where it is built. */
internal fun AppCore.userCommandsFor(): UserCommands = UserCommands(
    // Leave: cancel in-flight downloads and drop non-terminal rows (imported photos stay;
    // suppression rows are permanent), then run the leave use-case (disable producer → notify
    // the backend it is leaving → clear config/producer). Imported foreign photos are never
    // touched.
    leave = {
        awaitingOnCoreLane<Unit>("tap.leave") {
            downloadController.onLeaveOrSwitch()
            leaveEvent.leave()
        }
    },
    // Create: mint via the backend; the use-case routes the minted event into the SAME join
    // gate a scanned QR takes (fire-and-forget; outcomes ride `creationStatus`).
    create = { name, startsAt, endsAt ->
        detachedOnCoreLane("tap.create") {
            eventCreator.create(name, startsAt.at.iso, endsAt.at.iso)
        }
    },
    // The join gate's commit (capability `join-event`): join (no body, no manifest) then
    // provision. The outcome is NAMED rather than reduced to a Boolean, because capacity and a
    // transient failure need different screens: one offers a Retry that may work, the other must
    // not offer one at all. The same-event no-op is a success.
    commitJoin = { choice ->
        awaitingOnCoreLane(
            "tap.commitJoin",
            params = "eventId=${choice.eventId}",
            result = { commit: JoinCommit -> "commit=$commit" },
        ) { joinEvent.join(choice).toCommit() }
    },
    // Share is pure platform (a system sheet over the top view controller). Decorated like the
    // rest: presenting the sheet is still a tap, and an unattributed line is the thing this
    // instrumentation exists to eliminate.
    share = { url ->
        detachedOnCoreLane("tap.share", result = { h: Handoff -> "$h" }) {
            tapLog.recordingRefusal("tap.share", ports.systemUi.share(url))
        }
    },
    // Leaving the app for the store page (capability `app-update-required`) — UI lane and
    // instrumented, like every other platform-surface command.
    openLink = { url ->
        detachedOnCoreLane("tap.openLink", result = { h: Handoff -> "$h" }) {
            tapLog.recordingRefusal("tap.openLink", ports.systemUi.openUrl(url))
        }
    },
    // The permission user-taps (capability `photo-access`), bound to the gallery here so presentation
    // never names it. Each tap is fire-and-forget: the screen follows the permission read-model StateFlow,
    // never the gallery's answer.
    requestAccess = { detachedOnCoreLane("tap.requestAccess") { ports.gallery.requestAccess() } },
    openSettings = { detachedOnCoreLane("tap.openSettings") { ports.systemUi.openSettings() } },
    // The picker presentation is platform surface; the selection outcome arrives only via
    // the selection-change seam.
    choosePhotos = { detachedOnCoreLane("tap.choosePhotos") { ports.gallery.widenSelection() } },
    // In-place membership reconfigure (capability `manage-membership`): edit direction/
    // cutoff/album without leaving. Distinct from `openSettings` (the iOS system settings page).
    reconfigure = { eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum ->
        awaitingOnCoreLane(
            "tap.reconfigure",
            params = "eventId=$eventId",
            result = { outcome: ReconfigureOutcome -> "$outcome" },
        ) {
            reconfigureEvent.reconfigure(eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum)
        }
    },
    // Rename the joined event (capability `manage-membership`): unlike `reconfigure`, which edits only
    // this device's settings, this rewrites the SHARED event — every member picks the new name up
    // on their next foreground refresh. Fire-and-forget; the outcome rides `renameStatus`.
    rename = { eventId, name ->
        detachedOnCoreLane("tap.rename", params = "eventId=$eventId") {
            renameEvent.rename(eventId, name)
        }
    },
    // Clear the rename latch once the screen has consumed a terminal status. Instrumented like
    // the taps even though it is a screen-fired acknowledgement rather than a tap: it mutates
    // the rename lifecycle, and an unattributed state change is the thing this trail exists to
    // eliminate.
    resetRename = { awaitingOnCoreLane<Unit>("tap.resetRename") { renameEvent.reset() } },
    // The hidden diagnostic dump (capability `privacy-security`), fired once the operator has
    // written what went wrong: sent where the build reports, kept on the device where it does not —
    // the process's crash reporting decides, and the answer is logged either way.
    sendDiagnostics = { note, screen ->
        // Core lane and awaited: the dump reads both device logs (~700 KB) before it sends or saves,
        // which is exactly the blocking work the main lane must never see, and the sheet waits on it.
        awaitingOnCoreLane<Unit>("tap.sendDiagnostics", params = "screen=$screen") {
            val result = process.crash.sendDump(collectDiagnosticDump.collect(note, screen))
            services.log.i { "diagnostic dump: $result" }
        }
    },
)

/**
 * The user-query bundle: the reads the status container invokes, each awaited on the composition lane — so a store,
 * photo-library or network read never runs on the thread that asked.
 */
internal fun AppCore.userQueriesFor(): UserQueries = UserQueries(
    loadJoinDetails = { id ->
        awaitingOnCoreLane("query.loadJoinDetails", "eventId=$id") { joinEvent.loadDetails(id).toJoinLoad() }
    },
    shareableCount = { cutoff, until ->
        awaitingOnCoreLane("query.shareableCount") { loadShareableCount(cutoff, until) }
    },
)

/**
 * Records a hand-off to the platform that did not happen. Nothing acts on a [Handoff], but a refusal is logged at
 * `Error`, because the user then tapped and nothing happened — on the update-required screen, to the only remedy the
 * screen offers (`docs/architecture.md`, "Absence is never silent"). `Error` is what reaches the operator from a
 * production build (capability `privacy-security`).
 */
private fun Logger.recordingRefusal(name: String, handoff: Handoff): Handoff = handoff.also {
    if (it is Handoff.Refused) e { "$name: nothing was handed off — ${it.reason}" }
}
