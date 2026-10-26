package app.snapsync.feature.upload

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.testShared
import app.snapsync.format.TimestampText
import app.snapsync.mock.fixedClock
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EventConfig
import app.snapsync.model.ManifestResource
import app.snapsync.model.UploadedManifest
import app.snapsync.model.encodeToJson
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The end of a wake for this device's share (capability `event-lifetime`: a member is done at the end of their OWN
 * range): once that range has ended, a manifest not yet published final is published once more — over the REAL
 * membership and manifest record.
 */
class ShareSettlementTest {

    private val joined = EventConfig(
        eventId = testEventId("E"),
        name = "Party",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T12:00:00Z")),
        startsAt = EventStart(TimestampText.parse("2026-07-06T12:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2026-07-13T12:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T12:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2026-08-05T12:00:00Z")),
    )

    private fun manifest(final: Boolean) = UploadedManifest(
        testEventId("E"),
        1,
        DeviceManifest(
            deviceId = testDeviceId("D"),
            assets = listOf(
                DeviceManifestAsset(
                    testAssetId("A_L0_1"),
                    "2026-07-10T00:00:00Z",
                    listOf(ManifestResource(ResourceRole.PRIMARY, "image/heic", "k", "IMG.HEIC")),
                ),
            ),
            version = 1,
            final = final,
        ).encodeToJson(),
    )

    /** One scenario: the membership at [now], the manifest [recorded] as last published, and the final publishes. */
    private class World(now: String, membership: EventConfig?, recorded: UploadedManifest?) {
        var publishes = 0
        val record = DeviceManifestService(testShared()).apply { recorded?.let(::saveLastUploaded) }
        val settlement = ShareSettlement(
            config = configService(membership, clock = fixedClock(Instant.parse(now))),
            manifestRecord = record,
            publishFinal = { publishes++ },
        )
    }

    private fun world(
        now: String = "2026-07-14T00:00:00Z",
        membership: EventConfig? = joined,
        recorded: UploadedManifest? = manifest(final = false),
    ) = World(now, membership, recorded)

    @Test
    fun `before the member's range has ended nothing is published`() = runTest {
        val w = world(now = "2026-07-12T00:00:00Z")
        w.settlement.settle()
        assertEquals(0, w.publishes)
    }

    @Test
    fun `after the end an unsettled share is published final once more`() = runTest {
        val w = world()
        w.settlement.settle()
        assertEquals(1, w.publishes)
    }

    @Test
    fun `a share published final already is not published again`() = runTest {
        val w = world(recorded = manifest(final = true))
        w.settlement.settle()
        assertEquals(0, w.publishes)
    }

    @Test
    fun `a range that ends before the event settles at the member's own end`() = runTest {
        val early = joined.copy(maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-10T20:00:00Z")))
        val w = world(now = "2026-07-11T00:00:00Z", membership = early)
        w.settlement.settle()
        assertEquals(1, w.publishes, "settled before the event's end")
    }

    @Test
    fun `a range that ends after the event is not settled at the event's end`() = runTest {
        val late = joined.copy(maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-15T12:00:00Z")))
        val w = world(membership = late)
        w.settlement.settle()
        assertEquals(0, w.publishes, "not before the member's own end")
    }

    @Test
    fun `a record of another event's manifest is no settled share here`() = runTest {
        val w = world(recorded = manifest(final = true).copy(eventId = testEventId("OTHER")))
        w.settlement.settle()
        assertEquals(1, w.publishes)
    }

    @Test
    fun `an unreadable manifest record settles the share once more`() = runTest {
        val w = world(recorded = UploadedManifest(testEventId("E"), 1, "not json"))
        w.settlement.settle()
        assertEquals(1, w.publishes)
    }

    @Test
    fun `a device that never recorded a published manifest settles its share once more`() = runTest {
        val w = world(recorded = null)
        w.settlement.settle()
        assertEquals(1, w.publishes)
    }

    @Test
    fun `no membership and a deleted event settle nothing`() = runTest {
        val none = world(membership = null)
        none.settlement.settle()
        val deleted = world(membership = joined.copy(deleted = true))
        deleted.settlement.settle()
        assertEquals(0, none.publishes + deleted.publishes)
    }
}
