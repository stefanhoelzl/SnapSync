package app.snapsync.data.receiving

import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.receiving.AdoptedPhoto
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A recognition pass's writes (capability `receiving-photos`, "Received photos are never shared back"): a photo an
 * earlier install received, recognised in the library by its mark, recorded as its ref's confirmed import — and as
 * the event's — so it is never downloaded again and never shared back; a ref this process is importing is never
 * adopted underneath it.
 */
class RecognitionRecordsTest {
    private val device = Store()

    private fun adopted(ref: AssetRef, local: String) = AdoptedPhoto(ref, testAssetId(local), captured())

    @Test
    fun `a photo with no row is recorded as imported onto the library's asset, and as the event's`() = runTest {
        val a = ref("A")

        assertEquals(setOf(a), device.records.adopt(EVENT, listOf(adopted(a, "LOCAL-A"))).ok())

        assertEquals("IMPORTED", device.state(a))
        assertEquals("LOCAL-A", device.marker(a))
        assertEquals(setOf(a), device.records.settled(listOf(a, ref("B"))).ok())
        assertEquals(1, device.counts.counts(EVENT).ok().imported, "the event's received photo")
    }

    @Test
    fun `a planned photo is settled onto the adopted asset instead of imported again`() = runTest {
        val a = photo(ref("A"))
        device.planned(page(a))

        assertEquals(setOf(a.ref), device.records.adopt(EVENT, listOf(adopted(a.ref, "LOCAL-A"))).ok())

        assertEquals("IMPORTED", device.state(a.ref))
        assertEquals("LOCAL-A", device.marker(a.ref))
    }

    @Test
    fun `a settled photo and a claimed import are left as they are`() = runTest {
        val claimed = photo(ref("CLAIMED"))
        device.planned(page(claimed))
        device.stagedAll(claimed)
        device.queue.claimNext(emptySet()).ok()!!
        val done = ref("DONE")
        device.records.adopt(EVENT, listOf(adopted(done, "LOCAL-DONE")))

        val again = device.records.adopt(
            EVENT,
            listOf(adopted(claimed.ref, "LOCAL-C"), adopted(done, "LOCAL-OTHER")),
        ).ok()

        assertEquals(emptySet(), again)
        assertEquals("PENDING", device.state(claimed.ref), "its own import's marker settles it")
        assertEquals("LOCAL-DONE", device.marker(done))
    }

    @Test
    fun `a join forgets every other event's union and recognition, never a photo's row`() = runTest {
        device.records.adopt(OTHER_EVENT, listOf(adopted(ref("A"), "LOCAL-A")))
        device.received.markRecognised(OTHER_EVENT)
        device.received.markRecognised(EVENT)

        assertSame(StoreWrite.Written, device.records.forgetOtherEvents(EVENT))

        assertEquals(0, device.counts.counts(OTHER_EVENT).ok().known)
        assertIs<RecognitionRead.NotRecognised>(device.received.recognition(OTHER_EVENT))
        assertIs<RecognitionRead.Recognised>(device.received.recognition(EVENT))
        assertEquals("LOCAL-A", device.marker(ref("A")), "the suppression handle stays")
    }

    @Test
    fun `a store that cannot be read records nothing`() = runTest {
        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.records.settled(listOf(ref("A"))))
        assertIs<DownloadRead.Unavailable>(device.records.adopt(EVENT, listOf(adopted(ref("A"), "LOCAL-A"))))
        assertIs<StoreWrite.Failed>(device.records.forgetOtherEvents(EVENT))
    }
}
