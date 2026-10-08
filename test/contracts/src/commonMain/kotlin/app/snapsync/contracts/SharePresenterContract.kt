package app.snapsync.contracts

import app.snapsync.model.Handoff
import app.snapsync.ports.SystemUi
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Whether the process has a surface to present a share sheet over. */
enum class SharePresenterState {
    /** The app is in the foreground with a key window. */
    PRESENTABLE,

    /**
     * The app running in the background, never brought to the front — launched by the operating system for its own
     * reasons — so there is nothing on screen to present from. A device's, recorded: the rig drives every CI host's app
     * in the foreground.
     */
    NO_WINDOW,
}

/**
 * What [SystemUi.share] promises (`docs/architecture.md` — this list IS the specification of
 * the port's obligations).
 *
 * Presented with a window to present from, refused without one — a state no CI host enters while the rig drives a
 * foreground app, so it is recorded on a phone the operating system launched in the background. What the user does
 * in the sheet is not the port's to report at all.
 */
object SharePresenterContract : Contract<SharePresenterState, SystemUi>("SharePresenter") {

    const val TEXT = "https://snapsync.stho.net/join#contract"
    const val TITLE = "Contract event"

    override val clauses = clauses {

        clause(
            "PRESENTABLE_SHARE_IS_ACCEPTED",
            SharePresenterState.PRESENTABLE,
            covers = cells {
                on<SystemUi>().answers(SystemUi::share).with(Handoff.Accepted::class)
            },
        ) { presenter ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { presenter.share(TEXT, TITLE) }
            assertEquals(Handoff.Accepted, answer, "with a key window to present from, the sheet is presented")
        }

        clause(
            "NO_WINDOW_SHARE_IS_REFUSED",
            SharePresenterState.NO_WINDOW,
            covers = cells {
                on<SystemUi>().answers(SystemUi::share).with(Handoff.Refused::class)
            },
        ) { presenter ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { presenter.share(TEXT, TITLE) }
            assertIs<Handoff.Refused>(answer, "with nowhere to present from, the share is refused, never claimed shown")
        }
    }
}
