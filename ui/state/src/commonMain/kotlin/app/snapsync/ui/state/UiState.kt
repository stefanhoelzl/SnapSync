package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * **Everything the screen shows**: the [layer] it is on, and the [overlays] drawn over it.
 *
 * The rule this type exists to make true is *what the screen SHOWS is `UiState`; how it DRAWS is local.*
 * A value the screen renders is a value this carries, so no host can supply the state and silently omit
 * something rendered — which is exactly what happened when the invite-link banner was left off one call
 * site and nothing failed.
 *
 * The split is by how a thing is drawn, not by what feature owns it: a [layer] is the body, an overlay is
 * drawn ON TOP of whatever body rendered. That is why the reconfigure surface is NOT an overlay — it
 * replaces the joined layer's body rather than covering it, so it lives on [Layer.Joined] as a surface
 * selection.
 */
@Serializable
data class UiState(
    val layer: Layer,
    val overlays: Overlays = Overlays(),
    /** Where a bug report goes on this build — what the report sheet says, and what its button reads. */
    val reportDestination: ReportTarget = ReportTarget.DEVELOPER,
    /** Which build this is — the app menu's footer. A constant of the build. */
    val build: BuildLine = BuildLine.UNKNOWN,
    /**
     * The device's mobile-data choice as the app menu shows it — a fact of the device, not of a layer, because the menu
     * offers it with and without an event.
     */
    val mobileData: MobileDataState = MobileDataState(),
)

/**
 * The app menu's mobile-data switch (decision record `changes/archive/2026-10-07-mobile-data-per-device`, D3): [on] is
 * the choice in effect, so a flip that could not be saved leaves the switch where it was, and [notSaved] says so until
 * the next flip or the menu closes.
 */
@Serializable
data class MobileDataState(val on: Boolean = true, val notSaved: Boolean = false)

/**
 * The build's version and build number as the app menu's footer shows them. Off-device
 * compositions know neither and say so ([UNKNOWN]) rather than showing a number nobody built.
 */
@Serializable
data class BuildLine(val version: String, val buildNumber: String) {
    companion object {
        val UNKNOWN: BuildLine = BuildLine(version = "unknown", buildNumber = "unknown")
    }
}

/**
 * The brief word on a bug report the user confirmed: handed to the reporting service,
 * kept on this device, or neither.
 */
@Serializable
enum class ReportNotice { SENT, SAVED, NOT_SENT }

/** Where the report sheet says a report goes: the developer, or this device. */
@Serializable
enum class ReportTarget { DEVELOPER, THIS_DEVICE }

/**
 * Whether [this] layer offers the app menu: everywhere except the event-settings surface and while a join or a create
 * is in progress. Loading an invite's details is a fetch, not a join, so it keeps the menu. One rule, read by the
 * reduction (which masks an open menu where it is not offered) and the screen (which draws the button), so the two
 * cannot disagree.
 */
val Layer.offersMenu: Boolean
    get() = when (this) {
        Layer.CreatingEvent -> false
        is Layer.Joined -> surface !is JoinedSurface.Reconfigure
        is Layer.JoiningEvent -> (phase as? JoinPhase.Detailed)?.step != JoinPhase.Detailed.Step.Committing
        else -> true
    }

/**
 * What is drawn OVER the current [Layer] — the confirmations and sheets.
 *
 * They have nothing in common as features (they belong to four capabilities); what groups them is the
 * only thing the layout cares about, which is that each is drawn over whatever body rendered. They live
 * here rather than on a layer because the app menu and the report sheet are reachable from (nearly)
 * **every** layer: a flag on `Joined` alone could not express them.
 *
 * What is TYPED into a sheet stays in the sheet (the stated IME exception): the presence and the seed are state, the
 * characters since it opened are not.
 */
