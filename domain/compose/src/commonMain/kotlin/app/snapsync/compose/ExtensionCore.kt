package app.snapsync.compose

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.BuildTarget
import app.snapsync.data.backend.VersionIgnored
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.identity.StoredDeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.DropOnlyCredential
import app.snapsync.data.trust.SecureCredentialStore
import app.snapsync.entrypoints.OnProcess
import app.snapsync.entrypoints.OnTerminate
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.SelectionScope
import app.snapsync.ports.Databases
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Port
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore
import app.snapsync.scheduling.Runtime
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.downloads.SuppressionService
import app.snapsync.services.gallery.AlbumLookupFailure
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.services.gallery.denylistedAlbumMembers
import app.snapsync.services.gallery.galleryAccess
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.settings.MobileDataSetting
import app.snapsync.services.upload.Uploader
import kotlinx.coroutines.CoroutineDispatcher

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
    /** The OS's upload-job queue the cycle creates its jobs on. */
    val uploadQueue: OsUploadQueue,
    /** HTTP: the backend's protocol is composed over it, behind a credential that only drops a rejected token. */
    val http: HttpTransport,
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
 * - the identity is **READ_ONLY** — this process neither mints nor adopts: guessing is
 *   what once gave one device two identities;
 * - echo suppression is the download store opened **read-only** ([SuppressionService]): the app creates and migrates
 *   it, and an older schema pauses the cycle instead;
 * - the credential only **drops** a rejected token (see [extensionBackend]), and the in-memory copy is re-read at every
 *   invocation, since the app renews into the shared item;
 * - admission is this process's own grant read (a full grant only), the library is walked unrestricted, and a failed
 *   denylisted-album lookup fails the cycle, which the next invocation retries.
 */
@OptIn(CompositionOnly::class)
fun snapSyncExtension(
    ports: ExtensionPorts,
    /** The lane the extension's own work runs on — its root's to name, as every root names the app's. */
    lane: CoroutineDispatcher,
    /**
     * Where `process()`'s work runs while the operating system's thread waits for its answer: `null` on the phone — on
     * that thread — or a lane a host must see every task on (`Runtime`'s `answerOn`).
     */
    answerOn: CoroutineDispatcher?,
): ComposedExtension {
    // The extension's own runtime: no scope of its root's to live under, and no background time to hold — it answers on
    // the invoking thread.
    val runtime = Runtime(parent = null, lane, backgroundTime = null, ports.process.threadLabel, answerOn = answerOn)
    val process = snapSyncProcess(ports.process, runtime)
    // The device token, read from the shared protected store the app writes. Held in
    // memory between re-reads ([CachedCredentialStore]): every request reads it, and a store read each time was
    // measurable.
    // Re-read at every invocation — the app renews into the shared item, which this copy cannot see — so the copy's
    // staleness is bounded to one invocation.
    val attestStore = CachedCredentialStore(SecureCredentialStore(ports.secureStore))
    val services = extensionServices(process, ports, attestStore)
    val cycle = lazy { uploadCycle(process, services) }
    ports.host.inbound(
        ExtensionHandlers(
            OnProcess(runtime, attestStore, services.membership, cycle, services.ledger),
            OnTerminate(runtime),
        ),
    )
    return ComposedExtension(process, services, cycle)
}

/** The extension's services over its ports — see [snapSyncExtension] for what differs from the app's. */
private fun extensionServices(
    process: ProcessServices,
    ports: ExtensionPorts,
    attestStore: CachedCredentialStore,
): UploadServices {
    val log = process.logger("UploadExtension")
    // Read only: the extension never mints an identity.
    val identity = StoredDeviceIdentity(ports.secureStore)
    val albums = GalleryAlbums(ports.gallery)
    val build = ports.process.build
    // No version gate: the extension has no screen to show a refusal on.
    val exchange = BackendExchange(ports.http, BuildTarget(build), VersionIgnored)
    // The extension never copies the records' legacy homes: it pauses until the app has (see `CycleGateRead`).
    val records = SharedDatabase(ports.databases, legacy = null)
    val membership = MembershipRepository(records)
    val config = MembershipProjection(membership, process.clock)
    // The app's download store, read-only: both the echo suppression and the recognition record.
    val downloads = SuppressionService(ports.databases)
    return UploadServices(
        records = records,
        membership = membership,
        config = config,
        // The same shared choice the app's menu writes, read per job.
        mobileData = MobileDataSetting(ports.preferences),
        deviceIdentity = identity,
        host = build.uploadHost,
        // Scoped to the joined event this process last read; each invocation re-reads the config first.
        ledger = LedgerService(records) { config.config.value?.eventId },
        uploader = Uploader.Queue(ports.uploadQueue),
        gallery = ports.gallery,
        discovery = GalleryDiscovery(ports.gallery),
        // This process's own grant read — the extension withholds its cycle without a full grant: the OS invokes a
        // surviving registration under a partial grant too, and this is what stops it.
        process = UploaderProcess.Extension(ports.gallery),
        // Unrestricted, stated: the extension never reads the library under a partial grant — its admission withholds
        // before any read.
        selectionScope = { SelectionScope.Unrestricted },
        manifestStore = DeviceManifestService(records),
        manifestPublisher = extensionBackend(exchange, attestStore, identity).manifest,
        suppression = downloads,
        recognition = downloads,
        // A failed denylisted-album lookup fails the cycle here; the next invocation retries.
        albumExclusions = { cutoff ->
            denylistedAlbumMembers(
                albums,
                cutoff,
                ports.gallery.access().galleryAccess(),
                AlbumLookupFailure.FailCycle,
                log,
            )
        },
        // The extension only ever ADDS completed uploads to the event album; the app is its sole creator.
        albumCoordinator = AlbumCoordinator(albums, AlbumMapService(records)),
        // A retry re-reads the shared item: the app may have renewed the token this copy still holds.
        credentials = attestStore,
        exchange = exchange,
        // Read only: the extension derives each file's key from the kept one and never writes the slot.
        eventKeys = EventKeys(process.crypto, ports.secureStore),
        log = log,
    )
}

/**
 * The upload extension's backend services: the same need-shaped services the app
 * composes, over a gateway whose credential only DROPS a rejected token ([DropOnlyCredential]) — the extension
 * cannot attest, so it never retries a rejected request — over an [exchange] with no version gate, because the
 * extension has no screen to show a refusal on.
 *
 * [attestStore] is the root's one in-memory copy of the shared token, the one it re-reads at every invocation.
 */
internal fun extensionBackend(
    exchange: BackendExchange,
    attestStore: CachedCredentialStore,
    identity: DeviceIdentity,
): BackendServices =
    // The extension never creates an event, so it has no zone to send.
    BackendServices(
        BackendGateway(exchange, DropOnlyCredential(attestStore)),
        identity,
        zone = { null },
        // No membership to mark: the app's next read of the event marks it.
        eventOver = null,
    )
