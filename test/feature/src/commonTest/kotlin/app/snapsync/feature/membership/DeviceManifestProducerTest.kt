package app.snapsync.feature.membership

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.LedgerState
import app.snapsync.feature.support.OwnLibrary
import app.snapsync.feature.support.captureRange
import app.snapsync.feature.support.testShared
import app.snapsync.format.ResourceRoleText
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.LedgerEntry
import app.snapsync.model.ManifestResource
import app.snapsync.model.UploadedManifest
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.encodeToJson
import app.snapsync.model.projectDeviceManifest
import app.snapsync.services.backend.ManifestPublisher
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An admitting policy over `[cutoff, +30 days]` — the projection's date filter — derived where production derives it.
 * Every fixture row is captured on 2026-06-27, inside the range [IN_RANGE] starts.
 */
private suspend fun policyFrom(cutoff: String): SelectionPolicy = OwnLibrary().contribution(captureRange(cutoff)).policy

/** A range every fixture row's default capture date lies in. */
private const val IN_RANGE = "2026-06-20T00:00:00Z"

class DeviceManifestProducerTest {

    private fun asset(id: String, date: String = "2026-06-27T10:00:00Z") = DeviceManifestAsset(
        assetId = testAssetId(id),
        creationDate = date,
        resources = listOf(
            ManifestResource(ResourceRole.PRIMARY, "image/jpeg", "$id-primary.jpg", "IMG_$id.JPG"),
        ),
    )

    /**
     * One ledger row — what the manifest is projected from (capability `photo-sharing`). [state] defaults
     * to COMPLETED for the tests that do not care, but the projection reads it for nothing: the manifest
     * declares INTENT, so every non-absent row is listed whatever its upload state.
     */
    private fun row(
        id: String,
        date: String = "2026-06-27T10:00:00Z",
        state: LedgerState = LedgerState.COMPLETED,
        role: ResourceRole = ResourceRole.PRIMARY,
    ) = LedgerEntry(
        key = "$id-${ResourceRoleText.of(role)}.jpg",
        assetId = testAssetId(id),
        state = state,
        creationDate = date,
        role = role,
        contentType = "image/jpeg",
        originalFilename = "IMG_$id.JPG",
    )

    /** The REAL manifest record over an in-memory shared database — [lastUploaded] is what it holds. */
    private fun manifestRecord(): DeviceManifestService = DeviceManifestService(testShared())

    private val DeviceManifestService.lastUploaded: UploadedManifest? get() = loadLastUploaded()

    private class FakeUploader(var ok: Boolean = true) : ManifestPublisher {
        val puts = mutableListOf<Triple<EventId, DeviceId, String>>()
        override suspend fun publish(eventId: EventId, deviceId: DeviceId, manifest: DeviceManifest): Boolean {
            puts += Triple(eventId, deviceId, manifest.encodeToJson())
            return ok
        }
    }

    // ── mapping from the cycle's discovered resources ──

    // ── projection ──

    @Test
    fun projection_keeps_every_asset_at_or_after_the_start_date_sorted() = runTest {
        val m =
            projectDeviceManifest(
                testDeviceId("dev"),
                listOf(row("B"), row("A")),
                policy = policyFrom(IN_RANGE),
            )
        assertEquals(testDeviceId("dev"), m.deviceId)
        assertEquals(listOf(testAssetId("A"), testAssetId("B")), m.assets.map { it.assetId }) // sorted, all kept
    }

    @Test
    fun projection_excludes_assets_before_the_start_date() = runTest {
        val rows = listOf(row("old", "2025-01-01T00:00:00Z"), row("new", "2026-06-27T10:00:00Z"))
        val m = projectDeviceManifest(testDeviceId("dev"), rows, policy = policyFrom(IN_RANGE))
        assertEquals(listOf(testAssetId("new")), m.assets.map { it.assetId })
    }

    @Test
    fun device_manifest_json_round_trips() {
        val m = DeviceManifest(testDeviceId("dev"), listOf(asset("A")))
        assertEquals(testDeviceId("dev"), deviceManifestFromJson(m.encodeToJson()).deviceId)
        assertEquals(testAssetId("A"), deviceManifestFromJson(m.encodeToJson()).assets.single().assetId)
    }

