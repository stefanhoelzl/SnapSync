package app.snapsync.contracts

import app.snapsync.model.LedgerState
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UploadRequest
import app.snapsync.model.destinationPathOf
import app.snapsync.model.toLedgerRow
import app.snapsync.model.uploadKey
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.model.CreateResult
import app.snapsync.ports.LedgerStore
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

    /*
     * The PhotoKit tier's own vocabulary: a transfer the OS has ALREADY settled and now presents, prepared by the
     * binding before the clause — on a device, across operating-system calls, because a job the upload extension
     * creates is uploaded only after its `process()` call returns (measured, SE2, iOS 26.6). The prepared transfer
     * is the clause's own: key [BackgroundTransferContract.key], route [BackgroundTransferContract.preparedRoute],
     * its row seeded `REQUESTED` with that destination. The URLSession tier settles a transfer at once and offers no
     * free retry, so its bindings declare all three unreachable.
     */

    /** A transfer the destination accepted, presented as succeeded. */
    PRESENTED_SUCCEEDED,

    /** A transfer the destination refused once, presented for its single free retry (and for acknowledgement). */
    PRESENTED_REFUSED_ONCE,

    /** A transfer refused again after its free retry to the identical destination, presented as retry-spent. */
    PRESENTED_RETRY_SPENT,
}

/**
 * The upload tier as a clause receives it (`docs/architecture.md`). [transfer] is the port; the rest is what
 * the clause needs that the port does not answer.
 *
 * - [base] is the fixture's address; a route is `base + TransferFixture.path(...)`.
 * - [usable] builds a resource for a key that this tier CAN upload — on a device tier, one backed by a real photo;
 *   [unusable] one it cannot, because its payload is not this tier's resource type.
 * - [ledger] is the ledger the transfer records terminal outcomes through, handed to it as its `TransferRecord`.
 *   It is the transfer's collaborator, not the port under contract (`LedgerStoreContract` licenses it), and a
 *   clause reads a row's state from it — the state reached, never which call reached it.
 * - [objects] is what landed on the fixture.
 * - [presentedKey] is the key a PRESENTED state's prepared transfer was created under. A tier that re-creates a
 *   retry-spent transfer from the photo its key names needs a key that names a real photo, which only the binding can
 *   supply; it defaults to the contract's own derivation.
 */
class TransferUnderTest(
    val transfer: BackgroundTransfer,
    val base: String,
    val usable: suspend (key: String) -> Resource,
    val unusable: (key: String) -> Resource,
    val ledger: LedgerStore,
    val objects: FixtureObjects,
    val presentedKey: (clauseId: String) -> String = { BackgroundTransferContract.key(it) },
)

/**
 * What every [BackgroundTransfer] owes the upload cycle, whichever tier it is (`docs/architecture.md` — this
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

    /** The states whose transfer the binding prepares and the OS settles before the clause. */
    val PRESENTED: Set<BackgroundTransferState> = setOf(
        BackgroundTransferState.PRESENTED_SUCCEEDED,
        BackgroundTransferState.PRESENTED_REFUSED_ONCE,
        BackgroundTransferState.PRESENTED_RETRY_SPENT,
    )

    /** The route a presented state's prepared transfer goes to: accepted for a success, refused otherwise. */
    fun preparedRoute(clauseId: String, state: BackgroundTransferState): String =
        path(clauseId, if (state == BackgroundTransferState.PRESENTED_SUCCEEDED) ACCEPT else REJECT)

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

    /*
     * The PRESENTED_* clauses act on a transfer the OS already settled — they create nothing, so none waits on an
     * upload. A retry that then SUCCEEDS has no clause: production retries to the identical destination, and a
     * fixture route answers one status for good (`TransferFixture`), so no route can refuse the first attempt and
     * accept the second.
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
    
        clause("PRESENTED_SUCCESS_IS_RECORDED_IN_PLACE", BackgroundTransferState.PRESENTED_SUCCEEDED) { subject ->
            val id = "PRESENTED_SUCCESS_IS_RECORDED_IN_PLACE"
            val key = subject.presentedKey(id)
            val handedUp = subject.transfer.drainTerminals().map { it.key }
            assertEquals(LedgerState.COMPLETED, subject.rowState(key), "a presented success is recorded COMPLETED by the drain")
            assertTrue(key !in handedUp, "a terminal fact never crosses the seam: a success is recorded in place, not handed up")
            assertEquals(
                "image/jpeg",
                subject.objects.landed(preparedRoute(id, BackgroundTransferState.PRESENTED_SUCCEEDED))?.contentType,
                "the object landed under the type it was created with",
            )
        }

        clause("PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY", BackgroundTransferState.PRESENTED_REFUSED_ONCE) { subject ->
            val id = "PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY"
            val key = subject.presentedKey(id)
            val offered = subject.transfer.fetchRetryJobs().firstOrNull { it.key == key }
            assertTrue(offered != null, "a transfer the destination refused once is offered for its free retry")
            assertEquals("image/jpeg", offered.contentType, "a retried transfer keeps the type it was created with")
            assertNotEquals(LedgerState.COMPLETED, subject.rowState(key), "a refused transfer is not completed")
        }

        clause("PRESENTED_RETRY_SPENT_IS_HANDED_UP_ONCE", BackgroundTransferState.PRESENTED_RETRY_SPENT) { subject ->
            val id = "PRESENTED_RETRY_SPENT_IS_HANDED_UP_ONCE"
            val key = subject.presentedKey(id)
            val first = subject.transfer.drainTerminals().map { it.key }
            val second = subject.transfer.drainTerminals().map { it.key }
            assertEquals(
                1,
                (first + second).count { it == key },
                "a transfer whose free retry was refused too is handed up for re-creation once — the tier acknowledged " +
                    "it, so it is not presented again",
            )
            assertNotEquals(LedgerState.COMPLETED, subject.rowState(key), "a refused transfer is not completed")
        }
    }
}