@Serializable
data class Overlays(
    /** The destructive leave confirmation. */
    val confirmingLeave: Boolean = false,
    /** The rename dialog, opened by the pen beside the heading. */
    val renaming: Boolean = false,
    /** The invite's QR code, shown on request over the joined screen. */
    val showingQr: Boolean = false,
    /**
     * The diagnostic-dump sheet, opened from the app menu's "Report a problem" or by the hidden double-tap on the
     * app-name label. Reachable from every layer, which is why this bundle is not layer-scoped.
     */
    val reportingBug: Boolean = false,
    /**
     * What the sheet's description opens with, when the app offered the report itself — "Report this" beside a refusal.
     * `null` opens it empty, as the menu and the hidden gesture do.
     */
    val reportSeed: ScreenMessage? = null,
    /** The app menu's drawer; never shown where the layer does not [offersMenu]. */
    val menuOpen: Boolean = false,
    /** The brief word on the last confirmed report, until it clears or is tapped. */
    val reportNotice: ReportNotice? = null,
)

/**
 * Display-ready projection of config presence, permission, and the latest sync snapshot. Once an
 * event is configured the screen is always the **joined layer** (name · QR · share · leave); permission
 * and sync activity are just moods of the one-line status ([SyncHealth]). No counts are carried — the
 * screen answers "is it healthy?", not "how many of N".
 */
@Serializable
sealed interface Layer {
    /**
     * The backend refuses this build as too old, and nothing else the app can show is true.
     *
     * The TOP rung of the reduction, above config-absent, because it is not a mood of some other screen
     * — every metadata call is refused, so a joined member is not syncing, a create cannot succeed, and
     * a join cannot commit. Rendering the joined layer under a refusal would show a healthy-looking
     * event that is doing nothing, which is the exact failure this state exists to make legible.
     *
     * It is also the only screen in the app whose remedy is outside the app, which is why it carries the
     * link rather than composing one: see [store].
     */
    @Serializable
    data class UpdateRequired(
        /**
         * The oldest version the backend serves, when it named one. `null` when the refusal carried no
         * version — the screen then states that an update is needed without naming one, which is true,
         * rather than showing a number nobody sent (`docs/architecture.md`, "Absence is never silent").
         */
        val minimumVersion: String? = null,
        /**
         * The store whose page the button opens — the App Store on iPhone, Google Play on Android — or `null` when
         * this build carries no store page (an Android build before the Play listing is public), which renders no
         * button. The page itself is the build's, opened by the tap's intent; the screen only names the store.
         */
        val store: StoreName? = null,
    ) : Layer

    /**
     * The create-event landing layer (create-event), shown while no event is connected
     * (`config == null`) and no create is in flight. Carries an optional inline
     * [error] — the last create failure (sticky until the next attempt) or a transient
     * invalid link. Config-absent outranks everything, so this is the top reduction rung.
     */
    @Serializable
    data class CreateEvent(
        /** What the host has entered so far, and what it makes. */
        val form: CreateForm,
        val error: ScreenMessage? = null,
        /**
         * The network is missing: the hint line shows it instead of [error] — which stays, and returns with the network
         * — and Create is unavailable.
         */
        val network: NetworkNotice? = null,
    ) : Layer

    /**
     * A `POST /events` create request is in flight (`config == null`, creation status `InFlight`): a
     * preparing spinner with no input. Auto-resolves — success provisions config (off this layer),
     * failure returns to [CreateEvent] with an inline error.
     */
    @Serializable
    data object CreatingEvent : Layer

    /**
     * An interactive join confirmation is in progress for [eventId], shown as a full-screen "Join event" surface.
     * Entered whenever a join is pending and **no event is configured** — which covers a first join and, equally, a
     * **switch after its leave**: the switch's confirmation ([Joined.pendingSwitch]) runs only the leave, and the same
     * pending join lands here the moment the config clears, so the member configures the new membership on this surface
     * like any other joiner. [phase] drives it (loading details → ready/blocked/retry → committing → commit-failed).
     */
    @Serializable
    data class JoiningEvent(
        val eventId: String,
        /** Where the confirmation stands, and — once the details are loaded — the choices and the range they make. */
        val stage: JoinStage,
        /**
         * A transient, self-clearing notice over the join surface — the rejected-event-link message, the same cell
         * [Joined.notice] and [CreateEvent.error] read: a bad link is rejected wherever it arrives, so the message
         * reaches whichever layer is showing.
         */
        val notice: ScreenMessage? = null,
        /**
         * Confirming this join also raises iOS's photo-access dialog: no event is configured and access was never asked
         * — the sole state from which iOS can still raise it (from a refusal a request is a silent no-op, so promising
         * a dialog would be false). The surface says so beside its confirm and names the confirm for it.
         */
        val asksAccessOnJoin: Boolean = false,
        /**
         * The network is missing: the surface says so, Join is unavailable while Cancel stays, and a
         * [JoinPhase.LoadFailed] offers no Retry — the details load by themselves once the network returns.
         */
        val network: NetworkNotice? = null,
    ) : Layer {
        val phase: JoinPhase get() = stage.phase

        /** The member's choices and the range they make, or `null` before the details are loaded. */
        val range: RangeView? get() = (stage as? JoinStage.Loaded)?.range
    }

