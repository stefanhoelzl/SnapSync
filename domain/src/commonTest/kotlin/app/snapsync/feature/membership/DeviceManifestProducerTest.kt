package app.snapsync.feature.membership

import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.encodeToJson
import app.snapsync.model.ManifestResource
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.ResourceRole
import app.snapsync.model.deviceManifestAssetsFromResources
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCutoff
import app.snapsync.model.projectDeviceManifest
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Enrollment

import app.snapsync.model.Resource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An admitting policy bounded below by [cutoff] and unbounded above — the projection's date filter. */
private fun policyFrom(cutoff: String): SelectionPolicy =
    SelectionPolicy.from(includesUpload = true, cutoff = captureCutoff(cutoff), ceiling = null)

class DeviceManifestProducerTest {

    private fun asset(id: String, date: String = "2026-06-27T10:00:00Z") = DeviceManifestAsset(
        assetId = id,
        creationDate = date,
        resources = listOf(
            ManifestResource(ResourceRole.PRIMARY, "image/jpeg", "$id-primary.jpg", "IMG_$id.JPG"),
        ),
    )

    private class FakeStore : DeviceManifestStore {
        var accumulator: List<DeviceManifestAsset> = emptyList()
        var lastUploaded: String? = null
        override fun loadAccumulator() = accumulator
        override fun saveAccumulator(assets: List<DeviceManifestAsset>) {
            accumulator = assets
        }
        override fun loadLastUploaded() = lastUploaded
        override fun saveLastUploaded(json: String) {
            lastUploaded = json
        }
    }

    private class FakeUploader(var ok: Boolean = true) : Enrollment {
        val puts = mutableListOf<Triple<String, String, String>>()
        override suspend fun put(eventId: String, deviceId: String, json: String): Boolean {
            puts += Triple(eventId, deviceId, json)
            return ok
        }
    }

    // ── mapping from the cycle's discovered resources ──

    @Test
    fun maps_resources_to_device_manifest_assets_grouping_and_reading_metadata() {
        fun res(filename: String, assetId: String, date: String, orig: String, mime: String) = Resource(
            filename = filename,
            assetId = assetId,
            contentType = "public.heic", // the UTI on the byte upload; the manifest uses the MIME from metadata
            metadata = mapOf(
                RESOURCE_META_CREATION_DATE to date,
                RESOURCE_META_ORIGINAL_FILENAME to orig,
                RESOURCE_META_MIME to mime,
            ),
            data = Unit,
        )
        // A Live Photo (primary + live) sharing one assetId, and a plain photo.
        val assets = deviceManifestAssetsFromResources(
            listOf(
                res("UUID-1-2_L0_001-primary.heic", "UUID-1-2_L0_001", "2026-06-27T10:00:00Z", "IMG_1.HEIC", "image/heic"),
                res("UUID-1-2_L0_001-live.mov", "UUID-1-2_L0_001", "2026-06-27T10:00:00Z", "IMG_1.MOV", "video/quicktime"),
                res("B-primary.jpg", "B", "2026-06-28T09:00:00Z", "IMG_2.JPG", "image/jpeg"),
            ),
        ).associateBy { it.assetId }

        val live = assets.getValue("UUID-1-2_L0_001")
        assertEquals("2026-06-27T10:00:00Z", live.creationDate)
        assertEquals(setOf(ResourceRole.PRIMARY, ResourceRole.LIVE), live.resources.map { it.role }.toSet())
        val primary = live.resources.single { it.role == ResourceRole.PRIMARY }
        assertEquals("image/heic", primary.contentType) // MIME, not the UTI
        assertEquals("IMG_1.HEIC", primary.filename) // human capture name
        assertEquals("UUID-1-2_L0_001-primary.heic", primary.key) // storage object name (fetch handle)

        assertEquals(listOf(ResourceRole.PRIMARY), assets.getValue("B").resources.map { it.role })
    }

    // ── projection ──

    @Test
    fun projection_keeps_every_asset_at_or_after_the_start_date_sorted() {
        val m = projectDeviceManifest("dev", listOf(asset("B"), asset("A")), policy = policyFrom("0001-01-01T00:00:00Z"))
        assertEquals("dev", m.deviceId)
        assertEquals(listOf("A", "B"), m.assets.map { it.assetId }) // sorted, all kept
    }

    @Test
    fun projection_excludes_assets_before_the_start_date() {
        val acc = listOf(asset("old", "2025-01-01T00:00:00Z"), asset("new", "2026-06-27T10:00:00Z"))
        val m = projectDeviceManifest("dev", acc, policy = policyFrom("2026-01-01T00:00:00Z"))
        assertEquals(listOf("new"), m.assets.map { it.assetId })
    }

