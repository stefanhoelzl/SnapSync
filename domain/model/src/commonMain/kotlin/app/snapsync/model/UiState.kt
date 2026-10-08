package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * **Everything the screen shows** (capability `sync-status`): the [layer] it is on, and the
 * [overlays] drawn over it.
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
    val reportDestination: ReportDestination = ReportDestination.DEVELOPER,
    /** Which build this is — the app menu's footer (capability `sync-status`). A constant of the build. */
    val build: BuildLabel = BuildLabel.UNKNOWN,
    /**
     * The device's mobile-data choice as the app menu shows it (capability `mobile-data`) — a fact of the device, not
     * of a layer, because the menu offers it with and without an event.
     */
    val mobileData: MobileDataState = MobileDataState(),
)

/**
 * The app menu's mobile-data switch (capability `mobile-data`; decision record `changes/archive/2026-10-07-mobile-data-per-device`, D3):
 * [on] is the choice in effect, so a flip that could not be saved leaves the switch where it was, and [notSaved] says
 * so until the next flip or the menu closes.
 */
@Serializable
data class MobileDataState(val on: Boolean = true, val notSaved: Boolean = false)

/**
 * The build's version and build number as the app menu shows them (capability `sync-status`). Off-device
 * compositions know neither and say so ([UNKNOWN]) rather than showing a number nobody built.
 */
@Serializable
data class BuildLabel(val version: String, val buildNumber: String) {
    companion object {
        val UNKNOWN: BuildLabel = BuildLabel(version = "unknown", buildNumber = "unknown")
    }
}

/**
 * What became of a bug report the user confirmed (capability `privacy-security`), as the app briefly says it: handed
 * to the reporting service, kept on this device, or neither. [SENT] is a hand-off, never a delivery — the channel
 * may queue and retry, and nothing reports back.
 */
@Serializable
enum class ReportOutcome { SENT, SAVED, NOT_SENT }

/**
 * Whether [this] layer offers the app menu (capability `sync-status`): everywhere except the event-settings surface
 * and while a join or a create is in progress. Loading an invite's details is a fetch, not a join, so it keeps the
 * menu. One rule, read by the reduction (which masks an open menu where it is not offered) and the screen (which
 * draws the button), so the two cannot disagree.
 */
val Layer.offersMenu: Boolean
    get() = when (this) {
        Layer.CreatingEvent -> false
        is Layer.Joined -> surface !is JoinedSurface.Reconfigure
        is Layer.JoiningEvent -> (phase as? JoinPhase.Detailed)?.step != JoinPhase.Detailed.Step.Committing
        else -> true
    }

/**
 * Where a bug report goes (capability `privacy-security`) — a constant of the build: a distributed build sends it to
 * the developer's error-tracking service; a build that reports nowhere keeps it on this device.
 */
@Serializable
enum class ReportDestination { DEVELOPER, THIS_DEVICE }

/**
 * What is drawn OVER the current [Layer] — the confirmations and sheets.
 *
 * They have nothing in common as features (they belong to four capabilities); what groups them is the
 * only thing the layout cares about, which is that each is drawn over whatever body rendered. They live
 * here rather than on a layer because the app menu and the report sheet are reachable from (nearly)
 * **every** layer: a flag on `Joined` alone could not express them.
 *
 * What is TYPED into a sheet stays in the sheet (capability `sync-status`, the stated IME
 * exception): the presence and the seed are state, the characters since it opened are not.
 */
