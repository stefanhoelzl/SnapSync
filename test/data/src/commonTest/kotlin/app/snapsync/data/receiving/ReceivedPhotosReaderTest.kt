package app.snapsync.data.receiving

import app.snapsync.contracts.at
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.mock.DatabasesMock
import app.snapsync.ports.DbOpen
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What every process reads of the photos this device received (capability `receiving-photos`, "Received photos are
 * never shared back"): the app over its read-write store, and the upload extension over the same file opened read-only
 * — what the app records is what the extension suppresses, and a store the updated app has not migrated yet pauses the
 * extension rather than being read as empty or migrated behind the app's back. Never "none received" or "recognised"
 * from a store that could not be read.
 */
class ReceivedPhotosReaderTest {
    private val device = Store()
    private val extension = ReceivedPhotosReader(device.databases.port())

    @Test
    fun `the extension suppresses what the app recorded, and sees the recognition it wrote`() = runTest {
        assertSame(ReceivedReadiness.Ready, extension.readiness(), "no store yet: nothing was received")
        assertEquals(emptySet(), assertIs<HandlesRead.Ok>(extension.handles()).ids)
        assertSame(RecognitionRead.NotRecognised, extension.recognition(EVENT), "the app recognised nothing here")

        device.records.adopt(
            EVENT,
            listOf(app.snapsync.domain.receiving.AdoptedPhoto(ref("A"), testAssetId("LOCAL-A"), captured())),
        )
        assertSame(StoreWrite.Written, device.received.markRecognised(EVENT))

        assertSame(ReceivedReadiness.Ready, extension.readiness())
        assertEquals(setOf(testAssetId("LOCAL-A")), assertIs<HandlesRead.Ok>(extension.handles()).ids)
        assertIs<RecognitionRead.Recognised>(extension.recognition(EVENT))
        assertSame(RecognitionRead.NotRecognised, extension.recognition(OTHER_EVENT))
    }

    @Test
    fun `the app reads its own record, and a recognition it withdraws is withdrawn for both`() = runTest {
        assertSame(ReceivedReadiness.Ready, device.received.readiness())
        device.received.markRecognised(EVENT)
        val recognised = assertIs<RecognitionRead.Recognised>(device.received.recognition(EVENT))
        assertEquals(EVENT, recognised.evidence.event)

        assertSame(StoreWrite.Written, device.received.withdrawRecognition(EVENT))

        assertSame(RecognitionRead.NotRecognised, device.received.recognition(EVENT))
        assertSame(RecognitionRead.NotRecognised, extension.recognition(EVENT))
        assertEquals(emptySet(), assertIs<HandlesRead.Ok>(device.received.handles()).ids)
    }

    @OptIn(AppProcessOnly::class)
    @Test
    fun `an unmigrated store pauses the extension until the app migrates it, keeping every handle`() = runTest {
        val databases = DatabasesMock()
        assertIs<DbOpen.Opened>(
            databases.port().open(
                DOWNLOADS_DB_NAME,
                DownloadDatabase.Schema.at(
                    6,
                    "INSERT INTO downloadAsset VALUES ('DEV-A', 'OLD', 'IMPORTED', '$DURING', 'LOCAL-OLD', 'E1')",
                ),
                readOnly = false,
            ),
        )
        val extension = ReceivedPhotosReader(databases.port())

        assertSame(ReceivedReadiness.OldSchema, extension.readiness())
        assertIs<HandlesRead.Unavailable>(extension.handles(), "an older schema is no answer")
        assertIs<RecognitionRead.Unavailable>(extension.recognition(EVENT))
        assertSame(ReceivedReadiness.OldSchema, extension.readiness(), "the extension migrated nothing")

        ReceivedPhotos(DownloadDatabaseFile(databases.port())).handles() // the app's first use migrates it

        assertSame(ReceivedReadiness.Ready, extension.readiness())
        assertEquals(setOf(testAssetId("LOCAL-OLD")), assertIs<HandlesRead.Ok>(extension.handles()).ids)
    }

    @Test
    fun `a store that will not open, or stops answering, is never read as nothing received`() = runTest {
        val refused = ReceivedPhotosReader(DatabasesMock(mapOf(DOWNLOADS_DB_NAME to DbOpen.Failed("locked"))).port())
        assertIs<ReceivedReadiness.Unavailable>(refused.readiness())
        assertIs<HandlesRead.Unavailable>(refused.handles())
        assertIs<RecognitionRead.Unavailable>(refused.recognition(EVENT))

        device.received.markRecognised(EVENT)
        assertIs<RecognitionRead.Recognised>(extension.recognition(EVENT))
        device.databases.lock()
        assertIs<HandlesRead.Unavailable>(extension.handles(), "held open, then its statements fail")
        assertIs<RecognitionRead.Unavailable>(extension.recognition(EVENT))
        assertIs<HandlesRead.Unavailable>(device.received.handles())
        assertIs<RecognitionRead.Unavailable>(device.received.recognition(EVENT))
        assertIs<StoreWrite.Failed>(device.received.markRecognised(EVENT))
        assertIs<StoreWrite.Failed>(device.received.withdrawRecognition(EVENT))
    }
}
