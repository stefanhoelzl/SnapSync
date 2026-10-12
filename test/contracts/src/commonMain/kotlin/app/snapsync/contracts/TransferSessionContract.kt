@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.contracts

import app.snapsync.ports.Completion
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferHandle
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the app's transfer session stands when a clause starts. */
enum class TransferSessionState {
    /** Nothing in flight: every creation below the limit. */
    IDLE,

    /** As many transfers in flight, to routes that never answer, as the session takes at once. */
    AT_CAP,

    /** Nothing in flight, and the device on a restricted network the binding can lift. */
    RESTRICTED_NETWORK,

    /** Nothing in flight, on a platform whose session takes the library's own resources and also a file. */
    TAKES_RESOURCES_AND_FILES,

    /** Nothing in flight, on a platform whose session takes only a file of the photo's bytes. */
    TAKES_FILES,

    /** Nothing in flight: any session reports each transfer's end as it happens. */
    REPORTS_AS_IT_HAPPENS,

    /** Nothing in flight, on a platform whose session reads a file it is handed from the device's disk. */
    READS_FILES_FROM_DISK,

    /**
     * The app relaunched by the operating system in the background to deliver its session's events: a transfer the
     * binding prepared — tagged [TransferSessionContract.relaunchedTag] — ended while the app was not running, and the
     * relaunch is held for the clause to hand over ([SessionUnderTest.relaunch]). A device's, recorded across the two
     * processes: no CI host's session outlives its app.
     */
    RELAUNCHED_WITH_EVENTS,
}

/** One end the session told its owner of: the [tag] it carried, and the [failure], `null` for a success. */
class Ended(val tag: String, val failure: TransferFailure?)

/**
 * The session as a clause receives it (`docs/architecture.md`). [session] is the port; [usable] builds a source the
 * session CAN send for a key — a library resource or a file of the photo's bytes, whichever it takes — and [unusable]
 * one it cannot; [ended] is every end the session told the binding, which listened.
 */
class SessionUnderTest(
    val session: TransferSession,
    /** The fixture's address; a route is `base + TransferFixture.path(...)`. */
    val base: String,
    val usable: suspend (key: String) -> TransferSource,
    val unusable: (key: String) -> TransferSource,
    val ended: () -> List<Ended>,
    /** What landed on the fixture. */
    val objects: FixtureObjects,
    /** Puts the device back on an unrestricted network; required of a binding that reaches a restricted one. */
    val liftRestriction: (suspend () -> Unit)? = null,
    /** A file of the photo's bytes for a key; required of a binding that reaches [TransferSessionState.TAKES_RESOURCES_AND_FILES]. */
    val fileSource: (suspend (key: String) -> TransferSource)? = null,
    /** The relaunch the operating system made; required of a binding that reaches [TransferSessionState.RELAUNCHED_WITH_EVENTS]. */
    val relaunch: Relaunch? = null,
) {
    /** The target an upload to [route] declares. */
    fun target(route: String, network: TransferNetwork = TransferNetwork.ANY) = uploadTarget(base, route, network)

    /** Every end the session told of the transfer tagged [key]: `true` for a success. */
    fun endsOf(key: String): List<Boolean> = ended().filter { it.tag == key }.map { it.failure == null }
}

/**
 * A relaunch the operating system made to deliver a session's events, held by the binding — which listened to the port
 * — until the clause hands it over.
 */
class Relaunch(
    /** Hands the port the relaunch, with the system's completion handler, as the app's delegate does. */
    val deliver: () -> Unit,
    /** Every completion the port handed its owner for a relaunch, in order. */
    val handedOver: () -> List<Completion>,
    /** How many drain reports the port gave its owner. */
    val drains: () -> Int,
)

/**
 * What every [TransferSession] owes the upload services (`docs/architecture.md` — this list IS the specification). What
 * an end MEANS for the ledger is the services' (`SessionTransfer`, tested over doubles); what is contracted here is that
 * the session starts what it is asked and refuses what it cannot, tells each end once, as it happens, under the tag the
 * transfer was made with, stops a live transfer when asked, and hands a relaunch's events over and then drains.
 */
object TransferSessionContract : Contract<TransferSessionState, SessionUnderTest>("TransferSession") {

    /** The route [clauseId]'s upload [n] goes to. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String = uploadClausePath(name, clauseId, answer, n)

    /** The tag a relaunched state's prepared transfer was made under, which its end is told with. */
    fun relaunchedTag(clauseId: String): String = uploadClauseKey(clauseId)

    /** A file no device holds: no host has a `/nonexistent` directory. */
    private const val MISSING_FILE = "/nonexistent/snapsync-contract/missing.bin"

    private fun key(clauseId: String, n: Int = 1) = uploadClauseKey(clauseId, n)

