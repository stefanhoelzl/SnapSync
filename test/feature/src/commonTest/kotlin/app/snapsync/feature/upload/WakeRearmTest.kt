package app.snapsync.feature.upload

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.configService
import app.snapsync.format.TimestampText
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryWake
import app.snapsync.model.AssetRef
import app.snapsync.model.CycleResult
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.PlannedResource
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.TransferNetwork
import app.snapsync.scheduling.Pass
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.upload.ExtensionRegistration
import app.snapsync.services.wake.Heartbeat
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The re-arm every wake ends with (capability `sync-status`; decision record `changes/timely-background-receiving`,
 * D1, D3): the library-change wake re-requested for a contributing membership, then the heartbeat at the cadence its
 * uploads and the device's facts call for — and a wake whose uploads were cut counts as work left.
 */
class WakeRearmTest {

    private val pending = MutableStateFlow<Map<WakeId, WakeTrigger>>(emptyMap())

    private val downloads = DownloadService(inMemoryDatabases())

    private fun rearm(facts: CadenceFacts, libraryWake: Boolean = true) = WakeRearm(
        Heartbeat(
            inMemoryWake(pending, supported = if (libraryWake) WakeId.entries.toSet() else setOf(WakeId.Heartbeat)),
            transferNetwork = { TransferNetwork.ANY },
        ),
        downloads,
        facts = { facts },
        log = Logger.withTag("test"),
    )

    private fun cadence(): WakeCadence? = (pending.value[WakeId.Heartbeat] as? WakeTrigger.After)?.cadence

    private val sharing = CadenceFacts(
        joined = true,
        ended = false,
        shares = true,
        fullGrant = true,
        osUploaderConfirmed = false,
    )

    @Test
    fun `a caught-up contributing device watches the library and idles`() = runTest {
        rearm(sharing).after(Pass.Completed(CycleResult.COMPLETED))
        assertTrue(WakeId.LibraryChanged in pending.value, "the next photo is noticed by the library-change wake")
        assertEquals(WakeCadence.IDLE, cadence())
    }

    @Test
    fun `a device that can only look for its own photos stays busy`() = runTest {
        rearm(sharing, libraryWake = false).after(Pass.Completed(CycleResult.COMPLETED))
        assertEquals(WakeCadence.BUSY, cadence())
    }

    @Test
    fun `a wake whose uploads were cut counts as work left`() = runTest {
        rearm(sharing).after(Pass.Cut)
        assertEquals(WakeCadence.BUSY, cadence())
    }

    @Test
    fun `staged photos still to import keep the heartbeat busy`() = runTest {
        val ref = AssetRef(testDeviceId("DEVICE-A"), testAssetId("Q"))
        downloads.plan(
            ref,
            "2026-06-01T10:00:00Z",
            listOf(PlannedResource("Q.heic", "https://x/Q", "primary", "image/heic", "Q.heic")),
        )
        downloads.markStaged(ref, "Q.heic", "/stage/Q")
        rearm(sharing).after(Pass.Completed(CycleResult.COMPLETED))
        assertEquals(WakeCadence.BUSY, cadence())
    }

    @Test
    fun `a device that left its event arms no heartbeat`() = runTest {
        rearm(sharing.copy(joined = false)).after(Pass.Completed(CycleResult.COMPLETED))
        assertEquals(null, cadence())
    }

    @Test
    fun `a membership that contributes nothing watches nothing and idles`() = runTest {
        rearm(sharing).after(Pass.Completed(CycleResult.SKIPPED))
        assertFalse(WakeId.LibraryChanged in pending.value)
        assertEquals(WakeCadence.IDLE, cadence())
    }

    @Test
    fun `the facts are read off the membership - the grant and the OS's own registration`() {
        val joined = configService(
            EventConfig(
                testEventId("E"),
                "E",
                CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
                maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
                endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
                deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
            ),
        )
        val registered = object : ExtensionRegistration {
            override suspend fun register() = Unit
            override suspend fun deregister() = Unit
            override fun isRegistered(): Boolean = true
        }
        assertEquals(
            CadenceFacts(joined = true, ended = false, shares = true, fullGrant = true, osUploaderConfirmed = true),
            cadenceFacts(joined, GalleryAccess.GRANTED, osUploaderRegistrable = true, registration = registered),
        )
        assertEquals(
            CadenceFacts(joined = false, ended = false, shares = false, fullGrant = false, osUploaderConfirmed = false),
            cadenceFacts(
                configService(),
                GalleryAccess.LIMITED,
                osUploaderRegistrable = false,
                registration = registered,
            ),
        )
    }
}
