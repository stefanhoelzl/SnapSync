package app.snapsync.feature.membership

import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Enrollment
import app.snapsync.model.deviceManifestFromJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CapturingUploader(private val result: Boolean = true) : Enrollment {
    var lastEventId: String? = null
    var lastDeviceId: String? = null
    var lastJson: String? = null
    override suspend fun put(eventId: String, deviceId: String, json: String): Boolean {
        lastEventId = eventId; lastDeviceId = deviceId; lastJson = json
        return result
    }
}

/** The producer's skip-if-unchanged record, which a successful enroll must falsify. */
private class RecordingStore(var lastUploaded: String? = "some prior projection") : DeviceManifestStore {
    override fun loadLastUploaded() = lastUploaded
    override fun saveLastUploaded(json: String) {
        lastUploaded = json
    }
    override fun clearLastUploaded() {
        lastUploaded = null
    }
}

class ManifestDeviceEnrollerTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `enroll PUTs an empty manifest for the device and event`() = runTest {
        val uploader = CapturingUploader()
        val ok = ManifestDeviceEnroller(uploader, RecordingStore()).enroll(eventId, deviceId)

        assertTrue(ok)
        assertEquals(eventId, uploader.lastEventId)
        assertEquals(deviceId, uploader.lastDeviceId)
        val manifest = deviceManifestFromJson(uploader.lastJson!!)
        assertEquals(deviceId, manifest.deviceId)
        assertTrue(manifest.assets.isEmpty(), "enrollment writes a register-only empty manifest")
    }

    @Test
    fun `enroll propagates an upload failure`() = runTest {
        val enroller = ManifestDeviceEnroller(CapturingUploader(result = false), RecordingStore())
        assertEquals(false, enroller.enroll(eventId, deviceId))
    }

    @Test
    fun `a successful enroll invalidates the producer's skip-if-unchanged record`() = runTest {
        // Enrollment overwrites the server's manifest with an empty one, so the producer's record of what
        // the server holds is now false — and false in the direction that hides this device's photos from
        // the event union until something happens to change the projection. Clearing it is how the
        // falsifying writer says so; the next cycle then rewrites the real manifest.
        val store = RecordingStore()
        ManifestDeviceEnroller(CapturingUploader(), store).enroll(eventId, deviceId)
        assertEquals(null, store.lastUploaded)
    }

    @Test
    fun `a failed enroll leaves the record intact`() = runTest {
        // The server was not changed, so the record is still true. Clearing it would cost a redundant PUT
        // next cycle — the same rule the producer records under (it, too, only writes on a confirmed PUT).
        val store = RecordingStore()
        ManifestDeviceEnroller(CapturingUploader(result = false), store).enroll(eventId, deviceId)
        assertEquals("some prior projection", store.lastUploaded)
    }
}
