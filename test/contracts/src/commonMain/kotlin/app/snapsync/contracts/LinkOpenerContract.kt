package app.snapsync.contracts

import app.snapsync.ports.Handoff
import app.snapsync.ports.Observed
import app.snapsync.ports.RefusalCircumstances
import app.snapsync.ports.SystemUi
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whether some app on the host claims the URL a clause opens. */
enum class LinkOpenerState {
    /** An app claims [LinkOpenerContract.CLAIMED_URL]; opening it leaves the process under test. */
    CLAIMED,

    /** No app claims [LinkOpenerContract.UNCLAIMED_URL]; opening it leaves nothing. */
    UNCLAIMED,

    /**
     * The browser is withheld by a restriction a person set (Screen Time's Allowed Apps on iOS), so even
     * [LinkOpenerContract.CLAIMED_URL] opens nothing — the field refusals of Bugsink SNAPSYNC-71.
     */
    RESTRICTED,
}

/**
 * What [SystemUi.openUrl] promises (`docs/architecture.md` — this list IS the specification of the
 * port's obligations): it answers what the platform did with the URL, and never claims a hand-off that
 * did not happen. Named for the port it was recorded against (`LinkOpener@IOS_DEVICE_APP.rec`), so the
 * recording replays unedited.
 *
 * The URLs are fixed so a recording replays: `https` is claimed by the browser on every iOS host, and the
 * scheme [UNCLAIMED_URL] names is registered by nothing. [CLAIMED] leaves the app under test, so it is the
 * last clause, and no CI host can run it live — it is recorded on a device and replayed.
 */
object LinkOpenerContract : Contract<LinkOpenerState, SystemUi>("LinkOpener") {

    const val CLAIMED_URL = "https://www.apple.com/"
    const val UNCLAIMED_URL = "snapsync-contract-unclaimed://link-opener"

    override val clauses = clauses {

        clause(
            "UNCLAIMED_URL_IS_REFUSED",
            LinkOpenerState.UNCLAIMED,
            covers = cells {
                on<SystemUi>().answers(SystemUi::openUrl).with(Handoff.Refused::class)
            },
        ) { opener ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { opener.openUrl(UNCLAIMED_URL) }
            assertTrue(answer is Handoff.Refused, "a URL no app claims opens nothing, so it is refused; got $answer")
            // The platform gives no reason of its own, so the refusal carries what it says of itself: here, that no
            // app claims the URL.
            val circumstances = answer.circumstances
            assertTrue(
                circumstances is RefusalCircumstances.Read,
                "a refused open reads its circumstances; got $circumstances",
            )
            assertEquals(Observed.Seen(false), circumstances.claimed, "no app claims the URL, and the platform says so")
        }

        clause(
            "A_RESTRICTED_BROWSER_IS_REFUSED_AND_SAYS_SO",
            LinkOpenerState.RESTRICTED,
            covers = cells {
                on<SystemUi>().answers(SystemUi::openUrl).with(Handoff.Refused::class)
            },
        ) { opener ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { opener.openUrl(CLAIMED_URL) }
            assertTrue(answer is Handoff.Refused, "a withheld browser opens nothing, so it is refused; got $answer")
            // What tells a restricted phone apart in the field: the app was in front, and the platform says no app may
            // open the address — the same answer it gives for a scheme nothing claims.
            val circumstances = answer.circumstances
            assertTrue(
                circumstances is RefusalCircumstances.Read,
                "a refused open reads its circumstances; got $circumstances",
            )
            assertEquals(Observed.Seen(true), circumstances.inFront, "the member tapped in the app, which was in front")
            assertEquals(Observed.Seen(false), circumstances.claimed, "the platform says no app may open the address")
        }

        clause(
            "CLAIMED_URL_IS_ACCEPTED",
            LinkOpenerState.CLAIMED,
            covers = cells {
                on<SystemUi>().answers(SystemUi::openUrl).with(Handoff.Accepted::class)
            },
        ) { opener ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { opener.openUrl(CLAIMED_URL) }
            assertEquals(Handoff.Accepted, answer, "a URL an app claims is handed to that app")
        }
    }
}
