package app.snapsync.mock

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.DevControlsView
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.LifecycleLevers
import app.snapsync.levers.LinksLevers
import app.snapsync.levers.PushLevers
import app.snapsync.levers.PushView
import app.snapsync.levers.ScreenLevers
import app.snapsync.levers.WorldEvent
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
// is the platform itself; the handlers are those of the process whose composition registered last, and whoever plays
// the operating system delivers through them — nothing fires on its own. A delivery is plumbing on the mock, not a
// lever on its operator face: the control channel's played OS calls it (`MockEntryDriver`), and a typed service names it
// (`OsLevers`), because a delivery may hand the app a live completion handler, which crosses no wire.

/** Told what the operating system records of the app, on the app's thread, as it records it. */
fun interface OsWatcher {
    fun told(event: WorldEvent.Os)
}

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

    /** The app became active. */
    fun foreground() {
        everActive = true
        handlers.require("foreground").onForeground.handle(Unit, Completion.None)
    }

    /** The app is leaving the active state. */
    fun background() = handlers.require("background").onBackground.deliver(Unit)

    val operator: LifecycleOperator = LifecycleOperator(this)
}

class LifecycleOperator internal constructor(private val mock: LifecycleMock) : LifecycleLevers {
    override suspend fun everActive(): Boolean = mock.everActive
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

    /** [url] was opened as a Universal Link, through [hook]'s path. */
    fun open(url: String, hook: String = "onSceneContinueActivity") = deliver(LinkDelivery.Link(hook, url))

    /** The platform delivered [delivery]. */
    fun deliver(delivery: LinkDelivery) = handlers.require("a link").onLink.deliver(delivery)

    /** A start of the installation, whose store answers [referrer]: delivered on the first start only. */
    internal fun startInstalled(referrer: String) {
        if (referrerDelivered) return
        referrerDelivered = true
        deliver(LinkDelivery.InstallReferrer("installReferrer", referrer))
    }

    val operator: LinksOperator = LinksOperator(this)
}

class LinksOperator internal constructor(private val mock: LinksMock) : LinksLevers {
    override suspend fun startInstalled(referrer: String) = mock.startInstalled(referrer)
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

    /** Who is told each registration — the control channel's event stream, or no one. */
    @Volatile private var watcher: OsWatcher = OsWatcher {}

    /** Tells [listener] each time the app asks for its token (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: OsWatcher) {
        watcher = listener
    }

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
            watcher.told(WorldEvent.PushRegistered)
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

    /** The push service issued this device [token] — kept, and re-delivered to a relaunched process's request. */
    fun deliverToken(token: String) {
        val current = handlers.require("a token")
        issued = token
        toldTo = current
        current.onToken.deliver(PushToken(token))
    }

    /** The push service could not issue a token. */
    fun deliverTokenFailure(description: String) =
        handlers.require("a token failure").onTokenFailure.deliver(PlatformError(description))

    /** A silent push carrying [payload] arrived, handing [completion]. */
    fun deliverMessage(payload: Map<Any?, *>, completion: Completion) =
        handlers.require("a push").onMessage.handle(PushMessage(payload), completion)

    val operator: PushServiceOperator = PushServiceOperator(this)
}

class PushServiceOperator internal constructor(private val mock: PushServiceMock) : PushLevers {
    override suspend fun kind(kind: String) {
        mock.kind = kind
    }

    override suspend fun refuseTokens(reason: String?) {
        mock.refusal = reason
    }

    override suspend fun push(): PushView = PushView(mock.registrations, mock.kind, mock.refusal)
}

/**
 * The platform's user interface: the screen keeps what it was last shown, and taps come back as intents. A screen is a
 * process's: a new process's face starts with nothing shown, as a dead process's scene goes with it.
 */
class ScreenMock {
    internal val handlers = HandlerSlot<UiHandlers<UiState, UiIntent>>("Ui", BeforeListen.Thrown)
    private val state = MutableStateFlow<UiState?>(null)

    /** Who is told each state the app shows, as it shows it — the control channel's event stream, or no one. */
    @Volatile private var watcher: ScreenWatcher = ScreenWatcher {}

    fun port(): Ui<UiState, UiIntent> = Face()

    /**
     * The last state the app showed, or `null` before any — plumbing for what composes over the mock in-process (the
     * world harness, the rig); a test reads it through [ScreenOperator.shown].
     */
    val shown: StateFlow<UiState?> = state.asStateFlow()

