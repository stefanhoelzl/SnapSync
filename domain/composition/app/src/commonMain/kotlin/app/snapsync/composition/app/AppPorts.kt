package app.snapsync.composition.app

import app.snapsync.composition.process.ProcessPorts
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Crypto
import app.snapsync.ports.Databases
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DevControls
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Download
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Files
import app.snapsync.ports.Gallery
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.Links
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Port
import app.snapsync.ports.Preferences
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SystemUi
import app.snapsync.ports.TransferSession
import app.snapsync.ports.Ui
import app.snapsync.ports.Wake
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState

/**
 * **The app process's ports** — everything the app-graph composition consumes, and nothing but ports ([Port]; spec
 * `docs/architecture.md`, "The composition layer"). The root constructs its platform adapters and supplies them here;
 * [composeApp] composes the process ([ProcessPorts] first — its crash reporting before anything else), then the
 * app's graph over these ports ([AppGraph]).
 *
 * No defaults: a port added here is added once, and every root fails to compile until it answers — the phone's, the
 * JVM's and the rig's alike. Nothing else rides here: services are built by the composition itself, build constants
 * are read from [BuildInfo], and a main lane is owned by the adapter that needs it.
 */
class AppPorts(
    /** The ports every process has exactly one of: crash reporter, log sinks, files, clock, build. */
    val process: ProcessPorts,
    /** The app's SQLite databases: the ledger and the download store open on them, on first use. */
    val databases: Databases,
    /** Small shared preferences: the event album's leave-surviving map. */
    val preferences: Preferences,
    /** The protected small-value store: the device id, the attestation token and key id, the legacy album map. */
    val secureStore: SecureStore,
    /** The platform's own stable device id, where it has one — what a first mint adopts. */
    val platformDeviceId: PlatformDeviceId,
    /**
     * The device's photo library (`docs/architecture.md`): every read the status total, the join preview, the
     * download guard and the event album make, the album writes, and the `requestAccess` / `choosePhotos`
     * user taps. The decisions over it — which grant may answer, what is denylisted — are the services this
     * graph builds over it, never the gallery's.
     */
    val gallery: Gallery,
    /** The platform's own UI, where this app hands something over and stops being involved — the share sheet
     *  for the invite URL, the URL opener for the store link, and the app's Settings page (see [SystemUi]). */
    val systemUi: SystemUi,
    /**
     * The platform's background downloads. An event port: the host zone registers
     * its download handlers on it as the graph is composed, so a background relaunch that delivers
     * finished transfers finds them.
     */
    val download: Download,
    /**
     * HTTP, and nothing above it (`docs/architecture.md`, "The backend boundary") — production passes
     * `:adapter:generic:app`'s `KtorHttpTransport` over the platform's HTTP client. The backend's protocol is composed
     * over it here — ONE exchange, ONE gateway — so no root can wire a call without the credential or without the
     * version verdicts.
     */
    val http: HttpTransport,
    /** The platform's device-integrity service (App Attest on iOS) — what the attestation service proves with. */
    val integrity: DeviceIntegrity,
    /**
     * The app's own uploader session (a background `URLSession` on iOS, Android's uploads) — what the app uploader's
     * cycle starts its transfers on, and an event port: the host zone registers its upload handlers
     * on it as the graph is composed, so a relaunch that hands back finished uploads records them.
     */
    val uploadSession: TransferSession,
    /** The process's background time (`docs/architecture.md`, "Background time is an outbound port named for
     *  the need"): what a push or a transfer wake holds across its own work and its tail, and the only "time is up"
     *  those wakes get. */
    val backgroundTime: BackgroundTime,
    /**
     * The operating system's scheduled wakes (`docs/architecture.md`, "Background execution"): the heartbeat the tail
     * runner re-arms, and — through its `listen`, registered as the graph is composed — the wakes it delivers.
     */
    val wake: Wake,
    /** The OS's record of the upload extension's registration — present on every platform; one without the
     *  OS-driven mechanism (iOS below 26.1, the JVM, Android) answers `Unsupported` and asks the OS nothing. The
     *  disable→enable ritual over it is the data layer's `ExtensionRegistrar`. */
    val extensionRegistry: ExtensionRegistry,
    /** The runtime inputs only a rig build can set — the per-uploader pin, the invite-link hints and the reset. All
     *  inert in a production build (see [DevControls]). */
    val devControls: DevControls,
    /** The platform's push service: asked for the token at launch and at every
     *  foreground entry; the host zone registers [AppGraph.pushHandlers] on it as the graph is composed. */
    val pushNotifications: PushNotifications,
    /** The app's foreground life — an event port the host zone registers the foreground work on. */
    val lifecycle: Lifecycle,
    /** The links the platform opens the app with — an event port the host zone registers the link handler on. */
    val links: Links,
    /** The platform's user interface — shown every state the status host reduces, from host assembly on. */
    val ui: Ui<UiState, UiIntent>,
    /** What the OS says about this process — whether protected storage is readable right now, recorded by the
     *  background entry points and deciding nothing. */
    val processInfo: ProcessInfo,
    /** The device's network as the operating system reports it to this app — watched only while the app is in front,
     *  to tell the member when it has none. */
    val network: NetworkMonitor,
    /** The device's power, battery, thermal state and background allowance — read only for a bug report. */
    val deviceConditions: DeviceConditions,
    /** The platform's date formatting — how a date reads on the screen; the core formats no date
     *  (`docs/architecture.md`, "Localization"). The screen renders through it, and only the app has one. */
    val dateFormatting: DateFormatting,
)

