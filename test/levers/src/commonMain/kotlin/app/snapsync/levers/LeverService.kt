package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.RpcServiceDescriptor
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlin.reflect.KClass

/**
 * **A mocked system's operator face as a typed service** (`docs/testing.md`, "Typed services"): an `@Rpc`
 * interface whose companion object extends this, naming the service once — its [name] and what a host must offer
 * before any of its methods is honoured ([needs]).
 *
 * The control channel serves it over kRPC: the kotlinx-rpc compiler plugin generates the client a test calls and the
 * server stub the rig registers the implementation under, so a renamed lever or a changed type is a compile error. A
 * method's parameters are its arguments and its return type is its reply — `Unit` for a lever, the value for a read.
 *
 * A method that needs more than its service's [needs] does not belong in it: it lives in a service declaring those.
 */
@OptIn(ExperimentalRpcApi::class)
abstract class LeverService<@Rpc T : Any>(
    /** The interface, which the channel registers its implementation under. */
    val type: KClass<T>,
    /** The service's name, by which a host classifies it (`<name>.<method>`) and a refusal names it. */
    val name: String,
    /** Every need a host must meet to honour this service's methods; one unmet refuses them all, with its reason. */
    val needs: Set<LeverNeed>,
    descriptor: () -> RpcServiceDescriptor<T>,
    /**
     * The methods that only read — the rest are acts. A host announces each act on the world's event stream and runs a
     * read unannounced (`docs/testing.md`, "How a test waits"): a caller polling a read makes no act to wait on.
     */
    val reads: Set<String> = emptySet(),
) {
    /** The compiler plugin's description of the interface: its methods, their parameters and an invoker. */
    val descriptor: RpcServiceDescriptor<T> by lazy(descriptor)

    /** Each method's entry in a host's advertisement — `<name>.<method>` — in declaration order. */
    val entries: List<String> by lazy { this.descriptor.callables.keys.map { "$name.$it" } }

    override fun toString(): String = "LeverService($name)"
}

/** What a host must offer before a service's methods are honoured. */
enum class LeverNeed(val what: String) {
    /** The backend is the mock, whose operator the service is: the levers a real backend has no route for. */
    BACKEND_MOCKED("the backend mock's operator"),

    /**
     * The backend's public surface is reachable from the channel: the routes any device or member calls, so these
     * methods work against the real `api/` as well as the mock.
     */
    BACKEND_REACHABLE("the backend's public surface"),

    /** The operating system's upload-job queue is the mock, whose operator the service is. */
    UPLOAD_QUEUE_MOCKED("the upload-job queue's mock"),

    /** The app uploader's background transfer session is the mock, whose operator the service is. */
    UPLOAD_SESSION_MOCKED("the upload session's mock"),

    /** The background download session is the mock, whose operator the service is. */
    DOWNLOADS_MOCKED("the download session's mock"),

    /** The photo library is the mock, whose operator the service is: a real library's photos are the member's. */
    LIBRARY_MOCKED("the photo library mock's operator"),

    /** The device's network is the mock, whose operator the service is. */
    NETWORK_MOCKED("the network's mock"),

    /** The device's power, battery, thermal and background-allowance state is the mock's. */
    DEVICE_CONDITIONS_MOCKED("the device conditions' mock"),

    /** The wall clock is the mock, whose operator the service is. */
    CLOCK_MOCKED("the clock's mock"),

    /**
     * Nothing else moves the clock: on the JVM host the loop owns it, and `world.advanceTo` moves it with the timers
     * due on the way — a lever that set it alone would leave them behind.
     */
    CLOCK_UNOWNED("a clock no loop owns"),

    /** What the operating system says of the app's process is the mock's. */
    PROCESS_INFO_MOCKED("the process information's mock"),

    /**
     * The platform's process-metric reports are the mock's, whose operator the service is: only where the app is
     * composed over it (the JVM host) — a device's reports are the platform's own.
     */
    PROCESS_METRICS_MOCKED("the process-metric reports' mock"),

    /** The crash reporter's channel is the mock, whose backend the service reads. */
    CRASH_REPORTER_MOCKED("the crash reporter's mock"),