    /** [ScreenOperator.live], for a caller on no coroutine of its own (the world harness's start). */
    fun live() {
        show(handlers.require("a live screen").onLive.deliver(Unit))
    }

    /** [ScreenOperator.tap], for a caller on no coroutine of its own (the world harness's phone pane). */
    fun tap(intent: UiIntent) = handlers.require("a tap").onIntent.deliver(intent)

    /**
     * Tells [listener] every state the app shows, on the showing thread, as it shows it — each one, where [shown] keeps
     * only the last. One listener: the control channel's event stream (`docs/testing.md`, "How a test waits").
     */
    fun watch(listener: ScreenWatcher) {
        watcher = listener
    }

    internal fun show(state: UiState) {
        this.state.value = state
        watcher.shown(state)
    }

    /** The port face a process is handed. */
    internal inner class Face : Ui<UiState, UiIntent> {
        init {
            state.value = null
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

class ScreenOperator internal constructor(private val mock: ScreenMock) : ScreenLevers {
    override suspend fun tap(intent: UiIntent) = mock.tap(intent)

    override suspend fun shown(): UiState? = mock.shown.value

    override suspend fun live() = mock.live()
}

/**
 * The build's development controls: the invite-link hints are fixed per build ([hints]) — a shipped build ignores
 * them, and an operator playing another build sets them — and the per-uploader pin is the channel's to set.
 */
class DevControlsMock(hints: InviteLinkHints = InviteLinkHints.Ignored) {
    internal val handlers = HandlerSlot<DevHandlers>("DevControls", BeforeListen.Thrown)

    // In-process plumbing — the hosts' own; the channel's is the [operator].

    /** How the build answers an invite link's hints — fixed per build; an operator plays another build with it. */
    @Volatile var hints: InviteLinkHints = hints

    /** The per-uploader switch; `null`, as on every shipped build, unless the channel pins one. */
    @Volatile var pin: UploaderPin? = null

    /** Whether an event this device creates is encrypted — `true`, as on every shipped build; `false` creates a plain one. */
    @Volatile var encrypts: Boolean = true

    /** The channel's reset; returns once the device's state is void. */
    suspend fun reset() {
        val done = Answered()
        handlers.require("a reset").onReset.handle(Unit, done)
        done.ended.await()
    }

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
    }

    val operator: DevControlsOperator = DevControlsOperator(this)
}

/**
 * The development controls as the channel sets them. A pin here only sets the switch: the host that serves the face
 * reconciles the uploads with it, which a mock cannot reach.
 */
class DevControlsOperator internal constructor(private val mock: DevControlsMock) : DevControlsLevers {
    override suspend fun pin(app: Boolean, extension: Boolean) {
        mock.pin = UploaderPin(app, extension)
    }

    override suspend fun unpin() {
        mock.pin = null
    }

    override suspend fun inviteLinkHints(honoured: Boolean) {
        mock.hints = if (honoured) InviteLinkHints.Honoured else InviteLinkHints.Ignored
    }

    override suspend fun encryptNewEvents(on: Boolean) {
        mock.encrypts = on
    }

    override suspend fun reset() = mock.reset()

    override suspend fun controls(): DevControlsView = devControlsView(mock.pin, mock.hints, mock.encrypts)
}

/** The development controls as the channel reads them. */
fun devControlsView(pin: UploaderPin?, hints: InviteLinkHints, encrypts: Boolean): DevControlsView = DevControlsView(
    appUploads = pin?.app ?: true,
    extensionUploads = pin?.extension ?: true,
    pinned = pin != null,
    inviteLinkHintsHonoured = hints == InviteLinkHints.Honoured,
    encryptsNewEvents = encrypts,
)

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

    /**
     * The operating system invokes the extension's `process()` — synchronously, on [thread], a thread of its own that
     * the answer blocks until the cycle has run. The caller names it: a mock starts no thread of its own.
     *
     * Plumbing, with no operator face: whoever plays the operating system names the thread, which crosses no wire — the
     * host's `extension-host` service ([ExtensionHostLevers]) is how a test invokes it.
     */
    suspend fun process(thread: CoroutineContext): ProcessingAnswer =
        withContext(thread) { handlers.require("process()").onProcess.answer(Unit) }

    /** The operating system ends the invocation. */
    fun terminate() = handlers.require("notifyTermination()").onTerminate.deliver(Unit)
}

/** A completion a mocked platform waits on: [ended] completes once the core answers it. */
internal class Answered : Completion {
    val ended = CompletableDeferred<Unit>()

    override fun complete() {
        ended.complete(Unit)
    }
}
