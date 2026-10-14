package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The operating system as the app meets it (`docs/testing.md`, "Typed services"): what it delivers to the app's entry
// points, what only an operating system that is PLAYED can do (its expiry), the upload extension's invocations, and what
// each of its systems recorded of the app. A delivery that hands the app a live completion handler crosses no wire, so
// the services name the delivery and the host hands the handler over.

/**
 * **The operating system's deliveries to the app's entry points** — each the delivery the iOS shell's entry point of the
 * same name receives, and Android's counterpart. Every host serves it: each delivery goes through the system that makes
 * it — its mock where the host's app runs over one, the platform's own adapter where the system is real.
 *
 * A delivery the operating system hands a completion handler ([silentPush], [backgroundTask], [backgroundTransfers])
 * answers once it is delivered; the app's release of the handler is the world's [WorldEvent.CompletionReleased]. A
 * caller on a host with no event stream — a device — passes `awaitRelease` `true`, and the call answers once the app
 * released it, with how long it held it.
 */
@Rpc
interface OsLevers {
    /** The app became active. */
    suspend fun foreground()

    /** The app is leaving the active state. */
    suspend fun background()

    /** The push service issued this device [token] (hex). */
    suspend fun pushToken(token: String)

    /** The push service could not issue a token, for [description]. */
    suspend fun pushTokenFailure(description: String)

    /** A running app was handed [url] as a Universal Link. */
    suspend fun continueLink(url: String)

    /** A silent push arrived naming [event] — omitted, a push naming none. */
    suspend fun silentPush(event: String? = null, awaitRelease: Boolean? = null): Released?

    /** The operating system launched the scheduled background task [identifier] (the platform's own task identifier). */
    suspend fun backgroundTask(identifier: String, awaitRelease: Boolean? = null): Released?

    /** The operating system relaunched the app for the background transfer session [identifier]'s events. */
    suspend fun backgroundTransfers(identifier: String, awaitRelease: Boolean? = null): Released?

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<OsLevers>(OsLevers::class, "os", emptySet(), { serviceDescriptorOf() })
}

/**
 * The app released a completion handler the operating system handed it, [heldMs] after it was handed over, with what
 * the device held [atRelease] — read on the releasing thread, so "released after the wake's own work" is a statement
 * about that instant.
 */
@Serializable
data class Released(val heldMs: Long, val atRelease: AtRelease)

/**
 * What the device held the instant the app released a completion handler — each `null` where the system that holds it
 * is the platform's on this launch, and keeps its own counsel.
 */
@Serializable
data class AtRelease(
    /** The files in the download staging directory ([FilesLevers.stagedFiles]). */
    val stagedFiles: Int?,
    /** How many times the operating system handed the upload session its events ([UploadSessionView.handbacks]). */
    val uploadSessionHandbacks: Int?,
)

/**
 * **What only an operating system that is PLAYED can do** — its expiry — and its own record of the completion handlers
 * it handed the app. A real operating system's expiry is its own, so a host honours this only where the background-time
 * holds are mocked.
 */
@Rpc
interface PlayedOsLevers {
    /** Time is up: every completion handler the operating system holds, and every background-time hold, is told so. */
    suspend fun expire()

    /** The next completion handler the operating system hands over arrives with its time already up. */
    suspend fun expireNext()

