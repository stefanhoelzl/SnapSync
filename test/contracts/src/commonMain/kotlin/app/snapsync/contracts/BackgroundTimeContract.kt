package app.snapsync.contracts

import app.snapsync.ports.BackgroundTime
import kotlin.test.assertEquals

/** The states the app's background time can be found in, as far as a clause cares. */
enum class BackgroundTimeState {
    /** A running app whose time is not up: the operating system grants a hold when asked. */
    TIME_REMAINS,
}

/**
 * What the app's background time promises the core (`docs/architecture.md` — this list IS the specification;
 * `docs/architecture.md`, "Background time is an outbound port named for the need").
 *
 * **What is contracted, and what cannot be.** Every clause here runs on the one state a real host presents to a
 * binding: a running app whose time is not up (the simulator app, in the foreground the rig drives). There, the
 * observable obligations are that a hold is **granted** — a refused hold is reported as an immediate expiry, so an
 * expiry is exactly what a refusal would look like — that holds do not refuse each other (background time is per
 * app), and that ending a hold is safe to repeat.
 *
 * Deliberately **no expiry clause**: no host lets a binding enter "time is up". The operating system fires a
 * background task's expiration handler only after the app has been in the background for as long as it allows,
 * which takes the process under test away from the rig that drives it, and no API lets a process expire its own
 * time. A clause only the in-memory double could reach may not exist (`docs/architecture.md`, "Every clause runs against
 * a real implementation on some host"), so the expiry behaviour — the handler invoked once and returning at once,
 * the hold NOT ended by the expiry, a refusal reported as an immediate expiry — lives in `IosBackgroundTime`'s
 * documentation and its own tests over its operating-system seam, and the core's reaction to an expiry is tested
 * over the honest double.
 */
object BackgroundTimeContract : Contract<BackgroundTimeState, BackgroundTime>("BackgroundTime") {

    override val clauses = clauses {

        clause("A_HOLD_IS_GRANTED_WHILE_TIME_REMAINS", BackgroundTimeState.TIME_REMAINS) { time ->
            val expiries = Expiries()
            val hold = time.begin("contract.granted", expiries::fire)
            assertEquals(0, expiries.count, "a granted hold does not report an expiry as it begins")
            settle()
            assertEquals(0, expiries.count, "nor shortly after, while the app's time is not up")
            hold.end()
        }

        clause("A_SECOND_HOLD_IS_GRANTED_BESIDE_THE_FIRST", BackgroundTimeState.TIME_REMAINS) { time ->
            val expiries = Expiries()
            val first = time.begin("contract.first", expiries::fire)
            val second = time.begin("contract.second", expiries::fire)
            settle()
            assertEquals(0, expiries.count, "background time is per app: a second hold is granted, not refused")
            first.end()
            second.end()
        }

        clause("AN_ENDED_HOLD_REPORTS_NO_EXPIRY", BackgroundTimeState.TIME_REMAINS) { time ->
            val expiries = Expiries()
            time.begin("contract.ended", expiries::fire).end()
            settle()
            assertEquals(0, expiries.count, "a hold ended before its time was up is never reported expired")
        }

        clause("ENDING_TWICE_IS_QUIET", BackgroundTimeState.TIME_REMAINS) { time ->
            val expiries = Expiries()
            val hold = time.begin("contract.twice", expiries::fire)
            hold.end()
            hold.end()
            val next = time.begin("contract.after", expiries::fire)
            settle()
            assertEquals(0, expiries.count, "a repeated end neither fails nor disturbs a hold begun after it")
            next.end()
        }
    }
}

/** Counts the expiries a clause's holds report. */
private class Expiries {
    var count = 0
        private set

    fun fire() {
        count++
    }
}
