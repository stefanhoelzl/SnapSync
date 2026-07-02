package app.snapsync.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadConfigTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val host = "https://snap-sync-n8xmz.bunny.run"

    @Test
    fun builds_config_from_eventId_and_host() {
        val config = buildUploadConfig(eventId, host)
        assertEquals(host, config?.host)
        assertEquals(eventId, config?.eventId)
    }

    @Test
    fun null_or_blank_eventId_skips() {
        assertNull(buildUploadConfig(null, host))
        assertNull(buildUploadConfig("", host))
    }

    @Test
    fun missing_or_blank_host_skips() {
        assertNull(buildUploadConfig(eventId, null))
        assertNull(buildUploadConfig(eventId, ""))
    }
}
