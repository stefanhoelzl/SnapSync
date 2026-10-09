@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.contracts

import app.snapsync.model.StartResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.TransferOutcome
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The state a clause opens its fresh port in. */
enum class DownloadState {
    /** Any network the device has; every other clause's. */
    READY,

    /**
     * The device on a restricted network — mobile data, a metered Wi-Fi, a hotspot, Low Data
     * Mode — which the binding can lift ([DownloadUnderTest.liftRestriction]).
     */
    RESTRICTED_NETWORK,

    /**
     * Any network, on a platform that delivers a finished transfer by waking the app — handing a completion it holds
     * the wake open on — which the binding can see end once released ([DownloadUnderTest.wakeEnded]).
     */
    WAKES_TO_DELIVER,

    /** Any network, on a platform whose queue refuses, as it is asked, a URL it cannot fetch (Android's DownloadManager). */
    REFUSES_UNFETCHABLE,

    /**
     * A transfer that ended while the app was gone, whose end the operating system relaunched the app to deliver — the
     * relaunch held by the binding ([DownloadUnderTest.relaunch]). A device's, recorded across the two processes: no CI
     * host's session outlives its app.
     */
    RELAUNCHED_WITH_EVENTS,
}

/**
 * The port as a clause receives it. [open] builds a fresh [Download] the clause listens to — its handlers are the
 * port's own output channel, so what they are told IS the port's answer. [readTemp] reads the platform file a finish
 * hands over, **inline**, as an owner must (the platform deletes it when the callback returns): an observation handle
 * over the system the binding built.
 */
class DownloadUnderTest(
    val open: () -> Download,
    val base: String,
    val readTemp: (path: String) -> ByteArray?,
    /** Puts the device back on an unrestricted network; required of a binding that reaches [DownloadState.RESTRICTED_NETWORK]. */
    val liftRestriction: (suspend () -> Unit)? = null,
    /**
     * Whether the platform's delivery wake has ended — the system holds no wake of this app open any more; required of a
     * binding that reaches [DownloadState.WAKES_TO_DELIVER].
     */
    val wakeEnded: (suspend () -> Boolean)? = null,
    /**
     * Hands the port the operating system's relaunch, with its completion handler, as the app's delegate does; required
     * of a binding that reaches [DownloadState.RELAUNCHED_WITH_EVENTS].
     */
    val relaunch: (() -> Unit)? = null,
)

/** One thing the port told its owner, in the order it was told. */
sealed interface DownloadEvent {
    /** A finish, with the body read from the temporary file while the callback ran. */
    class Finished(val tag: String, val facts: TransferOutcome, val body: ByteArray?) : DownloadEvent

    data class Completed(val tag: String, val error: String?) : DownloadEvent
}

/**
 * The owner a clause registers: it keeps what it was told, reading a finished body from its temporary file before the
 * callback returns. Atomic, because a real session tells it from its own queue while the clause reads.
 */
class ClauseDownloadHandlers(private val readTemp: (String) -> ByteArray?) {
    private val log = AtomicReference<List<DownloadEvent>>(emptyList())
    private val wakes = AtomicInt(0)
    private val drains = AtomicInt(0)

    val events: List<DownloadEvent> get() = log.load()

    /** How many delivery wakes the port handed over (each completion released at once), and how many drain reports. */
    val wakesHandedOver: Int get() = wakes.load()
    val drainsReported: Int get() = drains.load()

    private fun count(counter: AtomicInt) {
        counter.incrementAndFetch()
    }

    private fun add(event: DownloadEvent) {
        while (true) {
            val now = log.load()
            if (log.compareAndSet(now, now + event)) return
        }
    }

    val handlers = DownloadHandlers(
        onFinished = { tag, facts, tempPath -> add(DownloadEvent.Finished(tag, facts, readTemp(tempPath))) },
        onCompleted = { tag, error -> add(DownloadEvent.Completed(tag, error)) },
        onBackgroundEvents = { completion: Completion ->
            count(wakes)
            completion.complete()
        },
        onEventsDrained = { count(drains) },
    )
}

/**
 * What every [Download] promises its owner (`docs/architecture.md` — this list IS the specification). Whether a body
 * may be staged, and where, are the owner's; what is contracted is that the port reports the facts truthfully, hands
 * over the body it received, completes every transfer it started, and cancels what it holds.
 */
