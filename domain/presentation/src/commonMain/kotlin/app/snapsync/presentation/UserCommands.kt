package app.snapsync.presentation

import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.ReportRequest
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * **One thing the member can ask for** (`docs/architecture.md`, "Commands cross one door"): the entry point that runs it,
 * fired and never awaited. Its work runs on the composition lane under the entry point's own label, holding the
 * process's background time; what it came to is read from a status, under the request's id, never returned.
 */
class UserCommand<A>(private val entry: Background<A>) {
    fun fire(arg: A) = entry.handle(arg, Completion.None)
}

/** Every command the screen fires, each an entry point the composition hands over. */
class UserCommands(
    val leave: UserCommand<Unit>,
    val create: UserCommand<CreateRequest>,
    val join: UserCommand<JoinRequest>,
    val share: UserCommand<ShareRequest>,
    val requestAccess: UserCommand<Unit>,
    val openSettings: UserCommand<Unit>,
    val openLink: UserCommand<AppLinkRequest>,
    val choosePhotos: UserCommand<Unit>,
    val reconfigure: UserCommand<SettingsRequest>,
    val rename: UserCommand<RenameRequest>,
    val switch: UserCommand<SwitchRequest>,
    val sendDiagnostics: UserCommand<ReportRequest>,
    val setMobileData: UserCommand<MobileDataRequest>,
    val restoreKey: UserCommand<KeyRestoreRequest>,
)

/** The ids of one screen's requests: unique for the container's life, which is as long as a status is read by it. */
@OptIn(ExperimentalAtomicApi::class)
internal class RequestIds {
    private val last = AtomicLong(0)

    fun next(): RequestId = RequestId(last.incrementAndFetch())
}