@Serializable
data class Overlays(
    /** The destructive leave confirmation. */
    val confirmingLeave: Boolean = false,
    /** The rename dialog, opened by the pen beside the heading (capability `manage-membership`). */
    val renaming: Boolean = false,
    /** The invite's QR code, shown on request over the joined screen (capability `manage-membership`). */
    val showingQr: Boolean = false,
    /**
     * The diagnostic-dump sheet (capability `privacy-security`), opened from the app menu's "Report a problem" or
     * by the hidden double-tap on the app-name label. Reachable from every layer, which is why this bundle is not
     * layer-scoped.
     */
    val reportingBug: Boolean = false,
    /**
     * What the sheet's description opens with, when the app offered the report itself — "Report this" beside a refusal
     * (capability `privacy-security`). `null` opens it empty, as the menu and the hidden gesture do.
     */
    val reportSeed: ScreenMessage? = null,
    /** The app menu's drawer (capability `sync-status`); never shown where the layer does not [offersMenu]. */
    val menuOpen: Boolean = false,
    /** The brief word on the last confirmed report (capability `privacy-security`), until it clears or is tapped. */
    val reportNotice: ReportOutcome? = null,
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
     * The backend refuses this build as too old (capability `app-update-required`), and nothing else the
     * app can show is true.
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
         * The build's store page — the App Store on iPhone, Google Play on Android — or `null` when this build
         * carries none (an Android build before the Play listing is public).
         *
         * Carried rather than composed, and null rather than derived from the bundle id, because the
         * country-less form of that URL is measurably a **404** while availability is limited to one
         * storefront — an offer that looks right and lands nowhere, on the one screen the user reaches
         * because something is already wrong. `null` renders no button.
         */
        val store: StoreLink? = null,
    ) : Layer

    /**
     * The create-event landing layer (create-event), shown while no event is connected
     * (`config == null`) and no create is in flight. Carries an optional inline
     * [error] — the last create failure (sticky until the next attempt) or a transient
     * invalid link. Config-absent outranks everything, so this is the top reduction rung.
     * [draft] is where the screen's draft stands against the app's foreground life ([CreateDraftSession]).
     */
    @Serializable
    data class CreateEvent(
        val error: ScreenMessage? = null,
        val draft: CreateDraftSession = CreateDraftSession(),
        /**
         * The network is missing (capability `create-event`, "Without a network, Create waits"): the hint line shows
         * it instead of [error] — which stays, and returns with the network — and Create is unavailable.
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
     * An interactive join confirmation is in progress for [eventId] (capability `join-event`), shown
     * as a full-screen "Join event" surface. Entered whenever a join is pending and **no event is
     * configured** — which covers a first join and, equally, a **switch after its leave**: the switch's
     * confirmation ([Joined.pendingSwitch]) runs only the leave, and the same pending join lands here the
     * moment the config clears, so the member configures the new membership on this surface like any
     * other joiner. [phase] drives it (loading details → ready/blocked/retry → committing →
     * commit-failed).
     */
    @Serializable
    data class JoiningEvent(
        val eventId: String,
        /** Where the confirmation stands, and — once the details are loaded — the range a confirm would commit. */
        val stage: JoinStage,
        /** The member's uncommitted choices on this surface. Seeded when the details load. */
        val form: RangeForm = RangeForm(),
        /**
         * A transient, self-clearing notice over the join surface — the rejected-event-link message
         * (capability `join-event`), the same cell [Joined.notice] and [CreateEvent.error] read: a bad
         * link is rejected wherever it arrives, so the message reaches whichever layer is showing.
         */
        val notice: ScreenMessage? = null,
        /**
         * Confirming this join also raises iOS's photo-access dialog (capability `join-event`,
         * `photo-access`): no event is configured and access was never asked — the sole state from which
         * iOS can still raise it (from a refusal a request is a silent no-op, so promising a dialog would
         * be false). The surface says so beside its confirm and names the confirm for it.
         */
        val asksAccessOnJoin: Boolean = false,
        /**
         * The network is missing (capability `join-event`, "Without a network, the join screen waits for one"): the
         * surface says so, Join is unavailable while Cancel stays, and a [JoinPhase.LoadFailed] offers no Retry —
         * the details load by themselves once the network returns.
         */
        val network: NetworkNotice? = null,
    ) : Layer {
        val phase: JoinPhase get() = stage.phase

        /** [form] resolved against the loaded event's window, or `null` before there is one. */
        val range: ResolvedRange? get() = (stage as? JoinStage.Loaded)?.range
    }

    /**
     * An event is connected (`config != null`) — the joined layer. Always renders the invite (name,
     * QR, share) and leave, regardless of permission; [health] is the one-line status mood.
     * [pendingSwitch] overlays a leave-style switch confirmation when an event link for a **different**
     * event was scanned while joined (capability `join-event`).
     */
    @Serializable
    data class Joined(
        /**
         * The persisted membership — **non-null**, because the reduction reaches this state only when
         * config is present. A nullable one would state a combination the reduction makes unreachable and
         * force the screen to re-check it (capability `sync-status`). Every joined surface reads
         * the event's name, id and settings from here; there is no second event-name value beside it.
         */
        val membership: EventConfig,
        /**
         * The invite link, derived **once** here from [membership]'s `eventId` (capability
         * `manage-membership`), so the rendered QR and the shared link cannot disagree. Carried rather than
         * re-derived at each render site: the derivation depends on a build-time link origin, so carrying
         * it is what makes a transported state render the origin the device actually shows.
         *
         * `null` when there is no whole invite to offer: the event is encrypted and this device cannot read its key
         * (lost, or locked since the phone started) — an invite is never offered without its key, so neither the share
         * action nor the QR code is (capability `manage-membership`).
         */
        val inviteUrl: String?,
        val health: SyncHealth,
        val pendingSwitch: PendingSwitch? = null,
        /** The joined layer offers "Choose more photos" — true exactly under a partial grant
         *  (capability `photo-access`): a resting affordance, never an attention state. */
        val canChoosePhotos: Boolean = false,
        /**
         * Where the event is in its life — the dates line's "starts in …" / "ends in …" / "ended" (capability
         * `sync-status`). The range itself is [membership]'s `startsAt`/`endsAt`; this is only the part that
         * needs a clock, reduced from the host's minute tick so the screen never reads one.
         */
        val timing: EventTiming = EventTiming.Running(remaining = null),
        /**
         * The counts line (capability `sync-status`): what was shared and received. Non-null exactly while
         * [health] is [SyncHealth.InSync] or [SyncHealth.Syncing] — in every other status the numbers are
         * unknown or zero, and the one status line is the thing to read.
         */
        val counts: SyncCounts? = null,
        /**
         * The rename lifecycle (capability `manage-membership`) for the heading's dialog. A field, never a
         * family and never a health rung: a rename changes one string and one dialog's state, so it adds
         * neither a layer nor a precedence step to the reduction.
         */
        val renameState: RenameState = RenameState.Idle,
        /**
         * Which body the joined layer is showing (capability `manage-membership`). A selection
         * WITHIN the joined layer rather than a `UiState` family of its own: the joined layer's health,
         * pending switch and membership all still apply while the settings surface is up — a sibling
         * family would have to duplicate them to model a surface that is still, in every other respect,
         * the joined layer. It is not an overlay either: it replaces the body rather than covering it.
         *
         * Opening and closing it remains client-side navigation touching no port
         * (`manage-membership` D4's reason), because the intent that changes this reduces and
         * nothing more.
         */
        val surface: JoinedSurface = JoinedSurface.Status,
        /**
         * A transient, self-clearing notice over the joined layer — today only the rejected-event-link
         * message (capability `join-event`).
         *
         * It exists because the gate raises that error from ANY layer: `onOpenUrl` decodes every
         * delivered link, and a member who scans a bad QR while already joined was told nothing at all —
         * the message was set, and the joined layer had nowhere to render it. "Nothing happened" and
         * "that code wasn't valid" are different answers, and the member could only see the first
         * (`docs/architecture.md`, "Absence is never silent").
         */
        val notice: ScreenMessage? = null,
        /**
         * The event has CLOSED (capability `event-lifetime`): the joined layer offers only Leave — no invite, share,
         * settings or rename — while its last photos arrive; the membership then ends on its own.
         */
        val closed: Boolean = false,
        /**
         * The ended event's waiting line (capability `sync-status`): shown only while the range has ended, the
         * event has not closed and this member's own part is in sync — how many of the event's current members it
         * is still waiting for to finish sharing. `null` hides it.
         */
        val waiting: MemberCounts? = null,
    ) : Layer {
        /** The event's declared end has passed (`now > endsAt`). Informational only: sync continues. */
        val ended: Boolean get() = timing == EventTiming.Ended
    }
}

