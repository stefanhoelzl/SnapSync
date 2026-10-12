package app.snapsync.contracts

import app.snapsync.model.destinationPathOf
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadJobHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.UploadJobState
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Where the OS's upload jobs stand when a clause starts. */
enum class OsUploadQueueState {
    /** Nothing in flight: every creation below the limit. */
    IDLE,

    /** As many jobs in flight as the queue takes at once. */
    AT_CAP,

    /*
     * A job the OS has ALREADY settled and now presents, prepared by the binding before the clause — on a device,
     * across operating-system calls, because a job the upload extension creates is uploaded only after its `process()`
     * call returns (measured, SE2, iOS 26.6). The prepared job is the clause's own, to [OsUploadQueueContract.preparedRoute].
     */

    /** A job the destination accepted, presented as succeeded. */
    PRESENTED_SUCCEEDED,

    /** A job the destination refused once, presented for its single free retry. */
    PRESENTED_REFUSED_ONCE,

    /** A job refused again after its free retry to the identical destination, presented as terminal. */
    PRESENTED_RETRY_SPENT,

    /**
     * Any queue, as it stands: the clause prepares nothing and asks the system nothing — what it hands the queue is not
     * the queue's (a resource of another library, a job it never presented), which the queue answers itself.
     */
    ANY,
}

/**
 * The queue as a clause receives it. [queue] is the port; [usable] is a library resource the queue CAN send for a key,
 * [unusable] one it cannot.
 */
class QueueUnderTest(
    val queue: OsUploadQueue,
    /** The fixture's address; a route is `base + TransferFixture.path(...)`. */
    val base: String,
    val usable: suspend (key: String) -> GalleryResourceHandle,
    val unusable: (key: String) -> GalleryResourceHandle,
    /** What landed on the fixture. */
    val objects: FixtureObjects,
)

/**
 * What every [OsUploadQueue] owes the upload services (`docs/architecture.md` — this list IS the specification). What a
 * job MEANS for the ledger is the services' (`OsQueueTransfer`, tested over doubles); what is contracted here is that
 * the queue makes what it is asked and refuses what it cannot, defers at its limit, presents each settled job under the
 * destination and type it was made with until acknowledged, offers a first refusal for its one free retry, and answers
 * only for the jobs it presented.
 *
 * A job the queue makes is uploaded by the OS, on its own schedule — inside the upload extension, only after the call
 * that made it returns — so no clause makes a job and waits for it: what becomes of a made job is the PRESENTED states'.
 */
object OsUploadQueueContract : Contract<OsUploadQueueState, QueueUnderTest>("OsUploadQueue") {

    /** The states whose job the binding prepares and the OS settles before the clause. */
    val PRESENTED: Set<OsUploadQueueState> = setOf(
        OsUploadQueueState.PRESENTED_SUCCEEDED,
        OsUploadQueueState.PRESENTED_REFUSED_ONCE,
        OsUploadQueueState.PRESENTED_RETRY_SPENT,
    )

    /** The route [clauseId]'s upload [n] goes to. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String = uploadClausePath(name, clauseId, answer, n)

    /** The route a presented state's prepared job goes to: accepted for a success, refused otherwise. */
    fun preparedRoute(clauseId: String, state: OsUploadQueueState): String =
        path(clauseId, if (state == OsUploadQueueState.PRESENTED_SUCCEEDED) UPLOAD_ACCEPT else UPLOAD_REJECT)

    private fun key(clauseId: String, n: Int = 1) = uploadClauseKey(clauseId, n)

    /** Whether [job] was made towards [route]: by its destination, the one fact the OS keeps for it. */
    private fun QueueUnderTest.isAt(job: OsUploadJob, route: String) =
        job.destinationUrl?.let(::destinationPathOf) == uploadDestination(base, route)

    private suspend fun QueueUnderTest.presented(terminal: Boolean, route: String): OsUploadJob? =
        (if (terminal) queue.terminals() else queue.retryOffered()).firstOrNull { isAt(it, route) }

    /** A job no queue presented: the queue's own answer for it is a refusal. */
    private fun QueueUnderTest.neverPresented(id: String) = OsUploadJob(
        handle = object : OsUploadJobHandle {},
        destinationUrl = base + path(id, UPLOAD_ACCEPT),
        contentType = UPLOAD_CONTENT_TYPE,
        state = UploadJobState.FAILED,
        failure = null,
        resource = null,
    )

