package app.snapsync.domain.delivery

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.DeliveredPhoto
import app.snapsync.domain.receiving.PushNotice
import app.snapsync.domain.time.Timestamp
import app.snapsync.domain.upload.Discovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The delivery record's values as the app carries them to the service (decision record `changes/delivery-record`):
 * a photo's discovery, a push a read answers, and a received photo's facts — each holding what it was given.
 */
class DeliveryValuesTest {
    private val at = Timestamp.of(Instant.parse("2026-07-14T12:00:00Z"))

    @Test
    fun a_discovery_names_when_and_by_which_entry_point_or_none() {
        val found = Discovery(at, "OnForeground")
        assertEquals(at, found.at)
        assertEquals("OnForeground", found.by)
        assertNull(Discovery(at, null).by)
    }

    @Test
    fun a_push_notice_names_the_position_it_announced_and_when_the_app_acted() {
        val notice = PushNotice(announced = 7, at = at)
        assertEquals(7, notice.announced)
        assertEquals(at, notice.at)
        assertEquals(notice, PushNotice(7, at))
    }

    @Test
    fun a_delivered_photo_names_whose_it_is_and_its_times() {
        val ref = AssetRef(DeviceId.parse("22222222-0000-4000-8000-000000000003")!!, AssetId.parse("A1")!!)
        val photo = DeliveredPhoto(ref, downloaded = null, added = at, by = "OnDownloadSessionEvents")
        assertEquals(ref, photo.ref)
        assertNull(photo.downloaded)
        assertEquals(at, photo.added)
        assertEquals("OnDownloadSessionEvents", photo.by)
    }
}