/**
 * The rename dialog's condition, as the screen renders it (capability `manage-membership`).
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
     * The event's settings, open over the joined status (capability `manage-membership`): the membership in effect,
     * each change applied as it is made.
     */
    @Serializable
    data class Reconfigure(
        val form: RangeForm,
        val range: ResolvedRange,
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
 * The event facts a join confirmation renders and commits (capability `join-event`).
 *
 * Stated ONCE per confirmation rather than repeated on each phase that needs them. They used to be four
 * fields declared on four separate phases — sixteen declarations of four facts — because a Retry commits
 * WITHOUT passing back through the loaded phase, so every phase that could precede a commit had to carry
 * them. Giving them a home ends that: the phases that have details hold this, the phases that have none
 * hold nothing, and no phase restates another's fields.
 *
 * [startsAt] is the event's **start date** — already a canonical UTC `…Z` string (`BackendEventDirectory`
 * normalizes it and fails the load rather than invent one). It is both the range row's lower **default**
 * and its **floor** (capability `photo-sharing`): the row cannot be empty and the confirm cannot
 * join below it, so joining at whole-library scope is unrepresentable. It also decides whether "From
 * now" is offered — when it is in the **future**, that preset would clamp to this same instant.
 *
 * [endsAt] is the event's **end date** — the range row's upper **default** and its **ceiling**
 * (capability `photo-sharing`).
 *
 * [deletesAt] is the event's **retention deadline** (capability `event-lifetime`) — **server-derived** and
 * carried verbatim. The commit persists it as the offline
 * witness of the self-leave (capability `manage-membership`). It is never computed on the device: a
 * client-side copy of the retention constant would promise a date the backend will not honour, silently.
 */
