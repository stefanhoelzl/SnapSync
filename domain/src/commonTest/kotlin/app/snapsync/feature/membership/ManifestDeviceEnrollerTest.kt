package app.snapsync.feature.membership

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

class ManifestDeviceEnrollerTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `enroll PUTs an empty manifest for the device and event`() = runTest {
        val uploader = CapturingUploader()
        val ok = ManifestDeviceEnroller(uploader).enroll(eventId, deviceId)

        assertTrue(ok)
        assertEquals(eventId, uploader.lastEventId)
        assertEquals(deviceId, uploader.lastDeviceId)
        val manifest = deviceManifestFromJson(uploader.lastJson!!)
        assertEquals(deviceId, manifest.deviceId)
        assertTrue(manifest.assets.isEmpty(), "enrollment writes a register-only empty manifest")
    }

    @Test
    fun `enroll propagates an upload failure`() = runTest {
        assertEquals(false, ManifestDeviceEnroller(CapturingUploader(result = false)).enroll(eventId, deviceId))
    }
}
