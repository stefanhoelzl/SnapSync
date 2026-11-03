package app.snapsync.domain.receiving

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.event.MintsIdentifiedKey
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.event.ts
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReceivingTest {

    @Test
    @OptIn(MakesEventKey::class, MintsIdentifiedKey::class)
    fun a_membership_receives_by_its_direction_and_while_its_key_is_not_lost() {
        val both = active(Direction.Both)
        assertEquals(
            both,
            assertIs<ReceivingVerdict.Receives>(ReceivingVerdict.of(both, KeyPresence.NotNeeded)).receiving.membership,
        )
        assertIs<ReceivingVerdict.Receives>(ReceivingVerdict.of(active(Direction.DownloadOnly), KeyPresence.Unknown))
        val held = KeyPresence.Held(
            IdentifiedKey(EventKey.of(ByteArray(EventKey.LENGTH))!!, EventKeyId("0011223344556677")),
        )
        assertIs<ReceivingVerdict.Receives>(ReceivingVerdict.of(both, held))
        assertEquals(ReceivingVerdict.KeyLost, ReceivingVerdict.of(both, KeyPresence.Lost))
        assertEquals(ReceivingVerdict.SharesOnly, ReceivingVerdict.of(active(Direction.UploadOnly), KeyPresence.Lost))
        assertEquals(ReceivingVerdict.SharesOnly, ReceivingVerdict.of(active(Direction.Neither), KeyPresence.NotNeeded))
    }

    @Test
    @OptIn(MintsRecognised::class)
    fun recognition_names_the_event_it_was_recorded_for() {
        assertEquals(anEvent().id, Recognised(anEvent().id).event)
    }

    @Test
    fun the_recognition_window_spans_the_open_photos_and_never_less_than_the_event() {
        val range = anEvent().range
        val inside = RecognitionWindow.of(range, listOf(CaptureDate.Dated(ts("2026-07-15T10:00:00Z"))))
        assertEquals(range.start.at, inside.from)
        assertEquals(range.end.at, inside.until)
        val wider = RecognitionWindow.of(
            range,
            listOf(
                CaptureDate.Dated(ts("2026-07-13T09:00:00Z")),
                CaptureDate.Undated,
                CaptureDate.Dated(ts("2026-07-23T09:00:00Z")),
            ),
        )
        assertEquals(ts("2026-07-13T09:00:00Z"), wider.from, "a member's range may reach before the event's")
        assertEquals(ts("2026-07-23T09:00:00Z"), wider.until)
    }

    @Test
    fun a_marked_photo_is_adopted_by_the_one_open_photo_its_token_names() {
        val a = photo("A")
        val b = photo("B", captured = null)
        val adopted = RecognitionMatch.of(
            listOf(a, b),
            mapOf(ReceivedPhotoName.token(a.ref) to local("LA"), "unknowntok" to local("LX")),
        )
        assertEquals(listOf(a.ref), adopted.map { it.ref })
        assertEquals(local("LA"), adopted.single().localId)
        assertEquals(a.captured, adopted.single().captured)
    }

    @Test
    fun a_token_two_open_photos_share_adopts_neither() {
        val a = photo("A")
        val twin = UnionPhoto(a.ref, a.captured, emptyList())
        assertEquals(
            emptyList(),
            RecognitionMatch.of(listOf(a, twin), mapOf(ReceivedPhotoName.token(a.ref) to local("L"))),
        )
    }

    @Test
    fun a_member_holds_the_read_position_when_everything_is_settled_and_nothing_waits() {
        val holds = assertIs<HeldPosition.Holds>(HeldPosition.of(7, foreign = 3, settled = 3, waiting = false))
        assertEquals(7, holds.position)
        assertEquals(7, assertIs<ReceivedReport.Position>(holds.report).position)
        assertEquals(true, holds.complete)
        assertEquals(HeldPosition.Lacks, HeldPosition.of(7, foreign = 3, settled = 2, waiting = false))
        assertEquals(HeldPosition.Lacks, HeldPosition.of(7, foreign = 3, settled = 3, waiting = true))
        assertEquals(false, HeldPosition.Lacks.complete)
        assertEquals(0, assertIs<ReceivedReport.Position>(HeldPosition.Lacks.report).position, "says it receives")
    }

    @Test
    fun a_membership_that_shares_only_holds_everything_and_says_it_receives_nothing() {
        assertEquals(true, HeldPosition.ReceivesNothing.complete)
        assertEquals(ReceivedReport.ReceivesNothing, HeldPosition.ReceivesNothing.report)
    }

    @Test
    fun a_share_only_membership_reports_at_once_and_one_that_receives_after_the_end() {
        val receives = ReceivingVerdict.of(active(), KeyPresence.NotNeeded)
        assertEquals(true, ReceivedReport.dueFor(ReceivingVerdict.SharesOnly, ended = false))
        assertEquals(false, ReceivedReport.dueFor(receives, ended = false))
        assertEquals(true, ReceivedReport.dueFor(receives, ended = true))
        assertEquals(true, ReceivedReport.dueFor(ReceivingVerdict.KeyLost, ended = true))
    }

    @Test
    fun only_a_higher_position_or_a_change_of_word_is_reported() {
        fun at(position: Long) = ReceivedReport.Position(position)
        fun reported(found: ReceivedReport, last: ReceivedReport?) = assertIs<ReportDue.Report>(
            ReportDue.of(found, last),
        )
        assertEquals(4, assertIs<ReceivedReport.Position>(reported(at(4), null).report).position)
        assertEquals(4, assertIs<ReceivedReport.Position>(reported(at(4), at(3)).report).position)
        assertEquals(ReportDue.Unchanged, ReportDue.of(at(4), at(4)))
        assertEquals(ReportDue.Unchanged, ReportDue.of(at(3), at(4)))
        assertIs<ReceivedReport.Position>(reported(at(0), ReceivedReport.ReceivesNothing).report, "receiving again")
        assertEquals(ReceivedReport.ReceivesNothing, reported(ReceivedReport.ReceivesNothing, null).report)
        assertEquals(ReceivedReport.ReceivesNothing, reported(ReceivedReport.ReceivesNothing, at(4)).report)
        assertEquals(ReportDue.Unchanged, ReportDue.of(ReceivedReport.ReceivesNothing, ReceivedReport.ReceivesNothing))
    }

    @Test
    fun only_a_receiving_member_awaits_the_others_declared_photos() {
        assertEquals(3, AwaitedPhotos.of(receives = true, othersDeclared = 5, known = 2))
        assertEquals(0, AwaitedPhotos.of(receives = true, othersDeclared = 1, known = 2), "clamped")
        assertEquals(0, AwaitedPhotos.of(receives = true, othersDeclared = null, known = 2))
        assertEquals(0, AwaitedPhotos.of(receives = false, othersDeclared = 5, known = 2))
        assertEquals(Int.MAX_VALUE, AwaitedPhotos.of(receives = true, othersDeclared = Long.MAX_VALUE, known = 0))
    }
}