@Serializable
data class EventDetails(
    val name: String,
    val startsAt: EventStart,
    val endsAt: EventEnd,
    val deletesAt: DeletesAt,
)

/**
 * The phase of a join/switch confirmation surface (capability `join-event`). The details fetch gates the
 * confirm; the commit (enroll → provision) follows on confirm.
 *
 * Four phases carry no event: the fetch has not resolved ([Loading]), or there is nothing to be invited
 * to ([NotFound] / [LoadFailed] / [Closed]). Every other phase is a [Detailed] — details plus which step of the
 * confirmation is showing — so **a step that renders or commits the event's facts cannot be constructed
 * without them.** A flat nullable field would have removed the same duplication while making
 * `Ready`-without-details representable; that trades a type guarantee for tidiness, which is the wrong
 * direction.
 */
@Serializable
sealed interface JoinPhase {
    /** A phase that carries no event: the details are not loaded, or there is nothing to load. */
    @Serializable
    sealed interface Eventless : JoinPhase

    /** Fetching `GET /event/:id` details ("Loading event details…"). */
    @Serializable
    data object Loading : Eventless

    /** The event does not exist (404) — an invalid/expired invite; no confirm offered. */
    @Serializable
    data object NotFound : Eventless

    /** The details fetch failed transiently (network/5xx); a Retry re-runs it. */
    @Serializable
    data object LoadFailed : Eventless

    /**
     * The event has closed, or finished and its photos were deleted (capability `join-event`, "A closed or finished
     * event cannot be joined") — at load, or refused at the commit. Like [NotFound] it offers only Cancel: closing is
     * final, so a Retry could never succeed.
     */
    @Serializable
    data object Closed : Eventless

    /**
     * The invite link does not open this event: the event is encrypted and the link carried no key, or another one
     * (the encrypted file format, `docs/architecture.md`) — a link cut short in sharing. Only Cancel: the remedy is
     * opening the whole invite again, which no Retry of this one can do.
     */
    @Serializable
    data object WrongLink : Eventless

