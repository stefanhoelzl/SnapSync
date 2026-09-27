package app.snapsync.mock

import app.snapsync.model.CycleResult
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
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
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

// The entry ports' mocks (`docs/testing.md`, "Mocks"): the platform side of each event port. What survives a relaunch
// is the platform itself; the handlers are those of the process whose composition registered last, and the operator
// delivers through them — nothing fires on its own.

private fun <H> H?.registered(port: String): H =
    checkNotNull(this) { "no process registered for the $port port — nothing would receive this delivery" }

/** The app's foreground life. */
class LifecycleMock {
    internal var handlers: LifecycleHandlers? = null
    internal var everActive = false

    fun port(): Lifecycle = object : Lifecycle {
        override fun listen(handlers: LifecycleHandlers) {
            this@LifecycleMock.handlers = handlers
        }
    }

    val operator: LifecycleOperator = LifecycleOperator(this)
}

class LifecycleOperator internal constructor(private val mock: LifecycleMock) {
    /** Whether the app has ever become active. */
    val everActive: Boolean get() = mock.everActive

    /** The app became active. */
    fun foreground() {
        mock.everActive = true
        mock.handlers.registered("Lifecycle").onForeground()
    }

    /** The app is leaving the active state. */
    fun background() = mock.handlers.registered("Lifecycle").onBackground()
}

/** The links the platform opens the app with. */
class LinksMock {
    internal var handlers: LinkHandlers? = null

    fun port(): Links = object : Links {
        override fun listen(handlers: LinkHandlers) {
            this@LinksMock.handlers = handlers
        }
    }

    val operator: LinksOperator = LinksOperator(this)
}

class LinksOperator internal constructor(private val mock: LinksMock) {
    /** [url] was opened as a Universal Link, through [hook]'s path. */
    fun open(url: String, hook: String = "onSceneContinueActivity") =
        deliver(LinkDelivery(hook, isWebLink = true, activityType = WEB_LINK_ACTIVITY, url = url))

    /** The platform delivered [delivery], raw. */
    fun deliver(delivery: LinkDelivery) = mock.handlers.registered("Links").onLink(delivery)

    private companion object {
        /** The browsing-web activity type the iOS adapter recognises — a label here; nothing decides on it. */
        const val WEB_LINK_ACTIVITY = "NSUserActivityTypeBrowsingWeb"
    }
}

/** The platform's push service. */
class PushServiceMock {
    internal var handlers: PushHandlers? = null
    internal var registrations = 0

    fun port(): PushNotifications = object : PushNotifications {
        override fun listen(handlers: PushHandlers) {
            this@PushServiceMock.handlers = handlers
        }

        override fun register() {
            registrations++
        }
    }

    val operator: PushServiceOperator = PushServiceOperator(this)
}

class PushServiceOperator internal constructor(private val mock: PushServiceMock) {
    /** How many times the app asked the push service for its token. */
    val registrations: Int get() = mock.registrations

    /** The push service issued this device [hex] as its token. */
    fun deliverToken(hex: String) = mock.handlers.registered("PushNotifications").onToken(PushToken(hex))

    /** The push service could not issue a token. */
    fun deliverTokenFailure(description: String?) =
        mock.handlers.registered("PushNotifications").onTokenFailure(description?.let(::PlatformError))

    /** A silent push carrying [payload] arrived, handing [completion]. */
    fun deliverMessage(payload: Map<Any?, *>, completion: Completion) =
        mock.handlers.registered("PushNotifications").onMessage(PushMessage(payload), completion)
}

/**
 * The platform's user interface: the screen keeps what it was last shown, and taps come back as intents. A screen is a
 * process's: a new process's face starts with nothing shown, as a dead process's scene goes with it.
 */
class ScreenMock {
    internal var handlers: UiHandlers? = null
    internal val shown = MutableStateFlow<UiState?>(null)

    fun port(): Ui = object : Ui {
        init {
            shown.value = null
        }

        override fun listen(handlers: UiHandlers) {
            this@ScreenMock.handlers = handlers
        }

        override fun show(state: UiState) {
            shown.value = state
        }
    }

    val operator: ScreenOperator = ScreenOperator(this)
}

class ScreenOperator internal constructor(private val mock: ScreenMock) {
    /** The last state the app showed, or `null` before any. */
    val shown: StateFlow<UiState?> = mock.shown.asStateFlow()

    /** A live screen is about to be built. */
    fun live() = mock.handlers.registered("Ui").onLive()

    /** A person did [intent] on the screen. */
    fun tap(intent: UiIntent) = mock.handlers.registered("Ui").onIntent(intent)
}

/**
 * The build's development controls: the invite-link hints are fixed per build ([hints]) — a shipped build ignores
 * them, and an operator playing another build sets them — and the per-uploader pin is the channel's to set.
 */
class DevControlsMock(internal var hints: InviteLinkHints = InviteLinkHints.Ignored) {
    internal var handlers: DevHandlers? = null
    internal var pin: UploaderPin? = null

    fun port(): DevControls = object : DevControls {
        override fun listen(handlers: DevHandlers) {
            this@DevControlsMock.handlers = handlers
        }

        override fun uploaderPin(): UploaderPin? = pin

        override fun inviteLinkHints(): InviteLinkHints = hints
    }

    val operator: DevControlsOperator = DevControlsOperator(this)
}

class DevControlsOperator internal constructor(private val mock: DevControlsMock) {
    /** The per-uploader switch; `null`, as on every shipped build, unless the channel pins one. */
    var pin: UploaderPin?
        get() = mock.pin
        set(value) { mock.pin = value }

    /** How the build answers an invite link's hints — fixed per build; an operator plays another build with it. */
    var inviteLinkHints: InviteLinkHints
        get() = mock.hints
        set(value) { mock.hints = value }

    /** The channel's reset. */
    suspend fun reset() = mock.handlers.registered("DevControls").onReset()
}

/** The operating system's invocations of the upload extension — a separate process's entry port. */
class ExtensionHostMock {
    internal var handlers: ExtensionHandlers? = null

    fun port(): ExtensionHost = object : ExtensionHost {
        override fun listen(handlers: ExtensionHandlers) {
            this@ExtensionHostMock.handlers = handlers
        }
    }

    val operator: ExtensionHostOperator = ExtensionHostOperator(this)
}

class ExtensionHostOperator internal constructor(private val mock: ExtensionHostMock) {
    /** The operating system invokes the extension's `process()`. */
    suspend fun process(): CycleResult = mock.handlers.registered("ExtensionHost").onProcess()

    /** The operating system ends the invocation. */
    fun terminate() = mock.handlers.registered("ExtensionHost").onTerminate()
}
