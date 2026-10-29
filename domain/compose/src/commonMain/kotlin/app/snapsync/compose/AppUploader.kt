package app.snapsync.compose

import app.snapsync.feature.upload.UploadCycle
import app.snapsync.services.upload.Uploader

/**
 * **The app's uploader**: the app-driven tier on every iOS version — the core tail's
 * units ② and ③ over the shared upload cycle, which `uploadCore` assembles over the app's own [AppPorts.uploadSession]
 * (a background `URLSession` on iOS). On iOS ≥26.1 under a full grant it runs **beside** the extension, both writing
 * the one App-Group ledger — every write a guarded single transaction, and an overlap a duplicate upload of the same
 * object, never a loss (decision record `changes/both-uploaders-active`).
 *
 * It reads [core] directly — its services, its ports and the build's constants — so no root supplies anything for it.
 * It holds **no trigger, no OS completion handler and no heartbeat**: which wake runs what is the entry points' (the
 * sequences `domain/entrypoints` runs; decision record `changes/own-work-per-wake`, D1 and D5). Both units pass through
 * the shared cycle's entry gate, which decides whether this process may create.
 */
internal class AppUploader(private val core: AppCore) {

    private val app get() = core.services
    private val ports get() = core.ports
    private val build get() = core.ports.process.build
    private val log get() = app.log

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (`docs/architecture.md`, "One shared composition"):
     * the entry-gate translation, the device-manifest producer (on this tier the APP is its sole writer, and without
     * the PUT this tier's uploads would never appear in the event union) and the engine wiring are the same code the
     * ≥26.1 extension and the world harness run. Long-lived: each unit re-reads the membership. Its photos are the
     * core's own ([AppCore.ownPhotos]), whose walk memo is the app process's alone.
     */
    val cycle: UploadCycle by lazy {
        uploadCycle(
            core.process,
            UploadServices(
                exchange = core.exchange,
                keys = app.eventKeyStore,
                process = UploaderProcess.App(core.appUploadAdmission),
                records = app.sharedDatabase,
                membership = app.membership,
                // The THREE-state membership read, never the core's StateFlow.
                config = app.config,
                mobileData = app.mobileData,
                // Resolved per probe/use, never held: an unresolvable Keychain id must skip the cycle cleanly.
                deviceIdentity = app.deviceIdentity,
                host = build.uploadHost,
                ledger = app.ledger,
                uploader = Uploader.Session(ports.uploadSession, core.uploadResuming),
                gallery = ports.gallery,
                photos = core.ownPhotos,
                manifestStore = app.manifestStore,
                manifestPublisher = core.backend.manifest,
                // Echo-suppression: the download store IS the narrowed suppression read.
                suppression = app.downloadStore,
                recognition = app.downloadStore.recognition,
                albumCoordinator = core.albumCoordinator,
                // The token the app's credential keeps, the same copy every request it authenticates reads.
                credentials = app.credentials,
                log = log,
            ),
        )
    }
}