    /**
     * Details loaded: [event] is what was fetched, [step] is where in the confirmation the member is.
     *
     * The step advances by user action only — the details never change under it, which is exactly why
     * they sit beside it rather than inside it.
     */
    @Serializable
    data class Detailed(
        val event: EventDetails,
        val step: Step,
        /**
         * Why the service refused this phone, on [Step.DeviceRefused] only (capability `join-event`, "A refused phone is
         * told why it cannot join") — captured when the join was refused, so a later attempt to verify, which clears
         * the attestation's own verdict while it runs, cannot change the sentence under the member.
         */
        val refusal: ScreenMessage? = null,
    ) : JoinPhase {
        /**
         * Where a loaded confirmation stands.
         *
         * - [Ready] — the confirm (Join/Switch) is offered.
         * - [Committing] — the confirm was taken; enroll + provision are in flight.
         * - [CommitFailed] — the commit failed for a reason that may not hold next time (the network,
         *   the backend, the moment), or a switch's join failed after leaving; a Retry re-runs the join.
         *   The retry commits **without** passing back through [Ready], which is why the details live on
         *   [Detailed] rather than on the loaded step alone.
         * - [EventFull] — the event is at capacity (capability `join-event`). A SEPARATE step from
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
 * The join surface's stage (capability `join-event`): a phase with no event, or a [JoinPhase.Detailed] TOGETHER WITH
 * the range its form resolves to against the loaded window — so a surface that renders or commits the range cannot be
 * constructed without one. The range is not part of the phase because the phase is also the pending join's own record,
 * where a range resolved from the form and the share count would be stale.
 */
@Serializable
sealed interface JoinStage {
    val phase: JoinPhase

    @Serializable
    data class Unloaded(override val phase: JoinPhase.Eventless) : JoinStage

    @Serializable
    data class Loaded(override val phase: JoinPhase.Detailed, val range: ResolvedRange) : JoinStage
}

/** The loaded event facts, or `null` on the three phases that carry none. */
val JoinPhase.details: EventDetails? get() = (this as? JoinPhase.Detailed)?.event

/** Where a loaded confirmation stands, or `null` on the three phases that are not loaded. */
val JoinPhase.step: JoinPhase.Detailed.Step? get() = (this as? JoinPhase.Detailed)?.step

/** A loaded phase at [step], built from [details] — the shape every construction site takes. */
fun joinPhase(step: JoinPhase.Detailed.Step, details: EventDetails): JoinPhase.Detailed =
    JoinPhase.Detailed(details, step)

/**
 * The joined-layer one-line health, the sole thing the status line renders. There is no standalone
 * "not syncing" state — the only reason contribution cannot run is missing permission ([NeedsAccess]),
 * the sole attention state (spec: sync-status).
 */
@Serializable
sealed interface SyncHealth {
    /**
     * Permission is not `GRANTED` while an event is connected. [permission] is `NOT_DETERMINED`
     * (never asked → tapping the status line requests it) or `DENIED` (tapping opens Settings). The
     * only health that carries a background. Sharing the invite still works with no access.
     */
    @Serializable
    data class NeedsAccess(val permission: GalleryAccess) : SyncHealth

    /**
     * The device gives the app no usable network (capability `sync-status`, "The app says when it cannot reach the
     * network"). Second in the ladder: below [NeedsAccess] — access decides what is shared, and the member can fix it
     * offline — and above everything else, [Unattested] included, whose most common cause this names. Tappable only
     * when [notice] is [NetworkNotice.BLOCKED]: it opens the app's Settings page.
     */
    @Serializable
    data class NoNetwork(val notice: NetworkNotice) : SyncHealth

    /**
     * The event has not begun: the membership's `startsAt` is still in the future (capability
     * `sync-status`). Carries nothing: *when* it starts is the dates line's to say ([Layer.Joined.timing]),
     * so the status line says only what the wait means — sharing starts with the event.
     *
     * It ranks **below** [NeedsAccess] and **above** the snapshot-derived values. Permission outranks it
     * because permission is the only **actionable** state, and a member must resolve it *before* the event
     * begins or they will miss the start; burying it behind a clock line would ambush them with a
     * permission prompt at the very moment the party starts. Everything below is outranked because, before
     * the start, nothing of the member's **can** be syncing — the cutoff floor guarantees it (capability
     * `photo-sharing`), so a snapshot-derived line would say nothing true this does not say better.
     *
     * Unlike every other health, this one depends on **wall-clock time** rather than the ledger, so no
     * snapshot emission retires it — `StatusContainerHost` runs a foreground tick for that.
     */
    @Serializable
    data object NotStarted : SyncHealth

    /**
     * Uploads are blocked: this device holds no valid attestation token, and the attempt to obtain one
     * **failed** (capability `privacy-security`).
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
     * [refusal] is why the service refused this phone, when that is why no token could be obtained: the line then
     * names the cause (capability `sync-status`). `null` — no answer, an expired proof that could not be renewed —
     * names none.
     */
    @Serializable
    data class Unattested(val refusal: DeviceRefusal? = null) : SyncHealth

    /** Joined, permission granted, but persisted state has not been read yet — a neutral first frame. */
    @Serializable
    data object Loading : SyncHealth

    /**
     * The member switched both sharing and receiving off (capability `sync-status`): they stay in the event and
     * nothing moves. First after [Loading] — access, the network, the start and verification all concern photos
     * that no longer move — and reached only once nothing is left in either direction, so work still draining in a
     * switched-off direction reads as [Syncing], never masked. Carries no counts.
     */
    @Serializable
    data object Inactive : SyncHealth

    /**
     * This device lost the joined event's key (capability `sync-status`): nothing is uploaded or downloaded until the
     * event's invite is opened again. Right after [Inactive] — no access, network or start moves a photo without the
     * key, and only the invite, from someone in the group, brings it back. Not tappable; carries no counts.
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
     * [waitingForWifi] (capability `mobile-data`): the member keeps photos off mobile data and the phone is on a
     * network that choice avoids, so nothing can be running — the arrows stay still, whatever is handed to the platform,
     * and the line says the photos wait for Wi-Fi.
     */
    @Serializable
    data class Syncing(val upload: Arrow, val download: Arrow, val waitingForWifi: Boolean = false) : SyncHealth
}

/**
 * Why the app has no usable network, as the member is told (capability `sync-status`): the two causes a user can tell
 * apart by what fixes them.
 */
@Serializable
enum class NetworkNotice {
    /** The device has no network at all — only connecting helps. */
    OFFLINE,

    /** The operating system withholds the network from this app — a setting of the app's, in Settings. */
    BLOCKED,
    ;

    companion object {
        /** The notice for [access], or `null` when there is nothing to tell. */
        fun of(access: NetworkAccess): NetworkNotice? = when (access) {
            is NetworkAccess.Online -> null
            NetworkAccess.Offline -> OFFLINE
            NetworkAccess.Blocked -> BLOCKED
        }
    }
}

/**
 * A message the screen shows, as a FACT: `ui/` alone turns it into words, so no sentence is written in the
 * domain and every one can be translated (`docs/architecture.md`, "Localization").
 */
@Serializable
enum class ScreenMessage {
    /** A delivered link that is not a SnapSync event (capability `join-event`). */
    INVALID_LINK,

    /** The backend refused the new event's name (capability `create-event`). */
    CREATE_NAME_REFUSED,

    /** The backend refused the new event's dates — its limit has shrunk since this build (capability `create-event`). */
    CREATE_DATES_REFUSED,

    /** The create request failed for any other reason (capability `create-event`). */
    CREATE_FAILED,

    /** The backend refused the event's new name (capability `manage-membership`). */
    RENAME_NAME_REFUSED,

    /** The rename failed for any other reason (capability `manage-membership`). */
    RENAME_FAILED,

    /** The service refused this phone: its system is modified (capability `privacy-security`, "A refused phone is told why"). */
    DEVICE_MODIFIED,

    /** The service refused this phone: it could not be verified — never the user's doing, and a report is offered. */
    DEVICE_UNVERIFIABLE,

    /** The service refused this copy of the app: it is not the official one, so the store is where to get it. */
    APP_NOT_GENUINE,
    ;

    /** Whether the screen offers to report this — only for a refusal the user can do nothing about but tell us. */
    val offersReport: Boolean get() = this == DEVICE_UNVERIFIABLE

    companion object {
        /** What the screen says for [refusal]. */
        fun of(refusal: DeviceRefusal): ScreenMessage = when (refusal) {
            DeviceRefusal.DEVICE_MODIFIED -> DEVICE_MODIFIED
            DeviceRefusal.DEVICE_UNVERIFIABLE -> DEVICE_UNVERIFIABLE
            DeviceRefusal.APP_NOT_GENUINE -> APP_NOT_GENUINE
        }
    }
}
