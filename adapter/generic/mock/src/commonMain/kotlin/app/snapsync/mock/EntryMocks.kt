package app.snapsync.mock

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.BeforeListen
import app.snapsync.model.HandlerSlot
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.PUSH_KIND_APNS
import app.snapsync.model.PlatformError
import app.snapsync.model.ProcessingAnswer
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.model.UploaderPin
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

// The entry ports' mocks (`docs/testing.md`, "Mocks"): the platform side of each event port. What survives a relaunch
// is the platform itself; the handlers are those of the process whose composition registered last, and the operator
// delivers through them — nothing fires on its own.

/** The app's foreground life. */
class LifecycleMock {
    internal val handlers = HandlerSlot<LifecycleHandlers>("Lifecycle", BeforeListen.Thrown)

    @Volatile internal var everActive = false

    fun port(): Lifecycle = Face()

    /** The port face a process is handed. */
    internal inner class Face : Lifecycle {
        @CompositionOnly
        override fun inbound(handlers: LifecycleHandlers) {
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
        mock.handlers.require("foreground").onForeground.handle(Unit, Completion.None)
    }

    /** The app is leaving the active state. */
    fun background() = mock.handlers.require("background").onBackground.deliver(Unit)
}

/** The links the platform opens the app with. */
class LinksMock {
    internal val handlers = HandlerSlot<LinkHandlers>("Links", BeforeListen.Thrown)

    /** Whether this installation's referrer was delivered — once per installation, as Android's record keeps it. */
    @Volatile internal var referrerDelivered = false

    fun port(): Links = Face()

    /** The port face a process is handed. */
    internal inner class Face : Links {
        @CompositionOnly
        override fun inbound(handlers: LinkHandlers) {
            this@LinksMock.handlers.set(handlers)
        }
    }

    val operator: LinksOperator = LinksOperator(this)
}

class LinksOperator internal constructor(private val mock: LinksMock) {
    /** [url] was opened as a Universal Link, through [hook]'s path. */
    fun open(url: String, hook: String = "onSceneContinueActivity") = deliver(LinkDelivery.Link(hook, url))

    /** The platform delivered [delivery]. */
    fun deliver(delivery: LinkDelivery) = mock.handlers.require("a link").onLink.deliver(delivery)

    /** A start of the installation, whose store answers [referrer]: delivered on the first start only. */
    fun startInstalled(referrer: String) {
        if (mock.referrerDelivered) return
        mock.referrerDelivered = true
        deliver(LinkDelivery.InstallReferrer("installReferrer", referrer))
    }
}

/**
 * The platform's push service. It keeps the token it issued this device, as the platform does: a process that asks
 * ([PushNotifications.register]) and has not been told it yet — a relaunched one — is answered with it, the way the
 * OS answers every launch's request. A process that already holds it is told nothing new.
 */
class PushServiceMock {
    internal val handlers = HandlerSlot<PushHandlers>("PushNotifications", BeforeListen.Thrown)

    @Volatile internal var registrations = 0

    @Volatile internal var kind = PUSH_KIND_APNS

    /** The token this device was issued, and which process's handlers have been told it. */
    @Volatile internal var issued: String? = null

    /** Why the service issues this device no token, or `null` where it does — a build that names no push project. */
    @Volatile internal var refusal: String? = null

    @Volatile internal var toldTo: PushHandlers? = null

    fun port(): PushNotifications = Face()

