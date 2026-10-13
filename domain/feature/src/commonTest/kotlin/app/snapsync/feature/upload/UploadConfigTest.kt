package app.snapsync.feature.upload

import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadConfigTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val host = "https://snap-sync-n8xmz.bunny.run"

    @Test
    fun builds_config_from_eventId_and_host() {
        val config = buildUploadConfig(testEventId(eventId), host)
        assertEquals(host, config?.host)
        assertEquals(testEventId(eventId), config?.eventId)
    }

    @Test
    fun a_missing_eventId_skips() {
        // A blank id is no event id: `EventId` cannot hold one, so only absence remains to skip on.
        assertNull(buildUploadConfig(null, host))
    }

    @Test
    fun missing_or_blank_host_skips() {
        assertNull(buildUploadConfig(testEventId(eventId), null))
        assertNull(buildUploadConfig(testEventId(eventId), ""))
    }

    @Test
    fun only_an_admission_admits() {
        assertTrue(UploadAdmission.Admit.admits)
        assertFalse(UploadAdmission.Withheld.admits)
    }
}
