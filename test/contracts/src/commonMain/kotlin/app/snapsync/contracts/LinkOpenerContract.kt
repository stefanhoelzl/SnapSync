package app.snapsync.contracts

import app.snapsync.ports.Handoff
import app.snapsync.ports.LinkOpener
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whether some app on the host claims the URL a clause opens. */
enum class LinkOpenerState {
    /** An app claims [LinkOpenerContract.CLAIMED_URL]; opening it leaves the process under test. */
    CLAIMED,

    /** No app claims [LinkOpenerContract.UNCLAIMED_URL]; opening it leaves nothing. */
    UNCLAIMED,
}

/**
 * What every [LinkOpener] promises (capability `port-contracts` — this list IS the specification of the
 * port's obligations): it answers what the platform did with the URL, and never claims a hand-off that
 * did not happen.
 *
 * The URLs are fixed so a recording replays: `https` is claimed by the browser on every iOS host, and the
 * scheme [UNCLAIMED_URL] names is registered by nothing. [CLAIMED] leaves the app under test, so it is the
 * last clause, and no CI host can run it live — it is recorded on a device and replayed.
 */
object LinkOpenerContract : Contract<LinkOpenerState, LinkOpener>("LinkOpener") {

    const val CLAIMED_URL = "https://www.apple.com/"
    const val UNCLAIMED_URL = "snapsync-contract-unclaimed://link-opener"

    override val clauses = clauses {

        clause("UNCLAIMED_URL_IS_REFUSED", LinkOpenerState.UNCLAIMED) { opener ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { opener.open(UNCLAIMED_URL) }
            assertTrue(answer is Handoff.Refused, "a URL no app claims opens nothing, so it is refused; got $answer")
        }

        clause("CLAIMED_URL_IS_ACCEPTED", LinkOpenerState.CLAIMED) { opener ->
            val answer = withinRealTime(HANDOFF_ANSWER_MILLIS) { opener.open(CLAIMED_URL) }
            assertEquals(Handoff.Accepted, answer, "a URL an app claims is handed to that app")
        }
    }
}
