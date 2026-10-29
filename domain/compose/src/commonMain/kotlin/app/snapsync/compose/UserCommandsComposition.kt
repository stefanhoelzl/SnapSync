package app.snapsync.compose

import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.entrypoints.JoinRequest
import app.snapsync.entrypoints.KeyRestoreRequest
import app.snapsync.entrypoints.RenameRequest
import app.snapsync.entrypoints.SettingsRequest
import app.snapsync.entrypoints.SwitchRequest
import app.snapsync.format.TimestampText
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Trigger
import app.snapsync.model.Handoff
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportOutcome
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.outcome
import app.snapsync.model.recordingRefusal
import app.snapsync.query.RequestStatusSink
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.JoinDetailsQuery
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

// The user-tap command bundle and the user-query bundle (`docs/architecture.md`, "Commands cross one door" and
// "Queries cross a lane-gated door"): built and decorated only here in `compose/`, injected into
// `StatusContainerHost` by constructor — so presentation never references a feature command directly.
//
// Every member is built through one of the two lane decorators below, which `CommandLaneTest` holds this file to.
// Top-level builders rather than `AppCore` bodies because `AppCore` is measured (see `shareSetLoadFor`).

/**
 * Wrap a user tap as a **platform entry point** (`docs/architecture.md`, "Absence is never silent"). `compose/` is
 * where this must live: it is where the door law says command instances are decorated, and
 * the only place that *can* — `:domain:presentation` sees no runtime, so it cannot label a run with its trigger.
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
): T = withContext(scope.coroutineContext.minusKey(Job) + Trigger.of(name)) {
    val answer = block()
    tapLog.i { "$name($params) = ${result(answer)}" }
    answer
}

/**
 * A fire-and-forget command, run on the composition lane ([AppCore.coreLane]). The tap returns at once and the
 * outcome rides a read-model, while the work runs as the tap's — its `done` waits for it. That includes a hand-off to
 * the platform's UI — a sheet, a prompt, the Settings page: the main thread is the ADAPTER's to reach, each
 * platform-UI adapter hopping there itself (`docs/architecture.md`, "Dispatcher lanes are fixed by the composition"),
 * so this graph names no main lane at all. A hand-off's [Handoff] is the one return value, and it is only rendered onto
 * the tap's line by [result] — nothing acts on it.
 *
 * The tap's line is written INSIDE the launch deliberately: written by the launcher instead it would report the
 * hand-off rather than the work, which is how `← tap.create (1ms)` came to be logged against a multi-second mint.
 */
private suspend fun <T> AppCore.detachedOnCoreLane(
    name: String,
    params: String = "",
    result: (T) -> String = { "" },
    block: suspend () -> T,
) {
    // Alongside the tap that ran it: a tap's `done` waits for its command, which holds no background time of its own.
    runtime.alongside(name) {
        val answer = block()
        tapLog.i { "$name($params) = ${result(answer)}" }
    }
}

/**
 * A tap handed to the entry point that runs it: the third lane-declaring form beside [awaitingOnCoreLane] and
 * [detachedOnCoreLane] — the entry point's runtime runs its work on the composition lane, and nothing but the hand-off
 * runs on the tapping thread. Returns at once.
 */
private fun <A> handedOff(name: String, params: String, entry: Background<A>, arg: A) {
    tapLog.i { "$name($params)" }
    entry.handle(arg, Completion.None)
}

/**
 * A member's request, [handedOff]: [id] marked as running on its status first, and [entry]'s work answers [id] on that
 * status when it ends.
 */
private fun <A> requested(
    name: String,
    params: String,
    id: RequestId,
    status: RequestStatusSink<*>,
    entry: Background<A>,
    arg: (RequestId) -> A,
): RequestId {
    status.begun(id)
    handedOff(name, "$params, request ${id.value}", entry, arg(id))
    return id
}

