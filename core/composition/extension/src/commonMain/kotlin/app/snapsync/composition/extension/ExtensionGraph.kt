package app.snapsync.composition.extension

import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.application.crash.DescribeReporter
import app.snapsync.application.upload.CreateJobs
import app.snapsync.application.upload.SettlePresentedJobs
import app.snapsync.composition.process.ProcessGraph
import app.snapsync.composition.process.UploadGraph
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumMap
import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.BuildTarget
import app.snapsync.data.backend.VersionIgnored
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.identity.StoredDeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.process.StartedProcess
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
import app.snapsync.domain.upload.UploadHost
import app.snapsync.entrypoints.ExtensionPass
import app.snapsync.entrypoints.ExtensionUploads
import app.snapsync.entrypoints.OnProcess
import app.snapsync.entrypoints.OnTerminate
import app.snapsync.scheduling.Runtime

/**
 * **The upload extension's graph** (`docs/architecture.md`, "The composition layer") — only what an upload pass
 * needs, wiring only. It can only be built over a started process ([StartedProcess]), and what this process builds
 * differently from the app's uploader is declared as the restricted type, each deliberately:
 * - the identity is **read-only** ([StoredDeviceIdentity]) — this process neither mints nor adopts: guessing is what
 *   once gave one device two identities;
 * - echo suppression is the download store opened **read-only** ([ReceivedPhotosReader]): the app creates and migrates
 *   it, and an older schema pauses the cycle instead;
 * - the credential only **drops** a rejected token ([DropOnlyCredential]) — the extension cannot attest, so a token the
 *   backend refused stays missing until the app renews it — and its in-memory copy is re-read at every invocation,
 *   since the app renews into the shared item;
 * - admission is this process's own grant read, a full grant only ([ExtensionUploaderAdmission]), and its own photos
 *   walk the library bare — no walk memo, and no selection: under a partial grant there is nothing it may read;
 * - it only **places** its own photos in the event album ([PlaceOwnPhotos]): the app is the album's sole creator.
 *
 * The app-only pieces cannot be built here at all: their constructors are `@AppProcessOnly`, and no module the
 * extension links opts into it (`ExtensionSafetyTest`).
 */
class ExtensionGraph(ports: ExtensionPorts, runtime: Runtime, process: ProcessGraph, started: StartedProcess) {
    /** Read only: the extension never mints an identity. */
    private val identity: StoredDeviceIdentity = StoredDeviceIdentity(ports.secureStore)

    /** The app's download store as the extension may read it: read-only, never created or migrated here. */
    private val received: ReceivedPhotosReader = ReceivedPhotosReader(ports.databases)

    /**
     * This process's own grant read — the extension withholds its cycle without a full grant: the OS invokes a
     * surviving registration under a partial grant too, and this is what stops it.
     */
    private val admission: ExtensionUploaderAdmission = ExtensionUploaderAdmission(ports.gallery)

    /**
     * The device token, read from the shared protected store the app writes, held in memory between re-reads: every
     * request reads it, and a store read each time was measurable. Re-read at every invocation ([OnProcess]) — the app
     * renews into the shared item, which this copy cannot see — so the copy's staleness is bounded to one invocation.
     */
    private val attestStore: CachedCredentialStore = CachedCredentialStore(SecureCredentialStore(ports.secureStore))

    /** It cannot attest: a token the backend refused stays missing until the app renews it, and nothing is retried. */
    private val credential: DropOnlyCredential = DropOnlyCredential(attestStore)

    /** No version gate: the extension has no screen to show a refusal on. */
    private val exchange: BackendExchange = BackendExchange(
        ports.http,
        BuildTarget(ports.process.build),
        VersionIgnored,
    )

    private val gateway: BackendGateway = BackendGateway(exchange, credential)

    /** The extension never copies the records' legacy homes: it pauses until the app has (see `UploadGateRead`). */
    private val records: SharedDatabase = SharedDatabase(ports.databases, legacy = null)

    private val membership: MembershipRepository = MembershipRepository(records)

    private val clock: WallClock = WallClock(process.clock)

    /**
     * The extension's own photos: walked afresh on every invocation (the walk memo is the app's), and never a
     * selection — it holds none, so under a partial grant its read plan is that nothing can be read.
     */
    private val photos: OwnPhotos = OwnPhotos(
        ports.gallery,
        GalleryWalk(ports.gallery, clock),
        SelectionSnapshots(),
        DenylistedAlbums(ports.gallery),
        clock,
        received,
    )

    /** The extension only ever ADDS its own photos to the event album: it holds no way to make one. */
    private val placeOwn: PlaceOwnPhotos = PlaceOwnPhotos(AlbumMap(records), AlbumFiling(ports.gallery))

    /** Read only: the extension derives each file's key from the kept one and never writes the slot. */
    private val keys: EventKeyStore = EventKeyStore(ports.secureStore, EventKeyIds(process.crypto))

    private val upload: UploadGraph = UploadGraph(
        records = records,
        membership = membership,
        identity = identity,
        host = UploadHost.of(ports.process.build.uploadHost),
        photos = photos,
        admission = admission,
        received = received,
        placeOwn = placeOwn,
        manifestRecord = ManifestRecord(records),
        clock = clock,
        credentials = attestStore,
        credential = credential,
        exchange = exchange,
        gateway = gateway,
        keys = keys,
        cipher = FileCipher(process.crypto, process.files),
    )

    /**
     * The extension's transfer: the OS's own queue — PhotoKit's, which sends the library's bytes — under the same
     * shared mobile-data choice the app's menu writes.
     */
    private val tier: OsQueueTier =
        OsQueueTier(
            ports.uploadQueue,
            upload.requests,
            upload.sealing,
            MobileDataStore(ports.preferences),
            upload.record,
        )

    /** What the OS queue presents, settled first at every invocation. */
    private val drain: OsQueueDrain = OsQueueDrain(
        ports.uploadQueue,
        JobOwners(upload.rows),
        JobAcknowledgement(ports.uploadQueue),
        upload.record,
    )

    /** The extension's one upload pass over the shared layers. */
    private val pass: ExtensionPass = ExtensionPass(
        upload.gate,
        upload.contribute,
        SettlePresentedJobs(drain, tier, upload.rows),
        ExtensionUploads(
            upload.walk,
            upload.recordWalk,
            upload.plan,
            CreateJobs(upload.rows, tier, upload.resources, upload.walkRecord, upload.recent),
            upload.album,
            upload.publish,
        ),
        upload.publish,
        upload.rows,
    )

    val onProcess: OnProcess = OnProcess(
        runtime,
        attestStore,
        membership,
        keys,
        DescribeReporter(ReportedIdsRead(membership, identity, ports.process.build), started.crash),
        pass,
    )

    val onTerminate: OnTerminate = OnTerminate(runtime)
}