    /**
     * An event is connected (`config != null`) — the joined layer. Always renders the invite (name, QR, share) and
     * leave, regardless of permission; [health] is the one-line status mood. [pendingSwitch] overlays a leave-style
     * switch confirmation when an event link for a **different** event was scanned while joined.
     */
    @Serializable
    data class Joined(
        /**
         * The membership as the joined screen states it — **non-null**, because the reduction reaches this state only
         * when config is present. Every joined surface reads the event's name, id, dates
         * and settings from here; there is no second event-name value beside it.
         */
        val membership: JoinedEvent,
        /**
         * The invite link, derived **once** from the membership's event id, so the rendered QR and the shared link
         * cannot disagree. Carried rather than re-derived at each render site: the derivation depends on a build-time
         * link origin, so carrying
         * it is what makes a transported state render the origin the device actually shows.
         *
         * `null` when there is no whole invite to offer: the event is encrypted and this device cannot read its key
         * (lost, or locked since the phone started) — an invite is never offered without its key, so neither the share
         * action nor the QR code is.
         */
        val inviteUrl: String?,
        val health: SyncHealth,
        /** The device's today — how the dates line words a range ("today, …") — reduced from the host's clock. */
        val today: LocalDate,
        val pendingSwitch: PendingSwitch? = null,
        /** The joined layer offers "Choose more photos" — true exactly under a partial grant: a resting
         *  affordance, never an attention state. */
        val canChoosePhotos: Boolean = false,
        /**
         * Where the event is in its life — the dates line's "starts in …" / "ends in …" / "ended". The range itself
         * is [membership]'s [JoinedEvent.startsAt]/[JoinedEvent.endsAt]; this is only the part that needs a clock,
         * reduced from the host's minute tick so the screen never reads one.
         */
        val timing: EventTiming = EventTiming.Running(remaining = null),
        /**
         * The counts line: what was shared and received. Non-null exactly while [health] is [SyncHealth.InSync] or
         * [SyncHealth.Syncing] — in every other status the numbers are unknown or zero, and the one status line is the
         * thing to read.
         */
        val counts: SyncCounts? = null,
        /**
         * The rename lifecycle for the heading's dialog. A field, never a family and never a health rung: a rename
         * changes one string and one dialog's state, so it adds neither a layer nor a precedence step to the reduction.
         */
        val renameState: RenameState = RenameState.Idle,
        /**
         * Which body the joined layer is showing. A selection WITHIN the joined layer rather than a `UiState` family of
         * its own: the joined layer's health, pending switch and membership all still apply while the settings surface
         * is up — a sibling family would have to duplicate them to model a surface that is still, in every other
         * respect, the joined layer. It is not an overlay either: it replaces the body rather than covering it.
         *
         * Opening and closing it remains client-side navigation touching no port (`add-reconfigure-membership` D4's
         * reason), because the intent that changes this reduces and nothing more.
         */
        val surface: JoinedSurface = JoinedSurface.Status,
        /**
         * A transient, self-clearing notice over the joined layer — today only the rejected-event-link message.
         *
         * It exists because the gate raises that error from ANY layer: `onOpenUrl` decodes every
         * delivered link, and a member who scans a bad QR while already joined was told nothing at all —
         * the message was set, and the joined layer had nowhere to render it. "Nothing happened" and
         * "that code wasn't valid" are different answers, and the member could only see the first
         * (`docs/architecture.md`, "Absence is never silent").
         */
        val notice: ScreenMessage? = null,
        /**
         * The event's photos were deleted: the joined layer says the event is over and
         * offers only Leave, which does not ask — no invite, settings, rename or member count. The membership stays
         * until the member leaves or opens another invite; the app never leaves on its own.
         */
        val deleted: Boolean = false,
        /** How many members the event has now — joined and not left — as last read; `null` when unknown or deleted. */
        val members: Int? = null,
        /**
         * The ended event's note beneath the counts: whom it still waits for to finish sharing, or that everyone has.
         * Shown only once the range has ended and this member is up to date; `null` hides it.
         */
        val sharingNote: SharingNote? = null,
        /**
         * For an event created after its end, until when guests can still join — its open-until date, while that has
         * not passed. `null` for every other event, and once it has passed.
         */
        val joinableUntil: LocalDateTime? = null,
    ) : Layer {
        /** The event's declared end has passed (`now > endsAt`). Informational only: sync continues. */
        val ended: Boolean get() = timing == EventTiming.Ended
    }
}