    @Test
    fun serialized_resource_carries_key_and_filename_field_names() {
        // The on-storage device.json shares its field vocabulary with the event-wide union: a resource
        // serializes as exactly { role, contentType, key, filename } — `key` the storage object name,
        // `filename` the human capture name (the rename from the per-asset manifest's filename/originalFilename).
        val json = Json.parseToJsonElement(DeviceManifest(testDeviceId("dev"), listOf(asset("A"))).encodeToJson())
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
        val m = projectDeviceManifest(testDeviceId("dev"), listOf(row("A")), policyFrom(IN_RANGE))
        val r = m.assets.single().resources.single()
        assertEquals(ResourceRole.PRIMARY, r.role)
        assertEquals("image/jpeg", r.contentType)
        assertEquals("A-primary.jpg", r.key)
        assertEquals("IMG_A.JPG", r.filename)
    }

    @Test
    fun every_state_is_declared_because_the_manifest_states_intent() = runTest {
        // The change this capability turns on: a resource is listed because the device INTENDS to provide
        // it, not because its bytes have landed. The backend keeps the asset out of the union until every
        // declared role has a resource, which is what lets it tell "not yet" from "never".
        val rows = LedgerState.entries.map { row("A${it.name}", state = it) }
        val m = projectDeviceManifest(testDeviceId("dev"), rows, policyFrom(IN_RANGE))
        assertEquals(
            LedgerState.entries.map { testAssetId("A${it.name}") }.sorted(),
            m.assets.map { it.assetId }.sorted(),
        )
    }

    @Test
    fun a_failing_resource_does_not_change_the_declaration() = runTest {
        // Why a failed resource stays declared: the engine retries forever, and a failure returns the row to
        // DISCOVERED — "still owed". Excluding it would make the declared role set
        // oscillate as a resource fails and retries — and each flip is a manifest write and a member wake.
        val policy = policyFrom(IN_RANGE)
        val discovered = listOf(row("A"), row("A", state = LedgerState.DISCOVERED, role = ResourceRole.LIVE))
        val failed = listOf(row("A"), row("A", state = LedgerState.DISCOVERED, role = ResourceRole.LIVE))
        val retried = listOf(row("A"), row("A", state = LedgerState.REQUESTED, role = ResourceRole.LIVE))

        val json = projectDeviceManifest(testDeviceId("dev"), discovered, policy).encodeToJson()
        assertEquals(json, projectDeviceManifest(testDeviceId("dev"), failed, policy).encodeToJson())
        assertEquals(json, projectDeviceManifest(testDeviceId("dev"), retried, policy).encodeToJson())
    }

    @Test
    fun a_partially_uploaded_asset_declares_both_roles() = runTest {
        // The defect this closes: with a COMPLETED-only projection the asset was declared with `primary`
        // alone, so the union served it as a complete one-resource asset and a recipient imported the
        // Live Photo as a plain still — permanently, since a recipient plans per asset.
        val m = projectDeviceManifest(
            testDeviceId("dev"),
            listOf(row("A"), row("A", state = LedgerState.DISCOVERED, role = ResourceRole.LIVE)),
            policyFrom(IN_RANGE),
        )
        assertEquals(1, m.assets.size)
        assertEquals(
            listOf(ResourceRole.LIVE, ResourceRole.PRIMARY),
            m.assets.single().resources.map { it.role }.sortedBy { it.name },
        )
    }

    @Test
    fun a_bare_row_is_not_listed() = runTest {
        // A row the join-time load seeded from a stored-file listing has no capture date until the next
        // full enumeration backfills it. It is excluded by the POLICY, not by a predicate in the
        // projection or its storage read: an empty capture date sorts before every real cutoff
        // (`SelectionRule.CaptureAfter`), so the one admission decides this like every other.
        val bare = LedgerEntry("Z-primary.jpg", testAssetId("Z"), LedgerState.COMPLETED)
        val m = projectDeviceManifest(testDeviceId("dev"), listOf(row("A"), bare), policyFrom(IN_RANGE))
        assertEquals(listOf(testAssetId("A")), m.assets.map { it.assetId })
    }

