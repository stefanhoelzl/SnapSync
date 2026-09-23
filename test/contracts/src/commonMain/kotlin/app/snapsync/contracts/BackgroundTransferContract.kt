package app.snapsync.contracts

import app.snapsync.model.LedgerState
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UploadRequest
import app.snapsync.model.destinationPathOf
import app.snapsync.model.toLedgerRow
import app.snapsync.model.uploadKey
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.CreateResult
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.PlatformUploadJob
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a transfer stands when a clause starts. */
enum class BackgroundTransferState {
    /** Nothing in flight: every creation below the cap. */
    IDLE,

    /** As many transfers in flight, to routes that never answer, as the tier allows at once. */
    AT_CAP,

    /**
     * Nothing in flight, on a tier that offers a transfer the destination refused ONCE for retry before settling it
     * — the PhotoKit tier's single free `.retry`. The URLSession tier settles a refusal at once, so its bindings
     * declare this unreachable; the clauses on it are the PhotoKit tier's own vocabulary, which is why they are
     * conditioned on a state rather than stated for every tier.
     */
    SINGLE_FREE_RETRY,
}

/**
 * The upload tier as a clause receives it (capability `port-contracts`). [transfer] is the port; the rest is what
 * the clause needs that the port does not answer.
 *
 * - [base] is the fixture's address; a route is `base + TransferFixture.path(...)`.
 * - [usable] builds a resource for a key that this tier CAN upload — on a device tier, one backed by a real photo;
 *   [unusable] one it cannot, because its payload is not this tier's resource type.
 * - [ledger] is the ledger the transfer records terminal outcomes through, handed to it as its `TransferRecord`.
 *   It is the transfer's collaborator, not the port under contract (`LedgerStoreContract` licenses it), and a
 *   clause reads a row's state from it — the state reached, never which call reached it.
 * - [objects] is what landed on the fixture.
 */
class TransferUnderTest(
    val transfer: BackgroundTransfer,
    val base: String,
    val usable: suspend (key: String) -> Resource,
    val unusable: (key: String) -> Resource,
    val ledger: LedgerStore,
    val objects: FixtureObjects,
)

/**
 * What every [BackgroundTransfer] owes the upload cycle, whichever tier it is (capability `port-contracts` — this
 * list IS the specification). One contract for both upload tiers, because the port is one interface: a tier's own
 * vocabulary — the PhotoKit tier's single free `.retry` — is not stated here, because the other tier answers it
 * trivially and no clause may be reached only by a fake.
 *
 * Every clause that expects a completion seeds its row `REQUESTED` BEFORE creating the job. The cycle writes
 * `REQUESTED` only after `createJob` returns (write-after-act), and over loopback a completion can arrive first;
 * seeding first keeps the clause deterministic. That window is a finding about the cycle, not a property of the
 * port (decision record: `changes/contract-background-transfers`, Risks).
 *
 * Recording is read "at the latest after the next drain": the PhotoKit tier learns of a terminal job when the
 * system presents it to `drainTerminals`, the URLSession tier the moment its delegate is called. Every wait below
 * therefore drains as it polls, which is what the cycle does every time it runs.
 */
object BackgroundTransferContract : Contract<BackgroundTransferState, TransferUnderTest>("BackgroundTransfer") {

    /** The ledger key a clause uploads under (`<assetId>-<role>.<ext>`), distinct per clause and per [n]. */
    fun key(clauseId: String, n: Int = 1): String =
        uploadKey("contract-${clauseId.lowercase()}-$n", ResourceRole.PRIMARY, "IMG_0001.JPG")

    /** The route a clause's transfer [n] goes to. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String =
        TransferFixture.path(name, clauseId, "upload-$n", answer)

    private val ACCEPT = FixtureAnswer.Respond(200)
    private val REJECT = FixtureAnswer.Respond(500)

    private fun TransferUnderTest.request(path: String, resource: Resource) =
        UploadRequest(url = base + path, headers = mapOf("Content-Type" to resource.contentType), resource = resource)

    /**
     * Seeds the row for [resource] in [state], carrying the destination its transfer to [route] is created with —
     * as the cycle's own record does. A tier that resolves a returned job by its destination (the PhotoKit tier,
     * through `TransferRecord.entryForDestination`) finds no row for a job whose row names none.
     */
    private suspend fun TransferUnderTest.seed(resource: Resource, state: LedgerState, route: String) =
        ledger.recordUnlessSettled(resource.toLedgerRow(state, destinationPath = destinationPathOf(base + route)))