/**
 * The rename dialog's condition, as the screen renders it.
 *
 * The reduction's own vocabulary, not the feature's: `RenameStatus.Failed` carries a REASON, and turning
 * a reason into words is a presentation job — the same one `CreationStatus.Failed` gets, whose copy is
 * formatted here too. Having one of the pair formatted in the reduction and the other in a composable
 * was the inconsistency this replaces.
 */
@Serializable
sealed interface RenameState {
    /** No rename in flight — the resting state, and where the screen resets it to. */
    @Serializable
    data object Idle : RenameState

    /** The request is running: the dialog is busy and refuses both confirm and dismissal. */
    @Serializable
    data object InFlight : RenameState

    /** The rename completed and the echoed name is persisted; the dialog closes. */
    @Serializable
    data object Succeeded : RenameState

    /** The request failed; the dialog stays open with [message] in a banner, never a reddened field. */
    @Serializable
    data class Failed(val message: ScreenMessage) : RenameState
}

/** Which body the joined layer shows. */
@Serializable
sealed interface JoinedSurface {
    /** The status surface: the invite hero, the health line, and the action cluster. */
    @Serializable
    data object Status : JoinedSurface

    /**
     * The event's settings, open over the joined status: the membership in effect, each change applied as it is made.
     */
    @Serializable
    data class Reconfigure(
        /** The membership in effect, as the controls show it. */
        val range: RangeView,
        /** The last change did not land: the controls show the setting still in effect, and the settings say so. */
        val saveFailed: Boolean = false,
        /** A change that would withdraw photos (sharing off, a narrower range) waits on "Stop sharing these photos?". */
        val askingToStopSharing: Boolean = false,
    ) : JoinedSurface
}

/**
 * A leave-style switch confirmation over the joined screen: an event link for a **different** [eventId]
 * was scanned while already joined. [phase] mirrors a first join's details load, and it is details-gated
 * the same way (a 404 blocks). Its confirm runs the **leave alone** — it commits no join and chooses
 * nothing; the join follows on the regular [Layer.JoiningEvent] surface, which the reduction reaches by
 * itself once the leave clears the config. So this state covers only the phases before that hand-off:
 * `Loading`, `Ready`, `NotFound`, `LoadFailed`.
 */
@Serializable
data class PendingSwitch(val eventId: String, val phase: JoinPhase)

/**
 * The phase of a join/switch confirmation surface, as the screen shows it. The details
 * fetch gates the confirm; the commit (enroll → provision) follows on confirm.
 *
 * Five phases carry no event: the fetch has not resolved ([Loading]), or there is nothing to be invited
 * to ([NotFound] / [LoadFailed] / [Closed] / [WrongLink]). Every other phase is a [Detailed] — the event's name plus
 * which step of the confirmation is showing — so a step that renders the event cannot be constructed without it.
 */
@Serializable
sealed interface JoinPhase {
    /**
     * The phase's name in a bug report's screen label (`JoiningEvent:<name>`, `Switch:<name>`) — stated, so renaming a
     * case cannot quietly change what reports say.
     */
    val screenName: String

    /** A phase that carries no event: the details are not loaded, or there is nothing to load. */
    @Serializable
    sealed interface Eventless : JoinPhase

    /** Fetching `GET /event/:id` details ("Loading event details…"). */
    @Serializable
    data object Loading : Eventless {
        override val screenName: String get() = "Loading"
    }