    override val clauses = clauses {

        clause(
            "CREATE_MAKES_A_JOB",
            OsUploadQueueState.IDLE,
            covers = cells { on<OsUploadQueue>().answers(OsUploadQueue::create).with(CreateOutcome.CREATED) },
        ) { subject ->
            val id = "CREATE_MAKES_A_JOB"
            assertEquals(
                CreateOutcome.CREATED,
                subject.queue.create(subject.usable(key(id)), uploadTarget(subject.base, path(id, UPLOAD_ACCEPT))),
                "a resource the queue can send is made a job",
            )
        }

        clause(
            "AT_CAP_DEFERS",
            OsUploadQueueState.AT_CAP,
            covers = cells { on<OsUploadQueue>().answers(OsUploadQueue::create).with(CreateOutcome.LIMIT_EXCEEDED) },
        ) { subject ->
            val id = "AT_CAP_DEFERS"
            val route = path(id, UPLOAD_ACCEPT, n = 0)
            assertEquals(
                CreateOutcome.LIMIT_EXCEEDED,
                subject.queue.create(subject.usable(key(id, n = 0)), uploadTarget(subject.base, route)),
                "at its in-flight limit the queue defers rather than making another job",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "a deferred creation sends nothing")
        }

        clause(
            "CREATE_UNUSABLE_SOURCE",
            OsUploadQueueState.ANY,
            covers = cells { on<OsUploadQueue>().answers(OsUploadQueue::create).with(CreateOutcome.FAILED) },
        ) { subject ->
            val id = "CREATE_UNUSABLE_SOURCE"
            assertEquals(
                CreateOutcome.FAILED,
                subject.queue.create(subject.unusable(key(id)), uploadTarget(subject.base, path(id, UPLOAD_ACCEPT))),
                "a resource the queue cannot send is no job; the caller must not record REQUESTED for it",
            )
        }

        clause(
            "ANY_REFUSES_TO_ACKNOWLEDGE_A_JOB_IT_NEVER_PRESENTED",
            OsUploadQueueState.ANY,
            covers = cells {
                on<OsUploadQueue>().answers(OsUploadQueue::acknowledge).with(ChangeOutcome.Refused::class)
            },
        ) { subject ->
            assertIs<ChangeOutcome.Refused>(
                subject.queue.acknowledge(
                    subject.neverPresented("ANY_REFUSES_TO_ACKNOWLEDGE_A_JOB_IT_NEVER_PRESENTED"),
                ),
                "only a job the queue presented is owed an acknowledgement; another is refused, not taken as dealt with",
            )
        }

        clause(
            "ANY_REFUSES_TO_RETRY_A_JOB_IT_NEVER_OFFERED",
            OsUploadQueueState.ANY,
            covers = cells { on<OsUploadQueue>().answers(OsUploadQueue::retry).with(ChangeOutcome.Refused::class) },
        ) { subject ->
            val id = "ANY_REFUSES_TO_RETRY_A_JOB_IT_NEVER_OFFERED"
            assertIs<ChangeOutcome.Refused>(
                subject.queue.retry(subject.neverPresented(id), uploadTarget(subject.base, path(id, UPLOAD_ACCEPT))),
                "only a job the queue offered can take its free retry; another is refused, not re-pointed",
            )
        }

        /*
         * The PRESENTED_* clauses act on a job the OS already settled — they create nothing, so none waits on an upload.
         * A retry is taken, and what it then does has no clause: production retries to the identical destination, and a
         * fixture route answers one status for good (`TransferFixture`), so the retried job is refused again and
         * presented as spent — which [OsUploadQueueState.PRESENTED_RETRY_SPENT] holds.
         */
        clause(
            "PRESENTED_SUCCESS_IS_PRESENTED_UNTIL_ACKNOWLEDGED",
            OsUploadQueueState.PRESENTED_SUCCEEDED,
            covers = cells {
                on<OsUploadQueue> {
                    answers(OsUploadQueue::terminals).returns()
                    answers(OsUploadQueue::acknowledge).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "PRESENTED_SUCCESS_IS_PRESENTED_UNTIL_ACKNOWLEDGED"
            val route = preparedRoute(id, OsUploadQueueState.PRESENTED_SUCCEEDED)
            val presented = assertNotNull(subject.presented(terminal = true, route), "a settled success is presented")
            assertEquals(UploadJobState.SUCCEEDED, presented.state)
            assertEquals(UPLOAD_CONTENT_TYPE, presented.contentType, "under the type it was made with")
            assertEquals(ChangeOutcome.Applied, subject.queue.acknowledge(presented))
            assertNull(subject.presented(terminal = true, route), "an acknowledged job is not presented again")
            assertEquals(
                UPLOAD_CONTENT_TYPE,
                subject.objects.landed(route)?.contentType,
                "the object landed under its type",
            )
        }

        clause(
            "PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY",
            OsUploadQueueState.PRESENTED_REFUSED_ONCE,
            covers = cells {
                on<OsUploadQueue> {
                    answers(OsUploadQueue::retryOffered).returns()
                    answers(OsUploadQueue::retry).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY"
            val route = preparedRoute(id, OsUploadQueueState.PRESENTED_REFUSED_ONCE)
            val offered = assertNotNull(
                subject.presented(terminal = false, route),
                "a job the destination refused once is offered for its free retry",
            )
            assertNotEquals(UploadJobState.SUCCEEDED, offered.state, "a refused job is not a success")
            assertEquals(UPLOAD_CONTENT_TYPE, offered.contentType, "a retried job keeps the type it was made with")
            assertEquals(
                ChangeOutcome.Applied,
                subject.queue.retry(offered, uploadTarget(subject.base, route)),
                "the free retry it is offered is taken",
            )
        }

        clause(
            "PRESENTED_RETRY_SPENT_IS_PRESENTED_UNTIL_ACKNOWLEDGED",
            OsUploadQueueState.PRESENTED_RETRY_SPENT,
            covers = cells {
                on<OsUploadQueue> {
                    answers(OsUploadQueue::terminals).returns()
                    answers(OsUploadQueue::acknowledge).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "PRESENTED_RETRY_SPENT_IS_PRESENTED_UNTIL_ACKNOWLEDGED"
            val route = preparedRoute(id, OsUploadQueueState.PRESENTED_RETRY_SPENT)
            val presented = assertNotNull(
                subject.presented(terminal = true, route),
                "a job whose free retry was refused too is presented as terminal",
            )
            assertNotEquals(UploadJobState.SUCCEEDED, presented.state, "a refused job is not a success")
            assertEquals(ChangeOutcome.Applied, subject.queue.acknowledge(presented))
            assertNull(subject.presented(terminal = true, route), "an acknowledged job is not presented again")
        }
    }
}
