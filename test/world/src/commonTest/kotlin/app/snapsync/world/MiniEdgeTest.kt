package app.snapsync.world

import app.snapsync.download.HttpEventUnionSource
import app.snapsync.join.HttpEventJoin
import app.snapsync.join.HttpManifestPublisher
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.JoinResult
import app.snapsync.ports.CreateOutcome
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.model.DeviceManifest
import app.snapsync.model.encodeToJson
import app.snapsync.membership.HttpDeviceFilesSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The four REAL common-Ktor seams round-trip against the mini-edge, incl. the offline failure levers. */
class MiniEdgeTest {

    // Every seam speaks v2. `v1Host` exists for the ONE test that proves the strict decode rejects the
    // frozen shape — the real backend still serves v1 for the installed base, so that shape is reachable
    // by a mis-baked build and is worth a negative fixture rather than a deletion.
    private val host = "https://edge.example/api/v2"
    private val v1Host = "https://edge.example/api/v1"

    @Test
    fun device_files_source_roundtrips() = runTest {
        // Against the v2 host: the seam now recomposes the key from the identity terms the backend
        // answers in, so it can no longer read the v1 listing (see the shape test below).
        val store = BackendStore().apply { deposit("D", "x-primary.jpg") }
        val src = HttpDeviceFilesSource(miniEdgeClient(store), host)
        assertEquals(listOf("x-primary.jpg"), src.list("D").getOrThrow())
    }

    @Test
    fun device_files_source_recomposes_a_key_from_identity_terms() = runTest {
        // The key is NOT echoed from the wire: an `assetId` carrying hyphens (every real one does — a
        // `localIdentifier` is a UUID) and a paired Live resource both have to come back byte-identical
        // to what the producer uploaded under, or the seed dedups against nothing.
        val store = BackendStore().apply {
            deposit("D", "9E3F-4A_L0_001-primary.heic")
            deposit("D", "9E3F-4A_L0_001-live.mov")
        }
        val src = HttpDeviceFilesSource(miniEdgeClient(store), host)
        assertEquals(
            listOf("9E3F-4A_L0_001-primary.heic", "9E3F-4A_L0_001-live.mov"),
            src.list("D").getOrThrow(),
        )
    }

    @Test
    fun a_v1_shaped_listing_fails_to_decode_rather_than_seeding_capture_names() = runTest {
        // The trap this strictness exists for. BOTH shapes carry a field named `filename`, and they mean
        // opposite things by it: the storage key in v1, the capture name in v2. A lenient decode would
        // read the v1 listing without complaint and seed whatever that field held — so a device pointed
        // at the wrong version would rebuild its ledger out of the wrong values and re-upload its whole
        // library, with no failed request anywhere. Requiring `assetId` and `role` is what makes this
        // loud instead.
        val store = BackendStore().apply { deposit("D", "x-primary.jpg") }
        val failure = HttpDeviceFilesSource(miniEdgeClient(store), v1Host).list("D").exceptionOrNull()
        assertTrue(failure is DeviceListingShapeException, "was $failure")
    }

    @Test
    fun device_files_offline_is_failure() = runTest {
        val store = BackendStore().apply { offline = true }
        val src = HttpDeviceFilesSource(miniEdgeClient(store), host)
        val failure = src.list("D").exceptionOrNull()
        assertNotNull(failure)
        // A transport failure, distinguishably: it heals on the next cycle, where a shape failure does
        // not, and the reconciler reports the two at different severities.
        assertFalse(failure is DeviceListingShapeException)
    }

    @Test
    fun union_source_roundtrips() = runTest {
        val store = BackendStore().apply { registerEvent("E") }
        val asset = World.foreignAsset("Q")
        store.putManifest("E", "D", foreignManifest("D", listOf(asset)))
        store.deposit("D", asset.resources[0].key)
        val union = HttpEventUnionSource(miniEdgeClient(store), host).union("E").getOrThrow()
        assertEquals(1, union.size)
        assertEquals("D", union[0].deviceId)
        assertEquals("Q-primary.heic", union[0].resources[0].key)
    }

    @Test
    fun union_unregistered_is_failure() = runTest {
        val src = HttpEventUnionSource(miniEdgeClient(BackendStore()), host)
        assertTrue(src.union("E").isFailure) // 404 → failed Result
    }

    @Test
    fun union_offline_is_failure() = runTest {
        val store = BackendStore().apply { registerEvent("E"); offline = true }
        assertTrue(HttpEventUnionSource(miniEdgeClient(store), host).union("E").isFailure)
    }

    @Test
    fun event_creation_mints_and_registers_marker() = runTest {
        val store = BackendStore()
        val outcome = HttpEventCreation(miniEdgeClient(store), host)
            .create("Party", "2026-07-14T18:00:00Z", null)
        assertTrue(outcome is CreateOutcome.Created)
        val eventId = (outcome as CreateOutcome.Created).eventId
        assertTrue(store.isRegistered(eventId))
        assertEquals("2026-07-14T18:00:00Z", store.startsAtOf(eventId), "the start date is stored")
    }

    @Test
    fun event_creation_rejects_blank_name() = runTest {
        val outcome = HttpEventCreation(miniEdgeClient(BackendStore()), host)
            .create("   ", "2026-07-14T18:00:00Z", null)
        assertEquals(CreateOutcome.InvalidName, outcome)
    }

    @Test
    fun event_creation_rejects_a_non_canonical_startsAt() = runTest {
        // The mini-edge is a FAITHFUL edge, not a lenient one: the real backend 400s a fractional-second
        // or offset-bearing `startsAt`, so a client that shipped one would only fail on device. Here it
        // fails in the fast loop instead.
        val store = BackendStore()
        for (bad in listOf("2026-07-14T18:00:00.000Z", "2026-07-14T18:00:00+02:00", "", "yesterday")) {
            val outcome = HttpEventCreation(miniEdgeClient(store), host).create("Party", bad, null)
            assertEquals(CreateOutcome.InvalidName, outcome, "startsAt=$bad must be rejected (400)")
        }
    }

    @Test
    fun joining_then_publishing_lands_the_manifest_in_the_store() = runTest {
        // Two requests now, in this order: the join owns membership, the publish owns contribution.
        val store = BackendStore().apply { registerEvent("E") }
        val client = miniEdgeClient(store)

        assertEquals(JoinResult.JOINED, HttpEventJoin(client, host).join("E", "D"))

        val json = DeviceManifest("D", listOf(World.foreignAsset("Q"))).encodeToJson()
        assertTrue(HttpManifestPublisher(client, host).publish("E", "D", json))
        assertNotNull(store.manifestOf("E", "D"))
    }

    @Test
    fun publishing_without_joining_is_refused() = runTest {
        // The divergence this world exists to prevent: modelling the publish as a create would let a
        // device pass here and fail against the real backend.
        val store = BackendStore().apply { registerEvent("E") }
        val json = DeviceManifest("D", emptyList()).encodeToJson()

        assertFalse(HttpManifestPublisher(miniEdgeClient(store), host).publish("E", "D", json))
        assertNull(store.manifestOf("E", "D"))
    }
}
