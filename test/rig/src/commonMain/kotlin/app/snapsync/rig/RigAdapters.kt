package app.snapsync.rig

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.UploaderPin
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CompletableDeferred

// The control channel's adapters (`docs/testing.md`, "The control channel"): what a rig build's `platformAdapters()`
// supplies in place of the production ones. A production build contains none of this — the source is compiled only
// under `-Psnapsync.rig=true` — so the switches below cannot exist in a shipped binary.

/**
 * The platform's UI, decorated for the channel: everything [inner] is shown it is still shown, and the channel's
 * `/user` verbs reach the core as the [UiIntent]s a tap would produce, through the same handlers. [inner] is lazy — the
 * root builds this set before the scene it decorates can be built — and is an adapter choice's screen mock where one mocks
 * it (`docs/testing.md`, "Launch-time adapters"). Forwarding
 * [inbound] to [inner] is the one registration the composition makes (a rig decorator's forwarding `inbound` counts as
 * the composition's single one).
 */
class RigUi(private val inner: Lazy<Ui<UiState, UiIntent>>) : Ui<UiState, UiIntent> {
    private var handlers: UiHandlers<UiState, UiIntent>? = null

    @CompositionOnly
    override fun inbound(handlers: UiHandlers<UiState, UiIntent>) {
        this.handlers = handlers
        inner.value.inbound(handlers)
    }

    override fun show(state: UiState) = inner.value.show(state)

    /** A `/user` verb: [intent], as the screen would have handed it over. */
    fun dispatch(intent: UiIntent) =
        checkNotNull(handlers) { "no composition registered for the Ui port — the intent has no receiver" }
            .onIntent.deliver(intent)
}

/**
 * A rig build's development controls: the per-uploader switch `/device/uploaders` sets, invite-link hints
 * **honoured** — the channel's callers join headlessly with `autoJoin` — unless
 * `/device/invite-link-hints` plays a shipped build that ignores them, and the reset `/device/reset` delivers.
 */
class RigDevControls : DevControls {
    private var handlers: DevHandlers? = null

    /** The per-uploader switch; `null` (both uploaders, as a shipped build runs) until the channel sets one. */
    var pin: UploaderPin? = null

    /** How this build answers an invite link's dev/test hints. */
    var hints: InviteLinkHints = InviteLinkHints.Honoured

    /** Whether an event this device creates is encrypted; `true`, as a shipped build, until the channel sets it. */
    var encrypts: Boolean = true

    @CompositionOnly
    override fun inbound(handlers: DevHandlers) {
        this.handlers = handlers
    }

    override fun uploaderPin(): UploaderPin? = pin

    override fun inviteLinkHints(): InviteLinkHints = hints

    override fun createsPlainEvents(): Boolean = !encrypts

    /** `/device/reset`: void this device's durable sync state; returns once it is void. */
    suspend fun reset() {
        val handlers = checkNotNull(
            handlers,
        ) { "no composition registered for the DevControls port — nothing would reset" }
        val done = CompletableDeferred<Unit>()
        handlers.onReset.handle(
            Unit,
            object : Completion {
                override fun complete() {
                    done.complete(Unit)
                }
            },
        )
        done.await()
    }
}
