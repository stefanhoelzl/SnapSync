package app.snapsync.presentation

import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.EventConfig
import app.snapsync.model.Arrow
import app.snapsync.model.PermissionStatus
import kotlinx.serialization.Serializable

/**
 * **Everything the screen shows** (capability `sync-status-screen`): the [layer] it is on, and the
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
)

/**
 * What is drawn OVER the current [Layer] — the confirmations and sheets.
 *
 * They have nothing in common as features (they belong to four capabilities); what groups them is the
 * only thing the layout cares about, which is that each is drawn over whatever body rendered. They live
 * here rather than on a layer because the diagnostic sheet's gesture is on the app-name label, which
 * **every** layer renders: a flag on `Joined` alone could not express it.
 *
 * What is TYPED into a sheet stays in the sheet (capability `sync-status-screen`, the stated IME
 * exception): the presence and the seed are state, the characters since it opened are not.
 */
@Serializable
data class Overlays(
    /** The destructive leave confirmation. */
    val confirmingLeave: Boolean = false,
    /** The rename dialog, opened by the pen beside the heading (capability `event-rename`). */
    val renaming: Boolean = false,
    /**
     * The diagnostic-dump sheet (capability `diagnostic-logging`), opened by a double-tap on the
     * app-name label. Reachable from every layer, which is why this bundle is not layer-scoped.
     */
    val reportingBug: Boolean = false,
)

/**
 * The overlays that can actually be shown over [layer].
 *
 * The leave confirmation and the rename sheet both belong to a membership — there is nothing to leave or
 * rename without one — so on any other layer they are not shown, whatever the cell holds. The diagnostic
 * sheet is reachable from every layer and is never masked.
 *
 * This is a display rule, not a reset: the cell is cleared where the membership actually ends (the leave
 * and the switch), and this makes a flag that outlived its layer by any other route unrenderable rather
 * than merely unlikely.
 */
internal fun Overlays.maskedFor(layer: Layer): Overlays =
    if (layer is Layer.Joined) this else copy(confirmingLeave = false, renaming = false)

/**
 * Display-ready projection of config presence, permission, and the latest sync snapshot. Once an
 * event is configured the screen is always the **joined layer** (name · QR · share · leave); permission
 * and sync activity are just moods of the one-line status ([SyncHealth]). No counts are carried — the
 * screen answers "is it healthy?", not "how many of N".
 */
@Serializable
sealed interface Layer {
    /**
     * The create-event landing layer (event-creation-ui), shown while no event is connected
     * (`config == null`) and no create is in flight. Carries an optional pre-formatted inline
     * [error] — the last create failure's copy (sticky until the next attempt) or a transient
     * invalid-link message. Config-absent outranks everything, so this is the top reduction rung.
     */
    @Serializable
    data class CreateEvent(val error: String? = null) : Layer

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
     * other joiner. [phase] drives it (loading details → explain/ready/blocked/retry → committing →
     * commit-failed).
     */
    @Serializable
    data class JoiningEvent(
        val eventId: String,
        val phase: JoinPhase,
        /** The member's uncommitted choices on this surface. Seeded when the details load. */
        val form: RangeForm = RangeForm(),
        /**
         * [form] resolved against the loaded event's window — what a confirm would commit, and what the
         * range row renders. `null` on the phases that have no window yet, which are exactly the phases
         * that render no range row.
         */
        val range: ResolvedRange? = null,
    ) : Layer

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
         * force the screen to re-check it (capability `sync-status-screen`). Every joined surface reads
         * the event's name, id and settings from here; there is no second event-name value beside it.
         */
        val membership: EventConfig,
        /**
         * The invite link, derived **once** here from [membership]'s `eventId` (capability
         * `event-invite-qr`), so the rendered QR and the shared link cannot disagree. Carried rather than
         * re-derived at each render site: the derivation depends on a build-time link origin, so carrying
         * it is what makes a transported state render the origin the device actually shows.
         */
        val inviteUrl: String,
        val health: SyncHealth,
        val pendingSwitch: PendingSwitch? = null,
        /** The joined layer offers "Choose more photos" — true exactly under a partial grant
         *  (capability `limited-photo-access`): a resting affordance, never an attention state. */
        val canChoosePhotos: Boolean = false,
        /** The event's declared end has passed (`now > endsAt`, capability `sync-status-screen`): the
         *  status line shows an "Event ended" marker prefixing the regular [health]. Informational only —
         *  sync continues during the backend grace window; joining is closed server-side. `false` when the
         *  membership has no stored `endsAt` (a legacy config before its reconcile backfill). */
        val ended: Boolean = false,
        /**
         * The rename lifecycle (capability `event-rename`) for the heading's dialog. A field, never a
         * family and never a health rung: a rename changes one string and one dialog's state, so it adds
         * neither a layer nor a precedence step to the reduction.
         */
        val renameState: RenameState = RenameState.Idle,
        /**
         * Which body the joined layer is showing (capability `reconfigure-membership`). A selection
         * WITHIN the joined layer rather than a `UiState` family of its own: the joined layer's health,
         * pending switch and membership all still apply while the settings surface is up — a sibling
         * family would have to duplicate them to model a surface that is still, in every other respect,
         * the joined layer. It is not an overlay either: it replaces the body rather than covering it.
         *
         * Opening and closing it remains client-side navigation touching no port
         * (`reconfigure-membership` D4's reason), because the intent that changes this reduces and
         * nothing more.
         */
        val surface: JoinedSurface = JoinedSurface.Status,
        /**
         * A transient, self-clearing notice over the joined layer — today only the rejected-event-link
         * message (capability `event-link`).
         *
         * It exists because the gate raises that error from ANY layer: `onOpenUrl` decodes every
         * delivered link, and a member who scans a bad QR while already joined was told nothing at all —
         * the message was set, and the joined layer had nowhere to render it. "Nothing happened" and
         * "that code wasn't valid" are different answers, and the member could only see the first
         * (spec `module-architecture`, "Absence is never silent").
         */
        val notice: String? = null,
    ) : Layer
}

