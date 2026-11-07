package app.snapsync.compose

import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.application.crash.DescribeReporter
import app.snapsync.application.upload.SettlePresentedJobs
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumMap
import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.BuildTarget
import app.snapsync.data.backend.VersionIgnored
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.identity.StoredDeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.receiving.ReceivedPhotosReader
import app.snapsync.data.sharing.DenylistedAlbums
import app.snapsync.data.sharing.GalleryWalk
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.time.WallClock
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.DropOnlyCredential
import app.snapsync.data.trust.SecureCredentialStore
import app.snapsync.data.upload.ExtensionUploaderAdmission
import app.snapsync.data.upload.JobAcknowledgement
import app.snapsync.data.upload.JobOwners
import app.snapsync.data.upload.OsQueueDrain
import app.snapsync.data.upload.OsQueueTier
import app.snapsync.entrypoints.ExtensionPass
import app.snapsync.entrypoints.ExtensionUploads
import app.snapsync.entrypoints.OnProcess
import app.snapsync.entrypoints.OnTerminate
import app.snapsync.inbound.CompositionOnly
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
import app.snapsync.scheduling.RuntimePorts
import app.snapsync.services.backend.BackendServices
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
 * The composed upload extension: its process's services; its upload pass is built on first use.
 */
class ComposedExtension internal constructor(
    /** What the process set up — its crash reporting started as the composition's first act. */
    val process: ProcessServices,
    internal val services: UploadServices,
)

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
 * - echo suppression is the download store opened **read-only** ([ReceivedPhotosReader]): the app creates and migrates
 *   it, and an older schema pauses the cycle instead;
 * - the credential only **drops** a rejected token (see [extensionBackend]), and the in-memory copy is re-read at every
 *   invocation, since the app renews into the shared item;
 * - admission is this process's own grant read (a full grant only), and its own photos walk the library bare — no
 *   walk memo, and no selection: under a partial grant there is nothing it may read.
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
    val runtime = Runtime(
        parent = null,
        lane,
        RuntimePorts(backgroundTime = null, ports.process.threadLabel, ports.process.entryEvents),
        answerOn = answerOn,
    )
    val process = snapSyncProcess(ports.process, runtime)
    // The device token, read from the shared protected store the app writes. Held in memory between re-reads
    // ([CachedCredentialStore]): every request reads it, and a store read each time was measurable. Re-read at every
    // invocation — the app renews into the shared item, which this copy cannot see — so the copy's staleness is bounded
    // to one invocation.
    val attestStore = CachedCredentialStore(SecureCredentialStore(ports.secureStore))
    val services = extensionServices(process, ports, attestStore)
    ports.host.inbound(
        ExtensionHandlers(
            OnProcess(
                runtime,
                attestStore,
                services.membership,
                services.keys,
                DescribeReporter(
                    ReportedIdsRead(services.membership, services.deviceIdentity, process.build),
                    process.crash,
                ),
                extensionPass(process, ports, services),
            ),
            OnTerminate(runtime),
        ),
    )
    return ComposedExtension(process, services)
}

/**
 * The extension's one upload pass over the shared layers ([UploadLayers]): its transfer the OS's own queue —
 * PhotoKit's, which sends the library's bytes — and what that queue presents settled first. Built lazily per piece:
 * composing it opens nothing.
 */
private fun extensionPass(process: ProcessServices, ports: ExtensionPorts, services: UploadServices): ExtensionPass {
    val layers = UploadLayers(process, services)
    val tier = OsQueueTier(ports.uploadQueue, layers.requests, layers.sealing, services.mobileData, layers.record)
    val drain = OsQueueDrain(
        ports.uploadQueue,
        JobOwners(layers.rows),
        JobAcknowledgement(ports.uploadQueue),
        layers.record,
    )
    return ExtensionPass(
        layers.gate,
        layers.contribute,
        SettlePresentedJobs(drain, tier, layers.rows),
        ExtensionUploads(
            layers.walk,
            layers.recordWalk,
            layers.plan,
            layers.create(tier),
            layers.album,
            layers.publish,
        ),
        layers.publish,
        layers.rows,
    )
}

/** The extension's services over its ports — see [snapSyncExtension] for what differs from the app's. */
private fun extensionServices(
    process: ProcessServices,
    ports: ExtensionPorts,
    attestStore: CachedCredentialStore,
): UploadServices {
    // Read only: the extension never mints an identity.
    val identity = StoredDeviceIdentity(ports.secureStore)
    val build = ports.process.build
    // No version gate: the extension has no screen to show a refusal on.
    val exchange = BackendExchange(ports.http, BuildTarget(build), VersionIgnored)
    // The extension never copies the records' legacy homes: it pauses until the app has (see `UploadGateRead`).
    val records = SharedDatabase(ports.databases, legacy = null)
    val membership = MembershipRepository(records)
    val clock = WallClock(process.clock)
    // The app's download store as the extension may read it: read-only, never created or migrated here.
    val received = ReceivedPhotosReader(ports.databases)
    val backend = extensionBackend(exchange, attestStore, identity)
    return UploadServices(
        records = records,
        membership = membership,
        // The same shared choice the app's menu writes, read per job.
        mobileData = MobileDataStore(ports.preferences),
        deviceIdentity = identity,
        host = build.uploadHost,
        gallery = ports.gallery,
        // The extension's own photos: walked afresh on every invocation (the walk memo is the app's), and never a
        // selection — it holds none, so under a partial grant its read plan is that nothing can be read.
        photos = OwnPhotos(
            ports.gallery,
            GalleryWalk(ports.gallery, clock),
            SelectionSnapshots(),
            DenylistedAlbums(ports.gallery),
            clock,
            received,
        ),
        // This process's own grant read — the extension withholds its cycle without a full grant: the OS invokes a
        // surviving registration under a partial grant too, and this is what stops it.
        admission = ExtensionUploaderAdmission(ports.gallery),
        manifestRecord = ManifestRecord(records),
        clock = clock,
        received = received,
        // The extension only ever ADDS its own photos to the event album: it holds no way to make one — the app is
        // its sole creator.
        placeOwn = PlaceOwnPhotos(AlbumMap(records), AlbumFiling(ports.gallery)),
        // A retry re-reads the shared item: the app may have renewed the token this copy still holds.
        credentials = attestStore,
        // It cannot attest: a token the backend refused stays missing until the app renews it.
        credential = DropOnlyCredential(attestStore),
        exchange = exchange,
        gateway = backend.gateway,
        // Read only: the extension derives each file's key from the kept one and never writes the slot.
        keys = EventKeyStore(ports.secureStore, EventKeyIds(process.crypto)),
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
    BackendServices(
        BackendGateway(exchange, DropOnlyCredential(attestStore)),
        identity,
    )