    /** The port face a process is handed. */
    internal inner class Face : PushNotifications {
        override val kind: String get() = this@PushServiceMock.kind

        @CompositionOnly
        override fun inbound(handlers: PushHandlers) {
            this@PushServiceMock.handlers.set(handlers)
        }

        override fun register() {
            registrations++
            refusal?.let { why ->
                handlers.orNull("a registration", BeforeListen.Dropped)?.onTokenFailure?.deliver(PlatformError(why))
                return
            }
            val token = issued ?: return
            val current = handlers.orNull("a registration", BeforeListen.Dropped) ?: return
            if (toldTo !== current) {
                toldTo = current
                current.onToken.deliver(PushToken(token))
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
        handlers.onToken.deliver(PushToken(token))
    }

    /**
     * The push service issues this device no token, for [description] — what a build naming no push project meets: every
     * ask is answered with this failure. `null` lifts it.
     */
    var refusesTokens: String?
        get() = mock.refusal
        set(value) {
            mock.refusal = value
        }

    /** The push service could not issue a token. */
    fun deliverTokenFailure(description: String) =
        mock.handlers.require("a token failure").onTokenFailure.deliver(PlatformError(description))

    /** A silent push carrying [payload] arrived, handing [completion]. */
    fun deliverMessage(payload: Map<Any?, *>, completion: Completion) =
        mock.handlers.require("a push").onMessage.handle(PushMessage(payload), completion)
}

/**
 * The platform's user interface: the screen keeps what it was last shown, and taps come back as intents. A screen is a
 * process's: a new process's face starts with nothing shown, as a dead process's scene goes with it.
 */
class ScreenMock {
    internal val handlers = HandlerSlot<UiHandlers<UiState, UiIntent>>("Ui", BeforeListen.Thrown)
    internal val shown = MutableStateFlow<UiState?>(null)

    /** Who is told each state the app shows, as it shows it — the control channel's event stream, or no one. */
    @Volatile private var watcher: ScreenWatcher = ScreenWatcher {}

    fun port(): Ui<UiState, UiIntent> = Face()

    /**
     * Tells [listener] every state the app shows, on the showing thread, as it shows it — each one, where [ScreenOperator.shown]
     * keeps only the last. One listener: the control channel's event stream (`docs/testing.md`, "How a test waits").
     */
    fun watch(listener: ScreenWatcher) {
        watcher = listener
    }

    internal fun show(state: UiState) {
        shown.value = state
        watcher.shown(state)
    }

    /** The port face a process is handed. */
    internal inner class Face : Ui<UiState, UiIntent> {
        init {
            shown.value = null
        }

        @CompositionOnly
        override fun inbound(handlers: UiHandlers<UiState, UiIntent>) {
            this@ScreenMock.handlers.set(handlers)
        }

        override fun show(state: UiState) = this@ScreenMock.show(state)
    }

    val operator: ScreenOperator = ScreenOperator(this)
}

/** Told each state the app shows, on the showing thread. */
fun interface ScreenWatcher {
    fun shown(state: UiState)
}

class ScreenOperator internal constructor(private val mock: ScreenMock) {
    /** The last state the app showed, or `null` before any. */
    val shown: StateFlow<UiState?> = mock.shown.asStateFlow()

    /** A live screen is about to be built: it renders the state the app answers, before its first frame. */
    fun live() {
        mock.show(mock.handlers.require("a live screen").onLive.deliver(Unit))
    }

    /** A person did [intent] on the screen. */
    fun tap(intent: UiIntent) = mock.handlers.require("a tap").onIntent.deliver(intent)
}

/**
 * The build's development controls: the invite-link hints are fixed per build ([hints]) — a shipped build ignores
 * them, and an operator playing another build sets them — and the per-uploader pin is the channel's to set.
 */
class DevControlsMock(internal var hints: InviteLinkHints = InviteLinkHints.Ignored) {
    internal val handlers = HandlerSlot<DevHandlers>("DevControls", BeforeListen.Thrown)

    @Volatile internal var pin: UploaderPin? = null

    @Volatile internal var encrypts: Boolean = true

    @Volatile internal var networkGraceMillis: Long? = null

    fun port(): DevControls = Face()

    /** The port face a process is handed. */
    internal inner class Face : DevControls {
        @CompositionOnly
        override fun inbound(handlers: DevHandlers) {
            this@DevControlsMock.handlers.set(handlers)
        }

        override fun uploaderPin(): UploaderPin? = pin

        override fun inviteLinkHints(): InviteLinkHints = hints

        override fun createsPlainEvents(): Boolean = !encrypts

        override fun networkGraceMillis(): Long? = this@DevControlsMock.networkGraceMillis
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

    /** Whether an event this device creates is encrypted — `true`, as on every shipped build; `false` creates a plain one. */
    var encryptsNewEvents: Boolean
        get() = mock.encrypts
        set(value) { mock.encrypts = value }

    /**
     * How long a missing network must last before the member is told, in milliseconds — `null`, the app's own grace as
     * on every shipped build, unless a test host shortens it. Read as the app composes its network watch.
     */
    var networkGraceMillis: Long?
        get() = mock.networkGraceMillis
        set(value) { mock.networkGraceMillis = value }

    /** The channel's reset; returns once the device's state is void. */
    suspend fun reset() {
        val done = Answered()
        mock.handlers.require("a reset").onReset.handle(Unit, done)
        done.ended.await()
    }
}

/** The operating system's invocations of the upload extension — a separate process's entry port. */
class ExtensionHostMock {
    internal val handlers = HandlerSlot<ExtensionHandlers>("ExtensionHost", BeforeListen.Thrown)

    fun port(): ExtensionHost = Face()

    /** The port face a process is handed. */
    internal inner class Face : ExtensionHost {
        @CompositionOnly
        override fun inbound(handlers: ExtensionHandlers) {
            this@ExtensionHostMock.handlers.set(handlers)
        }
    }

    val operator: ExtensionHostOperator = ExtensionHostOperator(this)
}

class ExtensionHostOperator internal constructor(private val mock: ExtensionHostMock) {
    /**
     * The operating system invokes the extension's `process()` — synchronously, on [thread], a thread of its own that
     * the answer blocks until the cycle has run. The caller names it: a mock starts no thread of its own.
     */
    suspend fun process(thread: CoroutineContext): ProcessingAnswer =
        withContext(thread) { mock.handlers.require("process()").onProcess.answer(Unit) }

    /** The operating system ends the invocation. */
    fun terminate() = mock.handlers.require("notifyTermination()").onTerminate.deliver(Unit)
}

/** A completion a mocked platform waits on: [ended] completes once the core answers it. */
internal class Answered : Completion {
    val ended = CompletableDeferred<Unit>()

    override fun complete() {
        ended.complete(Unit)
    }
}
