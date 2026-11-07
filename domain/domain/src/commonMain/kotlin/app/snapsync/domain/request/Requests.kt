package app.snapsync.domain.request

import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteText
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.version.Store

// What a member asks for with a tap, each request answered on its own status under its [RequestId]: the screen mints
// the id, fires the request, and acts only on the answer to it.

/** A join the member confirmed: [id] is answered on the join status. */
class JoinRequest(val id: RequestId, val choice: JoinChoice)

/** Settings the member changed: [id] is answered on the settings status. */
class SettingsRequest(val id: RequestId, val choice: ShareChoice)

/** A rename of [event] the member asked for: [id] is answered on the rename status. */
class RenameRequest(val id: RequestId, val event: EventId, val name: String)

/** Another event's invite, loaded: [id] is answered with the switch verdict. */
class SwitchRequest(val id: RequestId, val invite: InviteFact)

/** A reopened invite's [key], offered to restore the joined event's: [id] is answered on the restore status. */
class KeyRestoreRequest(val id: RequestId, val key: EventKey)

/** Trying again to read whether this device is in an event: [id] is answered once the try has run. */
class MembershipReadRequest(val id: RequestId)

/** A change of the device's mobile-data choice the member made: [id] is answered on the mobile-data status. */
class MobileDataRequest(val id: RequestId, val choice: MobileDataChoice)

/**
 * An event the member asked to create, [name] over [startsAt]–[endsAt], others joining until [openUntil], the range
 * picked in [zone] (its id — for the event page alone): [id] is answered on the creation status.
 */
class CreateRequest(
    val id: RequestId,
    val name: String,
    val startsAt: EventStart,
    val endsAt: EventEnd,
    val openUntil: OpenUntil?,
    val zone: String,
)

/** The platform's share sheet asked for, carrying [text], titled [title] where the platform shows a title. */
class ShareRequest(val text: InviteText, val title: String)

/** A page outside the app the member asked to open: this build's store page, or a page of the app's own site. */
sealed interface AppLinkRequest {
    /** The store page [store] names. */
    class StorePage(val store: Store) : AppLinkRequest

    /** The app's own site's [page]. */
    class SitePage(val page: OwnPage) : AppLinkRequest
}

/** The pages of the app's own site a member opens from the app: its front page, and its privacy policy. */
enum class OwnPage { WEBSITE, PRIVACY_POLICY }

/** A bug report the member confirmed, [note] in their words, from [context]: [id] is answered on the report status. */
class ReportRequest(val id: RequestId, val note: String, val context: ReportContext)