object DownloadContract : Contract<DownloadState, DownloadUnderTest>("Download") {

    /** The tag the relaunched state's transfer was started under, which its end is reported with. */
    const val RELAUNCHED_TAG = "d-relaunched"

    /** The route a clause fetches from. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String =
        TransferFixture.path(name, clauseId, "download-$n", answer)

    /** Opens a port, listens, starts one transfer of [answer] and waits until it completes; returns what was told. */
    private suspend fun DownloadUnderTest.transfer(clauseId: String, answer: FixtureAnswer): List<DownloadEvent> {
        val owner = ClauseDownloadHandlers(readTemp)
        val download = open()
        download.listen(owner.handlers)
        assertEquals(
            StartResult.Started,
            download.start(base + path(clauseId, answer), "d-$clauseId", TransferNetwork.ANY),
            "a fetchable URL starts",
        )
        awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
        return owner.events
    }

    override val clauses = clauses {

        clause(
            "WAKES_TO_DELIVER_A_FINISH_IS_DRAINED_AND_THE_WAKE_RELEASED",
            DownloadState.WAKES_TO_DELIVER,
            covers = cells {
                on<Download> {
                    calls(DownloadHandlers::onBackgroundEvents, Completion::class)
                    calls(DownloadHandlers::onEventsDrained)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
        ) { subject ->
            val id = "WAKES_TO_DELIVER_A_FINISH_IS_DRAINED_AND_THE_WAKE_RELEASED"
            val ended = assertNotNull(subject.wakeEnded, "a binding that reaches this state can see the wake end")
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            assertEquals(
                StartResult.Started,
                download.start(
                    subject.base + path(id, FixtureAnswer.Respond(200, length = 64)),
                    "d-$id",
                    TransferNetwork.ANY,
                ),
            )
            awaitWithin { owner.drainsReported > 0 }
            assertTrue(owner.wakesHandedOver > 0, "the finish arrived in a wake, its completion handed over first")
            assertTrue(
                owner.events.any { it is DownloadEvent.Finished },
                "and was delivered inside it, before the drain report",
            )
            awaitWithin { ended() }
        }

        clause(
            "FINISH_REPORTS_THE_TRUE_FACTS_AND_THE_BODY",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                    calls(DownloadHandlers::onFinished, String::class, TransferOutcome::class, String::class)
                    calls(DownloadHandlers::onCompleted, String::class, null)
                }
            },
        ) { subject ->
            val id = "FINISH_REPORTS_THE_TRUE_FACTS_AND_THE_BODY"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 1024))
            val finished = events.first() as? DownloadEvent.Finished
            assertNotNull(finished, "a finish comes first, then the completion: $events")
            assertEquals("d-$id", finished.tag, "reported under the tag it was started with")
            assertEquals(TransferOutcome(200, 1024, 1024), finished.facts, "judged on the true facts")
            assertContentEquals(TransferFixture.body(1024), finished.body, "the temporary file holds the body, inline")
            assertEquals(DownloadEvent.Completed("d-$id", null), events.last(), "then completed without an error")
        }

        clause(
            "A_REDIRECT_IS_FOLLOWED_TO_THE_BODY",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                    calls(DownloadHandlers::onFinished, String::class, TransferOutcome::class, String::class)
                    calls(DownloadHandlers::onCompleted, String::class, null)
                }
            },
        ) { subject ->
            // A download URL may name a route that answers `302` to where the bytes are (an edge route redirecting to a
            // freshly presigned object URL). The port follows it on its own and reports the TARGET's answer: the facts
            // and the body are the final response's, never the redirect's.
            val id = "A_REDIRECT_IS_FOLLOWED_TO_THE_BODY"
            val events = subject.transfer(id, FixtureAnswer.Redirect(FixtureAnswer.Respond(200, length = 1024)))
            val finished = events.first() as? DownloadEvent.Finished
            assertNotNull(finished, "the redirect is followed to a finish, then the completion: $events")
            assertEquals("d-$id", finished.tag, "reported under the tag it was started with")
            assertEquals(TransferOutcome(200, 1024, 1024), finished.facts, "judged on the target's facts")
            assertContentEquals(TransferFixture.body(1024), finished.body, "the temporary file holds the target's body")
            assertEquals(DownloadEvent.Completed("d-$id", null), events.last(), "then completed without an error")
        }

        clause(
            "AN_ERROR_STATUS_IS_A_FINISHED_TRANSFER_OF_ITS_BODY",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                }
            },
        ) { subject ->
            // Two honest shapes, one outcome. A background `URLSession` reports an HTTP error as a SUCCESSFUL transfer of
            // the error body, with a nil completion error — the port reports the status, and the owner's integrity
            // check refuses it. Android's DownloadManager fails the transfer instead, the status as its reason, and
            // keeps no body. Either way nothing is staged and the resource stays pending; what the port may never do is
            // report an error answer as a success.
            val id = "AN_ERROR_STATUS_IS_A_FINISHED_TRANSFER_OF_ITS_BODY"
            val events = subject.transfer(id, FixtureAnswer.Respond(404, length = 16))
            val finished = events.filterIsInstance<DownloadEvent.Finished>()
            if (finished.isEmpty()) {
                assertNotNull(
                    events.filterIsInstance<DownloadEvent.Completed>().single().error,
                    "an error answer fails",
                )
            } else {
                assertEquals(404, finished.single().facts.statusCode)
            }
            assertTrue(events.last() is DownloadEvent.Completed, "the slot is freed either way")
        }

        clause(
            "NO_LENGTH_IS_NEGATIVE",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                }
            },
        ) { subject ->
            // Either the port finishes a body whose length was never declared and says the length is unknown (never
            // zero), or it refuses to download what it cannot size — DownloadManager's answer ("can't know size of
            // download"), a failed completion. Every object the backend presigns declares its length, so the refusal
            // never meets a real download; what is asserted is that neither shape lies.
            val id = "NO_LENGTH_IS_NEGATIVE"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 32, declaresLength = false))
            val finished = events.filterIsInstance<DownloadEvent.Finished>()
            if (finished.isEmpty()) {
                assertNotNull(
                    events.filterIsInstance<DownloadEvent.Completed>().single().error,
                    "an unsized body fails",
                )
            } else {
                val facts = finished.single().facts
                assertTrue(facts.expectedBytes < 0, "an undeclared length is 'unknown', not zero: $facts")
                assertEquals(32, facts.receivedBytes)
            }
        }

        clause(
            "SHORT_READ_IS_REPORTED_TRUTHFULLY",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                }
            },
        ) { subject ->
            val id = "SHORT_READ_IS_REPORTED_TRUTHFULLY"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 64, short = true))
            // Either the port fails a body cut short — no finish, completed with an error — or it reports the TRUE
            // facts, so the owner's integrity check can refuse it. What it may never do is report a cut transfer as
            // whole: that is the one lie the integrity check cannot catch.
            val finished = events.filterIsInstance<DownloadEvent.Finished>()
            if (finished.isEmpty()) {
                assertNotNull(events.filterIsInstance<DownloadEvent.Completed>().single().error, "a cut transfer fails")
            } else {
                assertTrue(finished.single().facts.receivedBytes < 64, "a cut transfer is not reported whole")
            }
        }

        clause(
            "CANCEL_ALL_COMPLETES_WHAT_IT_HOLDS_WITH_AN_ERROR",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                    answers(Download::cancelAll).returns()
                    calls(DownloadHandlers::onCompleted, String::class, String::class)
                }
            },
        ) { subject ->
            val id = "CANCEL_ALL_COMPLETES_WHAT_IT_HOLDS_WITH_AN_ERROR"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            assertEquals(
                StartResult.Started,
                download.start(subject.base + path(id, FixtureAnswer.Hold), "d-$id", TransferNetwork.ANY),
            )
            download.cancelAll()
            awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
            assertNotNull(
                owner.events.filterIsInstance<DownloadEvent.Completed>().single().error,
                "cancelled: an error",
            )
            assertTrue(owner.events.none { it is DownloadEvent.Finished }, "and nothing finished")
        }

        clause(
            "A_TRANSFER_STARTED_AFTER_CANCEL_ALL_RETURNS_RUNS",
            DownloadState.READY,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::cancelAll).returns()
                    answers(Download::start).with(StartResult.Started)
                    calls(DownloadHandlers::onCompleted, String::class, null)
                }
            },
        ) { subject ->
            val id = "A_TRANSFER_STARTED_AFTER_CANCEL_ALL_RETURNS_RUNS"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            download.cancelAll()
            val tag = "d-$id"
            assertEquals(
                StartResult.Started,
                download.start(
                    subject.base + path(id, FixtureAnswer.Respond(200, length = 8)),
                    tag,
                    TransferNetwork.ANY,
                ),
            )
            awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
            assertEquals(
                DownloadEvent.Completed(tag, null),
                owner.events.last(),
                "a transfer after the cancel is untouched",
            )
        }

        clause(
            "A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE",
            DownloadState.RESTRICTED_NETWORK,
            covers = cells {
                on<Download> {
                    answers(Download::listen).returns()
                    answers(Download::start).with(StartResult.Started)
                    calls(DownloadHandlers::onFinished, String::class, TransferOutcome::class, String::class)
                    calls(DownloadHandlers::onCompleted, String::class, null)
                }
            },
        ) { subject ->
            val id = "A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE"
            val lift = assertNotNull(subject.liftRestriction, "a binding that reaches a restricted network can lift it")
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            val tag = "d-$id"
            val route = subject.base + path(id, FixtureAnswer.Respond(200, length = 8))
            assertEquals(StartResult.Started, download.start(route, tag, TransferNetwork.UNRESTRICTED_ONLY))
            heldSettle()
            assertEquals(
                emptyList(),
                owner.events,
                "a transfer held to unrestricted networks does not run on a restricted one",
            )
            lift()
            awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
            assertEquals(DownloadEvent.Completed(tag, null), owner.events.last(), "it runs once the network allows it")
            assertEquals(200, owner.events.filterIsInstance<DownloadEvent.Finished>().single().facts.statusCode)
        }

        clause(
            "AN_UNFETCHABLE_URL_IS_NOT_STARTED",
            DownloadState.REFUSES_UNFETCHABLE,
            covers = cells {
                on<Download>().answers(Download::start).with(StartResult.NotStarted)
            },
        ) { subject ->
            val id = "AN_UNFETCHABLE_URL_IS_NOT_STARTED"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            assertEquals(
                StartResult.NotStarted,
                download.start("ftp://contract.invalid/$id", "d-$id", TransferNetwork.ANY),
                "a scheme the queue cannot fetch is refused as it is asked, never started to fail later",
            )
            transferSettle()
            assertEquals(emptyList(), owner.events, "a transfer never started tells its owner nothing")
        }

        clause(
            "UNUSABLE_URL_NEVER_FINISHES",
            DownloadState.READY,
            covers = cells {
                oneOf {
                    on<Download>().answers(Download::start).with(StartResult.Started)
                    on<Download>().answers(Download::start).with(StartResult.NotStarted)
                }
            },
        ) { subject ->
            val id = "UNUSABLE_URL_NEVER_FINISHES"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            // Never a throw. The port leaves filtering an unusable URL to its caller and collapses "not started" with
            // "started, then failed" because they converge — so either answer is honest, and what is asserted is the
            // convergence: nothing finished, and a transfer that did start completes with an error. Measured
            // 2026-09-23 (iOS 26.5 simulator): `NSURL.URLWithString("")` is NOT nil, so the real session starts one.
            when (download.start("", "d-$id", TransferNetwork.ANY)) {
                StartResult.NotStarted -> {
                    transferSettle()
                    assertEquals(emptyList(), owner.events, "a transfer never started tells its owner nothing")
                }
                StartResult.Started -> {
                    awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
                    assertTrue(owner.events.none { it is DownloadEvent.Finished }, "an unusable URL finishes nothing")
                    assertNotNull(owner.events.filterIsInstance<DownloadEvent.Completed>().single().error)
                }
            }
        }

        clause(
            "RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED",
            DownloadState.RELAUNCHED_WITH_EVENTS,
            covers = cells {
                on<Download> {
                    calls(DownloadHandlers::onBackgroundEvents, Completion::class)
                    calls(DownloadHandlers::onEventsDrained)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
        ) { subject ->
            val relaunch = assertNotNull(subject.relaunch, "a binding that reaches this state holds the relaunch")
            val owner = ClauseDownloadHandlers(subject.readTemp)
            subject.open().listen(owner.handlers)
            relaunch()
            awaitWithin { owner.drainsReported > 0 }
            assertEquals(1, owner.wakesHandedOver, "the relaunch's completion is handed over once")
            assertTrue(
                owner.events.any { it is DownloadEvent.Completed && it.tag == RELAUNCHED_TAG },
                "the transfer that ended while the app was gone is reported, before the drain: ${owner.events}",
            )
        }
    }
}
