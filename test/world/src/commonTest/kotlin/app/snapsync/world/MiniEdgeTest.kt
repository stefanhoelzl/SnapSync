package app.snapsync.world

import app.snapsync.download.HttpEventUnionSource
import app.snapsync.eventcreation.CreateOutcome
import app.snapsync.eventcreation.HttpEventCreationClient
import app.snapsync.gallery.DeviceManifest
import app.snapsync.gallery.encodeToJson
import app.snapsync.membership.HttpDeviceFilesSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The four REAL common-Ktor seams round-trip against the mini-edge, incl. the offline failure levers. */
class MiniEdgeTest {

    private val host = "https://edge.example"

    @Test
    fun device_files_source_roundtrips() = runTest {
        val store = BackendStore().apply { deposit("D", "x-primary.jpg") }
        val src = HttpDeviceFilesSource(miniEdgeClient(store), host)
        assertEquals(listOf("x-primary.jpg"), src.list("D").getOrThrow())
    }

    @Test
    fun device_files_offline_is_failure() = runTest {
        val store = BackendStore().apply { offline = true }
        val src = HttpDeviceFilesSource(miniEdgeClient(store), host)
        assertTrue(src.list("D").isFailure)
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
        val outcome = HttpEventCreationClient(miniEdgeClient(store), host)
            .create("Party", "2026-07-14T18:00:00Z")
        assertTrue(outcome is CreateOutcome.Created)
        val eventId = (outcome as CreateOutcome.Created).eventId
        assertTrue(store.isRegistered(eventId))
        assertEquals("2026-07-14T18:00:00Z", store.startsAtOf(eventId), "the start date is stored")
    }

    @Test
    fun event_creation_rejects_blank_name() = runTest {
        val outcome = HttpEventCreationClient(miniEdgeClient(BackendStore()), host)
            .create("   ", "2026-07-14T18:00:00Z")
        assertEquals(CreateOutcome.InvalidName, outcome)
    }

    @Test
    fun event_creation_rejects_a_non_canonical_startsAt() = runTest {
        // The mini-edge is a FAITHFUL edge, not a lenient one: the real backend 400s a fractional-second
        // or offset-bearing `startsAt`, so a client that shipped one would only fail on device. Here it
        // fails in the fast loop instead.
        val store = BackendStore()
        for (bad in listOf("2026-07-14T18:00:00.000Z", "2026-07-14T18:00:00+02:00", "", "yesterday")) {
            val outcome = HttpEventCreationClient(miniEdgeClient(store), host).create("Party", bad)
            assertEquals(CreateOutcome.InvalidName, outcome, "startsAt=$bad must be rejected (400)")
        }
    }

    @Test
    fun manifest_put_lands_in_store() = runTest {
        val store = BackendStore()
        val json = DeviceManifest("D", listOf(World.foreignAsset("Q"))).encodeToJson()
        assertTrue(HttpDeviceManifestUploader(miniEdgeClient(store), host).put("E", "D", json))
        assertNotNull(store.manifestOf("E", "D"))
    }
}
