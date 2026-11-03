package app.snapsync.rig

import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.DevControlsView
import app.snapsync.mock.devControlsView
import app.snapsync.ports.DevControls
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.ports.UploaderPin
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState

// The control channel's adapters (`docs/testing.md`, "The control channel"): what a rig build's `platformAdapters()`
// supplies in place of the production ones. A production build contains none of this — the source is compiled only
// under `-Psnapsync.rig=true` — so the switches below cannot exist in a shipped binary.

/**
 * The platform's UI, decorated for the channel: everything [inner] is shown it is still shown, and the channel's
 * `/user` verbs reach the core as the [UiIntent]s a tap would produce, through the same handlers. [inner] is lazy — the
 * root builds this set before the scene it decorates can be built — and is an adapter choice's screen mock where one mocks
 * it (`docs/testing.md`, "Launch-time adapters"). Forwarding
 * [inbound] to [inner] is the one registration the composition makes (a rig decorator's forwarding `inbound` counts as
 * the composition's single one). Every state it is shown, it also hands [shown] — a device host's world tells it as
 * [app.snapsync.levers.WorldEvent.UiChanged], the screen being real there.
 */
class RigUi(
    private val inner: Lazy<Ui<UiState, UiIntent>>,
    private val shown: (UiState) -> Unit = {},
) : Ui<UiState, UiIntent> {
    private var handlers: UiHandlers<UiState, UiIntent>? = null

    @CompositionOnly
    override fun inbound(handlers: UiHandlers<UiState, UiIntent>) {
        this.handlers = handlers
        inner.value.inbound(handlers)
    }

    override fun show(state: UiState) {
        inner.value.show(state)
        shown(state)
    }

    /** A `/user` verb: [intent], as the screen would have handed it over. */
    fun dispatch(intent: UiIntent) =
        checkNotNull(handlers) { "no composition registered for the Ui port — the intent has no receiver" }
            .onIntent.deliver(intent)
}

/**
 * A rig build's development controls, as the `dev-controls` service ([levers]) moves them: the per-uploader switch,
 * and encrypted creation.
 */
class RigDevControls : DevControls {
    /** The per-uploader switch; `null` (both uploaders, as a shipped build runs) until the channel sets one. */
    var pin: UploaderPin? = null

    /** Whether an event this device creates is encrypted; `true`, as a shipped build, until the channel sets it. */
    var encrypts: Boolean = true

    override fun uploaderPin(): UploaderPin? = pin

    override fun createsPlainEvents(): Boolean = !encrypts

    /** The controls as the channel moves them — a pin only sets the switch; the channel reconciles the uploads. */
    val levers: DevControlsLevers = object : DevControlsLevers {
        override suspend fun pin(app: Boolean, extension: Boolean) {
            pin = UploaderPin(app, extension)
        }

        override suspend fun unpin() {
            pin = null
        }

        override suspend fun encryptNewEvents(on: Boolean) {
            encrypts = on
        }

        override suspend fun controls(): DevControlsView = devControlsView(pin, encrypts)
    }
}
