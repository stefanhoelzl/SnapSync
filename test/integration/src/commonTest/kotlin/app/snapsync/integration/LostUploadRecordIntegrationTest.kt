package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.captureCutoff
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The read-only foreground check** (capability `event-rejoin-reconciliation`): does what the ledger
 * believes landed agree with what the backend actually holds?
 *
 * Driven through the **trigger**, never through the method. `flow/Foreground` is the same instance the
 * iOS shell runs on scene activation, composed by the same `snapSyncApp`, so a check that is built but
 * never called fails these — which is the failure mode this whole change exists to avoid repeating
 * (`releaseSettledBytes` was built, spec'd, contract-tested and unreachable for months).
 *
 * Two of these assert **silence**, and they are the ones that earn the fault the right to ride at
 * `Error`. A detector that cries on ordinary backend housekeeping burns the operator's attention and
 * trains them to ignore the channel, which is strictly worse than having no detector at all.
 */
class LostUploadRecordIntegrationTest {

    private val EVENT = "11111111-1111-4111-8111-111111111111"

    /**
     * Join, contribute [assets] for real, and settle them — a device in the ordinary resting state, with
     * the backend holding its bytes, the ledger holding `COMPLETED` rows, and the join marker set (which
     * the real re-join reconcile does on the first cycle, and which the check requires).
     */
    private suspend fun World.contribute(vararg assets: String) {
        assets.forEach { addOwnAsset(it) }
        runUploadCycle()
        ledgerBackend.requestedKeys().forEach { platform.completeJob(it) }
        platform.drainTerminals()
        runUploadCycle() // promotes UPLOADED -> COMPLETED
    }

    /** Every row the ledger holds, as a comparable snapshot. */
    private suspend fun World.ledgerSnapshot(): Map<String, LedgerEntry> =
        ledgerBackend.manifestRows().associateBy { it.key }

    private suspend fun world(scope: CoroutineScope, body: suspend World.() -> Unit): World =
        World(scope).also { it.body() }

    @Test
    fun a_believed_landed_resource_the_backend_does_not_hold_is_reported_at_Error() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                contribute("A", "B")
            }
            val lost = "B-primary.jpg"
            assertTrue(lost in w.store.objectsOf(w.ownDeviceId), "precondition: the bytes really landed")

            // The loss, behind the device's back. Nothing on the device is told, and nothing on the
            // device would ever ask again: the ledger row is the only thing that decides whether this
            // photo still needs uploading, and it says it does not.
            w.store.collectBytes(w.ownDeviceId, lost)

            w.core.foregroundFlow.run()

            val fault = w.logs.errors().single()
            assertTrue("1 of 2" in fault, fault)
            assertTrue("mechanism=" in fault, fault)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_check_leaves_the_ledger_byte_identical() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                contribute("A", "B")
            }
            w.store.collectBytes(w.ownDeviceId, "B-primary.jpg")
            val before = w.ledgerSnapshot()

            w.core.foregroundFlow.run()

            assertTrue(w.logs.errors().isNotEmpty(), "precondition: it found the disagreement")
            assertEquals(before, w.ledgerSnapshot(), "a detector writes nothing — that is its whole safety")
            assertEquals(EVENT, w.marker.read(), "and the marker is not touched either")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun SILENCE_collected_residue_the_policy_no_longer_admits_is_not_a_lost_photo() = worldTest {
        // The nightly sweep collects UNREFERENCED bytes (capability `scheduled-cleanup`). A row the
        // membership's current policy excludes is not declared by the manifest, so it is unreferenced by
        // construction — and its collection is housekeeping, not data loss. Without the admission filter
        // this fires on every device that ever narrowed its cutoff.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                addOwnAsset("OLD", creationDate = "2026-02-01T10:00:00Z")
                contribute("A")
            }
            assertTrue("OLD-primary.jpg" in w.store.objectsOf(w.ownDeviceId), "precondition: OLD uploaded")

            // The member narrows their cutoff past OLD; the sweep then collects the bytes nothing declares.
            w.provision(
                eventId = EVENT,
                minPhotoDate = captureCutoff("2026-05-01T00:00:00Z"),
                direction = Direction.Both,
            )
            w.store.collectBytes(w.ownDeviceId, "OLD-primary.jpg")

            w.core.foregroundFlow.run()

            assertTrue(
                w.logs.errors().isEmpty(),
                "ordinary collection must be silent: ${w.logs.errors()}",
            )
            // …and silent because it COMPARED and agreed, not because it skipped. Without this a
            // regression that stops the check running altogether would keep this test green.
            assertTrue(
                w.logs.infos().any { "agrees with the backend (1 believed-landed" in it },
                "the check ran and compared exactly the one admitted row: ${w.logs.lines}",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun SILENCE_a_pending_rejoin_suppresses_the_check() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                contribute("A")
            }
            w.store.wipeBytes(w.ownDeviceId)
            // A switch: the config names a new event the marker has not reconciled yet, so the ledger is
            // known-divergent and the marker-gated re-join is about to re-baseline it. Reporting now
            // would report a state already understood.
            w.marker.set("22222222-2222-4222-8222-222222222222")

            w.core.foregroundFlow.run()

            assertTrue(w.logs.errors().isEmpty(), "a pending re-join is not a finding: ${w.logs.errors()}")
            // And it did not merely find nothing — it never looked. Every other outcome writes one of
            // these lines, so their absence is the skip.
            assertTrue(
                w.logs.lines.none { "agrees with the backend" in it.message || "nothing compared" in it.message },
                "the check does not run at all on a marker mismatch: ${w.logs.lines}",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun SILENCE_a_backend_that_cannot_answer_says_nothing_about_the_ledger() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                contribute("A", "B")
            }
            w.store.collectBytes(w.ownDeviceId, "B-primary.jpg")
            w.backendOffline = true // the listing route answers 502

            w.core.foregroundFlow.run()

            assertTrue(
                w.logs.errors().none { "does not hold" in it },
                "a fetch that could not answer is no information: ${w.logs.errors()}",
            )
            // It looked, and could not tell — which is a different fact from "nothing is wrong", and is
            // told as one (`module-architecture`, "Absence is never silent").
            assertTrue(
                w.logs.warnings().any { "nothing compared" in it },
                "the failure to look is itself recorded: ${w.logs.lines}",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_listed_resource_the_ledger_forgot_is_counted_but_never_faulted() = worldTest {
        // The other direction: the bytes are there and the row is not. It costs one idempotent
        // re-upload to the same deterministic key — bandwidth, never a photo — so it is counted and
        // must not be allowed to bury the direction that matters.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                contribute("A")
            }
            w.store.deposit(w.ownDeviceId, "Z-primary.jpg") // stored, and the ledger never knew

            w.core.foregroundFlow.run()

            assertTrue(w.logs.errors().isEmpty(), "not a fault: ${w.logs.errors()}")
            assertTrue(
                w.logs.lines.any { it.severity == "Info" && "no ledger row" in it.message },
                "but it is counted: ${w.logs.lines}",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_agreeing_device_reports_nothing() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = world(scope) {
                provision(eventId = EVENT, direction = Direction.Both)
                contribute("A", "B")
            }

            w.core.foregroundFlow.run()

            assertTrue(w.logs.errors().isEmpty(), "the resting state is quiet: ${w.logs.errors()}")
            assertEquals(
                LedgerState.COMPLETED,
                w.ledgerBackend.get("A-primary.jpg")?.state,
                "precondition: there was something to compare",
            )
        } finally {
            scope.cancel()
        }
    }
}
