package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.FileNameCipher
import app.snapsync.data.membership.active
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.TransferFacts
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.wake.StagedImports
import app.snapsync.levers.WriteKind
import app.snapsync.mock.fakeCrypto
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A finished transfer, from the platform's delivering thread to the store: judged before it is kept — an error body
 * delivered as a "successful" transfer is never staged — moved to the path its tag names, opened when sealed, then
 * recorded; and a transfer's end frees its slot.
 */
@OptIn(EntryPointCall::class)
class StagingTest {

    private val r = Receiver()
    private val stage = StageDownload(r.staging, r.opening)
    private val record = RecordStaged(r.queue, r.opening)
    private val free = FreeTransfer(r.queue)
    private val healthy = TransferFacts(status = 200, expectedBytes = -1, receivedBytes = 1024)

    /** [membership] joined, its union serving A, planned: the transfer A's resource started under. */
    private suspend fun plannedTag(membership: Membership.Active = r.joined(), listed: Listed = Listed("A")): String {
        r.listed += listed
        r.position = 1
        PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)(
            receiving(membership),
            UnionTrigger.FOREGROUND,
        )
        return r.download.started.single()
    }

    @Test
    fun `a healthy transfer is kept at its tag's path and recorded as staged`() = runTest {
        val tag = plannedTag()

        val landed = assertIs<StageOutcome.Landed>(stage(tag, healthy, r.temporary("t1")))
        assertTrue(landed.landing.stagedAt in r.shared, "moved into staging")

        assertSame(RecordOutcome.Recorded, record(landed))
        assertEquals(StagedImports.REMAIN, r.counts.stagedImports(), "the photo waits to be imported")
    }

    @Test
    fun `an error body or a short body is rejected and never kept`() = runTest {
        val tag = plannedTag()
        for (facts in listOf(TransferFacts(500, -1, 10), TransferFacts(200, expectedBytes = 100, receivedBytes = 10))) {
            val rejected = assertIs<StageOutcome.Rejected>(stage(tag, facts, r.temporary("t")))
            assertSame(facts, rejected.facts)
        }
        assertEquals(emptySet(), r.shared.keys)
    }

    @Test
    fun `a tag no build wrote is not staged`() = runTest {
        assertSame(StageOutcome.UnknownTag, stage("not a tag", healthy, r.temporary("t")))
    }

    @Test
    fun `bytes that cannot be moved into staging are downloaded again`() = runTest {
        val tag = plannedTag()
        val notKept = assertIs<StageOutcome.NotKept>(stage(tag, healthy, "mem:/private/gone"))
        assertTrue(notKept.detail.isNotEmpty())
    }

    @Test
    fun `a staging no row takes is orphaned, and one the store cannot record is downloaded again`() = runTest {
        val membership = r.joined()
        val stray = listOf(testDeviceId(SENDER).value, testAssetId("Z").value, "Z-primary.heic", "").joinToString("\n")
        val orphan = assertIs<StageOutcome.Landed>(stage(stray, healthy, r.temporary("t1")))
        assertSame(RecordOutcome.Orphaned, record(orphan), "a tag from before transfers named their event, no row")

        val tag = plannedTag(membership)
        val landed = assertIs<StageOutcome.Landed>(stage(tag, healthy, r.temporary("t2")))
        r.fail("downloadResource", WriteKind.UPDATE)
        assertIs<RecordOutcome.Unavailable>(record(landed))
    }

    @Test
    fun `an encrypted event's download that does not open is not staged`() = runTest {
        val plain = active()
        val key = EventKeyIds(fakeCrypto()).mint()
        r.keys.keep(KeyToKeep.Key(key), plain.event.id)
        // Its name is sealed as any member's build seals it, so the union names it and the transfer starts.
        val binding = FileBinding(plain.event.id, testDeviceId(SENDER), testAssetId("A"), ResourceRole.PRIMARY)
        val tag = plannedTag(
            r.joined(Membership.Active(plain.event.copy(encryption = Encryption.Encrypted(key.id)), plain.share)),
            Listed("A", filename = FileNameCipher(fakeCrypto()).sealFileName(key, binding, "IMG_A.JPG")),
        )

        val landed = assertIs<StageOutcome.Landed>(stage(tag, healthy, r.temporary("t1")))
        assertSame(RecordOutcome.NotOpened, record(landed), "its bytes are no file of the event's key")
    }

    @Test
    fun `a transfer's end frees its slot, failed or not`() = runTest {
        val tag = plannedTag()
        free(tag, error = "timed out")
        free(tag, error = null)
        assertEquals(1, r.download.started.size, "nothing else was queued")
    }
}
