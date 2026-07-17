package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.feature.creation.EventCreator
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.QueuedPhotoDownloadJobs
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.ManifestDeviceEnroller
import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.feature.trust.DeviceAttestation
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.AttestClient
import app.snapsync.ports.AttestKey
import app.snapsync.ports.AttestStore
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.EventCreation
import app.snapsync.ports.EventDirectory
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.Enrollment
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoLibrary
import app.snapsync.ports.PhotoLibraryImporter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The ports (and shell-supplied inputs) the app-graph composition consumes (spec
 * `module-architecture`, "One shared composition"). The shell constructs its platform adapters and
 * supplies them here; [snapSyncApp] composes the feature graph.
 *
 * Three groups of inputs are deliberately **lambdas built by the shell**, each with the migration
 * step that dissolves it (PLAN steps 8–9): the coordination hooks ([provision], [onEventMinted],
 * [notifyLeave]) are flow material — `flow/` does not exist until step 8, so the shell's
 * coordination functions are injected rather than re-seated twice; [uploadProducer] is the tier
 * selection, which becomes the pure sealed `resolveComposition` at step 8; [albumExcludedAssetIds]
 * carries the app process's admit-on-doubt wrapper, shared verbatim with the own-device status
 * total so the two consumers of the policy can never diverge.
 */
class AppPorts(
    val configSource: ConfigSource,
    val configStore: ConfigStore,
    val photoAccess: PhotoAccessStatusSource,
    val photoLibrary: PhotoLibrary,
    /** Read-only in this graph: the app-side ledger handle (aggregates read; the arm never writes records). */
    val ledger: LedgerStore,
    val downloadStore: DownloadStore,
    val importer: PhotoLibraryImporter,
    /** The durable download-staging directory — read lazily (an App-Group container lookup on iOS). */
    val downloadStagingRoot: () -> String,
    val newDownloadTransport: (DownloadTransportHost) -> DownloadTransport,
    val union: EventUnionSource,
    val directory: EventDirectory,
    /** The enrollment PUT — production passes `:adapter:generic`'s `HttpEnrollment`. */
    val enrollment: Enrollment,
    val eventCreation: EventCreation,
    val attestKey: AttestKey,
    val attestClient: AttestClient,
    val attestStore: AttestStore,
    /** The device-identity resolve; throws while protected data is unavailable. Kept a thunk so no
     *  composition-time resolve can abort a locked background launch. */
    val deviceId: () -> String,
    val now: () -> Long,
    /** The tier's selected producer (exactly one per process); a thunk so the non-selected tier's
     *  mechanism is never constructed. Becomes `resolveComposition` at step 8. */
    val uploadProducer: () -> UploadProducer,
    val albumManager: AlbumManager,
    val albumMapStore: AlbumMapStore,
    val albumExcludedAssetIds: suspend (cutoff: String) -> Set<String>,
    val notifyLeave: suspend (eventId: String) -> Unit,
    val provision: suspend (EventConfig) -> Unit,
    val onEventMinted: suspend (eventId: String) -> Unit,
    val log: Logger,
)

/**
 * The composed app graph. Every property is `by lazy`, mirroring the composition root's previous
 * lazy web **byte-for-byte in construction timing**: nothing here resolves the device identity or
 * touches a platform store until the same first-use moment the root's own lazies did — the property
 * a locked background launch depends on.
 */
