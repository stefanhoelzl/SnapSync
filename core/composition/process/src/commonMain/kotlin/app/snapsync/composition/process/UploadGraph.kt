package app.snapsync.composition.process

import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.application.sharing.CheckSettleNeed
import app.snapsync.application.sharing.PublishManifest
import app.snapsync.application.upload.DeriveContribution
import app.snapsync.application.upload.PlanTopUp
import app.snapsync.application.upload.RecordWalk
import app.snapsync.application.upload.WalkOwnPhotos
import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.Credential
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.FileNameCipher
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.sharing.ManifestPublication
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.time.WallClock
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.upload.JobRecord
import app.snapsync.data.upload.OwnPhotoResources
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.PassEntryRead
import app.snapsync.data.upload.RecentWalk
import app.snapsync.data.upload.RowTransitions
import app.snapsync.data.upload.UploadGateRead
import app.snapsync.data.upload.UploadRequests
import app.snapsync.data.upload.UploaderAdmission
import app.snapsync.data.upload.WalkRecord
import app.snapsync.domain.upload.UploadHost
import app.snapsync.entrypoints.AlbumPlacement

/**
 * **The upload's layers over one process** — the app's and the upload extension's alike: its rows, the request
 * builder, the sealing, the gate, the use cases and the steps the sequences share. Wiring only.
 *
 * What each process builds differently arrives as a parameter typed by the general interface — the identity, the
 * credential, the received photos it reads, its admission, its own photos, the album placement — so the restriction
 * is the caller's graph's: the extension's declares the restricted types it passes. The transfer tier is the
 * process's own too: each graph builds its `CreateJobs` over its tier from the pieces here.
 *
 * Nothing here opens a store or reads a port when built: every store opens on first use (`OwnedDatabase`), which a
 * background relaunch depends on (`BackgroundCompositionIntegrationTest`).
 */
class UploadGraph(
    records: SharedDatabase,
    membership: MembershipRepository,
    identity: DeviceIdentity,
    host: UploadHost,
    photos: OwnPhotos,
    admission: UploaderAdmission,
    /** What this process reads of the photos it received — echo suppression and recognition. */
    received: ReceivedPhotosRead,
    placeOwn: PlaceOwnPhotos,
    manifestRecord: ManifestRecord,
    clock: WallClock,
    /** The process's one in-memory copy of the device token. */
    credentials: CachedCredentialStore,
    /** This process's credential — the app's attests a missing token, the extension's cannot. */
    credential: Credential,
    exchange: BackendExchange,
    gateway: BackendGateway,
    keys: EventKeyStore,
    cipher: FileCipher,
    /** What seals a photo's name into an encrypted event's upload. */
    names: FileNameCipher,
) {
    val rows: OwnUploadRows = OwnUploadRows(records)
    val walkRecord: WalkRecord = WalkRecord(records)
    val transitions: RowTransitions = RowTransitions(records)
    val record: JobRecord = JobRecord(transitions)
    val recent: RecentWalk = RecentWalk()
    val resources: OwnPhotoResources = OwnPhotoResources(photos)
    val requests: UploadRequests = UploadRequests(exchange, credentials, credential, identity, names)
    val sealing: UploadSealing = UploadSealing(membership, keys, identity, cipher)
    val gate: UploadGateRead =
        UploadGateRead(records, PassEntryRead(rows, membership, identity, keys), host, admission, received)
    val contribute: DeriveContribution = DeriveContribution(photos)
    val walk: WalkOwnPhotos = WalkOwnPhotos(photos, rows, resources)
    val recordWalk: RecordWalk = RecordWalk(walkRecord, recent)
    val plan: PlanTopUp = PlanTopUp(rows)
    val album: AlbumPlacement = AlbumPlacement(placeOwn)

    /** The device manifest's publish: the step every look at the member's photos ends with. */
    val publish: PublishManifest =
        PublishManifest(rows, manifestRecord, ManifestPublication(gateway), membership, clock)

    /** Whether the end of a wake owes the share a settling look. */
    val settleNeed: CheckSettleNeed = CheckSettleNeed(manifestRecord, clock)
}
