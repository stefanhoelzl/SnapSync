package app.snapsync.data.receiving

import app.snapsync.format.TimestampText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The received photos' delivery facts the service was not told yet (`DeliveredPhotos`; decision record
 * `changes/delivery-record`, D3): an event's photos added to the library, oldest first, each told once.
 */
class DeliveredPhotosTest {
    private val s = Store()
    private val delivered = DeliveredPhotos(s.downloads)

    private fun photo(asset: String, added: String?, downloaded: String? = null, by: String? = null) = arrayOf(
        "INSERT INTO eventRef (eventId, sourceDeviceId, sourceAssetId) VALUES ('${EVENT.value}', '$OWNER', '$asset')",
        "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, createdLocalId, downloadedAt, " +
            "addedAt, addedBy) VALUES ('$OWNER', '$asset', 'IMPORTED', '2026-07-01T00:00:00Z', 'LOCAL-$asset', " +
            "${text(downloaded)}, ${text(added)}, ${text(by)})",
    )

    private fun text(value: String?) = value?.let { "'$it'" } ?: "NULL"

    private fun untold() = assertIs<DownloadRead.Ok<List<app.snapsync.domain.receiving.DeliveredPhoto>>>(
        delivered.untold(EVENT),
    ).value

    @Test
    fun `the event's photos added to the library are told oldest first, with their times and entry point`() {
        s.sql(
            *photo("B", added = "2026-07-14T12:00:30Z"),
            *photo("A", added = "2026-07-14T12:00:21Z", downloaded = "2026-07-14T12:00:20Z", by = "OnForeground"),
            *photo("C", added = null),
        )

        val told = untold()

        assertEquals(listOf("A", "B"), told.map { it.ref.sourceAssetId.value }, "a photo not yet added is not told")
        assertEquals("2026-07-14T12:00:20Z", told[0].downloaded?.let(TimestampText::format))
        assertEquals("OnForeground", told[0].by)
        assertEquals(null, told[1].downloaded)
    }

    @Test
    fun `a photo told in a recorded report is never told again, and an unreadable one is never told`() {
        s.sql(*photo("A", added = "2026-07-14T12:00:21Z"), *photo("Z", added = "yesterday"))

        assertIs<DownloadRead.Ok<Unit>>(delivered.told(untold()))

        assertEquals(emptyList(), untold())
    }

    private companion object {
        const val OWNER = "22222222-0000-4000-8000-000000000003"
    }
}
