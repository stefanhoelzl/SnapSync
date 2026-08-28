package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.model.LedgerState
import app.snapsync.model.normalizeAssetId
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
 * **A terminal upload outcome survives the process that learned it** (capability `sync-ledger`,
 * `ios-url-session-upload`; decision record `changes/fix-lost-upload-acks`).
 *
 * The defect these pin is Bugsink `SNAPSYNC-11`. iOS delivers a background-`URLSession` completion
 * exactly once — `URLSessionTask.State.completed` is documented as *"the task has completed (without
 * being canceled), and the task's delegate receives no further callbacks"* — and the adapter used to
 * park that outcome in an `ArrayList` for a later `UploadCycle` to drain. The drain is gated on a
 * single-flight cycle measured in the field at 27 minutes, 65 minutes and 4h49m, so a process death in
 * between lost the fact outright: the row still read `REQUESTED` with no live task, the next cycle
 * called it stranded, and bytes that had already landed were sent again. One device uploaded the same
 * two photos three times over two days and its status screen said "uploading" throughout.
 *
 * **What makes this assertable without a device** is that the world can simulate the one thing that
 * matters — a process boundary. Building a second [World] over the **same** ledger backend is exactly
 * what a relaunch is: every in-memory registry the adapter held is gone, and only what reached durable
 * storage survives. If a completion is ever parked in memory again, the second world cannot see it and
 * these fail.
 */
class LostUploadAckIntegrationTest {

    private val EVENT = "11111111-1111-4111-8111-111111111111"

    @Test
    fun a_completion_learned_by_a_dead_process_is_promoted_and_never_re_uploaded() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val ledger = try {
            val first = World(scope)
            first.provision(eventId = EVENT, direction = Direction.Both)
            first.addOwnAsset("A")

            // Cycle 1 creates the upload job; the "OS" then finishes the transfer and tells the adapter,
            // which records it where the OS told it.
            first.runUploadCycle()
            val key = first.ledgerBackend.requestedKeys().single()
            first.platform.completeJob(key)
            first.platform.drainTerminals()

            assertEquals(
                LedgerState.UPLOADED, first.ledgerBackend.get(key)?.state,
                "the outcome is durable the moment the platform reports it — not when a cycle next runs",
            )
            first.ledgerBackend
        } finally {
            scope.cancel()
        }

        // ── the process dies here. Everything the adapter held in memory goes with it. ──
        val next = CoroutineScope(coroutineContext + Job())
        try {
            val second = World(next, ledgerBackend = ledger)
            second.provision(eventId = EVENT, direction = Direction.Both)

            second.runUploadCycle()

            val key = ledger.uploadedRows().map { it.key } + ledger.pendingResources().map { it.key }
            assertTrue(key.isEmpty(), "nothing is left outstanding: $key")
            assertTrue(
                second.platform.created.isEmpty(),
                "and its bytes are NEVER sent again — this is the whole defect: a lost acknowledgement " +
                    "used to read as a lost upload, and the photo was re-uploaded on every relaunch",
            )
        } finally {
            next.cancel()
        }
    }

    @Test
    fun a_transfer_the_os_dropped_is_re_uploaded_without_asking_storage() = worldTest {
        // The negative of the above, and the reason the stranded pass still exists. A force-quit or a
        // dropped transfer delivers NO completion at all, so nothing records anything — the row rests
        // `REQUESTED` with no live task and genuinely did not land. It must be re-uploaded.
        //
        // No device listing is fetched to decide that. `ios-url-session-upload` used to require a
        // "not present in storage" check here, which the adapter never implemented; it existed to
        // compensate for an outcome that was not recorded durably, and with the outcome recorded when the
        // platform reports it, what reaches this pass genuinely did not land.
        val scope = CoroutineScope(coroutineContext + Job())
        val ledger = try {
            val first = World(scope)
            first.provision(eventId = EVENT, direction = Direction.Both)
            first.addOwnAsset("A")
            first.runUploadCycle()

            assertTrue(
                first.ledgerBackend.requestedKeys().isNotEmpty(),
                "in flight, and the OS is about to drop it without telling anyone",
            )
            first.ledgerBackend
        } finally {
            scope.cancel()
        }

        val next = CoroutineScope(coroutineContext + Job())
        try {
            // A fresh world means a fresh session holding no tasks — exactly what a relaunch finds after
            // the OS discarded the transfer.
            val second = World(next, ledgerBackend = ledger)
            second.provision(eventId = EVENT, direction = Direction.Both)
            second.addOwnAsset("A")

            second.runUploadCycle()

            assertTrue(
                second.platform.created.isNotEmpty(),
                "a genuinely lost transfer is re-created rather than abandoned",
            )
        } finally {
            next.cancel()
        }
    }

    @Test
    fun a_platform_that_reports_within_the_cycle_promotes_places_and_notifies_in_that_same_cycle() =
        worldTest {
            // The PhotoKit tier's shape, and the reason the two-phase completion costs it nothing. That
            // tier has no callback outside the cycle: its adapter fetches the finished jobs, records them
            // `UPLOADED` and acknowledges in place, and the SAME cycle's promotion pass then places, notifies
            // and promotes. So `UPLOADED` is never observable at a cycle boundary there — it is a state the
            // row passes through, not one it rests in — while on the app-driven tier it is precisely what
            // survives a process death. One state machine, two arrival times.
            val scope = CoroutineScope(coroutineContext + Job())
            try {
                val w = World(scope)
                w.provision(eventId = EVENT, direction = Direction.Both, saveToAlbum = true)
                // The app creates the event album on the photo-permission grant; `place` only ever ADDS to
                // an album that already exists, so without this the placement is correctly skipped.
                w.albumCoordinator.ensureAlbum(EVENT, name = "World Event", saveToAlbum = true)
                w.addOwnAsset("A")

                w.runUploadCycle() // creates the job
                val key = w.ledgerBackend.requestedKeys().single()
                w.platform.completeJob(key) // the "OS" finished it while we were away

                // ONE cycle: the drain records UPLOADED and the promotion pass carries it the rest of the way.
                w.runUploadCycle()

                assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get(key)?.state)
                assertTrue(
                    w.ledgerBackend.uploadedRows().isEmpty(),
                    "nothing rests UPLOADED when the report and the promotion share a cycle",
                )
                assertEquals(
                    listOf(normalizeAssetId("A")),
                    w.albumManager.added.flatMap { it.second },
                    "placed in the event album exactly once",
                )
                // And the announcement, which IS the manifest write: on the versioned device API
                // there is no notify route, so publishing the device's asset set is the only thing that
                // tells the event anything happened. Asserting the published set — rather than a
                // recorded notify call — also asserts the ORDER that used to be implicit, because the
                // promoted row could not appear here had it been promoted after the write.
                assertEquals(
                    listOf(normalizeAssetId("A")),
                    w.store.manifestOf(EVENT, w.ownDeviceId)?.assets?.map { it.assetId },
                    "the promoted row reached the published manifest",
                )
            } finally {
                scope.cancel()
            }
        }
}
