package app.snapsync.data.receiving

import app.snapsync.domain.wake.StagedImports
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How far this device has received (capability `sync-status`): each count read in one statement, scoped to the event
 * the screen shows — never every photo ever received — and the device-wide census beside it.
 */
class ReceivingCountsTest {
    private val device = Store()

    @Test
    fun `the event's counts and the device's census are read together`() = runTest {
        val imported = photo(ref("IMPORTED"))
        val waiting = photo(ref("WAITING"))
        device.planned(page(imported, waiting, othersDeclared = 5))
        device.stagedAll(imported)
        device.queue.imported(device.queue.claimNext(emptySet()).ok()!!, testAssetId("LOCAL-I"))
        val elsewhere = app.snapsync.domain.receiving.AdoptedPhoto(ref("ELSE"), testAssetId("LOCAL-E"), captured())
        device.records.adopt(OTHER_EVENT, listOf(elsewhere))

        val counts = device.counts.counts(EVENT).ok()
        assertEquals(1, counts.imported)
        assertEquals(2, counts.stillArriving)
        assertEquals(1, counts.inFlight)
        assertEquals(2, counts.known)
        assertEquals(5L, counts.othersDeclared)
        val census = device.counts.census().ok()
        assertEquals(2, census.imported, "every event's")
        assertEquals(3, census.stillArriving)
        assertEquals(0, census.known)
        assertEquals(null, census.othersDeclared)
        assertEquals(setOf(testAssetId("LOCAL-I")), device.counts.importedOf(EVENT).ok())
    }

    @Test
    fun `a whole read's foreign photos are held when every one is settled and nothing waits`() = runTest {
        val a = photo(ref("A"))
        device.planned(page(a))
        val waiting = device.counts.held(listOf(a.ref)).ok()
        assertEquals(1, waiting.foreign)
        assertEquals(0, waiting.settled)
        assertEquals(true, waiting.waiting, "its download is pending")

        device.stagedAll(a)
        assertEquals(true, device.counts.held(listOf(a.ref)).ok().waiting, "staged, waiting for its import")
        assertEquals(StagedImports.of(1), device.counts.stagedImports())

        device.queue.imported(device.queue.claimNext(emptySet()).ok()!!, testAssetId("LOCAL-A"))
        val held = device.counts.held(listOf(a.ref)).ok()
        assertEquals(1, held.settled)
        assertEquals(false, held.waiting)
        assertEquals(StagedImports.of(0), device.counts.stagedImports())
    }

    @Test
    fun `a store that cannot be read counts nothing, and may still hold imports`() = runTest {
        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.counts.held(emptyList()))
        assertIs<DownloadRead.Unavailable>(device.counts.counts(EVENT))
        assertIs<DownloadRead.Unavailable>(device.counts.census())
        assertIs<DownloadRead.Unavailable>(device.counts.importedOf(EVENT))
        assertEquals(StagedImports.REMAIN, device.counts.stagedImports())
    }

    @Test
    fun `a row whose created id is no id is no imported photo of the event`() = runTest {
        device.sql(
            "INSERT INTO downloadAsset VALUES ('${ANNA.value}', 'X', 'IMPORTED', '$DURING', '')",
            "INSERT INTO eventRef VALUES ('${EVENT.value}', '${ANNA.value}', 'X')",
        )
        assertEquals(emptySet(), device.counts.importedOf(EVENT).ok())
    }
}