/** The user-tap command bundle, each command's lane declared where it is built. */
@OptIn(ExperimentalAtomicApi::class)
internal fun AppCore.userCommandsFor(): UserCommands = object : UserCommands {
    /** Each request's id: unique for the process, which is as long as a status lives. */
    private val ids = AtomicLong(0)

    private fun nextId() = RequestId(ids.incrementAndFetch())

    // Leave: the membership ends, then everything it held is stopped (`OnLeaveConfirmed`). Its outcome is the
    // membership turning "not joined"; one that stays joined is a leave that could not be written.
    override fun leave() = handedOff("tap.leave", "", work.leaveConfirmed, Unit)

    // Create: mint via the backend; the use-case routes the minted event into the SAME join
    // gate a scanned QR takes (fire-and-forget; outcomes ride `creationStatus`).
    override suspend fun create(
        name: String,
        startsAt: EventStart,
        endsAt: EventEnd,
        openUntil: OpenUntil?,
    ) = detachedOnCoreLane("tap.create") {
        eventCreator.create(name, TimestampText.format(startsAt.at), TimestampText.format(endsAt.at), openUntil)
    }

    // The join gate's commit: enrolled, then entered, then what follows a join (`OnJoinConfirmed`). Answered on the
    // join status under the returned id.
    override fun commitJoin(choice: JoinChoice): RequestId = requested(
        "tap.commitJoin",
        "eventId=${choice.event.id}",
        nextId(),
        membershipStatuses.join,
        work.joinConfirmed,
    ) { id -> JoinRequest(id, choice) }

    // Share is pure platform (a system sheet over the top view controller). Decorated like the
    // rest: presenting the sheet is still a tap, and an unattributed line is the thing this
    // instrumentation exists to eliminate.
    override suspend fun share(url: String, title: String) =
        detachedOnCoreLane("tap.share", result = { h: Handoff -> "$h" }) {
            ports.systemUi.share(url, title).recordingRefusal(tapLog, "tap.share")
        }

    // Leaving the app for the store page — UI lane and
    // instrumented, like every other platform-surface command.
    override suspend fun openLink(url: String) =
        detachedOnCoreLane("tap.openLink", result = { h: Handoff -> "$h" }) {
            ports.systemUi.openUrl(url).recordingRefusal(tapLog, "tap.openLink")
        }

    // The permission user-taps, bound to the gallery here so presentation
    // never names it. Each tap is fire-and-forget: the screen follows the permission read-model StateFlow,
    // never the gallery's answer.
    override suspend fun requestAccess() = detachedOnCoreLane("tap.requestAccess") { ports.gallery.requestAccess() }

    override suspend fun openSettings() = detachedOnCoreLane("tap.openSettings") { ports.systemUi.openSettings() }

    // The picker presentation is platform surface; the selection outcome arrives only via
    // the selection-change seam.
    override suspend fun choosePhotos() = detachedOnCoreLane("tap.choosePhotos") { ports.gallery.widenSelection() }

    // In-place membership settings: range, direction and album without leaving (`OnSettingsChanged`). Distinct from
    // `openSettings` (the system settings page). Answered on the settings status under the returned id.
    override fun reconfigure(choice: ShareChoice): RequestId = requested(
        "tap.reconfigure",
        "eventId=${choice.event}",
        nextId(),
        membershipStatuses.settings,
        work.settingsChanged,
    ) { id -> SettingsRequest(id, choice) }

    // The device's mobile-data choice from the app menu: one shared preference, read by
    // each transfer as it is created, so the change governs only what starts after it.
    override suspend fun setMobileData(on: Boolean): Boolean =
        awaitingOnCoreLane("tap.setMobileData", params = "on=$on") { services.mobileData.set(on) }

    // Rename the joined event: unlike `reconfigure`, which edits only this device's settings, this rewrites the SHARED
    // event — every member picks the new name up on their next refresh (`OnRenameRequested`). Answered on the rename
    // status under the returned id.
    override fun rename(eventId: EventId, name: String): RequestId = requested(
        "tap.rename",
        "eventId=$eventId",
        nextId(),
        membershipStatuses.rename,
        work.renameRequested,
    ) { id -> RenameRequest(id, eventId, name) }

    // Another event's invite loaded while joined: may the joined one be left without asking (`OnInviteForOtherEvent`)?
    // Answered on the switch status under the returned id.
    override fun switch(invite: InviteFact): RequestId = requested(
        "tap.switch",
        "invite=$invite",
        nextId(),
        membershipStatuses.switch,
        work.inviteForOtherEvent,
    ) { id -> SwitchRequest(id, invite) }

    // The diagnostic dump, fired once the user has
    // written what went wrong: sent where the build reports, kept on the device where it does not —
    // the process's crash reporting decides, and the answer is logged either way.
    // Core lane and awaited: the dump reads both device logs (~700 KB) before it sends or saves,
    // which is exactly the blocking work the main lane must never see, and the sheet waits on it.
    override suspend fun sendDiagnostics(note: String, context: ReportContext): ReportOutcome =
        awaitingOnCoreLane<ReportOutcome>("tap.sendDiagnostics", params = "screen=${context.screen}") {
            val result = process.crash.sendDump(collectDiagnosticDump.collect(note, context))
            services.log.i { "diagnostic dump: $result" }
            // What the user is told: handed off, kept here, or neither.
            result.outcome
        }

    // Keep the key a reopened invite of the joined event carries, when this device lost it (`OnKeyRestoreRequested`).
    // Answered on the restore status under the returned id.
    override fun restoreKey(key: EventKey): RequestId = requested(
        "tap.restoreKey",
        "key=present",
        nextId(),
        membershipStatuses.restore,
        work.keyRestoreRequested,
    ) { id -> KeyRestoreRequest(id, key) }
}

/**
 * The user-query bundle: the reads the status container invokes, each awaited on the composition lane — so a store,
 * photo-library or network read never runs on the thread that asked.
 */
internal fun AppCore.userQueriesFor(): UserQueries = object : UserQueries {
    override suspend fun shareableCount(range: CaptureRange): Int? =
        awaitingOnCoreLane("query.shareableCount") { loadShareableCount(range) }
}

/** The join gate's read of an invite's event, awaited on the composition lane like every query. */
internal fun AppCore.joinDetailsFor(): JoinDetailsQuery = object : JoinDetailsQuery {
    override suspend fun load(invite: InviteLink): JoinDetails =
        awaitingOnCoreLane(
            "query.joinDetails",
            "eventId=${invite.event}",
        ) { work.membership.joinDetails.load(invite) }
}