    @Test
    fun puts_the_projected_snapshot_and_records_it_on_success() = runTest {
        val store = manifestRecord()
        val up = FakeUploader(ok = true)
        DeviceManifestProducer(
            store,
            up,
            testDeviceId("dev"),
        ).produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 1L)
        assertEquals(1, up.puts.size)
        assertEquals(testEventId("E"), up.puts.single().first)
        assertTrue(store.lastUploaded!!.manifest.endsWith(up.puts.single().third)) // the record holds the json
    }

    @Test
    fun does_not_skip_when_the_event_changes() = runTest {
        // The projected JSON is event-independent, so a switch must NOT be skipped — the new event
        // needs its own device.json written.
        val store = manifestRecord()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, testDeviceId("dev"))
        producer.produce(
            testEventId("EVENT-A"),
            policyFrom(IN_RANGE),
            listOf(row("A")),
            manifestVersion = 1L,
        )
        producer.produce(
            testEventId("EVENT-B"),
            policyFrom(IN_RANGE),
            listOf(row("A")),
            manifestVersion = 1L,
        ) // same content, new event
        assertEquals(2, up.puts.size) // both events written, despite identical content
        assertEquals(listOf(testEventId("EVENT-A"), testEventId("EVENT-B")), up.puts.map { it.first })
    }

    @Test
    fun skips_the_put_when_the_snapshot_is_unchanged() = runTest {
        val store = manifestRecord()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, testDeviceId("dev"))
        producer.produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 1L)
        producer.produce(
            testEventId("E"),
            policyFrom(IN_RANGE),
            listOf(row("A")),
            manifestVersion = 1L,
        ) // identical
        assertEquals(1, up.puts.size) // second produce skipped the PUT
    }

    @Test
    fun re_joining_leaves_the_contribution_intact_and_still_skips() = runTest {
        // What this replaces: enrolment used to PUT a register-only EMPTY manifest, so a re-join blanked
        // this device's asset set until the next cycle republished it — a window in which the event union
        // listed none of its photos. The repair was to invalidate the producer's skip record, so the next
        // cycle rewrote the projection over the empty document.
        //
        // The join writes no manifest now, so there is nothing to repair: the server still holds the real
        // projection, and the record is still TRUE for the version it names. What the join DOES change is the
        // version: its ledger load advances the counter (capability `photo-sharing`), and the backend's join
        // cleared the stored one — so the next cycle republishes the unchanged projection under the newer
        // version, which is what re-establishes it on the backend (capability `photo-sharing`).
        val store = manifestRecord()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, testDeviceId("dev"))
        producer.produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 1L)
        assertEquals(1, up.puts.size)

        // the re-join writes no manifest: the producer's record is untouched
        assertEquals(1, up.puts.size)

        producer.produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 1L)
        assertEquals(1, up.puts.size, "an unchanged projection under the same version is correctly skipped")

        producer.produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 7L)
        assertEquals(2, up.puts.size, "the join's ledger load advanced the version, so the unchanged set republishes")
        assertEquals(up.puts[0].third.substringBefore("\"version\""), up.puts[1].third.substringBefore("\"version\""))
    }

    @Test
    fun the_publish_carries_the_manifest_version() = runTest {
        val up = FakeUploader()
        DeviceManifestProducer(manifestRecord(), up, testDeviceId("dev"))
            .produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 42L)
        assertEquals(42L, deviceManifestFromJson(up.puts.single().third).version)
    }

    @Test
    fun a_new_version_republishes_an_unchanged_snapshot() = runTest {
        // THE same-version crossed pair (decision record `changes/manifest-versions`, D3): two cycles read one
        // version, a change commits between their row reads, the OLDER snapshot lands last on the backend (an
        // equal version is accepted) and the NEWER one's skip record is saved last. A marker comparing content
        // alone would now skip forever against a stale backend. The next cycle reads a higher version, so the
        // marker no longer matches and it republishes.
        val store = manifestRecord()
        val up = FakeUploader()
        val producer = DeviceManifestProducer(store, up, testDeviceId("dev"))
        producer.produce(
            testEventId("E"),
            policyFrom(IN_RANGE),
            listOf(row("A"), row("B")),
            manifestVersion = 5L,
        )
        producer.produce(
            testEventId("E"),
            policyFrom(IN_RANGE),
            listOf(row("A"), row("B")),
            manifestVersion = 6L,
        )
        assertEquals(2, up.puts.size)
    }

    @Test
    fun a_failed_put_does_not_record_last_uploaded() = runTest {
        val store = manifestRecord()
        val up = FakeUploader(ok = false)
        DeviceManifestProducer(
            store,
            up,
            testDeviceId("dev"),
        ).produce(testEventId("E"), policyFrom(IN_RANGE), listOf(row("A")), manifestVersion = 1L)
        assertEquals(1, up.puts.size)
        assertEquals(null, store.lastUploaded) // not recorded → retried next cycle
        assertFalse(up.puts.isEmpty())
    }
}
