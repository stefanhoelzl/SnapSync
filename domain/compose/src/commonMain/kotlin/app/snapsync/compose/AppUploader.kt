package app.snapsync.compose

import app.snapsync.feature.upload.UploadCycle
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.model.CycleResult
import app.snapsync.model.invocation
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery

/**
 * **The app's uploader** (capability `background-upload`): the app-driven tier on every iOS version — the core tail's
 * units ② and ③ over the shared upload cycle, which `uploadCore` assembles over the app's own [AppPorts.appUpload]
 * (a background `URLSession` on iOS). On iOS ≥26.1 under a full grant it runs **beside** the extension, both writing
 * the one App-Group ledger — every write a guarded single transaction, and an overlap a duplicate upload of the same
 * object, never a loss (decision record `changes/both-uploaders-active`).
 *
 * It reads [core] directly — its services, its ports and the build's constants — so no root supplies anything for it.
 * It holds **no trigger, no OS completion handler and no heartbeat**: which wake runs what is the core's tail runner's
 * (decision record `changes/own-work-per-wake`, D1 and D5). Both units pass through the shared cycle's entry gate,
 * which decides whether this process may create.
 */
internal class AppUploader(private val core: AppCore) {

    private val app get() = core.services
    private val ports get() = core.ports
    private val build get() = core.ports.process.build
    private val log get() = app.log

    /**
     * The platform's live grant read — what the walk memo keys on, and what the denylisted-album lookup asks. Read
     * through the gallery port rather than the core's permission cell, which follows the platform rather than leading
     * it.
     */
    private val grant = PhotoGrantRead { core.ports.gallery.access() }

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (`docs/architecture.md`, "One shared composition"):
     * the entry-gate translation, the device-manifest producer (on this tier the APP is its sole writer, and without
     * the PUT this tier's uploads would never appear in the event union) and the engine wiring are the same code the
     * ≥26.1 extension and the world harness run. Long-lived: each unit re-reads the membership.
     */
    private val cycle: UploadCycle by lazy {
        uploadCycle(
            core.process,
            UploadServices(
                appVersion = build.appVersion,
                eventKeys = app.eventKeys,
                process = UploaderProcess.App(
                    core.appUploadAdmission,
                    PhotoGrantRead { ports.photoAccess.permission.value },
                ),
                // The THREE-state membership read, never the core's StateFlow (capability `join-event`).
                config = app.config,
                mobileData = app.mobileData,
                // Resolved per probe/use, never held: an unresolvable Keychain id must skip the cycle cleanly.
                deviceIdentity = app.deviceIdentity,
                host = build.uploadHost,
                ledger = app.ledger,
                upload = ports.appUpload,
                gallery = ports.gallery,
                // The walk memo is the app uploader's alone (capability `photo-sharing`, "An unchanged library is
                // answered from the walk memo"); the extension walks bare.
                discovery = appUploadDiscovery(
                    walk = GalleryDiscovery(ports.gallery),
                    changeToken = ports.gallery,
                    grant = grant,
                    log = log,
                ),
                selectionScope = core::selectionScope,
                manifestStore = app.manifestStore,
                manifestPublisher = core.backend.manifest,
                // Echo-suppression: the download store IS the narrowed suppression read.
                suppression = app.downloadStore,
                // Denylisted-album membership, scoped by the cutoff — the SAME admit-on-doubt answer the own-device
                // status total gets, so the two consumers of the policy cannot diverge.
                albumManager = GalleryAlbums(ports.gallery),
                albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
                albumCoordinator = core.albumCoordinator,
                token = { core.attestation.token() },
                // A retry's request re-reads the store of record (the extension may have cleared a rejected token).
                freshToken = { core.attestation.freshToken() },
                log = log,
            ),
        )
    }

    /** The tail's ② — the top-up from the ledger; `stopRequested` is checked between two job creations. */
    suspend fun topUp(stopRequested: () -> Boolean): CycleResult =
        log.invocation(core.process.entryContext, "url-session.topUp", result = { "$it" }) {
            cycle.topUp(stopRequested)
        }

    /**
     * The tail's ③ — the walk and the manifest publish, abandoned on a stop (capability `sync-status`, "The discovery
     * walk is atomic under a stop"). Also a selection change's own work under a partial grant, where the walk is the
     * selection snapshot.
     */
    suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome =
        log.invocation(core.process.entryContext, "url-session.walkAndPublish", result = { "$it" }) {
            cycle.walkAndPublish(stopRequested)
        }

    /**
     * Cancel every in-flight transfer and delete its staged file — a **leave** only (a switch leaves first). A
     * cancelled transfer's terminal is recorded through the guarded write, which matches no row once the leave has
     * cleared the ledger.
     */
    suspend fun cancelTransfers() = core.events.uploadTransfer.cancelAll()
}