    override val clauses = clauses {

        fun created(outcome: CreateOutcome) = cells {
            on<TransferSession>().answers(
                TransferSession::create,
            ).with(outcome)
        }
        val landed = cells {
            on<TransferSession> {
                answers(TransferSession::create).with(CreateOutcome.CREATED)
                calls(TransferHandlers::onFinished, String::class, null)
            }
        }

        clause("CREATE_UNUSABLE_SOURCE", TransferSessionState.IDLE, created(CreateOutcome.FAILED)) { subject ->
            val id = "CREATE_UNUSABLE_SOURCE"
            val route = path(id, UPLOAD_ACCEPT)
            assertEquals(
                CreateOutcome.FAILED,
                subject.session.create(subject.unusable(key(id)), subject.target(route), key(id)),
                "a source the session cannot send is not a transfer; the caller must not record REQUESTED for it",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "nothing was sent")
        }

        clause(
            "CREATE_FROM_A_MISSING_FILE_IS_REFUSED_NEVER_AN_ABORT",
            TransferSessionState.READS_FILES_FROM_DISK,
            created(CreateOutcome.FAILED),
        ) { subject ->
            val id = "CREATE_FROM_A_MISSING_FILE_IS_REFUSED_NEVER_AN_ABORT"
            val route = path(id, UPLOAD_ACCEPT)
            // iOS's background session raises an Objective-C exception for a file it cannot read — one no Kotlin catch
            // stops, so the process would abort: the session must find out first and refuse.
            assertEquals(
                CreateOutcome.FAILED,
                subject.session.create(TransferSource.File(MISSING_FILE), subject.target(route), key(id)),
                "a file that is not there is not a transfer",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "nothing was sent")
        }

        clause("CREATE_LANDS_AND_ENDS_SUCCEEDED", TransferSessionState.IDLE, landed) { subject ->
            val id = "CREATE_LANDS_AND_ENDS_SUCCEEDED"
            val route = path(id, UPLOAD_ACCEPT)
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.objects.landed(route) != null && subject.endsOf(key(id)).any { it } }
        }

        heldUntilUnrestricted(
            TransferSessionState.RESTRICTED_NETWORK,
            landed,
            route = { id -> path(id, UPLOAD_ACCEPT) },
        ) {
                subject ->
            heldSession(subject)
        }

