package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.ResourceRole
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.destinationPathOf
import app.snapsync.model.uploadKey
import app.snapsync.ports.Upload
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the platform's upload jobs stand when a clause starts. */
enum class UploadState {
    /** Nothing in flight: every creation below the limit. */
    IDLE,

    /** As many transfers in flight, to routes that never answer, as the platform takes at once. */
    AT_CAP,

    /*
     * The PhotoKit queue's own vocabulary: a transfer the OS has ALREADY settled and now presents, prepared by the
     * binding before the clause — on a device, across operating-system calls, because a job the upload extension creates
     * is uploaded only after its `process()` call returns (measured, SE2, iOS 26.6). The prepared transfer is the
     * clause's own, to route [UploadContract.preparedRoute]. The URLSession uploader reports a transfer's end at once
     * and offers no free retry, so its bindings declare all three unreachable.
     */

    /** A transfer the destination accepted, presented as succeeded. */
    PRESENTED_SUCCEEDED,

    /** A transfer the destination refused once, presented for its single free retry. */
    PRESENTED_REFUSED_ONCE,

    /** A transfer refused again after its free retry to the identical destination, presented as terminal. */
    PRESENTED_RETRY_SPENT,
}

/**
 * The port as a clause receives it (`docs/architecture.md`). [upload] is the port; the rest is what the clause needs that
 * the port does not answer.
 *
 * - [base] is the fixture's address; a route is `base + TransferFixture.path(...)`.
 * - [usable] builds a source this platform CAN upload for a key — a photo-library resource handle, or a file of the
 *   photo's bytes, whichever it [accepts][Upload.accepts]; [unusable] one it cannot.
 * - [ended] is every job the port reported through its handlers (the binding listened): how a platform that reports
 *   as it happens tells its owner a transfer ended. A platform that presents terminal jobs when asked answers through
 *   [Upload.jobs] instead; a clause reads both.
 * - [objects] is what landed on the fixture.
 */
class UploadUnderTest(
    val upload: Upload,
    val base: String,
    val usable: suspend (key: String) -> UploadSource,
    val unusable: (key: String) -> UploadSource,
    val ended: () -> List<UploadJob>,
    val objects: FixtureObjects,
)

/**
 * What every [Upload] owes the upload services, whichever platform it is (`docs/architecture.md` — this list IS the
 * specification). One contract for both iOS uploaders, because the port is one interface. What a job MEANS for the
 * ledger is the services' (`UploadTransferService`, tested over doubles); what is contracted here is that the port
 * creates what it is asked and refuses what it cannot, reports how a transfer ended truthfully and under the destination
 * it was created with, and presents, retries and acknowledges what it holds.
 *
 * A platform learns a transfer's end one of two ways: the URLSession uploader the moment its delegate is called
 * ([UploadUnderTest.ended]), the PhotoKit queue when it presents a terminal job to [Upload.jobs]. Every wait below reads
 * both, as the services do.
 */
object UploadContract : Contract<UploadState, UploadUnderTest>("Upload") {

    /** The ledger key a clause uploads under (`<assetId>-<role>.<ext>`), distinct per clause and per [n]. */
    fun key(clauseId: String, n: Int = 1): String =
        uploadKey(AssetId("contract-${clauseId.lowercase()}-$n"), ResourceRole.PRIMARY, "IMG_0001.JPG")

    /** The route a clause's transfer [n] goes to. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String =
        TransferFixture.path(name, clauseId, "upload-$n", answer)

    /** The states whose transfer the binding prepares and the OS settles before the clause. */
    val PRESENTED: Set<UploadState> = setOf(
        UploadState.PRESENTED_SUCCEEDED,
        UploadState.PRESENTED_REFUSED_ONCE,
        UploadState.PRESENTED_RETRY_SPENT,
    )

    /** The route a presented state's prepared transfer goes to: accepted for a success, refused otherwise. */
    fun preparedRoute(clauseId: String, state: UploadState): String =
        path(clauseId, if (state == UploadState.PRESENTED_SUCCEEDED) ACCEPT else REJECT)

    /** The type every clause's transfer declares. */
    const val CONTENT_TYPE = "image/jpeg"

    private val ACCEPT = FixtureAnswer.Respond(200)
    private val REJECT = FixtureAnswer.Respond(500)

    private fun UploadUnderTest.target(route: String) = UploadTarget(base + route, mapOf("Content-Type" to CONTENT_TYPE))

    private fun UploadUnderTest.destination(route: String) = destinationPathOf(base + route)

    /** Every job the port has told of for [route]: reported through its handlers, or presented in either set. */
    private suspend fun UploadUnderTest.jobsAt(route: String): List<UploadJob> {
        val path = destination(route)
        return (ended() + upload.jobs(UploadJobSet.TERMINAL) + upload.jobs(UploadJobSet.RETRY_OFFERED))
            .filter { it.destinationPath == path }
    }

    private suspend fun UploadUnderTest.presented(set: UploadJobSet, route: String): UploadJob? =
        upload.jobs(set).firstOrNull { it.destinationPath == destination(route) }