    /** The platform's share sheet, URL opener and Settings page are the mock's. */
    SYSTEM_UI_MOCKED("the system UI's mock"),

    /** The build's declared version is a cell the channel may change — where the backend is the mock, which reads it. */
    VERSION_SETTABLE("a declared version the channel may change"),

    /** The device's files are the mock's: the disk a test may write to. */
    FILES_MOCKED("the device's files, mocked"),

    /** The device's databases are the mock's: the SQLite whose faults a test may play. */
    DATABASES_MOCKED("the device's databases, mocked"),

    /** The device's Keychain is the mock's. */
    KEYCHAIN_MOCKED("the device's Keychain, mocked"),

    /** The device's Preferences (user defaults) are the mock's. */
    PREFERENCES_MOCKED("the device's Preferences, mocked"),

    /** The app's foreground life is the mock, whose operator the service is. */
    LIFECYCLE_MOCKED("the foreground life's mock"),

    /** The links the platform opens the app with are the mock's. */
    LINKS_MOCKED("the links' mock"),

    /** The push service is the mock, whose operator the service is. */
    PUSH_MOCKED("the push service's mock"),

    /** The operating system's scheduled wakes are the mock's. */
    WAKE_MOCKED("the scheduled wakes' mock"),

    /** The operating system's background-time holds are the mock's: the operating system is played, its expiry too. */
    BACKGROUND_TIME_MOCKED("the background-time holds' mock"),

    /** The upload extension's registration is the mock's. */
    EXTENSION_REGISTRY_MOCKED("the extension registration's mock"),

    /**
     * The host serves the service itself, and classifies it **method by method**: each honoured, or refused with the
     * host's reason. For a verb whose subject is the host — its process, its screen, its launch, its platform — and not
     * a mocked system: no single need says where it is honoured. A host that serves no implementation of such a
     * service leaves every method of it unclassified, which its advertisement names.
     */
    HOST("this host's own verb"),
}

/**
 * On an `event` parameter: omitted (`null`), it names the event this device is joined to — the channel resolves it
 * before the call reaches the service, and answers `400` when the device is joined to none.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class JoinedEvent

/** On a `device` parameter: omitted (`null`), it names this device — the channel resolves it before the call. */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ThisDevice

/**
 * On a public-route method: the operator [members] it stands in for, each as `<Face>.<member>` — what a test reaches
 * through a surface every host serves instead of through the operator: the backend's public routes, so it holds against
 * the real `api/` too, or the operating system's deliveries. The gate (`LeverFacesTest`) accepts a face member that is no
 * method of its interface only when such a method names it here — that face's member, and no other face's of the name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class StandsInFor(vararg val members: String)

/** Every service the channel serves. A face migrates by joining this list; nothing else registers it. */
val LEVER_SERVICES: List<LeverService<*>> = listOf<LeverService<*>>(
    RigLevers,
    WorldLevers,
    BackendLevers,
    PublicBackendLevers,
    UploadQueueLevers,
    UploadSessionLevers,
    DownloadSessionLevers,
    LibraryLevers,
    NetworkLevers,
    DeviceConditionsLevers,
    ClockLevers,
    ProcessInfoLevers,
    ProcessMetricsLevers,
    CrashReporterLevers,
    SystemUiLevers,
    DevControlsLevers,
    BuildLevers,
    DatabasesLevers,
    FilesLevers,
    KeychainLevers,
    PreferencesLevers,
    DeviceLevers,
    OlderInstallLevers,
    OsLevers,
    PlayedOsLevers,
    ExtensionHostLevers,
    LifecycleLevers,
    LinksLevers,
    PushLevers,
    WakeLevers,
    BackgroundTimeLevers,
    ExtensionRegistryLevers,
    ScreenLevers,
    HostLevers,
    GalleryLevers,
    AdaptersLevers,
    ContractLevers,
    UploadJobLevers,
)

/**
 * A host refused a service's method: one of the service's needs is unmet here. kRPC carries it to the client as this
 * same class (it is rebuilt by name), so a caller tells a refusal from a failure by its type.
 */
class LeverRefused(message: String) : IllegalStateException(message)
