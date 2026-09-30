package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.SelectionScope
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Databases
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Port
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore
import app.snapsync.ports.Upload
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.SuppressionService
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.services.identity.AttestState
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.ports.Backend
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.EntryContext
import app.snapsync.model.invocation
import app.snapsync.services.upload.runProcessCycle
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.backend.CredentialedBackend
import app.snapsync.services.trust.CachedAttestStore
import app.snapsync.services.trust.ExtensionCredential

/**
 * **The upload extension's ports** — everything its composition consumes, and nothing but ports ([Port]; spec
 * `docs/architecture.md`, "One shared composition"). The root constructs this process's adapters and supplies them
 * here; [snapSyncExtension] composes the process first, then the services over these ports, then the cycle.
 *
 * The extension has no screen, no lifecycle and no transfer session of its own: it reads the library, creates jobs on
 * the OS's upload-job queue, and reaches the backend — and it is invoked through [host] alone.
 */
class ExtensionPorts(
    /** The ports every process has exactly one of: crash reporter, log sinks, files, clock, build. */
    val process: ProcessPorts,
    /** The App-Group databases: the ledger (shared with the app) and the app's download store (read-only here). */
    val databases: Databases,
    /** Small shared preferences: the event album's map, which the app creates and this process only reads. */
    val preferences: Preferences,
    /** The shared protected store: the device id and the attestation token the app wrote. */
    val secureStore: SecureStore,
    /** The platform's own stable device id — consulted by the identity service, which never mints here. */
    val platformDeviceId: PlatformDeviceId,
    /** The extension's photo library: reads and album adds only — and the grant it reads, which admits its cycle. */
    val gallery: GalleryReader,
    /** The upload-job queue the cycle creates its jobs on. */
    val upload: Upload,
    /** The backend, behind a credential that only drops a rejected token (see [extensionBackend]). */
    val backend: Backend,
    /** The operating system's invocations of this extension — the one entry port, listened to at composition. */
    val host: ExtensionHost,
)

/**
 * The composed upload extension: its process's services and its one cycle, built on first use.
 */
class ComposedExtension internal constructor(
    /** What the process set up — its crash reporting started as the composition's first act. */
    val process: ProcessServices,
    internal val services: UploadServices,
    cycle: Lazy<UploadCycle>,
) {
    /** The cycle, assembled by the shared [uploadCycle] on the first invocation. */
    val cycle: UploadCycle by cycle
}

/**
 * The upload extension's composition (`docs/architecture.md`, "One shared composition"; "Events arrive through
 * `listen`"): the process first (`snapSyncProcess` — crash reporting before anything else), then this process's
 * services over [ports], and its one entry port's handlers registered on [ExtensionPorts.host] — here, because the
 * extension has no host zone. Registering builds nothing: the cycle is assembled on the operating system's first
 * invocation.
 *
 * What this process builds differently from the app's uploader, each deliberately:
 * - the identity is **READ_ONLY** — this process neither mints nor adopts (capability `photo-sharing`): guessing is
 *   what once gave one device two identities;
 * - echo suppression is the download store opened **read-only** ([SuppressionService]): the app creates and migrates
 *   it, and an older schema pauses the cycle instead;
 * - the credential only **drops** a rejected token (see [extensionBackend]), and the in-memory copy is re-read at every
 *   invocation, since the app renews into the shared item;
 * - admission is this process's own grant read (a full grant only), the library is walked unrestricted, and a failed
 *   denylisted-album lookup fails the cycle, which the next invocation retries.
 */
fun snapSyncExtension(ports: ExtensionPorts): ComposedExtension {
    val process = snapSyncProcess(ports.process)
    // The device token (capability `privacy-security`), read from the shared protected store the app writes. Held in
    // memory between re-reads ([CachedAttestStore]): every request reads it, and a store read each time was measurable.
    // Re-read at every invocation — the app renews into the shared item, which this copy cannot see — so the copy's
    // staleness is bounded to one invocation.
    val attestStore = CachedAttestStore(AttestState(ports.secureStore))
    val services = extensionServices(process, ports, attestStore)
    val composed = ComposedExtension(process, services, lazy { uploadCycle(process, services) })
    ports.host.listen(
        extensionHandlers(
            services = services,
            cycle = { composed.cycle },
            entryContext = process.entryContext,
            rereadCredential = attestStore::reread,
        ),
    )
    return composed
}

