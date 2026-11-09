package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.DEVICE_ID
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.active
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.data.receiving.ReceivedPhotos
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.membership.MintsConfirmedNotJoined
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.HttpResult
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Recognising the photos an earlier install received (decision record `changes/archive/2026-10-11-close-share-back-gaps`):
 * a pass that READ the union records each marked library photo as its ref's import and the membership as recognised —
 * which every import and every uploader waits for; a pass that could not read the union, the store or the library
 * records nothing, and the next pass asks again.
 */
@OptIn(EntryPointCall::class, MintsConfirmedNotJoined::class)
class RecognizeReceivedPhotosTest {

    private val r = Receiver()
    private val membership = r.joined()
    private val a = Listed("A")

    private fun recognize(photos: ReceivedPhotos = r.received) =
        RecognizeReceivedPhotos(r.records, r.union, photos, r.marked, r.device.identity, r.passes)

    /** A received-photos read over a download store that cannot be opened. */
    @OptIn(AppProcessOnly::class)
    private fun lockedPhotos(): ReceivedPhotos =
        ReceivedPhotos(DownloadDatabaseFile(DatabasesMock().apply { lock() }.port()))

    @Test
    fun `a marked photo the library still holds is adopted as its ref's import, and the membership is recognised`() =
        runTest {
            r.listed += listOf(a, Listed("B"))
            r.position = 2
            r.library.value = listOf(r.markedPhoto("LOCAL-A", a), LibraryAssets.photo("OWN", "2026-07-15T11:00:00Z"))

            val outcome = assertIs<RecognitionOutcome.Recognised>(recognize()(membership, UnionTrigger.JOIN))

            assertEquals(2, outcome.read.photos.size, "the union it read, which a join plans from")
            assertEquals(
                setOf(a.ref),
                (
                    r.records.settled(listOf(a.ref, Listed("B").ref)) as
                        app.snapsync.data.receiving.DownloadRead.Ok
                    ).value,
            )
            assertIs<RecognitionRead.Recognised>(r.received.recognition(membership.event.id))
            assertTrue(r.passes.ranFor(membership.event.id))
        }

    @Test
    fun `a union with nothing open recognises without reading the library`() = runTest {
        r.listed += Listed("OWN", device = DEVICE_ID)
        r.grant.value = LibraryGrant.DENIED

        assertIs<RecognitionOutcome.Recognised>(recognize()(membership, UnionTrigger.JOIN))
    }

    @Test
    fun `a union that cannot be read recognises nothing`() = runTest {
        r.unionAnswer = HttpResult.Unreachable("offline")

        val outcome = recognize()(membership, UnionTrigger.FOREGROUND)

        assertSame(RecognitionOutcome.UnionUnavailable, outcome)
        assertNull(outcome.read)
        assertSame(RecognitionRead.NotRecognised, r.received.recognition(membership.event.id))
    }

    @Test
    fun `a library that cannot be read leaves the membership unrecognised`() = runTest {
        r.listed += a
        r.grant.value = LibraryGrant.DENIED

        val outcome = assertIs<RecognitionOutcome.LibraryUnreadable>(recognize()(membership, UnionTrigger.JOIN))

        assertEquals(1, outcome.read.photos.size)
        assertFalse(r.passes.ranFor(membership.event.id))
    }

    @Test
    fun `no device id, an unreadable store or a refused write recognises nothing`() = runTest {
        r.listed += a
        r.library.value = listOf(r.markedPhoto("LOCAL-A", a))

        for (unread in listOf(DeviceIdRead.Unavailable("keychain locked"))) {
            r.device.identity.read = unread
            assertIs<RecognitionOutcome.Unavailable>(recognize()(membership, UnionTrigger.JOIN), "$unread")
        }
        r.device.identity.read = DeviceIdRead.Ok(app.snapsync.testids.testDeviceId(DEVICE_ID), DeviceIdRead.Via.READ)

        assertEquals(
            1,
            assertIs<RecognitionOutcome.Unavailable>(
                recognize(lockedPhotos())(membership, UnionTrigger.JOIN),
            ).read.photos.size,
            "the received photos cannot be read",
        )

        r.fail("downloadAsset", WriteKind.INSERT)
        assertIs<RecognitionOutcome.Unavailable>(recognize()(membership, UnionTrigger.JOIN), "the adoption is refused")

        r.fail("recognised", WriteKind.INSERT)
        assertIs<RecognitionOutcome.Unavailable>(recognize()(membership, UnionTrigger.JOIN), "the record is refused")

        r.unreadable()
        assertIs<RecognitionOutcome.Unavailable>(recognize()(membership, UnionTrigger.JOIN), "the store is unreadable")
        assertFalse(r.passes.ranFor(membership.event.id))
    }

