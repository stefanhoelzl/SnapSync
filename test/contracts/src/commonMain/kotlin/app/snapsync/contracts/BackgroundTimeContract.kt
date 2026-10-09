package app.snapsync.contracts

import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The states the app's background time can be found in, as far as a clause cares. */
enum class BackgroundTimeState {
    /** A running app whose time is not up: the operating system grants a hold when asked. */
    TIME_REMAINS,

    /**
     * A hold that runs until its time is up: on iOS the app sent to the background, a device's, recorded; on Android
     * the hold's work stopped while it runs.
     */
    TIME_RUNS_OUT,
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
 * **The expiry** ([BackgroundTimeState.TIME_RUNS_OUT]) has no live iOS host: the operating system fires a background
 * task's expiration handler only after the app has been in the background for as long as it allows, and no API lets
 * a process expire its own time. So it is recorded on a phone a person sent to the home screen, and replayed — the
 * expiry delivered where it arrived among the adapter's calls. On Android a hold is a work, and every stop of a
 * running work reaches it the same way, so the emulator stops one live. A refusal reported as an immediate expiry stays in
 * `IosBackgroundTime`'s own tests over its operating-system seam: no host refuses a running app its first hold.
 */
object BackgroundTimeContract : Contract<BackgroundTimeState, BackgroundTime>("BackgroundTime") {

    override val clauses = clauses {

        clause(
            "A_HOLD_IS_GRANTED_WHILE_TIME_REMAINS",
            BackgroundTimeState.TIME_REMAINS,
            covers = cells {
                on<BackgroundTime>().answers(BackgroundTime::begin).returns()
            },
        ) { time ->
            val expiries = Expiries()
            val hold = time.begin("contract.granted", expiries::fire)
            assertEquals(0, expiries.count, "a granted hold does not report an expiry as it begins")
            settle()
            assertEquals(0, expiries.count, "nor shortly after, while the app's time is not up")
            hold.end()
        }

        clause(
            "A_SECOND_HOLD_IS_GRANTED_BESIDE_THE_FIRST",
            BackgroundTimeState.TIME_REMAINS,
            covers = cells {
                on<BackgroundTime>().answers(BackgroundTime::begin).returns()
            },
        ) { time ->
            val expiries = Expiries()
            val first = time.begin("contract.first", expiries::fire)
            val second = time.begin("contract.second", expiries::fire)
            settle()
            assertEquals(0, expiries.count, "background time is per app: a second hold is granted, not refused")
            first.end()
            second.end()
        }

        clause(
            "AN_ENDED_HOLD_REPORTS_NO_EXPIRY",
            BackgroundTimeState.TIME_REMAINS,
            covers = cells {
                on<BackgroundTime>().handle<BackgroundTimeHold>().answers(BackgroundTimeHold::end).returns()
            },
        ) { time ->
            val expiries = Expiries()
            time.begin("contract.ended", expiries::fire).end()
            settle()
            assertEquals(0, expiries.count, "a hold ended before its time was up is never reported expired")
        }

        clause(
            "ENDING_TWICE_IS_QUIET",
            BackgroundTimeState.TIME_REMAINS,
            covers = cells {
                on<BackgroundTime> {
                    answers(BackgroundTime::begin).returns()
                    handle<BackgroundTimeHold>().answers(BackgroundTimeHold::end).returns()
                }
            },
        ) { time ->
            val expiries = Expiries()
            val hold = time.begin("contract.twice", expiries::fire)
            hold.end()
            hold.end()
            val next = time.begin("contract.after", expiries::fire)
            settle()
            assertEquals(0, expiries.count, "a repeated end neither fails nor disturbs a hold begun after it")
            next.end()
        }

        clause(
            "AN_EXPIRY_IS_REPORTED_ONCE_AND_THE_HOLD_STILL_ENDS",
            BackgroundTimeState.TIME_RUNS_OUT,
            covers = cells {
                on<BackgroundTime> {
                    answers(BackgroundTime::begin).returns()
                    callsBack(BackgroundTime::begin, "onExpiry")
                    handle<BackgroundTimeHold>().answers(BackgroundTimeHold::end).returns()
                }
            },
        ) { time ->
            val expiries = Expiries()
            val hold = time.begin("contract.expiry", expiries::fire)
            awaitWithin(EXPIRY_BOUND) { expiries.count > 0 }
            settle()
            assertEquals(1, expiries.count, "the operating system's \"time is up\" is reported exactly once")
            // The expiry only requested a stop; the hold is the caller's to end, inside the system's grace.
            hold.end()
        }
    }
}

/** How long a backgrounded app's hold may run before its time is up: about thirty seconds on iOS 26, with margin. */
private val EXPIRY_BOUND = 3.minutes

/** Counts the expiries a clause's holds report. */
private class Expiries {
    var count = 0
        private set

    fun fire() {
        count++
    }
}