    override val clauses = clauses {

        clause("CREATE_UNUSABLE_SOURCE", UploadState.IDLE) { subject ->
            val id = "CREATE_UNUSABLE_SOURCE"
            val route = path(id, ACCEPT)
            assertEquals(
                UploadCreateOutcome.FAILED,
                subject.upload.create(subject.unusable(key(id)), subject.target(route), key(id)),
                "a source the platform cannot upload is not a job; the caller must not record REQUESTED for it",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "nothing was sent")
        }

        clause("CREATE_LANDS_AND_ENDS_SUCCEEDED", UploadState.IDLE) { subject ->
            val id = "CREATE_LANDS_AND_ENDS_SUCCEEDED"
            val route = path(id, ACCEPT)
            assertEquals(UploadCreateOutcome.CREATED, subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)))
            awaitWithin {
                subject.objects.landed(route) != null &&
                    subject.jobsAt(route).any { it.state == UploadJobState.SUCCEEDED }
            }
            val ended = subject.jobsAt(route).first { it.state == UploadJobState.SUCCEEDED }
            assertEquals(subject.destination(route), ended.destinationPath, "a job is told of under its destination")
            assertTrue(ended.tag == null || ended.tag == key(id), "and, where the platform keeps one, its tag")
            assertEquals(ChangeOutcome.Applied, subject.upload.acknowledge(ended), "a presented end is acknowledged")
        }

        clause("CREATE_KEEPS_CONTENT_TYPE", UploadState.IDLE) { subject ->
            val id = "CREATE_KEEPS_CONTENT_TYPE"
            val route = path(id, ACCEPT)
            assertEquals(UploadCreateOutcome.CREATED, subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)))
            awaitWithin { subject.objects.landed(route) != null }
            assertEquals(
                CONTENT_TYPE,
                subject.objects.landed(route)?.contentType,
                "the object is stored under the type it was created with, for the rest of its life",
            )
        }

        clause("REFUSED_NEVER_ENDS_SUCCEEDED", UploadState.IDLE) { subject ->
            val id = "REFUSED_NEVER_ENDS_SUCCEEDED"
            val route = path(id, REJECT)
            assertEquals(UploadCreateOutcome.CREATED, subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)))
            awaitWithin { subject.jobsAt(route).isNotEmpty() }
            transferSettle()
            assertTrue(
                subject.jobsAt(route).none { it.state == UploadJobState.SUCCEEDED },
                "a destination that refused the bytes is not a completed upload",
            )
        }

        clause("AT_CAP_DEFERS", UploadState.AT_CAP) { subject ->
            val id = "AT_CAP_DEFERS"
            val route = path(id, ACCEPT, n = 0)
            assertEquals(
                UploadCreateOutcome.LIMIT_EXCEEDED,
                subject.upload.create(subject.usable(key(id, n = 0)), subject.target(route), key(id, n = 0)),
                "at the in-flight limit the platform defers rather than starting another transfer",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "a deferred creation sends nothing")
        }

        /*
         * The PRESENTED_* clauses act on a transfer the OS already settled — they create nothing, so none waits on an
         * upload. A retry that then SUCCEEDS has no clause: production retries to the identical destination, and a
         * fixture route answers one status for good (`TransferFixture`).
         */
        clause("PRESENTED_SUCCESS_IS_PRESENTED_UNTIL_ACKNOWLEDGED", UploadState.PRESENTED_SUCCEEDED) { subject ->
            val id = "PRESENTED_SUCCESS_IS_PRESENTED_UNTIL_ACKNOWLEDGED"
            val route = preparedRoute(id, UploadState.PRESENTED_SUCCEEDED)
            val presented = assertNotNull(subject.presented(UploadJobSet.TERMINAL, route), "a settled success is presented")
            assertEquals(UploadJobState.SUCCEEDED, presented.state)
            assertEquals(CONTENT_TYPE, presented.contentType, "under the type it was created with")
            assertEquals(ChangeOutcome.Applied, subject.upload.acknowledge(presented))
            assertNull(subject.presented(UploadJobSet.TERMINAL, route), "an acknowledged job is not presented again")
            assertEquals(CONTENT_TYPE, subject.objects.landed(route)?.contentType, "the object landed under its type")
        }

        clause("PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY", UploadState.PRESENTED_REFUSED_ONCE) { subject ->
            val id = "PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY"
            val route = preparedRoute(id, UploadState.PRESENTED_REFUSED_ONCE)
            val offered = assertNotNull(
                subject.presented(UploadJobSet.RETRY_OFFERED, route),
                "a transfer the destination refused once is offered for its free retry",
            )
            assertNotEquals(UploadJobState.SUCCEEDED, offered.state, "a refused transfer is not a success")
            assertEquals(CONTENT_TYPE, offered.contentType, "a retried transfer keeps the type it was created with")
        }

        clause("PRESENTED_RETRY_SPENT_IS_PRESENTED_UNTIL_ACKNOWLEDGED", UploadState.PRESENTED_RETRY_SPENT) { subject ->
            val id = "PRESENTED_RETRY_SPENT_IS_PRESENTED_UNTIL_ACKNOWLEDGED"
            val route = preparedRoute(id, UploadState.PRESENTED_RETRY_SPENT)
            val presented = assertNotNull(
                subject.presented(UploadJobSet.TERMINAL, route),
                "a transfer whose free retry was refused too is presented as terminal",
            )
            assertNotEquals(UploadJobState.SUCCEEDED, presented.state, "a refused transfer is not a success")
            assertEquals(ChangeOutcome.Applied, subject.upload.acknowledge(presented))
            assertNull(subject.presented(UploadJobSet.TERMINAL, route), "an acknowledged job is not presented again")
        }
    }
}
