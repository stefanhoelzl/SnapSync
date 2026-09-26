package app.snapsync.world

import app.snapsync.model.InviteLinkHints
import app.snapsync.model.LinkDelivery
import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import app.snapsync.model.UploaderPin
import app.snapsync.ports.Completion
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The app's entry ports as the world plays them (`docs/testing.md`, "Operator levers"): each holds the handlers the
// running launch's composition registered, and the operator delivers through them — nothing fires on its own. Each is
// durable across a relaunch, as the platform is; every relaunch's composition registers its own handlers.

private fun <H> H?.registered(port: String): H =
    checkNotNull(this) { "no composition registered for the $port port — nothing would receive this delivery" }

/** The app's foreground life, played by the operator. */
class WorldLifecycle : Lifecycle {
    private var handlers: LifecycleHandlers? = null

    /** Whether the app has ever become active — the fact the iOS adapter records before its handler runs. */
    var everActive: Boolean = false
        private set

    override fun listen(handlers: LifecycleHandlers) {
        this.handlers = handlers
    }

    /** Operator lever: the app became active. */
    fun foreground() {
        everActive = true
        handlers.registered("Lifecycle").onForeground()
    }

    /** Operator lever: the app is leaving the active state. */
    fun background() = handlers.registered("Lifecycle").onBackground()
}

/** The links the platform opens the app with, played by the operator. */
class WorldLinks : Links {
    private var handlers: LinkHandlers? = null

    override fun listen(handlers: LinkHandlers) {
        this.handlers = handlers
    }

    /** Operator lever: [url] was opened as a Universal Link, through [hook]'s path. */
    fun open(url: String, hook: String = "onSceneContinueActivity") =
        deliver(LinkDelivery(hook, isWebLink = true, activityType = WEB_LINK_ACTIVITY, url = url))

    /** Operator lever: the platform delivered [delivery], raw. */
    fun deliver(delivery: LinkDelivery) = handlers.registered("Links").onLink(delivery)

    private companion object {
        /** The browsing-web activity type the iOS adapter recognises — a label here; the world decides nothing on it. */
        const val WEB_LINK_ACTIVITY = "NSUserActivityTypeBrowsingWeb"
    }
}

/** The platform's push service, played by the operator. */
class WorldPushNotifications : PushNotifications {
    private var handlers: PushHandlers? = null

    /** How many times the app asked the push service for its token. */
    var registrations: Int = 0
        private set

    override fun listen(handlers: PushHandlers) {
        this.handlers = handlers
    }

    override fun register() {
        registrations++
    }

    /** Operator lever: the push service issued this device [hex] as its token. */
    fun deliverToken(hex: String) = handlers.registered("PushNotifications").onToken(PushToken(hex))

    /** Operator lever: the push service could not issue a token. */
    fun deliverTokenFailure(description: String?) =
        handlers.registered("PushNotifications").onTokenFailure(description?.let(::PlatformError))

    /** Operator lever: a silent push carrying [payload] arrived, handing [completion]. */
    fun deliverMessage(payload: Map<Any?, *>, completion: Completion) =
        handlers.registered("PushNotifications").onMessage(PushMessage(payload), completion)
}

/** The platform's user interface, played by the operator: it keeps what it was shown, and taps through intents. */
class WorldUi : Ui {
    private var handlers: UiHandlers? = null
    private val shownCell = MutableStateFlow<UiState?>(null)

    /** The last state the core showed, or `null` before any. */
    val shown: StateFlow<UiState?> = shownCell.asStateFlow()

    override fun listen(handlers: UiHandlers) {
        this.handlers = handlers
    }

    override fun show(state: UiState) {
        shownCell.value = state
    }

    /** Operator lever: a live screen is about to be built. */
    fun live() = handlers.registered("Ui").onLive()

    /** Operator lever: a person did [intent] on the screen. */
    fun tap(intent: UiIntent) = handlers.registered("Ui").onIntent(intent)
}

/**
 * The build's development controls as the world plays them: [inviteLinkHints] fixed per world (the JVM control
 * channel's world honours them; every other world ignores them, as a shipped build does), and [uploaderPin] settable.
 */
class WorldDevControls(private val hints: InviteLinkHints) : DevControls {
    private var handlers: DevHandlers? = null

    /** The per-uploader switch the channel sets; `null` — as on every shipped build — unless an operator pins one. */
    var pin: UploaderPin? = null

    override fun listen(handlers: DevHandlers) {
        this.handlers = handlers
    }

    override fun uploaderPin(): UploaderPin? = pin

    override fun inviteLinkHints(): InviteLinkHints = hints

    /** Operator lever: the channel's `POST /device/reset`. */
    suspend fun reset() = handlers.registered("DevControls").onReset()
}
