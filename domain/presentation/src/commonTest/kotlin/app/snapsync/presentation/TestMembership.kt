package app.snapsync.presentation

import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlin.test.assertIs

/**
 * A membership as a test states it, flat — the event's facts and the member's choices side by side, each a default
 * unless the test is about it — and [held], the domain's [Membership] it is. A fixture that is no membership the domain
 * takes fails the test that wrote it, never quietly.
 */
internal data class TestMembership(
    val eventId: EventId,
    val name: String,
    val minPhotoDate: CaptureCutoff,
    val startsAt: EventStart = EventStart(minPhotoDate.at),
    val endsAt: EventEnd,
    val maxPhotoDate: CaptureCeiling,
    val deletesAt: DeletesAt,
    val direction: Direction = Direction.Both,
    val saveToAlbum: Boolean = false,
    val members: MemberCounts? = null,
    val openUntil: OpenUntil? = null,
    val deleted: Boolean = false,
    val keyId: String? = null,
) {
    fun held(): Membership {
        val range = assertIs<EventRangeVerdict.Ok>(EventRange.of(startsAt, endsAt), "the event's range").range
        val event = Event(
            id = eventId,
            name = assertIs<EventNameVerdict.Ok>(EventName.of(name), "the event's name").name,
            range = range,
            openUntil = openUntil,
            deletesAt = deletesAt,
            encryption = keyId?.let { Encryption.Encrypted(EventKeyId(it)) } ?: Encryption.Plain,
            members = members,
        )
        val shared = CaptureRange.of(minPhotoDate, maxPhotoDate, startsAt, endsAt)
        val share = Share(
            assertIs<CaptureRangeVerdict.Ok>(shared, "the shared range").range,
            direction,
            if (saveToAlbum) AlbumChoice.SaveToAlbum else AlbumChoice.LibraryOnly,
        )
        return if (deleted) Membership.Deleted(event, share) else Membership.Active(event, share)
    }
}

/** [this] flow of test memberships as the screen reads it: the domain's membership held, or none. */
internal fun StateFlow<TestMembership?>.held(): StateFlow<Membership?> = HeldFlow(this)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class HeldFlow(private val source: StateFlow<TestMembership?>) : StateFlow<Membership?> {
    override val value: Membership? get() = source.value?.held()
    override val replayCache: List<Membership?> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Membership?>): Nothing {
        source.map { it?.held() }.collect(collector)
        awaitCancellation()
    }
}
