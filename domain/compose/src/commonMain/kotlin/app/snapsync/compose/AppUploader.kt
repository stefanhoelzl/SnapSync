package app.snapsync.compose

import app.snapsync.feature.upload.UploadCycle
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.ports.GalleryReader
import app.snapsync.services.gallery.GalleryDiscovery
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
     * The platform's live grant read — what the walk memo keys on, and what the denylisted-album lookup asks. Read
     * through the gallery port rather than the core's permission cell, which follows the platform rather than leading
     * it.
     */
    private val grant: GalleryReader get() = core.ports.gallery

    /** The attestation bearer every request carries, read per request from the core's attestation service. */
    private val token: suspend () -> String? = { core.attestation.token() }

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (`docs/architecture.md`, "One shared composition"):
     * the entry-gate translation, the device-manifest producer (on this tier the APP is its sole writer, and without
     * the PUT this tier's uploads would never appear in the event union) and the engine wiring are the same code the
     * ≥26.1 extension and the world harness run. Long-lived: each unit re-reads the membership. It binds the walk
     * memo, the app process's alone ([AppProcessOnly]).
     */
    @OptIn(AppProcessOnly::class)
    val cycle: UploadCycle by lazy {
        uploadCycle(
            core.process,
            UploadServices(
                exchange = core.exchange,
                eventKeys = app.eventKeys,
                process = UploaderProcess.App(core.appUploadAdmission),
                records = app.sharedDatabase,
                // The THREE-state membership read, never the core's StateFlow.
                config = app.config,
                mobileData = app.mobileData,
                // Resolved per probe/use, never held: an unresolvable Keychain id must skip the cycle cleanly.
                deviceIdentity = app.deviceIdentity,
                host = build.uploadHost,
                ledger = app.ledger,
                uploader = Uploader.Session(ports.uploadSession, core.uploadResuming),
                gallery = ports.gallery,
                // The walk memo, which answers an unchanged library, is the app uploader's alone; the extension
                // walks bare.
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
                recognition = app.downloadStore.recognition,
                // Denylisted-album membership, scoped by the cutoff — the SAME admit-on-doubt answer the own-device
                // status total gets, so the two consumers of the policy cannot diverge.
                albumExclusions = core::albumExclusionsWhenReadable,
                albumCoordinator = core.albumCoordinator,
                token = token,
                // The app's transports offer no retry — a failed transfer is recorded FAILED and the next top-up
                // creates it afresh — so no request is ever minted for one here: a retry's credential is the same read.
                freshToken = token,
                log = log,
            ),
        )
    }
}
