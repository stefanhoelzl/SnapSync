package app.snapsync.presentation

import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.presentation.JoinedLayer.message
import app.snapsync.presentation.JoinedLayer.shown
import app.snapsync.presentation.RangeResolution.resolve
import app.snapsync.presentation.ScreenModel.Companion.view
import app.snapsync.presentation.UnjoinedLayer.asksAccessOnJoin
import app.snapsync.presentation.UnjoinedLayer.message
import app.snapsync.query.RequestStatus
import app.snapsync.ui.state.CreateForm
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage

/** The create surface's two inputs: the create request's status, and the form as it stands. */
internal class Creation(val status: RequestStatus<CreateResult>, val form: CreateForm)

/** A participation surface's choices, and the calendar open over them, if any. */
internal class Choices(val form: RangeForm, val picking: Picking?)

object UnjoinedLayer {
    /**
     * Whether confirming a join also raises iOS's photo-access dialog: no event is
     * configured, and access was never asked — the only state from which iOS can still raise it. From a
     * refusal a request is a silent no-op, so announcing a dialog there would be false; a switch's previous
     * event, while still configured, is not yet a join this rule speaks for.
     */
    internal fun asksAccessOnJoin(joined: Membership?, permission: PhotoGrant): Boolean =
        joined == null && permission == PhotoGrant.UNDECIDED

    /**
     * What is shown while NO event is configured: the interactive join confirmation, or the create surface. Nothing in
     * it consults the health or the clock.
     */
    internal fun of(
        pending: PendingJoin?,
        create: Creation,
        transient: ScreenMessage?,
        choices: Choices,
        permission: PhotoGrant,
        network: NetworkNotice?,
        refusal: DeviceRefusal?,
        attestFault: ServiceFault?,
        display: Display,
    ): Reduced =
        // A pending interactive join outranks the create layer (a switch whose leave already ran also
        // lands here — a transient no-event, shown full-screen with a Retry).
        if (pending != null) {
            joiningLayer(pending, transient, choices, permission, network, display)
        } else {
            createLayer(create, transient, network, refusal, attestFault)
        }

    /** The join gate over [pending]: its phase, the choices it offers and what it says. */
    private fun joiningLayer(
        pending: PendingJoin,
        transient: ScreenMessage?,
        choices: Choices,
        permission: PhotoGrant,
        network: NetworkNotice?,
        display: Display,
    ): Reduced {
        val form = choices.form
        // Resolved only where there IS a window: the detail-less phases render no range row, so an absent
        // resolution is the honest answer rather than one invented from `now`.
        val phase = pending.phase
        val range = (phase as? JoinProgress.Detailed)?.let { display.resolve(form, it.event.startsAt, it.event.endsAt) }
        val layer = Layer.JoiningEvent(
            eventId = pending.eventId.value,
            stage = when (val shown = phase.shows) {
                is JoinPhase.Detailed ->
                    JoinStage.Loaded(shown, form.view(checkNotNull(range), permission, choices.picking))
                is JoinPhase.Eventless -> JoinStage.Unloaded(shown)
            },
            // The same transient cell the create and joined layers read: a rejected link is rejected
            // wherever it arrives, including over an open join surface, and it touches the join not at all.
            notice = transient,
            // This rung is reached only with no event configured, so the permission alone decides.
            asksAccessOnJoin = asksAccessOnJoin(null, permission),
            // Join waits for a network; the screen names why.
            network = network,
            // A failed load or join the service caused is told in its own words.
            service = pending.service?.notice,
        )
        return Reduced(layer, range)
    }

    /** The create surface: its form and its one line. */
    private fun createLayer(
        create: Creation,
        transient: ScreenMessage?,
        network: NetworkNotice?,
        refusal: DeviceRefusal?,
        attestFault: ServiceFault?,
    ): Reduced {
        // The passing word on a link that opened nothing is carried apart from the failure and the network: it shows
        // above
        // both for its seconds, and they return as they were. A create failure is sticky until the next attempt; a
        // missing
        // network outranks it on screen but is carried beside it, so the failure returns with the network. A create in
        // flight is not interrupted: it ends as any create ends.
        //
        // A phone the service refuses is told so in that same line as soon as the app learns of it — the front screen
        // tells a refused phone before it tries: below a failure, which for a refused phone IS the refusal, and above
        // the
        // scan hint. Create stays available.
        val refused = refusal?.message
        val layer = when (val creation = create.status) {
            is RequestStatus.InFlight -> Layer.CreatingEvent
            is RequestStatus.Done -> Layer.CreateEvent(
                create.form,
                creation.value.message(refused, attestFault.takeIf { refused == null }) ?: refused,
                network,
                passing = transient,
            )
            RequestStatus.Idle -> Layer.CreateEvent(
                create.form,
                error = refused,
                network = network,
                passing = transient,
            )
        }
        return Reduced(layer)
    }

    /**
     * The create request as the screen shows it: [mine] fired and not yet answered is in flight whether or not its run
     * has
     * begun — a run that answers within a frame would otherwise never show the preparing screen. The screen lets go of
     * it
     * once answered, and the status says the rest.
     */
    internal fun creatingOf(status: RequestStatus<CreateResult>, mine: RequestId?): RequestStatus<CreateResult> =
        if (mine == null) status else RequestStatus.InFlight(mine)

    /**
     * A failed create in words. [refused] is the attestation's refusal, if it gave one: a create refused for this
     * phone's
     * credential says why, and without a verdict — the attempt to verify got no answer — it is the unreachable server.
     */
    /** What the create screen says of a create that did not mint: [refused] is the phone's refusal, when it has one. */
    internal fun CreateResult.message(
        refused: ScreenMessage?,
        attestFault: ServiceFault?,
    ): ScreenMessage? = when (this) {
        is CreateResult.Created -> null
        CreateResult.InvalidName -> ScreenMessage.CREATE_NAME_REFUSED
        CreateResult.InvalidWindow -> ScreenMessage.CREATE_DATES_REFUSED
        CreateResult.Unverified -> refused ?: attestFault?.message ?: ScreenMessage.CREATE_FAILED
        is CreateResult.Failed -> fault.message
    }
}