        clause("CREATE_KEEPS_CONTENT_TYPE", TransferSessionState.IDLE, created(CreateOutcome.CREATED)) { subject ->
            val id = "CREATE_KEEPS_CONTENT_TYPE"
            val route = path(id, UPLOAD_ACCEPT)
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.objects.landed(route) != null }
            assertEquals(
                UPLOAD_CONTENT_TYPE,
                subject.objects.landed(route)?.contentType,
                "the object is stored under the type it was created with, for the rest of its life",
            )
        }

        clause(
            "REFUSED_NEVER_ENDS_SUCCEEDED",
            TransferSessionState.IDLE,
            cells {
                on<TransferSession> {
                    answers(TransferSession::create).with(CreateOutcome.CREATED)
                    calls(TransferHandlers::onFinished, String::class, TransferFailure::class)
                }
            },
        ) { subject ->
            val id = "REFUSED_NEVER_ENDS_SUCCEEDED"
            val route = path(id, UPLOAD_REJECT)
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.endsOf(key(id)).isNotEmpty() }
            transferSettle()
            assertTrue(
                subject.endsOf(key(id)).none { it },
                "a destination that refused the bytes is not a completed upload",
            )
        }

        clause("AT_CAP_DEFERS", TransferSessionState.AT_CAP, created(CreateOutcome.LIMIT_EXCEEDED)) { subject ->
            val id = "AT_CAP_DEFERS"
            val route = path(id, UPLOAD_ACCEPT, n = 0)
            assertEquals(
                CreateOutcome.LIMIT_EXCEEDED,
                subject.session.create(subject.usable(key(id, n = 0)), subject.target(route), key(id, n = 0)),
                "at its in-flight limit the session defers rather than starting another transfer",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "a deferred creation sends nothing")
        }

        clause(
            "TAKES_RESOURCES_AND_FILES_LANDS_BOTH",
            TransferSessionState.TAKES_RESOURCES_AND_FILES,
            covers = cells {
                on<TransferSession> {
                    answers(TransferSession::takesResources).with(true)
                    answers(TransferSession::create).with(CreateOutcome.CREATED)
                }
            },
        ) { subject ->
            val id = "TAKES_RESOURCES_AND_FILES_LANDS_BOTH"
            val file = assertNotNull(subject.fileSource, "a binding that reaches this state can make a file source")
            assertTrue(subject.session.takesResources, "the library's own resource is what it takes")
            val resource = path(id, UPLOAD_ACCEPT, n = 1)
            val sealed = path(id, UPLOAD_ACCEPT, n = 2)
            val source = subject.usable(key(id, n = 1))
            assertIs<TransferSource.Resource>(source, "what it takes is a resource")
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(source, subject.target(resource), key(id, n = 1)),
            )
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(file(key(id, n = 2)), subject.target(sealed), key(id, n = 2)),
                "and a file too, so an encrypted event's upload can be sealed first",
            )
            awaitWithin { subject.objects.landed(resource) != null && subject.objects.landed(sealed) != null }
        }

        clause(
            "TAKES_FILES_LANDS_A_FILE",
            TransferSessionState.TAKES_FILES,
            covers = cells {
                on<TransferSession> {
                    answers(TransferSession::takesResources).with(false)
                    answers(TransferSession::create).with(CreateOutcome.CREATED)
                }
            },
        ) { subject ->
            val id = "TAKES_FILES_LANDS_A_FILE"
            assertFalse(subject.session.takesResources, "the photo's bytes are exported to a file first")
            val source = subject.usable(key(id))
            assertIs<TransferSource.File>(source, "what it takes is a file")
            val route = path(id, UPLOAD_ACCEPT)
            assertEquals(CreateOutcome.CREATED, subject.session.create(source, subject.target(route), key(id)))
            awaitWithin { subject.objects.landed(route) != null }
        }

        relaunchHandedOverThenDrained(
            TransferSessionState.RELAUNCHED_WITH_EVENTS,
            covers = cells {
                on<TransferSession> {
                    calls(TransferHandlers::onBackgroundEvents, Completion::class)
                    calls(TransferHandlers::onFinished, String::class, TransferFailure::class)
                    calls(TransferHandlers::onEventsDrained)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
            tag = relaunchedTag("RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED"),
        ) { subject -> relaunchedSession(subject) }

        clause(
            "REPORTS_AS_IT_HAPPENS_AN_END_REACHES_THE_HANDLER",
            TransferSessionState.REPORTS_AS_IT_HAPPENS,
            covers = cells {
                on<TransferSession> {
                    answers(TransferSession::listen).returns()
                    answers(TransferSession::create).with(CreateOutcome.CREATED)
                    calls(TransferHandlers::onFinished, String::class, null)
                }
            },
        ) { subject ->
            val id = "REPORTS_AS_IT_HAPPENS_AN_END_REACHES_THE_HANDLER"
            val route = path(id, UPLOAD_ACCEPT)
            // The clause's own owner, listened last: the session tells whoever listened, not whoever listened first.
            val told = AtomicReference<List<Ended>>(emptyList())
            subject.session.listen(
                TransferHandlers(
                    onFinished = { tag, failure -> told.update { it + Ended(tag, failure) } },
                    onBackgroundEvents = { it.complete() },
                    onEventsDrained = {},
                ),
            )
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { told.load().any { it.tag == key(id) && it.failure == null } }
        }

        clause(
            "REPORTS_AS_IT_HAPPENS_A_LIVE_TRANSFER_IS_CANCELLED",
            TransferSessionState.REPORTS_AS_IT_HAPPENS,
            covers = cells {
                on<TransferSession> {
                    answers(TransferSession::create).with(CreateOutcome.CREATED)
                    answers(TransferSession::live).returns()
                    answers(TransferSession::cancel).returns()
                }
            },
        ) { subject ->
            val id = "REPORTS_AS_IT_HAPPENS_A_LIVE_TRANSFER_IS_CANCELLED"
            val route = path(id, FixtureAnswer.Hold)
            assertEquals(
                CreateOutcome.CREATED,
                subject.session.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.session.live().any { it.tag == key(id) } }
            subject.session.cancel(subject.session.live().first { it.tag == key(id) })
            awaitWithin { subject.ended().any { it.tag == key(id) } }
            assertTrue(
                subject.ended().none { it.tag == key(id) && it.failure == null },
                "a stopped transfer is told of, and never as a success",
            )
            // A transfer the session never made: stopping it stops nothing, and tells of no end.
            subject.session.cancel(Transfer(object : TransferHandle {}, key(id, n = 2)))
            transferSettle()
            assertTrue(subject.ended().none { it.tag == key(id, n = 2) }, "a transfer never made has no end to tell")
        }
    }
}

/** The session as the shared hold clause drives it: a transfer's end is its told success and its bytes landed. */
private fun heldSession(subject: SessionUnderTest): HeldSession {
    val routes = mutableMapOf<String, String>()
    return object : HeldSession {
        override suspend fun start(route: String, tag: String, network: TransferNetwork): Boolean {
            routes[tag] = route
            return subject.session.create(subject.usable(tag), subject.target(route, network), tag) == CreateOutcome.CREATED
        }

        override suspend fun endOf(tag: String): Boolean? {
            val ends = subject.endsOf(tag).ifEmpty { return null }
            return ends.any { it } && routes[tag]?.let { subject.objects.landed(it) } != null
        }

        override val liftRestriction = subject.liftRestriction
    }
}

/** The session the OS relaunched the app for: the binding holds what the port handed over until the clause releases. */
private fun relaunchedSession(subject: SessionUnderTest): RelaunchedSession {
    val relaunch = assertNotNull(subject.relaunch, "a binding that reaches this state holds the relaunch")
    return object : RelaunchedSession {
        override fun deliver() = relaunch.deliver()
        override fun handedOver() = relaunch.handedOver().size
        override fun drains() = relaunch.drains()
        override fun toldTimes(tag: String) = subject.ended().count { it.tag == tag }
        override fun release() = relaunch.handedOver().forEach { it.complete() }
    }
}