    /** The event does not exist (404) — an invalid/expired invite; no confirm offered. */
    @Serializable
    data object NotFound : Eventless {
        override val screenName: String get() = "NotFound"
    }

    /** The details fetch failed transiently (network/5xx); a Retry re-runs it. */
    @Serializable
    data object LoadFailed : Eventless {
        override val screenName: String get() = "LoadFailed"
    }

    /**
     * The event has closed, or finished and its photos were deleted (a closed or finished event cannot be joined) — at
     * load, or refused at the commit. Like [NotFound] it offers only Cancel: closing is final, so a Retry could never
     * succeed.
     */
    @Serializable
    data object Closed : Eventless {
        override val screenName: String get() = "Closed"
    }

    /**
     * The invite link does not open this event: the event is encrypted and the link carried no key, or another one
     * (the encrypted file format, `docs/architecture.md`) — a link cut short in sharing. Only Cancel: the remedy is
     * opening the whole invite again, which no Retry of this one can do.
     */
    @Serializable
    data object WrongLink : Eventless {
        override val screenName: String get() = "WrongLink"
    }

    /**
     * Details loaded: [eventName] is the fetched event's name, [step] is where in the confirmation the member is.
     */
    @Serializable
    data class Detailed(
        val eventName: String,
        val step: Step,
        /**
         * Why the service refused this phone, on [Step.DeviceRefused] only — captured when the join was refused, so a
         * later attempt to verify, which clears the attestation's own verdict while it runs, cannot change the sentence
         * under the member.
         */
        val refusal: ScreenMessage? = null,
        /** How many members the event has now — joined and not left — as the details read served; `null` if not. */
        val members: Int? = null,
    ) : JoinPhase {
        override val screenName: String get() = "Detailed"

        /**
         * Where a loaded confirmation stands.
         *
         * - [Ready] — the confirm (Join/Switch) is offered.
         * - [Committing] — the confirm was taken; enroll + provision are in flight.
         * - [CommitFailed] — the commit failed for a reason that may not hold next time (the network,
         *   the backend, the moment), or a switch's join failed after leaving; a Retry re-runs the join.
         * - [EventFull] — the event is at capacity. A SEPARATE step from
         *   [CommitFailed], and the difference is the whole reason it exists: capacity does not heal, so
         *   this step offers **no Retry**. It used to be collapsed into [CommitFailed], which pinned a
         *   Retry button on a wall — the member could press it forever, and nothing on the screen said
         *   what the wall was. Cancel is the only action, and it is a real one: it discards the pending
         *   join and returns to the front door.
         * - [DeviceRefused] — the service refused this phone as not genuine, and [refusal] says why. Unlike
         *   [EventFull] it keeps the Retry: a Retry tries to verify the phone again first, and a refusal the service
         *   stops making heals there. It is never shown as the generic [CommitFailed].
         */
        @Serializable
        enum class Step { Ready, Committing, CommitFailed, EventFull, DeviceRefused }
    }
}

/**
 * The join surface's stage: a phase with no event, or a [JoinPhase.Detailed] TOGETHER WITH
 * the member's choices and the range they make against the loaded window — so a surface that renders the range cannot
 * be constructed without one. The range is not part of the phase because a pending switch shows the same phase with
 * no range.
 */
@Serializable
sealed interface JoinStage {
    val phase: JoinPhase

    @Serializable
    data class Unloaded(override val phase: JoinPhase.Eventless) : JoinStage

    @Serializable
    data class Loaded(override val phase: JoinPhase.Detailed, val range: RangeView) : JoinStage
}

/** Where a loaded confirmation stands, or `null` on the three phases that are not loaded. */
val JoinPhase.step: JoinPhase.Detailed.Step? get() = (this as? JoinPhase.Detailed)?.step

/**
 * The joined-layer one-line health, the sole thing the status line renders. There is no standalone
 * "not syncing" state — the only reason contribution cannot run is missing permission ([NeedsAccess]),
 * the sole attention state (spec: sync-status).
 */
@Serializable
sealed interface SyncHealth {
    /**
     * The app has no usable photo access while an event is connected. [canAsk]: access was never asked, so tapping
     * the status line raises the system's dialog; otherwise it was refused, and tapping opens Settings. The only
     * health that carries a background. Sharing the invite still works with no access.
     */
    @Serializable
    data class NeedsAccess(val canAsk: Boolean) : SyncHealth