class AppCore internal constructor(
    private val scope: CoroutineScope,
    private val ports: AppPorts,
) {

    /**
     * Device attestation (capability `device-attestation`) — the bearer token EVERY backend call
     * carries. Composed here; only the app process can attest (`DCAppAttestService.isSupported` is
     * false in an app extension — measured), and the extension reads the token this writes.
     */
    val attestation: DeviceAttestation by lazy {
        DeviceAttestation(
            key = ports.attestKey,
            client = ports.attestClient,
            store = ports.attestStore,
            deviceId = ports.deviceId,
            now = ports.now,
        )
    }

    // Own-device completeness AND in-flight, both from one consistent ledger `aggregates()` read
    // (capability `sync-status`). Read-only; on any failure the last good counts are retained.
    val ledgerCounts: ReadingLedgerCountsSource by lazy {
        ReadingLedgerCountsSource {
            ports.ledger.aggregates().let { LedgerCounts(completed = it.completed, pending = it.pending) }
        }
    }

    // The own-device upload TOTAL N (capability `sync-status`): gallery enumeration minus downloaded
    // foreign photos, scoped by the membership's cutoff and the origin exclusions
    // (capability `photo-selection-policy`) — the SAME album lookup the upload cycle gets, because the
    // two enumerate independently and a rule applied to one and not the other would peg the joined
    // screen below 100% forever.
    val gallery: OwnDeviceGalleryStatusSource by lazy {
        OwnDeviceGalleryStatusSource(
            ports.photoLibrary,
            suppressedLocalIds = { ports.downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = ports.albumExcludedAssetIds,
        )
    }

    /** The real ledger-backed status source (ledger truth × permission × gallery total). */
    val syncStatusSource: SyncStatusSource by lazy {
        LedgerBackedSyncStatusSource(ledgerCounts, ports.photoAccess, gallery, scope)
    }

    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`).
    val downloadStatusSource: StoreDownloadStatusSource by lazy {
        StoreDownloadStatusSource(ports.downloadStore)
    }
    val downloadStatus: DownloadStatusSource get() = downloadStatusSource

    // Background byte transfers → durable staging. The queue, bounded window, and cancellation
    // lifecycle live in the tested feature; the transport is the shell's adapter thunk.
    val downloadJobs: QueuedPhotoDownloadJobs by lazy {
        QueuedPhotoDownloadJobs(scope, ports.downloadStagingRoot(), ports.newDownloadTransport)
    }

    // The download orchestrator: union → foreign selection → download → import → suppression.
    val downloadController: DownloadController by lazy {
        val controller = DownloadController(
            union = ports.union,
            store = ports.downloadStore,
            jobs = downloadJobs,
            importer = ports.importer,
            myDeviceId = ports.deviceId(),
            // Three-valued, no fallback (capability `photo-download`): no membership → `null` → no arm.
            downloadEnabled = { ports.configSource.config.value?.direction?.includesDownload },
        )
        // Deliver each staged resource back to the controller off the transport delegate thread —
        // an adapter outbound callback satisfied by a compose-built lambda (law: "Commands cross one
        // door"; becomes a flow command at step 8).
        downloadJobs.onStaged = { ref, key, path -> scope.launch { controller.onResourceStaged(ref, key, path) } }
        controller
    }

    // The silent-push receiver for the download arm (capability `photo-download`); its active-event
    // guard is the feature's rule. The app shell fans this out with the upload arm's receiver until
    // the fan-out re-homes (step 8).
    val downloadPushReceiver: DownloadPushReceiver by lazy {
        DownloadPushReceiver(
            activeEventId = { ports.configSource.config.value?.eventId },
            controller = downloadController,
        )
    }

    // Event album (capability `event-album`): the coordinator over the shared leave-surviving map.
    // The APP is the SOLE creator (on the permission grant); both processes only add.
    val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(ports.albumManager, ports.albumMapStore)
    }

    // The tier-neutral upload lifecycle (capability `upload-lifecycle`): which producer verb fires on
    // which membership transition. The root defaults nothing — an absent membership is `null`, and
    // the decision lives in the tested arm.
    val uploadArm: UploadArm by lazy {
        UploadArm(
            producer = ports.uploadProducer(),
            isGranted = { ports.photoAccess.permission.value == PermissionStatus.GRANTED },
            membershipIncludesUpload = { ports.configSource.config.value?.direction?.includesUpload },
            log = ports.log,
        )
    }

    // The leave use-case: stop the producer, clear the config (which flips the screen off the joined
    // layer), then notify the backend fire-and-forget. It destroys no dedup state.
    val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = ports.configStore,
            configSource = ports.configSource,
            stopUploads = { uploadArm.onLeave() },
            notifyLeave = ports.notifyLeave,
            scope = scope,
        )
    }

    // The join use-case (capability `join-event`): fetch details, enroll by writing the register-only
    // EMPTY device manifest, then provision through the same path as create/scan.
    val joinEvent: JoinEvent by lazy {
        JoinEvent(
            configSource = ports.configSource,
            deviceId = ports.deviceId,
            details = ports.directory,
            enroller = ManifestDeviceEnroller(ports.enrollment),
            provision = ports.provision,
        )
    }

    /** The create-event status the use-case drives and the container reads (same instance). */
    val creationStatus: MutableCreationStatusSource = MutableCreationStatusSource()

    // The create-event use-case: mint via the backend, then route the minted event into the SAME
    // join gate a scanned QR takes (capability `photo-selection-policy`).
    val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = ports.eventCreation,
            status = creationStatus,
            onMinted = ports.onEventMinted,
            scope = scope,
        )
    }
}

/**
 * The ONE app-graph composition (spec `module-architecture`, "One shared composition"): the app
 * shell calls this — and, at migration step 10, the world harness does too — so a wiring difference
 * between binaries is impossible rather than undetected. Manual DI (decision D6 of
 * `establish-target-architecture`): plain constructors, no framework.
 */
fun snapSyncApp(scope: CoroutineScope, ports: AppPorts): AppCore = AppCore(scope, ports)
