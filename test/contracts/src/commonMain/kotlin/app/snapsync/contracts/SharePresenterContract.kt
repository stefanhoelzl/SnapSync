package app.snapsync.contracts

import app.snapsync.ports.Handoff
import app.snapsync.ports.SharePresenter
import kotlin.test.assertEquals

/** Whether the process has a surface to present a share sheet over. */
enum class SharePresenterState {
    /** The app is in the foreground with a key window. */
    PRESENTABLE,
}

/**
 * What every [SharePresenter] promises (`docs/architecture.md` — this list IS the specification of
 * the port's obligations).
 *
 * One clause, because one state is reachable. The adapter's other answer — no key window, so nothing to
 * present from — is a state no host can enter while the rig is driving a foreground app, so it stays in
 * the adapter's documentation rather than becoming a clause only a fake could run. What the user does in
 * the sheet is not the port's to report at all.
 */
object SharePresenterContract : Contract<SharePresenterState, SharePresenter>("SharePresenter") {

    const val TEXT = "https://snapsync.stho.net/join#contract"

    override val clauses = clauses {

        clause("PRESENTABLE_SHARE_IS_ACCEPTED", SharePresenterState.PRESENTABLE) { presenter ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { presenter.share(TEXT) }
            assertEquals(Handoff.Accepted, answer, "with a key window to present from, the sheet is presented")
        }
    }
}
