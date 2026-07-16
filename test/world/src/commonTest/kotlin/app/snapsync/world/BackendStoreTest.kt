package app.snapsync.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavioral read-model contract — shapes, completeness semantics, marker gate, empty cases (not byte-golden). */
class BackendStoreTest {

    @Test
    fun per_device_listing_reflects_deposits() {
        val store = BackendStore()
        store.deposit("D", "a-primary.jpg")
        store.deposit("D", "b-primary.jpg")
        val listing = store.deviceListing("D")
        assertEquals(setOf("a-primary.jpg", "b-primary.jpg"), listing.map { it.filename }.toSet())
        assertTrue(listing.all { it.url == "https://world.store/D/${it.filename}" && it.size == 1L })
    }

    @Test
    fun empty_partition_lists_empty() {
        assertEquals(emptyList(), BackendStore().deviceListing("D"))
    }

    @Test
    fun union_null_when_event_unregistered() {
        assertNull(BackendStore().union("00000000-0000-4000-8000-000000000000"))
    }

    @Test
    fun union_empty_when_registered_but_no_complete_assets() {
        val store = BackendStore()
        store.registerEvent("E")
        assertEquals(emptyList(), store.union("E"))
    }

    @Test
    fun union_includes_only_complete_assets_tagged_by_device() {
        val store = BackendStore()
        store.registerEvent("E")
        val asset = World.foreignAsset("Q") // primary key Q-primary.heic
        store.putManifest("E", "D", foreignManifest("D", listOf(asset)))
        // Manifest present but bytes absent → incomplete → omitted.
        assertEquals(emptyList(), store.union("E"))
        // Deposit the byte → complete → included.
        store.deposit("D", asset.resources[0].key)
        val union = store.union("E")!!
        assertEquals(1, union.size)
        assertEquals("D", union[0].deviceId)
        assertEquals("Q", union[0].assetId)
        assertEquals("Q-primary.heic", union[0].resources[0].key)
        assertEquals("https://world.store/D/Q-primary.heic", union[0].resources[0].url)
    }
}