/**
 * The rename dialog's condition, as the screen renders it (capability `event-rename`).
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
    data class Failed(val message: String) : RenameState
}

/** Which body the joined layer shows. */
@Serializable
sealed interface JoinedSurface {
    /** The status surface: the invite hero, the health line, and the action cluster. */
    @Serializable
    data object Status : JoinedSurface

    /**
     * The in-place settings surface (capability `reconfigure-membership`), pre-filled from the
     * membership and carrying the member's uncommitted edits until Save or Cancel.
     */
    @Serializable
    data class Reconfigure(val form: RangeForm, val range: ResolvedRange) : JoinedSurface
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
 * [startsAt] is the event's **start date** — already a canonical UTC `…Z` string (`HttpEventDirectory`
 * normalizes it and fails the load rather than invent one). It is both the range row's lower **default**
 * and its **floor** (capability `photo-selection-policy`): the row cannot be empty and the confirm cannot
 * join below it, so joining at whole-library scope is unrepresentable. It also decides the range
 * selector's shape — when it is in the **future**, the "Now" preset would clamp to this same instant, so
 * it is offered disabled rather than as a button that visibly does nothing.
 *
 * [endsAt] is the event's **end date** — the range row's upper **default** and its **ceiling**
 * (capability `photo-selection-policy`), and the seed for the "Event end" preset.
 *
 * [deletesAt] is the event's **retention deadline** (capability `event-limits`) — **server-derived** and
 * carried verbatim. The gate states it before the confirm, and the commit persists it as the offline
 * witness of the self-leave (capability `leave-event`). It is never computed on the device: a
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
 * Three phases carry no event: the fetch has not resolved ([Loading]), or there is nothing to be invited
 * to ([NotFound] / [LoadFailed]). Every other phase is a [Detailed] — details plus which step of the
 * confirmation is showing — so **a step that renders or commits the event's facts cannot be constructed
 * without them.** A flat nullable field would have removed the same duplication while making
 * `Ready`-without-details representable; that trades a type guarantee for tidiness, which is the wrong
 * direction.
 */
@Serializable
sealed interface JoinPhase {
    /** Fetching `GET /event/:id` details ("Loading event details…"). */
    @Serializable
    data object Loading : JoinPhase

    /** The event does not exist (404) — an invalid/expired invite; no confirm offered. */
    @Serializable
    data object NotFound : JoinPhase

    /** The details fetch failed transiently (network/5xx); a Retry re-runs it. */
    @Serializable
    data object LoadFailed : JoinPhase