    /**
     * The device gives the app no usable network. Second in the ladder: below [NeedsAccess] — access decides what is
     * shared, and the member can fix it offline — and above everything else, [Unattested] included, whose most common
     * cause this names. Tappable only when [notice] is [NetworkNotice.BLOCKED]: it opens the app's Settings page.
     */
    @Serializable
    data class NoNetwork(val notice: NetworkNotice) : SyncHealth

    /**
     * The event has not begun: the membership's `startsAt` is still in the future. Carries nothing: *when* it starts is
     * the dates line's to say ([Layer.Joined.timing]), so the status line says only what the wait means — sharing
     * starts with the event.
     *
     * It ranks **below** [NeedsAccess] and **above** the snapshot-derived values. Permission outranks it because
     * permission is the only **actionable** state, and a member must resolve it *before* the event begins or they will
     * miss the start; burying it behind a clock line would ambush them with a permission prompt at the very moment the
     * party starts. Everything below is outranked because, before the start, nothing of the member's **can** be syncing
     * — the cutoff floor guarantees it, so a snapshot-derived line would say nothing true this does not say better.
     *
     * Unlike every other health, this one depends on **wall-clock time** rather than the ledger, so no
     * snapshot emission retires it — `StatusContainerHost` runs a foreground tick for that.
     */
    @Serializable
    data object NotStarted : SyncHealth

    /**
     * Uploads are blocked: this device holds no valid attestation token, and the attempt to obtain one **failed**.
     *
     * **A user should essentially never see this**, and that is by construction rather than by hope. The
     * app renews at every wake — and *opening the app is a wake*, so the very act of looking at this screen
     * triggers a renewal that clears it. It therefore only survives long enough to be rendered when the
     * renewal itself fails: the device is offline, or the backend is refusing us. Both are real, persistent
     * problems that no amount of waiting fixes, and both would otherwise be invisible — the uploads would
     * simply `401` forever behind a screen that cheerfully said "Syncing".
     *
     * It is deliberately NOT raised merely because a token is stale. A stale token that renews on the next
     * wake is a non-event, and flashing an error at the user for it would be noise.
     *
     * It ranks below [NotStarted] for the same reason it ranks below [NeedsAccess]: before the event
     * begins, nothing of this member's **can** be uploading, so an unusable token is not yet their problem
     * — and two attention lines at once would only compete.
     *
     * [refusal] is why the service refused this phone, when that is why no token could be obtained: the line then names
     * the cause. `null` — no answer, an expired proof that could not be renewed — names none.
     */
    @Serializable
    data class Unattested(val refusal: RefusalCause? = null) : SyncHealth

    /** Joined, permission granted, but persisted state has not been read yet — a neutral first frame. */
    @Serializable
    data object Loading : SyncHealth

    /**
     * The member switched both sharing and receiving off: they stay in the event and nothing moves. First after
     * [Loading] — access, the network, the start and verification all concern photos that no longer move — and reached
     * only once nothing is left in either direction, so work still draining in a switched-off direction reads as
     * [Syncing], never masked. Carries no counts.
     */
    @Serializable
    data object Inactive : SyncHealth

    /**
     * This device lost the joined event's key: nothing is uploaded or downloaded until the event's invite is opened
     * again. Right after [Inactive] — no access, network or start moves a photo without the key, and only the invite,
     * from someone in the group, brings it back. Not tappable; carries no counts.
     */
    @Serializable
    data object KeyLost : SyncHealth

    /** Everything shared and received — the settled state (no arrows). */
    @Serializable
    data object InSync : SyncHealth

    /**
     * Work remaining in at least one direction. Each arrow is shown by completeness and pulses by live
     * activity (spec: sync-status): [upload] from `synced < total` (shown) × `pending > 0` (pulse),
     * [download] from `downloaded < total` (shown) × `inFlight > 0` (pulse).
     *
     * [waitingForWifi]: the member keeps photos off mobile data and the phone is on a network that choice avoids, so
     * nothing can be running — the arrows stay still, whatever is handed to the platform, and the line says the photos
     * wait for Wi-Fi.
     */
    @Serializable
    data class Syncing(val upload: Arrow, val download: Arrow, val waitingForWifi: Boolean = false) : SyncHealth
}