/**
 * **The app process's ports onto the device's systems** — what an iOS or Android app root composes over, each resolved
 * on first use: the root hands its REAL adapters in as `lazy { … }` and composes over what its build's adapter set
 * hands back — the same bundle on a production build, and on a rig build the launch-time adapters', a mocked system's
 * ports swapped for its mock's faces (`docs/testing.md`, "Launch-time adapters"). Nothing is built until it is asked
 * for, so a mocked system's real adapter is never constructed.
 *
 * Every port is required: a process's bundle names exactly the ports it has, so a port its root did not supply is a
 * compile error, never a runtime one. The upload extension has its own bundle ([ExtensionDevicePorts]).
 */
class AppDevicePorts(
    val clock: Lazy<Clock>,
    /** The platform's cryptographic primitives. */
    val crypto: Lazy<Crypto>,
    val crashReporter: Lazy<CrashReporter>,
    val files: Lazy<Files>,
    val databases: Lazy<Databases>,
    val preferences: Lazy<Preferences>,
    val secureStore: Lazy<SecureStore>,
    /** The platform's own stable device id, where it has one (none on iOS). */
    val platformDeviceId: Lazy<PlatformDeviceId>,
    val integrity: Lazy<DeviceIntegrity>,
    val processInfo: Lazy<ProcessInfo>,
    /** The device's network as the operating system reports it to this app. */
    val network: Lazy<NetworkMonitor>,
    /** The device's power, battery, thermal state and background allowance, read for a bug report. */
    val deviceConditions: Lazy<DeviceConditions>,
    val http: Lazy<HttpTransport>,
    val backgroundTime: Lazy<BackgroundTime>,
    val wake: Lazy<Wake>,
    val extensionRegistry: Lazy<ExtensionRegistry>,
    /** The app's photo library: its reads, its writes, its selection observer and its imports. */
    val gallery: Lazy<Gallery>,
    /** The app uploader's background transfer session. */
    val uploadSession: Lazy<TransferSession>,
    val download: Lazy<Download>,
    val systemUi: Lazy<SystemUi>,
    val lifecycle: Lazy<Lifecycle>,
    val links: Lazy<Links>,
    val pushNotifications: Lazy<PushNotifications>,
    val ui: Lazy<Ui<UiState, UiIntent>>,
    /** How a date reads on the screen — the platform's CLDR data. */
    val dateFormatting: Lazy<DateFormatting>,
) {
    /**
     * The app process's [AppPorts] over these ports — the one place an app root's ports become the composition's, so
     * an iOS and an Android root hand over the same set. What the bundle does not hold comes from the root: the
     * process's own [process] ports, and the two its build's adapter set decorates ([devControls] — inert on every
     * production build, the control channel's on a rig build — and [ui], the rig's decorator where it wraps it).
     */
    fun appPorts(process: ProcessPorts, devControls: DevControls, ui: Ui<UiState, UiIntent>): AppPorts = AppPorts(
        process = process,
        // This process's SQLite databases: the ledger (shared with an upload extension where one exists, every write
        // one guarded transaction) and the download store the app alone writes.
        databases = databases.value,
        preferences = preferences.value,
        // The protected small-value store — the device id, the attestation token.
        secureStore = secureStore.value,
        platformDeviceId = platformDeviceId.value,
        systemUi = systemUi.value,
        // Every photo-library read and write, the partial grant's selection observer (opened at host assembly only)
        // and the import of foreign photos, whose markers the core's handlers write.
        gallery = gallery.value,
        // The platform's background downloads — an event port the host zone listens to.
        download = download.value,
        // HTTP: the backend's protocol, and every need-shaped service, are composed over it inside the core.
        http = http.value,
        integrity = integrity.value,
        // The app's uploader transport (a background `URLSession` on iOS; Android's only uploader).
        uploadSession = uploadSession.value,
        // The upload extension's registration record, on every platform: where it cannot exist (below iOS 26.1, and
        // on Android) the adapter answers `Unsupported` itself, so no root holds an `if` around it.
        extensionRegistry = extensionRegistry.value,
        devControls = devControls,
        // The entry ports: each registered by the host zone as this graph is composed, so a delivery in a background
        // wake finds its handler.
        pushNotifications = pushNotifications.value,
        lifecycle = lifecycle.value,
        links = links.value,
        ui = ui,
        backgroundTime = backgroundTime.value,
        // The operating system's scheduled wakes: the heartbeat the tail re-arms, and — by the host zone's `listen` —
        // its launch handler.
        wake = wake.value,
        processInfo = processInfo.value,
        network = network.value,
        deviceConditions = deviceConditions.value,
        dateFormatting = dateFormatting.value,
    )
}