/** The extension's services over its ports — see [snapSyncExtension] for what differs from the app's. */
private fun extensionServices(
    process: ProcessServices,
    ports: ExtensionPorts,
    attestStore: CachedAttestStore,
): UploadServices {
    val log = process.logger("UploadExtension")
    val identity = PersistedDeviceIdentity(DeviceIdentityRole.READ_ONLY, ports.secureStore, ports.platformDeviceId)
    // Non-throwing: the store is unreadable before the first unlock since boot, and this runs on a background wake. A
    // null token is a `401`, which is retryable; the collapse is logged, never silent (law "Absence is never silent").
    val token: suspend () -> String? = {
        runCatchingCancellable { attestStore.token() }
            .onFailure { log.w(it) { "attest token unreadable — proceeding unauthenticated (expect 401)" } }
            .getOrNull()
    }
    val albums = GalleryAlbums(ports.gallery)
    val build = ports.process.build
    return UploadServices(
        config = ConfigService(process.files, process.clock),
        deviceIdentity = identity,
        host = build.uploadHost,
        ledger = LedgerService(ports.databases),
        upload = ports.upload,
        gallery = ports.gallery,
        discovery = GalleryDiscovery(ports.gallery),
        // This process's own grant read (capability `background-upload`, "The extension withholds its cycle without a
        // full grant"): the OS invokes a surviving registration under a partial grant too, and this is what stops it.
        process = UploaderProcess.Extension(PhotoGrantRead { ports.gallery.access() }),
        // Unrestricted, stated: the extension never reads the library under a partial grant — its admission withholds
        // before any read.
        selectionScope = { SelectionScope.Unrestricted },
        manifestStore = DeviceManifestService(process.files),
        manifestPublisher = extensionBackend(ports.backend, attestStore, identity).manifest,
        suppression = SuppressionService(ports.databases),
        albumManager = albums,
        albumLookupFailure = AlbumLookupFailure.FailCycle,
        // The extension only ever ADDS completed uploads to the event album; the app is its sole creator.
        albumCoordinator = AlbumCoordinator(
            albums,
            AlbumMapService(ports.preferences, ports.secureStore),
            kind = ports.gallery.albumKind,
        ),
        token = token,
        // A retry re-reads the shared item: the app may have renewed the token this copy still holds.
        freshToken = {
            attestStore.reread()
            token()
        },
        appVersion = build.appVersion,
        log = log,
    )
}

/** The extension's handlers: one upload cycle per invocation, and the invocation's end recorded. */
internal fun extensionHandlers(
    services: UploadServices,
    cycle: () -> UploadCycle,
    entryContext: EntryContext,
    /**
     * Drop this process's in-memory copy of the device token, so the invocation reads the one the app last
     * stored (capability `privacy-security`). The app renews into the shared protected item, which the
     * extension's copy cannot see; re-reading at every OS invocation bounds that copy's staleness to one.
     */
    rereadCredential: () -> Unit,
): ExtensionHandlers = ExtensionHandlers(
    // The cycle, the pending → PROCESSING requeue and the never-throw guard around both are `runProcessCycle`: a
    // throwable escaping here would cross the ObjC boundary and abort the extension process.
    onProcess = {
        val log = services.log
        log.invocation(entryContext, "process", result = { "$it" }) {
            runProcessCycle(
                // Inside the guarded run, so nothing the re-read could raise escapes across the ObjC boundary.
                run = {
                    rereadCredential()
                    cycle().run()
                },
                pending = { services.ledger.aggregates().pending },
                onCycleFinished = { log.i { "process: cycle finished — $it" } },
                onCycleFailed = { log.e(it) { "process cycle failed" } },
                onRequeue = { open -> log.i { "process: $open pending — requesting re-invocation" } },
                onLateFailure = { log.e(it) { "process failed after the cycle — reporting FAILED" } },
            )
        }
    },
    // The OS's `notifyTermination` marks the END of an invocation, not a kill (see [ExtensionHandlers.onTerminate]).
    // So this records an ordinary end at `Info`. A KILLED call is the one that reads as a `→ process` with no
    // `← process` and no line from here — which is how to tell the two apart.
    onTerminate = {
        val log = services.log
        log.invocation(entryContext, "onTerminate") {
            log.i {
                "the OS ended this invocation — notifyTermination follows a normal return; a killed call gets none"
            }
        }
    },
)

/**
 * The upload extension's backend services (capability `privacy-security`): the same need-shaped services the app
 * composes, over an authenticated backend whose credential only DROPS a rejected token ([ExtensionCredential]) —
 * the extension cannot attest, so it never retries a rejected call — and with no version gate, because the
 * extension has no screen to show a refusal on.
 *
 * [attestStore] is the root's one in-memory copy of the shared token, the one it re-reads at every invocation.
 */
internal fun extensionBackend(
    backend: Backend,
    attestStore: CachedAttestStore,
    identity: PersistedDeviceIdentity,
): BackendServices =
    BackendServices(CredentialedBackend(backend, ExtensionCredential(attestStore), versionGate = null), identity)
