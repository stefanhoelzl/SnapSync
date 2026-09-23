package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.JoinLoad
import app.snapsync.model.UserQueries

/** A query bundle that answers nothing: every details load fails and no count is available. */
internal val noQueries: UserQueries = UserQueries(loadJoinDetails = { JoinLoad.Failed }, shareableCount = { _, _ -> null })

/** A query bundle whose join-details read is [load]; no count is available. */
internal fun joinDetails(load: suspend (String) -> JoinLoad): UserQueries =
    UserQueries(loadJoinDetails = load, shareableCount = { _, _ -> null })

/** A query bundle whose shareable count is [count]; every details load fails. */
internal fun counting(count: suspend (CaptureCutoff, CaptureCeiling?) -> Int?): UserQueries =
    UserQueries(loadJoinDetails = { JoinLoad.Failed }, shareableCount = count)

/** The user-command bundle with the inert defaults the production type no longer carries. */
internal fun testCommands(
    leave: suspend () -> Unit = {},
    create: (name: String, startsAt: app.snapsync.model.EventStart, endsAt: app.snapsync.model.EventEnd) -> Unit = { _, _, _ -> },
    commitJoin: suspend (
        eventId: String,
        name: String,
        startsAt: app.snapsync.model.EventStart,
        endsAt: app.snapsync.model.EventEnd,
        deletesAt: app.snapsync.model.DeletesAt,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        direction: app.snapsync.model.Direction,
        saveToAlbum: Boolean,
    ) -> app.snapsync.model.JoinCommit = { _, _, _, _, _, _, _, _, _ -> app.snapsync.model.JoinCommit.Failed },
    share: (String) -> Unit = {},
    requestAccess: () -> Unit = {},
    openSettings: () -> Unit = {},
    openLink: (url: String) -> Unit = {},
    choosePhotos: () -> Unit = {},
    reconfigure: suspend (
        eventId: String,
        direction: app.snapsync.model.Direction,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        saveToAlbum: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    rename: (eventId: String, name: String) -> Unit = { _, _ -> },
    resetRename: suspend () -> Unit = {},
    sendDiagnostics: (suspend (note: String, screen: String) -> Unit)? = null,
) = app.snapsync.model.UserCommands(
    leave, create, commitJoin, share, requestAccess, openSettings, openLink, choosePhotos, reconfigure, rename,
    resetRename, sendDiagnostics,
)

/** Diagnostics that go nowhere unless a test is about them. */
internal fun testDiagnostics(log: (String) -> Unit = {}, onIntentError: (Throwable) -> Unit = {}) =
    StatusDiagnostics(log = log, onIntentError = onIntentError)
