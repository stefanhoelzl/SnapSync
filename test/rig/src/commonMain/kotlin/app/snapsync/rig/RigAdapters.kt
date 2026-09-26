package app.snapsync.rig

import app.snapsync.model.InviteLinkHints
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import app.snapsync.model.UploaderPin
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers

// The control channel's adapters (`docs/testing.md`, "The control channel"): what a rig build's `platformAdapters()`
// supplies in place of the production ones. A production build contains none of this — the source is compiled only
// under `-Psnapsync.rig=true` — so the switches below cannot exist in a shipped binary.

/**
 * The platform's UI, decorated for the channel: everything [inner] is shown it is still shown, and the channel's
 * `/user` verbs reach the core as the [UiIntent]s a tap would produce, through the same handlers. Forwarding
 * [listen] to [inner] is the one registration the composition makes (a rig decorator's forwarding `listen` counts as
 * the composition's single `listen`).
 */
class RigUi(private val inner: Ui) : Ui {
    private var handlers: UiHandlers? = null

    override fun listen(handlers: UiHandlers) {
        this.handlers = handlers
        inner.listen(handlers)
    }

    override fun show(state: UiState) = inner.show(state)

    /** A `/user` verb: [intent], as the screen would have handed it over. */
    fun dispatch(intent: UiIntent) =
        checkNotNull(handlers) { "no composition registered for the Ui port — the intent has no receiver" }
            .onIntent(intent)
}

/**
 * A rig build's development controls: the per-uploader switch `/device/uploaders` sets, invite-link hints
 * **honoured** — the channel's callers join headlessly with `autoJoin` (capability `join-event`) — and the reset
 * `/device/reset` delivers.
 */
class RigDevControls : DevControls {
    private var handlers: DevHandlers? = null

    /** The per-uploader switch; `null` (both uploaders, as a shipped build runs) until the channel sets one. */
    var pin: UploaderPin? = null

    override fun listen(handlers: DevHandlers) {
        this.handlers = handlers
    }

    override fun uploaderPin(): UploaderPin? = pin

    override fun inviteLinkHints(): InviteLinkHints = InviteLinkHints.Honoured

    /** `/device/reset`: void this device's durable sync state. */
    suspend fun reset() =
        checkNotNull(handlers) { "no composition registered for the DevControls port — nothing would reset" }.onReset()
}