    @Test
    fun `a pass is due unless one read this membership here, and never without a usable grant`() = runTest {
        val needed = RecognitionNeeded(r.passes, r.received, GrantRead(r.grant))

        assertTrue(needed(membership, RecognitionAsk.BEFORE_IMPORTING))
        assertTrue(needed(membership, RecognitionAsk.AFTER_SELECTION))

        r.received.markRecognised(membership.event.id)
        assertTrue(needed(membership, RecognitionAsk.BEFORE_IMPORTING), "no pass ran in this process")
        assertFalse(needed(membership, RecognitionAsk.AFTER_SELECTION), "the store holds it recognised")

        r.passes.ran(membership.event.id)
        assertFalse(needed(membership, RecognitionAsk.BEFORE_IMPORTING))

        r.passes.forget()
        r.grant.value = LibraryGrant.LIMITED
        assertTrue(needed(membership, RecognitionAsk.BEFORE_IMPORTING), "a partial grant reads the selection")
        r.grant.value = LibraryGrant.DENIED
        assertFalse(needed(membership, RecognitionAsk.BEFORE_IMPORTING))
    }

    @Test
    fun `a selection bringing unrecognised marked photos withdraws the recognition before it is published`() =
        runTest {
            val withhold = WithholdRecognition(r.marked, r.received, r.passes)
            r.received.markRecognised(membership.event.id)
            r.passes.ran(membership.event.id)

            withhold(MembershipRead.Joined(membership), SelectionState.Read(listOf(LibraryAssets.photo("PLAIN"))))
            assertIs<RecognitionRead.Recognised>(r.received.recognition(membership.event.id), "nothing marked")

            val marked = SelectionState.Read(listOf(r.markedPhoto("LOCAL-A", a)))
            withhold(MembershipRead.Unavailable("locked"), marked)
            withhold(MembershipRead.NotJoined(ConfirmedNotJoined()), marked)
            withhold(MembershipRead.Joined(Membership.Deleted(membership.event, membership.share)), marked)
            assertIs<RecognitionRead.Recognised>(r.received.recognition(membership.event.id), "no active event")

            withhold(MembershipRead.Joined(membership), marked)
            assertSame(RecognitionRead.NotRecognised, r.received.recognition(membership.event.id))
            assertFalse(r.passes.ranFor(membership.event.id))

            r.passes.ran(membership.event.id)
            WithholdRecognition(
                r.marked,
                lockedPhotos(),
                r.passes,
            )(MembershipRead.Joined(membership), SelectionState.Read(emptyList()))
            assertFalse(r.passes.ranFor(membership.event.id), "an unreadable store withholds too")
        }

    @Test
    fun `a join forgets every other event's refs and any pass that ran`() = runTest {
        val forget = ForgetOtherEvents(r.records, r.passes)
        r.passes.ran(membership.event.id)

        forget(membership)
        assertFalse(r.passes.ranFor(membership.event.id))

        r.fail("eventRef", WriteKind.DELETE)
        assertEquals(
            listOf("earlier events' received-photo refs stay, inert"),
            linesOf("ForgetOtherEvents") { forget(active(OTHER)) }.messages,
        )
    }

    @Test
    fun `a drain imports only under a usable grant and once the membership is recognised`() = runTest {
        val ready = ImportReady(r.received, GrantRead(r.grant))

        assertSame(ImportReadiness.NotReady, ready(membership))

        r.received.markRecognised(membership.event.id)
        assertEquals(membership.event.id, assertIs<ImportReadiness.Ready>(ready(membership)).recognised.event)
        r.grant.value = LibraryGrant.LIMITED
        assertIs<ImportReadiness.Ready>(ready(membership))

        assertSame(ImportReadiness.NotReady, ImportReady(lockedPhotos(), GrantRead(r.grant))(membership), "unreadable")

        r.grant.value = LibraryGrant.NOT_DETERMINED
        assertSame(ImportReadiness.NotReady, ready(membership))
    }
}