    /**
     * Details loaded: [event] is what was fetched, [step] is where in the confirmation the member is.
     *
     * The step advances by user action only — the details never change under it, which is exactly why
     * they sit beside it rather than inside it.
     */
    @Serializable
    data class Detailed(val event: EventDetails, val step: Step) : JoinPhase {
        /**
         * Where a loaded confirmation stands.
         *
         * - [ExplainAccess] — the photo-access explainer, shown **before** the system permission dialog
         *   is ever raised. Chosen instead of [Ready] when **no event is configured** and permission is
         *   `NOT_DETERMINED` (the sole state from which iOS can still raise the dialog; from `DENIED` a
         *   request is a silent no-op, so an explainer promising a dialog would be false). Its confirm
         *   requests permission and advances to [Ready]; its cancel discards the pending join. It renders
         *   none of the event's dates — it carries them only because the step it advances to needs them.
         * - [Ready] — the confirm (Join/Switch) is offered.
         * - [Committing] — the confirm was taken; enroll + provision are in flight.
         * - [CommitFailed] — enrollment/commit failed (or a switch's join failed after leaving); a Retry
         *   re-runs the join. The retry commits **without** passing back through [Ready], which is why
         *   the details live on [Detailed] rather than on the loaded step alone.
         */
        @Serializable
        enum class Step { ExplainAccess, Ready, Committing, CommitFailed }
    }
}

/** The loaded event facts, or `null` on the three phases that carry none. */
val JoinPhase.details: EventDetails? get() = (this as? JoinPhase.Detailed)?.event

/** Where a loaded confirmation stands, or `null` on the three phases that are not loaded. */
val JoinPhase.step: JoinPhase.Detailed.Step? get() = (this as? JoinPhase.Detailed)?.step

/** A loaded phase at [step], built from [details] — the shape every construction site takes. */
fun joinPhase(step: JoinPhase.Detailed.Step, details: EventDetails): JoinPhase =
    JoinPhase.Detailed(details, step)

/**
 * The joined-layer one-line health, the sole thing the status line renders. There is no standalone
 * "not syncing" state — the only reason contribution cannot run is missing permission ([NeedsAccess]),
 * the sole attention state (spec: sync-status-screen).
 */
@Serializable
sealed interface SyncHealth {
    /**
     * Permission is not `GRANTED` while an event is connected. [permission] is `NOT_DETERMINED`
     * (never asked → tapping the status line requests it) or `DENIED` (tapping opens Settings). The
     * only health that carries a background. Sharing the invite still works with no access.
     */
    @Serializable
    data class NeedsAccess(val permission: PermissionStatus) : SyncHealth

    /**
     * The event has not begun: the membership's `startsAt` is still in the future (capability
     * `sync-status-screen`). Carries the start instant so the screen can say *when* — a bare "not started
     * yet" invites exactly the question it fails to answer.
     *
     * It ranks **below** [NeedsAccess] and **above** the snapshot-derived values. Permission outranks it
     * because permission is the only **actionable** state, and a member must resolve it *before* the event
     * begins or they will miss the start; burying it behind a clock line would ambush them with a
     * permission prompt at the very moment the party starts. Everything below is outranked because, before
     * the start, nothing of the member's **can** be syncing — the cutoff floor guarantees it (capability
     * `photo-selection-policy`), so a snapshot-derived line would say nothing true this does not say better.
     *
     * Unlike every other health, this one depends on **wall-clock time** rather than the ledger, so no
     * snapshot emission retires it — `StatusContainerHost` runs a foreground tick for that.
     */
    @Serializable
    data class NotStarted(val startsAt: EventStart) : SyncHealth

    /**
     * Uploads are blocked: this device holds no valid attestation token, and the attempt to obtain one
     * **failed** (capability `device-attestation`).
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
     */
    @Serializable
    data object Unattested : SyncHealth

    /** Joined, permission granted, but persisted state has not been read yet — a neutral first frame. */
    @Serializable
    data object Loading : SyncHealth

    /** Everything shared and received — the settled state (no arrows). */
    @Serializable
    data object InSync : SyncHealth

    /**
     * Work remaining in at least one direction. Each arrow is shown by completeness and pulses by live
     * activity (spec: sync-status-screen): [upload] from `synced < total` (shown) × `pending > 0` (pulse),
     * [download] from `downloaded < total` (shown) × `inFlight > 0` (pulse).
     */
    @Serializable
    data class Syncing(val upload: Arrow, val download: Arrow) : SyncHealth
}