/**
 * Why the app has no usable network, as the member is told: the two causes a user can tell apart by what fixes them.
 */
@Serializable
enum class NetworkNotice {
    /** The device has no network at all — only connecting helps. */
    OFFLINE,

    /** The operating system withholds the network from this app — a setting of the app's, in Settings. */
    BLOCKED,
}

/**
 * A message the screen shows, as a FACT: `ui/` alone turns it into words, so no sentence is written in the
 * domain and every one can be translated (`docs/architecture.md`, "Localization").
 */
@Serializable
enum class ScreenMessage {
    /** A delivered link that is not a SnapSync event. */
    INVALID_LINK,

    /** The backend refused the new event's name. */
    CREATE_NAME_REFUSED,

    /** The backend refused the new event's dates — its limit has shrunk since this build. */
    CREATE_DATES_REFUSED,

    /** The create request failed for any other reason. */
    CREATE_FAILED,

    /** The backend refused the event's new name. */
    RENAME_NAME_REFUSED,

    /** The rename failed for any other reason. */
    RENAME_FAILED,

    /** The service refused this phone: its system is modified. */
    DEVICE_MODIFIED,

    /** The service refused this phone: it could not be verified — never the user's doing, and a report is offered. */
    DEVICE_UNVERIFIABLE,

    /** The service refused this copy of the app: it is not the official one, so the store is where to get it. */
    APP_NOT_GENUINE,
    ;

    /** Whether the screen offers to report this — only for a refusal the user can do nothing about but tell us. */
    val offersReport: Boolean get() = this == DEVICE_UNVERIFIABLE
}

/**
 * The joined membership as the joined screen states it: its name and id, its dates and the
 * member's own range — each a moment already in the device's zone — and what the member chose to do.
 */
@Serializable
data class JoinedEvent(
    val eventId: String,
    val name: String,
    /** The event's declared start and end. */
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    /** The member's own capture-date range: which of their photos the event takes. */
    val sharesFrom: LocalDateTime,
    val sharesUntil: LocalDateTime,
    /** The member shares their photos into the event. */
    val shares: Boolean,
    /** The member receives everyone else's photos. */
    val receives: Boolean,
    /** Received photos are collected into the event album. */
    val savesToAlbum: Boolean,
)

/** The ended event's note beneath the counts: whom it still waits for to finish sharing, or that everyone has. */
@Serializable
sealed interface SharingNote {
    /** [members] of the event's present members have not yet finished sharing. */
    @Serializable
    data class WaitingFor(val members: Int) : SharingNote

    /** Every member still in the event has shared all their photos. */
    @Serializable
    data object EveryoneShared : SharingNote
}

/**
 * A participation surface's choices and the range they make, as the join gate and the event settings show them.
 * Every moment is already in the device's zone.
 */
@Serializable
data class RangeView(
    val shareOn: Boolean,
    val receiveOn: Boolean,
    val saveToAlbum: Boolean,
    /** How this phone holds the event album — the album note follows it. */
    val albumStyle: AlbumStyle,
    /** Which range choice is selected. */
    val preset: RangeChoice,
    /** The range the choice makes: which of the member's photos are shared. */
    val from: LocalDateTime,
    val until: LocalDateTime,
    /** The event's window, which the range lies within. */
    val windowStart: LocalDateTime,
    val windowEnd: LocalDateTime,
    /** Whether "From now" is offered: now lies within the window. */
    val nowAvailable: Boolean,
    /** The device's today, for the calendar. */
    val today: LocalDate,
    /** Whether the confirm is offered: at least one direction is on. */
    val commitEnabled: Boolean,
    /** How many of the member's own photos the range would share. */
    val shareCount: ShareCount,
    /** The range calendar, while it is open over the surface; `null` while closed. */
    val picker: PickerView? = null,
)

/**
 * How this phone holds an event album, as the album note says it: an album the received
 * photos are added to, or a folder they are saved into.
 */
@Serializable
enum class AlbumStyle { COLLECTION, FOLDER }