    /** The completion handlers the operating system handed the app, as it counts them. */
    suspend fun completions(): Completions

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<PlayedOsLevers>(
        PlayedOsLevers::class,
        "played-os",
        setOf(LeverNeed.BACKGROUND_TIME_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** Completion handlers: [handed] over, [released] by the app, [releasedAgain] for one already released, still [held]. */
@Serializable
data class Completions(val handed: Int, val released: Int, val releasedAgain: Int, val held: Int)

/**
 * **The operating system's invocations of the upload extension** — a separate process's entry port. A host serves it
 * where it has an extension to invoke: the JVM host's, and an iOS app host's (the real extension root, run in the app's
 * process); Android has none.
 */
@Rpc
interface ExtensionHostLevers {
    /** The operating system invokes the extension's `process()`, and acts on what it answers. */
    suspend fun process(): ExtensionAnswer

    /** The operating system ends the invocation. */
    suspend fun terminate()

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<ExtensionHostLevers>(
        ExtensionHostLevers::class,
        "extension-host",
        emptySet(),
        { serviceDescriptorOf() },
    )
}

/** What the extension's `process()` answered the operating system. */
@Serializable
enum class ExtensionAnswer {
    /** Nothing more to do now; the system may rest. */
    @SerialName("completed")
    COMPLETED,

    /** Work remains; the system should invoke again. */
    @SerialName("processing")
    PROCESSING,

    /** The invocation failed. */
    @SerialName("failed")
    FAILED,
}

/** **The app's foreground life, as the operating system recorded it.** */
@Rpc
interface LifecycleLevers {
    /** Whether the app has ever become active. */
    suspend fun everActive(): Boolean

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<LifecycleLevers>(
        LifecycleLevers::class,
        "lifecycle",
        setOf(LeverNeed.LIFECYCLE_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** **The links the platform opens the app with**, where it is the mock: what only an installation's store delivers. */
@Rpc
interface LinksLevers {
    /** A start of the installation, whose store answers [referrer]: delivered on the installation's first start only. */
    suspend fun startInstalled(referrer: String)

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<LinksLevers>(
        LinksLevers::class,
        "links",
        setOf(LeverNeed.LINKS_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** **The push service's operator face**: what it is, whether it issues tokens, and what the app asked of it. */
@Rpc
interface PushLevers {
    /** The push service this device speaks (`apns`, `fcm`) — read as the app composes, so it takes effect at a relaunch. */
    suspend fun kind(kind: String)

    /**
     * The push service issues this device no token, for [reason] — what a build naming no push project meets: every ask
     * is answered with this failure. Omitted, it issues tokens again.
     */
    suspend fun refuseTokens(reason: String? = null)

    /** The push service as it stands now. */
    suspend fun push(): PushView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<PushLevers>(
        PushLevers::class,
        "push",
        setOf(LeverNeed.PUSH_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The push service: how many times the app asked it for its token, what it is, and why it refuses one, if it does. */
@Serializable
data class PushView(val registrations: Int, val kind: String, val refusal: String?)

/** **The operating system's scheduled wakes**: what the app asked it for. */
@Rpc
interface WakeLevers {
    /** The wake requests as the operating system holds them now, and how many heartbeats it accepted. */
    suspend fun wakes(): WakeView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<WakeLevers>(
        WakeLevers::class,
        "wake",
        setOf(LeverNeed.WAKE_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The wake queue: how many heartbeat requests the operating system accepted, and the requests [pending] now. */
@Serializable
data class WakeView(val heartbeatsScheduled: Int, val pending: List<PendingWake>)

/**
 * One wake request: [wake] (`heartbeat`, `library-changed`), and for a timed one its [cadence] (`busy`, `idle`) and the
 * delay it may not fire before, [earliestSeconds].
 */
@Serializable
data class PendingWake(val wake: String, val cadence: String? = null, val earliestSeconds: Long? = null)

/** **The operating system's background-time holds**: the table of what the app holds, and their expiry. */
@Rpc
interface BackgroundTimeLevers {
    /**
     * The holds outstanding, each by the name the app began it under — as `beginBackgroundTask(withName:)` names it to
     * the system — verbatim.
     */
    suspend fun holds(): List<String>

    /** Every outstanding hold's time is up: each one's expiry runs. The completion handlers are not told (`played-os`). */
    suspend fun expireAll()

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<BackgroundTimeLevers>(
        BackgroundTimeLevers::class,
        "background-time",
        setOf(LeverNeed.BACKGROUND_TIME_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** **The upload extension's registration**, as the operating system records it. */
@Rpc
interface ExtensionRegistryLevers {
    /** Whether a registration exists; `null` on a platform without the extension. */
    suspend fun registered(): Boolean?

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<ExtensionRegistryLevers>(
        ExtensionRegistryLevers::class,
        "extension-registry",
        setOf(LeverNeed.EXTENSION_REGISTRY_MOCKED),
        { serviceDescriptorOf() },
    )
}
