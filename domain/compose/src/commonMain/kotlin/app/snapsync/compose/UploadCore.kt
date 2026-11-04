package app.snapsync.compose

import app.snapsync.application.upload.CreateJobs
import app.snapsync.application.upload.DeriveContribution
import app.snapsync.application.upload.PlanTopUp
import app.snapsync.application.upload.RecordWalk
import app.snapsync.application.upload.WalkOwnPhotos
import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.Credential
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.upload.JobRecord
import app.snapsync.data.upload.OwnPhotoResources
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.PassEntryRead
import app.snapsync.data.upload.RecentWalk
import app.snapsync.data.upload.RowTransitions
import app.snapsync.data.upload.UploadGateRead
import app.snapsync.data.upload.UploadRequests
import app.snapsync.data.upload.UploadTier
import app.snapsync.data.upload.UploaderAdmission
import app.snapsync.data.upload.WalkRecord
import app.snapsync.domain.upload.UploadHost
import app.snapsync.entrypoints.AlbumPlacement
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.membership.DeviceManifestProducer
import app.snapsync.feature.membership.ManifestPublish
import app.snapsync.ports.GalleryReader
import app.snapsync.services.backend.ManifestPublisher
import app.snapsync.services.identity.requireDeviceId
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.membership.MembershipProjection

/**
 * **What one process's uploads are built over** — the app's and the upload extension's alike: its stores, its
 * identity, its own photos and grant, its admission, the backend it reaches and the manifest it publishes. Each
 * process states its own [admission]: the app from the grant's read plan, the extension from its own grant read.
 */
internal class UploadServices(
    val records: SharedDatabase,
    val membership: MembershipRepository,
    val config: MembershipProjection,
    val mobileData: MobileDataStore,
    val deviceIdentity: DeviceIdentity,
    val host: String,
    val gallery: GalleryReader,
    val photos: OwnPhotos,
    val admission: UploaderAdmission,
    val manifestStore: DeviceManifestService,
    val manifestPublisher: ManifestPublisher,
    /** What this process reads of the photos it received — echo suppression and recognition: required, no default. */
    val received: ReceivedPhotosRead,
    val albumCoordinator: AlbumCoordinator,
    val credentials: CachedCredentialStore,
    /** This process's credential — the app's attests a missing token, the extension's cannot. */
    val credential: Credential,
    val exchange: BackendExchange,
    val gateway: BackendGateway,
    val keys: EventKeyStore,
)

/**
 * **The upload's layers over one process** — its rows, the request builder, the gate, the use cases and the steps the
 * sequences share — each built on first use, never at composition: a background relaunch must not open the shared
 * database early. The transfer tier is the process's own ([create] takes it): the app's session, or the OS's queue.
 */
internal class UploadLayers(process: ProcessServices, private val services: UploadServices) {
    val rows: OwnUploadRows by lazy { OwnUploadRows(services.records) }
    val walkRecord: WalkRecord by lazy { WalkRecord(services.records) }
    val transitions: RowTransitions by lazy { RowTransitions(services.records) }
    val record: JobRecord by lazy { JobRecord(transitions) }
    val recent: RecentWalk = RecentWalk()
    val resources: OwnPhotoResources by lazy { OwnPhotoResources(services.photos) }

    val requests: UploadRequests by lazy {
        UploadRequests(services.exchange, services.credentials, services.credential, services.deviceIdentity)
    }

    val sealing: UploadSealing by lazy {
        UploadSealing(
            services.membership,
            services.keys,
            services.deviceIdentity,
            FileCipher(process.crypto, process.files),
        )
    }

    val gate: UploadGateRead by lazy {
        UploadGateRead(
            services.records,
            PassEntryRead(rows, services.membership, services.deviceIdentity, services.keys),
            UploadHost.of(services.host),
            services.admission,
            services.received,
        )
    }

    val contribute: DeriveContribution by lazy { DeriveContribution(services.photos) }
    val walk: WalkOwnPhotos by lazy { WalkOwnPhotos(services.photos, rows, resources) }
    val recordWalk: RecordWalk by lazy { RecordWalk(walkRecord, recent) }
    val plan: PlanTopUp by lazy { PlanTopUp(rows) }
    val album: AlbumPlacement by lazy { AlbumPlacement(services.albumCoordinator) }

    /**
     * The device manifest's publish — phase 4.4c's step. Its producer resolves the device id on first in-pass use —
     * after the gate's probe has succeeded — never at composition, where a locked device would throw out of assembly.
     */
    val publish: ManifestPublish by lazy {
        ManifestPublish(
            rows,
            lazy {
                DeviceManifestProducer(
                    services.manifestStore,
                    services.manifestPublisher,
                    services.deviceIdentity.requireDeviceId(),
                )
            },
            services.config,
        )
    }

    /** Job creation through this process's [tier]. */
    fun create(tier: UploadTier): CreateJobs = CreateJobs(rows, tier, resources, walkRecord, recent)
}
