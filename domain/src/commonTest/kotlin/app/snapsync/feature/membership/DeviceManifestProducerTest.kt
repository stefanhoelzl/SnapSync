package app.snapsync.feature.membership

import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.encodeToJson
import app.snapsync.model.ManifestResource
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.ResourceRole
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
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

    /** One COMPLETED ledger row — what the manifest is now projected from (capability `sync-ledger`). */
    private fun row(id: String, date: String = "2026-06-27T10:00:00Z") = LedgerEntry(
        key = "$id-primary.jpg",
        assetId = id,
        state = LedgerState.COMPLETED,
        attempt = 0,
        eventId = "E",
        creationDate = date,
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        originalFilename = "IMG_$id.JPG",
    )

    private class FakeStore : DeviceManifestStore {
        var lastUploaded: String? = null
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

    // ── projection ──

    @Test
    fun projection_keeps_every_asset_at_or_after_the_start_date_sorted() = runTest {
        val m = projectDeviceManifest("dev", listOf(row("B"), row("A")), policy = policyFrom("0001-01-01T00:00:00Z"))
        assertEquals("dev", m.deviceId)
        assertEquals(listOf("A", "B"), m.assets.map { it.assetId }) // sorted, all kept
    }

    @Test
    fun projection_excludes_assets_before_the_start_date() = runTest {
        val rows = listOf(row("old", "2025-01-01T00:00:00Z"), row("new", "2026-06-27T10:00:00Z"))
        val m = projectDeviceManifest("dev", rows, policy = policyFrom("2026-01-01T00:00:00Z"))
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
    fun the_projection_carries_each_rows_full_resource_detail() = runTest {
        // The ledger row is what names the resource now, so it must carry everything the union's field
        // vocabulary needs — otherwise the manifest would have to re-read PhotoKit to describe bytes it
        // has already uploaded.
        val m = projectDeviceManifest("dev", listOf(row("A")), policyFrom("0001-01-01T00:00:00Z"))
        val r = m.assets.single().resources.single()
        assertEquals(ResourceRole.PRIMARY, r.role)
        assertEquals("image/jpeg", r.contentType)
        assertEquals("A-primary.jpg", r.key)
        assertEquals("IMG_A.JPG", r.filename)
    }

    @Test
    fun a_bare_row_is_not_listed() = runTest {
        // A row the re-join reconcile seeded from a filename listing has no capture date until the next
        // full enumeration backfills it. Listing it would place it outside every membership window
        // rather than inside the right one, so the projection waits for the sweep.
        val bare = LedgerEntry("Z-primary.jpg", "Z", LedgerState.COMPLETED, attempt = 0, eventId = "E")
        val m = projectDeviceManifest("dev", listOf(row("A"), bare), policyFrom("0001-01-01T00:00:00Z"))
        assertEquals(listOf("A"), m.assets.map { it.assetId })
    }

    @Test
    fun puts_the_projected_snapshot_and_records_it_on_success() = runTest {
        val store = FakeStore()
        val up = FakeUploader(ok = true)
        DeviceManifestProducer(store, up, "dev").produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(row("A")))
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
        producer.produce("EVENT-A", policyFrom("0001-01-01T00:00:00Z"), listOf(row("A")))
        producer.produce("EVENT-B", policyFrom("0001-01-01T00:00:00Z"), listOf(row("A"))) // same content, new event
        assertEquals(2, up.puts.size) // both events written, despite identical content
        assertEquals(listOf("EVENT-A", "EVENT-B"), up.puts.map { it.first })
    }

    @Test
    fun skips_the_put_when_the_snapshot_is_unchanged() = runTest {
        val store = FakeStore()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, "dev")
        producer.produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(row("A")))
        producer.produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(row("A"))) // identical
        assertEquals(1, up.puts.size) // second produce skipped the PUT
    }

    @Test
    fun a_failed_put_does_not_record_last_uploaded() = runTest {
        val store = FakeStore()
        val up = FakeUploader(ok = false)
        DeviceManifestProducer(store, up, "dev").produce("E", policyFrom("0001-01-01T00:00:00Z"), listOf(row("A")))
        assertEquals(1, up.puts.size)
        assertEquals(null, store.lastUploaded) // not recorded → retried next cycle
        assertFalse(up.puts.isEmpty())
    }
}
