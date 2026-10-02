package app.snapsync.mock

import app.snapsync.model.BeforeListen
import app.snapsync.model.CycleResult
import app.snapsync.model.HandlerSlot
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.LinkDelivery
import app.snapsync.model.PlatformError
import app.snapsync.model.PUSH_KIND_APNS
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

/** The app's foreground life. */
class LifecycleMock {
    internal val handlers = HandlerSlot<LifecycleHandlers>("Lifecycle", BeforeListen.Thrown)
    internal var everActive = false

    fun port(): Lifecycle = object : Lifecycle {
        override fun listen(handlers: LifecycleHandlers) {
            this@LifecycleMock.handlers.set(handlers)
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
        mock.handlers.require("foreground").onForeground()
    }

    /** The app is leaving the active state. */
    fun background() = mock.handlers.require("background").onBackground()
}

/** The links the platform opens the app with. */
class LinksMock {
    internal val handlers = HandlerSlot<LinkHandlers>("Links", BeforeListen.Thrown)

    fun port(): Links = object : Links {
        override fun listen(handlers: LinkHandlers) {
            this@LinksMock.handlers.set(handlers)
        }
    }

    val operator: LinksOperator = LinksOperator(this)
}

class LinksOperator internal constructor(private val mock: LinksMock) {
    /** [url] was opened as a Universal Link, through [hook]'s path. */
    fun open(url: String, hook: String = "onSceneContinueActivity") =
        deliver(LinkDelivery(hook, isWebLink = true, activityType = WEB_LINK_ACTIVITY, url = url))

    /** The platform delivered [delivery], raw. */
    fun deliver(delivery: LinkDelivery) = mock.handlers.require("a link").onLink(delivery)

    private companion object {
        /** The browsing-web activity type the iOS adapter recognises — a label here; nothing decides on it. */
        const val WEB_LINK_ACTIVITY = "NSUserActivityTypeBrowsingWeb"
    }
}

/**
 * The platform's push service. It keeps the token it issued this device, as the platform does: a process that asks
 * ([PushNotifications.register]) and has not been told it yet — a relaunched one — is answered with it, the way the
 * OS answers every launch's request. A process that already holds it is told nothing new.
 */
class PushServiceMock {
    internal val handlers = HandlerSlot<PushHandlers>("PushNotifications", BeforeListen.Thrown)
    internal var registrations = 0
    internal var kind = PUSH_KIND_APNS

    /** The token this device was issued, and which process's handlers have been told it. */
    internal var issued: String? = null
    internal var toldTo: PushHandlers? = null

    fun port(): PushNotifications = object : PushNotifications {
        override val kind: String get() = this@PushServiceMock.kind

        override fun listen(handlers: PushHandlers) {
            this@PushServiceMock.handlers.set(handlers)
        }

        override fun register() {
            registrations++
            val token = issued ?: return
            val current = handlers.orNull("a registration", BeforeListen.Dropped) ?: return
            if (toldTo !== current) {
                toldTo = current
                current.onToken(PushToken(token))
            }
        }
    }

    val operator: PushServiceOperator = PushServiceOperator(this)
}

class PushServiceOperator internal constructor(private val mock: PushServiceMock) {
    /** How many times the app asked the push service for its token. */
    val registrations: Int get() = mock.registrations

    /** The push service this device speaks — APNs unless a test plays an Android device. Set before composing. */
    var kind: String
        get() = mock.kind
        set(value) {
            mock.kind = value
        }

    /** The push service issued this device [token] — kept, and re-delivered to a relaunched process's request. */
    fun deliverToken(token: String) {
        val handlers = mock.handlers.require("a token")
        mock.issued = token
        mock.toldTo = handlers
        handlers.onToken(PushToken(token))
    }

    /** The push service could not issue a token. */
    fun deliverTokenFailure(description: String?) =
        mock.handlers.require("a token failure").onTokenFailure(description?.let(::PlatformError))

    /** A silent push carrying [payload] arrived, handing [completion]. */
    fun deliverMessage(payload: Map<Any?, *>, completion: Completion) =
        mock.handlers.require("a push").onMessage(PushMessage(payload), completion)
}

/**
 * The platform's user interface: the screen keeps what it was last shown, and taps come back as intents. A screen is a
 * process's: a new process's face starts with nothing shown, as a dead process's scene goes with it.
 */
class ScreenMock {
    internal val handlers = HandlerSlot<UiHandlers>("Ui", BeforeListen.Thrown)
    internal val shown = MutableStateFlow<UiState?>(null)

    fun port(): Ui = object : Ui {
        init {
            shown.value = null
        }

        override fun listen(handlers: UiHandlers) {
            this@ScreenMock.handlers.set(handlers)
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
    fun live() = mock.handlers.require("a live screen").onLive()

    /** A person did [intent] on the screen. */
    fun tap(intent: UiIntent) = mock.handlers.require("a tap").onIntent(intent)
}

/**
 * The build's development controls: the invite-link hints are fixed per build ([hints]) — a shipped build ignores
 * them, and an operator playing another build sets them — and the per-uploader pin is the channel's to set.
 */
class DevControlsMock(internal var hints: InviteLinkHints = InviteLinkHints.Ignored) {
    internal val handlers = HandlerSlot<DevHandlers>("DevControls", BeforeListen.Thrown)
    internal var pin: UploaderPin? = null

    fun port(): DevControls = object : DevControls {
        override fun listen(handlers: DevHandlers) {
            this@DevControlsMock.handlers.set(handlers)
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
    suspend fun reset() = mock.handlers.require("a reset").onReset()
}

/** The operating system's invocations of the upload extension — a separate process's entry port. */
class ExtensionHostMock {
    internal val handlers = HandlerSlot<ExtensionHandlers>("ExtensionHost", BeforeListen.Thrown)

    fun port(): ExtensionHost = object : ExtensionHost {
        override fun listen(handlers: ExtensionHandlers) {
            this@ExtensionHostMock.handlers.set(handlers)
        }
    }

    val operator: ExtensionHostOperator = ExtensionHostOperator(this)
}

class ExtensionHostOperator internal constructor(private val mock: ExtensionHostMock) {
    /** The operating system invokes the extension's `process()`. */
    suspend fun process(): CycleResult = mock.handlers.require("process()").onProcess()

    /** The operating system ends the invocation. */
    fun terminate() = mock.handlers.require("notifyTermination()").onTerminate()
}
