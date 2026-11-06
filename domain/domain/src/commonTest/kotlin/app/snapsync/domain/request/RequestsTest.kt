package app.snapsync.domain.request

import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A request carries what the member asked for, under the id its answer is read by. */
@OptIn(MakesEventKey::class)
class RequestsTest {
    private val id = RequestId(7)
    private val event = anEvent()
    private val join = JoinChoice(
        event,
        InviteLink(event.id, key = null),
        CaptureCutoff(event.range.start.at),
        CaptureCeiling(event.range.end.at),
        Direction.Both,
        album = AlbumChoice.SaveToAlbum,
    )
    private val share = ShareChoice(event.id, join.from, join.until, Direction.DownloadOnly, AlbumChoice.LibraryOnly)
    private val key = EventKey.of(ByteArray(EventKey.LENGTH) { 1 })!!

    @Test
    fun `a membership's requests carry their id and what was asked`() {
        assertEquals(id to join, JoinRequest(id, join).let { it.id to it.choice })
        assertEquals(id to share, SettingsRequest(id, share).let { it.id to it.choice })
        val rename = RenameRequest(id, event.id, "Lake week")
        assertEquals(Triple(id, event.id, "Lake week"), Triple(rename.id, rename.event, rename.name))
        assertEquals(id to InviteFact.Unknown, SwitchRequest(id, InviteFact.Unknown).let { it.id to it.invite })
        assertEquals(id to key, KeyRestoreRequest(id, key).let { it.id to it.key })
        val wifi = MobileDataRequest(id, MobileDataChoice.WIFI_ONLY)
        assertEquals(id to MobileDataChoice.WIFI_ONLY, wifi.id to wifi.choice)
    }

    @Test
    fun `the platform's and the report's requests carry what was asked`() {
        val share = ShareRequest("https://snapsync.app/join", "Party")
        assertEquals("https://snapsync.app/join" to "Party", share.text to share.title)
        assertEquals("https://apps.apple.com/app", AppLinkRequest("https://apps.apple.com/app").url)
        val report = ReportRequest(id, "stuck", ReportContext("Joined"))
        assertEquals(id to "stuck", report.id to report.note)
        assertEquals(ReportContext("Joined", emptyMap(), verification = false), report.context)
        assertEquals("Joined", report.context.screen)
        assertEquals(emptyMap(), report.context.shown)
        assertEquals(false, report.context.verification)
        assertEquals(listOf("SENT", "SAVED", "NOT_SENT"), ReportOutcome.entries.map { it.name })
    }

    @Test
    fun `a create request carries the event's name, range and open-until`() {
        val request = CreateRequest("Party", event.range.start, event.range.end, openUntil = null)
        assertEquals("Party", request.name)
        assertEquals(event.range.start to event.range.end, request.startsAt to request.endsAt)
        assertNull(request.openUntil)
    }
}
