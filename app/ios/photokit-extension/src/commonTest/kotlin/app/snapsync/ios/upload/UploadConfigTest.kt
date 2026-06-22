package app.snapsync.ios.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadConfigTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val host = "https://snap-sync-n8xmz.bunny.run"

    @Test
    fun builds_config_from_eventId_host_and_deviceId() {
        val config = buildUploadConfig(eventId, host, deviceId)
        assertEquals(host, config?.host)
        assertEquals(eventId, config?.eventId)
        assertEquals(deviceId, config?.deviceId)
    }

    @Test
    fun null_or_blank_eventId_skips() {
        assertNull(buildUploadConfig(null, host, deviceId))
        assertNull(buildUploadConfig("", host, deviceId))
    }

    @Test
    fun missing_or_blank_host_skips() {
        assertNull(buildUploadConfig(eventId, null, deviceId))
        assertNull(buildUploadConfig(eventId, "", deviceId))
    }

    @Test
    fun null_or_blank_deviceId_skips() {
        assertNull(buildUploadConfig(eventId, host, null))
        assertNull(buildUploadConfig(eventId, host, ""))
    }
}