/** The store an update button opens. */
@Serializable
enum class StoreName { APP_STORE, GOOGLE_PLAY }

/** Why the service refused this phone, as the status line names it. */
@Serializable
enum class RefusalCause { DEVICE_MODIFIED, DEVICE_UNVERIFIABLE, APP_NOT_GENUINE }

/** The live shareable count, as the row renders it. */
@Serializable
sealed interface ShareCount {
    /** Being (re)computed — the row shows `counting…`. */
    @Serializable
    data object Counting : ShareCount

    /** No count is available (no usable grant, or the read failed) — the row is omitted entirely. */
    @Serializable
    data object Unavailable : ShareCount

    /** The chosen range would share [count] of the member's photos. */
    @Serializable
    data class Ready(val count: Int) : ShareCount
}

/**
 * The create form: the name and the range the host has entered — kept by the app across a
 * failed create's round trip, and started over after a long absence — and what they make.
 */
@Serializable
data class CreateForm(
    val name: String,
    /** Which draft this is: a new value is a fresh draft, so a field holding its own copy of [name] takes it again. */
    val draft: Int,
    val picker: PickerView,
    /** The next thing the host still has to do before Create is offered — or, once there is none, how long it lasts. */
    val nextStep: CreateStep,
    /** The draft may be created: complete, and inside the event window. */
    val canCreate: Boolean,
    /** The longest event window, in whole days — what the range's note states. */
    val maxDays: Int,
    /**
     * The question a Create for a range that is already over asks first — how long guests have to join — or `null`
     * while it is not asked.
     */
    val keepOpen: KeepOpenSheet? = null,
)

/** The sheet that asks how long guests have to join an event created after its end: one [choices] each. */
@Serializable
data class KeepOpenSheet(val choices: List<KeepOpenChoice>)

/** Guests may join for [days] more days — until [until], the day the sheet names beside it. */
@Serializable
data class KeepOpenChoice(val days: Int, val until: LocalDate)

/** What the create form asks for next, in the order it asks. */
@Serializable
sealed interface CreateStep {
    @Serializable
    data object Name : CreateStep

    @Serializable
    data object EndTime : CreateStep

    /** Nothing is missing: the event lasts [lasts]. */
    @Serializable
    data class Complete(val lasts: CoarseDuration) : CreateStep
}

/**
 * A range calendar with its time wheels, as the screen draws it: the range
 * so far — its start, its last day, and the end time an hour and a minute at a time, each `null` while blank — and
 * what may be picked next. The rules that decide all of it are the app's; the calendar only draws it and reports
 * what was touched.
 */
@Serializable
data class PickerView(
    val from: LocalDateTime,
    val endDay: LocalDate,
    val untilHour: Int?,
    val untilMinute: Int?,
    /** The next tap on a day places the last day (rather than starting a new range). */
    val endPending: Boolean,
    /** The device's date, which the calendar marks. */
    val today: LocalDate,
    /** The first and the last day that may be picked; `null` where any day may. */
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    /** The start times and the end times that may be settled on; `null` where none may. */
    val fromTimes: TimeSpan?,
    val untilTimes: TimeSpan?,
    /** The range is complete and inside its bounds — what a calendar that confirms offers OK on. */
    val complete: Boolean,
) {
    /** The chosen end, or `null` while its time is still blank. */
    val until: LocalDateTime?
        get() = if (untilHour != null && untilMinute != null) {
            LocalDateTime(
                endDay,
                LocalTime(untilHour, untilMinute),
            )
        } else {
            null
        }
}

/** The times of day from [first] to [last], both included, to the minute. */
@Serializable
data class TimeSpan(val first: LocalTime, val last: LocalTime) {
    operator fun contains(time: LocalTime): Boolean = time in first..last
}

/** A duration named by its coarsest whole unit ("lasts 2 weeks"). */
@Serializable
sealed interface CoarseDuration {
    @Serializable
    data class Weeks(val count: Int) : CoarseDuration

    @Serializable
    data class Days(val count: Int) : CoarseDuration

    @Serializable
    data class Hours(val count: Int) : CoarseDuration

    @Serializable
    data class Minutes(val count: Int) : CoarseDuration

    @Serializable
    data object UnderAMinute : CoarseDuration
}