    private suspend fun TransferUnderTest.rowState(key: String): LedgerState? = ledger.get(key)?.state

    /** Waits until [path] landed and the row for [key] reads [state], draining as the cycle does. */
    private suspend fun TransferUnderTest.awaitRecorded(path: String, key: String, state: LedgerState) =
        awaitWithin {
            transfer.drainTerminals()
            objects.landed(path) != null && rowState(key) == state
        }

    /** Waits until the tier offers a refused transfer for [key] for retry, and answers it. */
    private suspend fun TransferUnderTest.awaitOfferedForRetry(key: String): PlatformUploadJob {
        var offered: PlatformUploadJob? = null
        awaitWithin {
            offered = transfer.fetchRetryJobs().firstOrNull { it.key == key }
            offered != null
        }
        return checkNotNull(offered)
    }

    /*
     * The PhotoKit tier's retry, on SINGLE_FREE_RETRY: a refusal is offered once for retry, and a retry refused again
     * is handed up for re-creation once. A retry that then SUCCEEDS has no clause: production retries to the identical
     * destination, and a fixture route answers one status for good (`TransferFixture`), so no route can refuse the
     * first attempt and accept the second.
     */
    override val clauses = clauses {

        clause("CREATE_UNUSABLE_PAYLOAD", BackgroundTransferState.IDLE) { subject ->
            val id = "CREATE_UNUSABLE_PAYLOAD"
            val resource = subject.unusable(key(id))
            val route = path(id, ACCEPT)
            assertEquals(
                CreateResult.FAILED,
                subject.transfer.createJob(subject.request(route, resource), resource),
                "a payload the tier cannot upload is not a job; the caller must not record REQUESTED for it",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "nothing was sent")
        }

        clause("CREATE_BAD_DESTINATION", BackgroundTransferState.IDLE) { subject ->
            val id = "CREATE_BAD_DESTINATION"
            val resource = subject.usable(key(id))
            assertEquals(
                CreateResult.FAILED,
                subject.transfer.createJob(UploadRequest("", mapOf("Content-Type" to resource.contentType), resource), resource),
                "a destination that is not a URL is not a job",
            )
        }

        clause("CREATE_LANDS_AND_RECORDS", BackgroundTransferState.IDLE) { subject ->
            val id = "CREATE_LANDS_AND_RECORDS"
            val resource = subject.usable(key(id))
            val route = path(id, ACCEPT)
            subject.seed(resource, LedgerState.REQUESTED, route)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(route, resource), resource))
            subject.awaitRecorded(route, resource.filename, LedgerState.COMPLETED)
        }

        clause("CREATE_KEEPS_CONTENT_TYPE", BackgroundTransferState.IDLE) { subject ->
            val id = "CREATE_KEEPS_CONTENT_TYPE"
            val resource = subject.usable(key(id))
            val route = path(id, ACCEPT)
            subject.seed(resource, LedgerState.REQUESTED, route)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(route, resource), resource))
            subject.awaitRecorded(route, resource.filename, LedgerState.COMPLETED)
            assertEquals(
                resource.contentType,
                subject.objects.landed(route)?.contentType,
                "the object is stored under the type it was created with, for the rest of its life",
            )
        }

        clause("REJECTED_NEVER_COMPLETES", BackgroundTransferState.IDLE) { subject ->
            val id = "REJECTED_NEVER_COMPLETES"
            val resource = subject.usable(key(id))
            val route = path(id, REJECT)
            subject.seed(resource, LedgerState.REQUESTED, route)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(route, resource), resource))
            transferSettle()
            subject.transfer.drainTerminals()
            transferSettle()
            subject.transfer.drainTerminals()
            assertNotEquals(
                LedgerState.COMPLETED,
                subject.rowState(resource.filename),
                "a destination that refused the bytes is not a completed upload",
            )
        }

        clause("AT_CAP_DEFERS", BackgroundTransferState.AT_CAP) { subject ->
            val id = "AT_CAP_DEFERS"
            val resource = subject.usable(key(id, n = 0))
            val route = path(id, ACCEPT, n = 0)
            assertEquals(
                CreateResult.LIMIT_EXCEEDED,
                subject.transfer.createJob(subject.request(route, resource), resource),
                "at the in-flight cap the tier defers rather than starting another transfer",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "a deferred creation sends nothing")
        }

        clause("TERMINAL_NEEDS_REQUESTED", BackgroundTransferState.IDLE) { subject ->
            val id = "TERMINAL_NEEDS_REQUESTED"
            val resource = subject.usable(key(id))
            val route = path(id, ACCEPT)
            // The row is NOT in flight — another uploader settled it, or a walk demoted it — when this completion
            // arrives. Several writers reach a row with no shared lock, so the record must be the guarded one.
            subject.seed(resource, LedgerState.DISCOVERED, route)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(route, resource), resource))
            awaitWithin { subject.objects.landed(route) != null }
            transferSettle()
            subject.transfer.drainTerminals()
            assertEquals(
                LedgerState.DISCOVERED,
                subject.rowState(resource.filename),
                "a completion records only onto a row still REQUESTED",
            )
        }

        clause("DRAIN_HANDS_UP_NO_SUCCESS", BackgroundTransferState.IDLE) { subject ->
            val id = "DRAIN_HANDS_UP_NO_SUCCESS"
            val resource = subject.usable(key(id))
            val route = path(id, ACCEPT)
            subject.seed(resource, LedgerState.REQUESTED, route)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(route, resource), resource))
            val handedUp = mutableListOf<String>()
            awaitWithin {
                handedUp += subject.transfer.drainTerminals().map { it.key }
                subject.rowState(resource.filename) == LedgerState.COMPLETED
            }
            handedUp += subject.transfer.drainTerminals().map { it.key }
            assertTrue(
                resource.filename !in handedUp,
                "a terminal fact never crosses the seam: a success is recorded in place, not handed up",
            )
        }
    
        clause("REFUSED_IS_OFFERED_FOR_RETRY", BackgroundTransferState.SINGLE_FREE_RETRY) { subject ->
            val id = "REFUSED_IS_OFFERED_FOR_RETRY"
            val resource = subject.usable(key(id))
            val route = path(id, REJECT)
            subject.seed(resource, LedgerState.REQUESTED, route)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(route, resource), resource))
            val offered = subject.awaitOfferedForRetry(resource.filename)
            assertEquals(resource.contentType, offered.contentType, "a retried transfer keeps the type it was created with")
        }

        clause("RETRY_SPENT_IS_HANDED_UP_ONCE", BackgroundTransferState.SINGLE_FREE_RETRY) { subject ->
            val id = "RETRY_SPENT_IS_HANDED_UP_ONCE"
            val resource = subject.usable(key(id))
            val refused = path(id, REJECT)
            subject.seed(resource, LedgerState.REQUESTED, refused)
            assertEquals(CreateResult.CREATED, subject.transfer.createJob(subject.request(refused, resource), resource))
            val offered = subject.awaitOfferedForRetry(resource.filename)
            // The retry goes where production sends it: the IDENTICAL destination (the cycle rebuilds the same edge
            // URL), which is also what keeps the row's recorded destination the job's.
            subject.transfer.retryJob(offered, subject.request(refused, resource))
            val handedUp = mutableListOf<String>()
            awaitWithin {
                handedUp += subject.transfer.drainTerminals().map { it.key }
                resource.filename in handedUp
            }
            transferSettle()
            handedUp += subject.transfer.drainTerminals().map { it.key }
            assertEquals(
                1,
                handedUp.count { it == resource.filename },
                "a transfer whose free retry was refused too is handed up for re-creation once — the tier " +
                    "acknowledged it, so it is not presented again",
            )
            assertNotEquals(LedgerState.COMPLETED, subject.rowState(resource.filename), "a refused transfer is not completed")
        }
}
}