    @Test
    fun device_manifest_json_round_trips() {
        val m = DeviceManifest("dev", listOf(asset("A")))
        assertEquals("dev", deviceManifestFromJson(m.encodeToJson()).deviceId)
        assertEquals("A", deviceManifestFromJson(m.encodeToJson()).assets.single().assetId)
    }

    @Test
    fun serialized_resource_carries_key_and_filename_field_names() {
        // The on-storage device.json shares its field vocabulary with the event-wide union: a resource
        // serializes as exactly { role, contentType, key, filename } — `key` the storage object name,
        // `filename` the human capture name (the rename from the per-asset manifest's filename/originalFilename).
        val json = Json.parseToJsonElement(DeviceManifest("dev", listOf(asset("A"))).encodeToJson())
        val resource = json.jsonObject["assets"]!!.jsonArray[0].jsonObject["resources"]!!.jsonArray[0]
        assertEquals(setOf("role", "contentType", "key", "filename"), resource.jsonObject.keys)
        assertEquals("A-primary.jpg", resource.jsonObject["key"]!!.toString().trim('"')) // storage name
        assertEquals("IMG_A.JPG", resource.jsonObject["filename"]!!.toString().trim('"')) // human name
    }

    // ── producer ──

    @Test
    fun incremental_upserts_discovered_and_prunes_removed() = runTest {
        val store = FakeStore().apply { accumulator = listOf(asset("A"), asset("B")) }
        val up = FakeUploader()
        DeviceManifestProducer(store, up, "dev").produce(
            eventId = "E",
            policy = policyFrom("0001-01-01T00:00:00Z"),
            discovered = listOf(asset("C")),
            removedAssetIds = setOf("A"),
            fullEnumeration = false,
        )
        assertEquals(listOf("B", "C"), store.accumulator.map { it.assetId }.sorted()) // A pruned, C added
    }

    @Test
    fun full_enumeration_replaces_the_accumulator_dropping_absent_assets() = runTest {
        val store = FakeStore().apply { accumulator = listOf(asset("A"), asset("B"), asset("C")) }
        val up = FakeUploader()
        DeviceManifestProducer(store, up, "dev").produce(
            eventId = "E",
            policy = policyFrom("0001-01-01T00:00:00Z"),
            discovered = listOf(asset("A"), asset("C")), // B gone from the library
            removedAssetIds = emptySet(),
            fullEnumeration = true,
        )
        assertEquals(listOf("A", "C"), store.accumulator.map { it.assetId }.sorted()) // B dropped
    }

    @Test
    fun puts_the_projected_snapshot_and_records_it_on_success() = runTest {
        val store = FakeStore()
        val up = FakeUploader(ok = true)
        DeviceManifestProducer(store, up, "dev").produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(asset("A")), emptySet(), false)
        assertEquals(1, up.puts.size)
        assertEquals("E", up.puts.single().first)
        assertTrue(store.lastUploaded!!.endsWith(up.puts.single().third)) // marker records the json
    }

    @Test
    fun does_not_skip_when_the_event_changes() = runTest {
        // The projected JSON is event-independent, so a switch must NOT be skipped — the new event
        // needs its own device.json written.
        val store = FakeStore()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, "dev")
        producer.produce("EVENT-A", policyFrom("0001-01-01T00:00:00Z"), listOf(asset("A")), emptySet(), false)
        producer.produce("EVENT-B", policyFrom("0001-01-01T00:00:00Z"), listOf(asset("A")), emptySet(), false) // same content, new event
        assertEquals(2, up.puts.size) // both events written, despite identical content
        assertEquals(listOf("EVENT-A", "EVENT-B"), up.puts.map { it.first })
    }

    @Test
    fun skips_the_put_when_the_snapshot_is_unchanged() = runTest {
        val store = FakeStore()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, "dev")
        producer.produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(asset("A")), emptySet(), false)
        producer.produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(asset("A")), emptySet(), false) // identical
        assertEquals(1, up.puts.size) // second produce skipped the PUT
    }

    @Test
    fun a_failed_put_does_not_record_last_uploaded() = runTest {
        val store = FakeStore()
        val up = FakeUploader(ok = false)
        DeviceManifestProducer(store, up, "dev").produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(asset("A")), emptySet(), false)
        assertEquals(1, up.puts.size)
        assertEquals(null, store.lastUploaded) // not recorded → retried next cycle
        assertTrue(store.accumulator.isNotEmpty()) // accumulator still saved
        assertFalse(up.puts.isEmpty())
    }
}
