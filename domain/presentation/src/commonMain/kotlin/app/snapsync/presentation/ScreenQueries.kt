package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportDestination
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.Store
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.query.RequestStatus
import app.snapsync.query.encryption.InviteRead
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.query.receiving.DownloadProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screen reads, in one bundle: the query layer's state and the request statuses, each a value the
 * container OBSERVES and folds into `UiState` — none of them is invoked. What the container invokes (the commands, the
 * lookups) and the build's constants ([BuildFacts]) stay apart for that reason.
 *
 * No defaults: a host states every source it wires, so a forgotten one is a compile error rather than a screen that
 * quietly never changes.
 */
class ScreenQueries(
    /** The member's own photos: how many are shared of how many the range holds. */
    val sharing: StateFlow<SharingProgressRead>,
    /** The others' photos: how many arrived of how many can. */
    val receiving: StateFlow<DownloadProgress>,
    /** The photo-library grant: the health's access rung, and whether a first join's confirm asks for it. */
    val grant: StateFlow<PhotoGrant>,
    /** The membership held, `null` while none is ([MembershipQuery.joined]): its presence is the reduction's top rung. */
    val membership: StateFlow<Membership?>,
    /** The joined event's key and the invite that carries it ([EventKeyView]). */
    val key: EventKeyView,
    /** The invite the app was opened with, read, until a screen has opened it. */
    val invites: StateFlow<InviteLinkVerdict?>,
    /** The member's creates, each answered under the request's id. */
    val creation: StateFlow<RequestStatus<CreateResult>>,
    /** The member's other requests, each answered under the request's id ([RequestStatusFlows]). */
    val requests: RequestStatusFlows,
    /** Whether this phone is verified, and how the service failed if it did ([DeviceVerification]). */
    val verification: DeviceVerification,
    /** Whether the service refuses this build as too old, and the version it named. */
    val versionRefusal: StateFlow<VersionRefusal?>,
    /** Whether the app can reach the network, and when a missing one comes back. */
    val network: NetworkStatusQuery,
    /** The device's mobile-data choice: the menu's switch, and the waiting line's cause. */
    val mobileData: MobileDataQuery,
    /** The app's latest return to the foreground: the create screen's draft follows it. */
    val foreground: StateFlow<ForegroundReturn>,
)

/**
 * What the screen reads of the joined event's key, when the event is ENCRYPTED (the encrypted file format,
 * `docs/architecture.md`) — read from the secure store, never from the membership.
 */
class EventKeyView(
    /** Whether this device holds it: a lost one is the joined screen's status line, and a reopened invite gives it back. */
    val presence: StateFlow<KeyPresence>,
    /** The joined event's invite as the member shares it, read at each reduction beside the membership ([InviteRead]). */
    val invite: InviteRead,
)

/**
 * The attestation's verdict, as the screen reads it: its halves travel together.
 *
 * [attested] is false only when this device's token is UNUSABLE (absent, unreadable, or expired) and the refresh could
 * not obtain one. Never false for a token merely due for renewal — that one still authorizes every upload, and saying
 * otherwise told a member sharing was paused with six days of token left (`SNAPSYNC-20`). [refusal] is why the service
 * refused this phone at the latest attempt to verify it, or `null` when it did not.
 */
class DeviceVerification(
    val attested: StateFlow<Boolean>,
    val refusal: StateFlow<DeviceRefusal?>,
    /**
     * How SnapSync's service failed the latest attempt to verify this phone — the same bracket as [attested]: a proof
     * the service could not renew is told as the service's condition, never as this device being unverifiable.
     */
    val fault: StateFlow<ServiceFault?>,
    /**
     * How SnapSync's service failed the latest request of any kind, or `null` while it answers — what the joined
     * screen's service line tells while photos wait. Forgotten at each opening, so an older failure is never the
     * opening's first word.
     */
    val service: StateFlow<ServiceFault?>,
)

/** The request statuses, read: a join, a settings change, a switch, a rename, and a bug report. */
class RequestStatusFlows(
    val join: StateFlow<RequestStatus<JoinCommit>>,
    val settings: StateFlow<RequestStatus<SettingsResult>>,
    val switch: StateFlow<RequestStatus<SwitchVerdict>>,
    val rename: StateFlow<RequestStatus<RenameResult>>,
    val report: StateFlow<RequestStatus<ReportOutcome>>,
)

/** The build's constants the screen states, as the composition names them. */
class BuildFacts(
    /** This build's store page, or `null` when it carries none — the update notice's one remedy. */
    val store: Store?,
    /** Where a bug report goes on this build, carried on every `UiState` so the sheet says it. */
    val reportDestination: ReportDestination,
    /** Which build this is — the app menu's footer. */
    val label: BuildLabel,
    /** What the event album holds on this phone: the surfaces' album note follows it. */
    val albumContents: AlbumContents,
)
