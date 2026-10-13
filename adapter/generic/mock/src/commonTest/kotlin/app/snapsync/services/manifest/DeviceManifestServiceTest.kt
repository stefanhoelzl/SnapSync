package app.snapsync.services.manifest

import app.snapsync.mock.DatabasesMock
import app.snapsync.model.AssetId
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UploadedManifest
import app.snapsync.model.encodeToJson
import app.snapsync.services.databases.sharedOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The device manifest's skip record (capability `photo-sharing`), over the shared database on in-memory SQLite.
 *
 * This record is the *skip-if-unchanged* memory behind the manifest upload. A store that silently forgot would
 * re-upload the manifest on every cycle (noise, no corruption); one that silently *remembered* a value it never
 * wrote would skip an upload that was needed, and the event union would keep listing photos this device no longer
 * has. Each [service] is a process of its own: the app and the extension both write it.
 */
class DeviceManifestServiceTest {

    private fun asset(id: String) = DeviceManifestAsset(
        AssetId(id),
        "2026-07-01T00:00:00Z",
        listOf(ManifestResource(ResourceRole.PRIMARY, "image/heic", "$id-primary", "IMG_$id.HEIC")),
    )

    private val databases = DatabasesMock()

    private fun service() = DeviceManifestService(sharedOver(databases.port()))

    @Test
    fun `a saved manifest is read back verbatim by every process`() {
        service().saveLastUploaded(UploadedManifest("E", 3, """{"assets":[{"id":"a"}]}"""))
        assertEquals(UploadedManifest("E", 3, """{"assets":[{"id":"a"}]}"""), service().loadLastUploaded())
    }

    // What the device last told the backend it shares in an event (change `count-declared-photos`): the term its
    // received total subtracts from the event's declared total.
    @Test
    fun `the published count is the record's photos for its own event and none for another`() {
        val manifest = DeviceManifest("D", listOf(asset("a"), asset("b"))).encodeToJson()
        service().saveLastUploaded(UploadedManifest("E", 3, manifest))
        assertEquals(2, service().publishedCount("E"))
        assertEquals(0, service().publishedCount("OTHER"), "nothing of this device's is in that event")
    }

    @Test
    fun `with nothing published the count is none and an undecodable record is no count`() {
        assertEquals(0, service().publishedCount("E"))
        service().saveLastUploaded(UploadedManifest("E", 1, "not a manifest"))
        assertNull(service().publishedCount("E"))
    }

    @Test
    fun `a store that has uploaded nothing believes nothing`() {
        assertNull(service().loadLastUploaded())
    }

    @Test
    fun `a newer save replaces the previous belief and an equal version replaces it too`() {
        val service = service()
        service.saveLastUploaded(UploadedManifest("E", 1, "first"))
        service.saveLastUploaded(UploadedManifest("E", 2, "second"))
        assertEquals("second", service.loadLastUploaded()?.manifest)

        // One version can name two snapshots (decision record `changes/manifest-versions`, D3): the later save wins.
        service.saveLastUploaded(UploadedManifest("E", 2, "second again"))
        assertEquals("second again", service.loadLastUploaded()?.manifest)
    }

    @Test
    fun `an older save landing last never replaces a newer one`() {
        service().saveLastUploaded(UploadedManifest("E", 5, "newer"))
        service().saveLastUploaded(UploadedManifest("E", 4, "older, crossing in the network"))
        assertEquals(UploadedManifest("E", 5, "newer"), service().loadLastUploaded())
    }

    @Test
    fun `another event's record is replaced whatever its version`() {
        service().saveLastUploaded(UploadedManifest("E1", 9, "old event"))
        service().saveLastUploaded(UploadedManifest("E2", 1, "new event"))
        assertEquals(UploadedManifest("E2", 1, "new event"), service().loadLastUploaded())
    }

    @Test
    fun `clearing returns the store to believing nothing - even one that never saved`() {
        service().clearLastUploaded()
        service().saveLastUploaded(UploadedManifest("E", 1, "a"))
        service().clearLastUploaded()
        assertNull(service().loadLastUploaded())
    }

    @Test
    fun `an unreadable record is not believed and a locked store raises nothing`() {
        service().saveLastUploaded(UploadedManifest("E", 1, "a"))
        databases.operator.lock()
        val service = service()

        assertNull(service.loadLastUploaded())
        assertNull(service.publishedCount("E"), "no own count from a store it cannot read")
        service.saveLastUploaded(UploadedManifest("E", 2, "b"))
        service.clearLastUploaded()

        databases.operator.lock(false)
        assertEquals(UploadedManifest("E", 1, "a"), service().loadLastUploaded(), "nothing changed while locked")
    }
}
