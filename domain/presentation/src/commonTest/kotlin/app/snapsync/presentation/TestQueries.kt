package app.snapsync.presentation

import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportOutcome
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A query bundle whose reads are [load] and [count]; by default every details load fails and no count is available. */
internal fun testQueries(
    load: suspend (eventId: String, linkKey: String?) -> JoinLoad = { _, _ -> JoinLoad.Failed },
    count: suspend (CaptureCutoff, CaptureCeiling?) -> Int? = { _, _ -> null },
): UserQueries = object : UserQueries {
    override suspend fun loadJoinDetails(eventId: String, linkKey: String?) = load(eventId, linkKey)

    override suspend fun shareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?) = count(cutoff, until)
}

/** A query bundle that answers nothing: every details load fails and no count is available. */
internal val noQueries: UserQueries = testQueries()

/** A query bundle whose join-details read is [load]; no count is available. */
internal fun joinDetails(load: suspend (String) -> JoinLoad): UserQueries =
    testQueries({ id, _ -> load(id) }, { _, _ -> null })

/** A query bundle whose shareable count is [count]; every details load fails. */
internal fun counting(count: suspend (CaptureCutoff, CaptureCeiling?) -> Int?): UserQueries =
    testQueries({ _, _ -> JoinLoad.Failed }, count)

/** The user-command bundle with the inert defaults the production implementation does not carry. */
internal fun testCommands(
    leave: suspend () -> Unit = {},
    create: (name: String, startsAt: EventStart, endsAt: EventEnd) -> Unit = { _, _, _ -> },
    commitJoin: suspend (JoinChoice) -> JoinCommit = { _ -> JoinCommit.Failed },
    share: (url: String, title: String) -> Unit = { _, _ -> },
    requestAccess: () -> Unit = {},
    openSettings: () -> Unit = {},
    openLink: (url: String) -> Unit = {},
    reconfigure: suspend (
        eventId: String,
        direction: Direction,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        saveToAlbum: Boolean,
    ) -> ReconfigureOutcome = { _, _, _, _, _ -> ReconfigureOutcome.Saved },
    rename: (eventId: String, name: String) -> Unit = { _, _ -> },
    resetRename: suspend () -> Unit = {},
    sendDiagnostics: suspend (note: String, context: ReportContext) -> ReportOutcome = { _, _ -> ReportOutcome.SENT },
): UserCommands = object : UserCommands {
    override suspend fun leave() = leave.invoke()

    override fun create(name: String, startsAt: EventStart, endsAt: EventEnd) = create.invoke(name, startsAt, endsAt)

    override suspend fun commitJoin(choice: JoinChoice) = commitJoin.invoke(choice)

    override fun share(url: String, title: String) = share.invoke(url, title)

    override fun requestAccess() = requestAccess.invoke()

    override fun openSettings() = openSettings.invoke()

    override fun openLink(url: String) = openLink.invoke(url)

    // No presentation test is about the picker; its binding is the shells' and compose/'s.
    override fun choosePhotos() = Unit

    override suspend fun reconfigure(
        eventId: String,
        direction: Direction,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        saveToAlbum: Boolean,
    ) = reconfigure.invoke(eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum)

    override fun rename(eventId: String, name: String) = rename.invoke(eventId, name)

    override suspend fun resetRename() = resetRename.invoke()

    override suspend fun sendDiagnostics(note: String, context: ReportContext) = sendDiagnostics.invoke(note, context)

    // The menu's mobile-data switch saves; a test about it builds its bundle with [testMenuCommands].
    override suspend fun setMobileData(on: Boolean) = true

    override suspend fun restoreEventKey(linkKey: String) = false
}

/** The command bundle a menu test drives: its links, its report and its mobile-data switch; the rest inert. */
internal fun testMenuCommands(
    openLink: (url: String) -> Unit,
    sendDiagnostics: suspend (note: String, context: ReportContext) -> ReportOutcome,
    setMobileData: suspend (on: Boolean) -> Boolean,
) = object : UserCommands by testCommands(openLink = openLink, sendDiagnostics = sendDiagnostics) {
    override suspend fun setMobileData(on: Boolean) = setMobileData.invoke(on)
}

/** Diagnostics that go nowhere unless a test is about them. */
internal fun testDiagnostics(log: (String) -> Unit = {}, onIntentError: (Throwable) -> Unit = {}) =
    StatusDiagnostics(log = log, onIntentError = onIntentError)

/** A sync snapshot that holds [status] and never moves — for a test about something other than the snapshot. */
internal class FixedSync(status: SyncStatus) : SyncStatusSource {
    override val status: StateFlow<SyncStatus> = MutableStateFlow(status)
}
