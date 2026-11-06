package app.snapsync.presentation

import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.query.RequestStatus
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ShareCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The attestation's verdict and the service's condition, as one input to the reduction. */
internal data class Trust(
    val attested: Boolean,
    val refusal: DeviceRefusal?,
    val attestFault: ServiceFault?,
    val serviceFault: ServiceFault?,
)

/** The membership and what its health is read from — what the screen observes (see [ScreenQueries]). */
internal data class Observed(
    val joined: Membership?,
    val permission: PhotoGrant,
    val sync: SharingProgressRead,
    val download: DownloadProgress,
    val attested: Boolean,
    /** What the member is told about the network — it reaches every layer, not only the joined one. */
    val network: NetworkAccess,
    /** The device's mobile-data choice: the menu's switch, and the waiting line's cause. */
    val mobileData: Boolean,
    /** Why the service refused this phone at the latest attempt to verify it, if it did. */
    val refusal: DeviceRefusal? = null,
    /** How SnapSync's service failed the latest attempt to verify this phone, if it did. */
    val attestFault: ServiceFault? = null,
    /** How SnapSync's service failed the latest request, if it is failing. */
    val serviceFault: ServiceFault? = null,
    /** The joined event's invite as the member shares it, read beside the membership ([EventKeyView.invite]). */
    val invite: String? = null,
)

/** The observed outcomes of what the member started: the join gate, a create, a rename — and a refused build. */
internal data class Interaction(
    val pending: PendingJoin?,
    val creation: RequestStatus<CreateResult>,
    val rename: RequestStatus<RenameResult>,
    val versionRefusal: VersionRefusal?,
)

/**
 * The presentation-owned cells, as one value: what the container itself writes, as distinct from the read-models
 * it observes. Opening a confirmation or editing the form touches no port and calls no command, so these are
 * container-local intents that reduce and nothing more — state rather than screen-local `remember`s because the
 * screen SHOWS them.
 */
internal data class Local(
    /**
     * The transient invalid-link error. An INPUT to the reduction, not a value beside it:
     * the create screen renders ONE banner, so the create state carries one error value and this is one of its
     * two causes.
     */
    val transientError: ScreenMessage? = null,
    /** What is drawn OVER the current layer (see [Overlays]). */
    val overlays: Overlays = Overlays(),
    /**
     * The member's uncommitted choices. ONE cell, because the two surfaces that ask for them are mutually exclusive
     * by construction — the join gate needs config ABSENT, the settings surface needs it PRESENT — and each open
     * re-seeds, so nothing can leak from one surface into the other.
     */
    val form: RangeForm,
    /** What the host has entered on the create form. */
    val create: CreateDraft,
    /** The range calendar open over a participation surface, or `null`. */
    val picking: Picking? = null,
    /**
     * Whether the joined layer is showing its settings surface (decision record `add-reconfigure-membership` D4:
     * opening is client-side navigation that touches no port). OWNED by the membership it was opened in (see
     * [Owned]): a switch, a leave or a fresh join cannot carry it into the next membership, whichever exit path ran
     * (B7).
     */
    val settings: Owned<SettingsSurface> = Owned(null, SettingsSurface.Closed),
    /**
     * A settings change that would withdraw photos from the event, held while the member is asked whether to stop
     * sharing them. Owned like [settings], so it never outlives its membership.
     */
    val pendingWithdrawal: Owned<SettingChange?> = Owned(null, null),
    /** The settings the last change applied — what the next change builds on (see `settingsInEffect`). */
    val lastApplied: Owned<SettingChange?> = Owned(null, null),
    /** The event the last rename was fired for: a rename result for an event no longer joined reads as Idle. */
    val renameOwner: EventId? = null,
    /** The member's latest rename request, until its answer is consumed — the only answer the screen reads. */
    val renameRequest: RequestId? = null,
    /** The member's latest create, until it is answered — preparing, however fast its run answers it. */
    val createRequest: RequestId? = null,
    /** Whether this device holds the joined event's key — copied from [EventKeyView.presence]. */
    val keyPresence: KeyPresence = KeyPresence.NotNeeded,
    /**
     * The shareable count for whichever surface is showing a range, computed over the
     * query bundle and reduced into the range. It starts Unavailable (no row) rather than Counting: a count is only
     * "being computed" once one has been asked for.
     */
    val shareCount: ShareCount = ShareCount.Unavailable,
    /** The menu's last mobile-data flip could not be saved; cleared by the next flip or a close. */
    val mobileDataNotSaved: Boolean = false,
    /**
     * The open report sheet was opened from "Report this" beside a refusal, so its report
     * carries the refused verification's facts. Kept out of [Overlays]: it is how the sheet was opened, not what is shown.
     */
    val verifiedReport: Boolean = false,
)

/**
 * [combine] over thirteen flows, typed. The library's typed overloads stop at five, and nesting them is not the same
 * thing: each level is a stage of its own, so one synchronous change to cells in different levels reaches the
 * screen as several emissions rather than one. The casts are positional and fixed by this signature.
 */
@Suppress("UNCHECKED_CAST", "LongParameterList")
internal fun <A, B, C, D, E, F, G, H, I, J, K, L, M, R> combineFlat(
    a: Flow<A>,
    b: Flow<B>,
    c: Flow<C>,
    d: Flow<D>,
    e: Flow<E>,
    f: Flow<F>,
    g: Flow<G>,
    h: Flow<H>,
    i: Flow<I>,
    j: Flow<J>,
    k: Flow<K>,
    l: Flow<L>,
    m: Flow<M>,
    transform: (A, B, C, D, E, F, G, H, I, J, K, L, M) -> R,
): Flow<R> = combine(listOf(a, b, c, d, e, f, g, h, i, j, k, l, m)) { v ->
    transform(
        v[0] as A, v[1] as B, v[2] as C, v[3] as D, v[4] as E, v[5] as F, v[6] as G,
        v[7] as H, v[8] as I, v[9] as J, v[10] as K, v[11] as L, v[12] as M,
    )
}
