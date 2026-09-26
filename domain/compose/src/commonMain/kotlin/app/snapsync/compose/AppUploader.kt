package app.snapsync.compose

import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.model.CycleResult
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.invocation
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery

/**
 * What the app's uploader needs that the core it serves does not hold: the root's own reads and constants.
 *
 * The three-state [config] read, never the core's `ConfigSource` — that port *"cannot express unreadable"*, and this
 * tier once read it anyway: a failed read arrived as `null`, and a device that never left was treated as one that had
 * (capability `join-event`). [grant] is the platform's live grant read — what the walk memo keys on. [host] and
 * [appVersion] are constants of the running build.
 */
class AppUploaderPorts(
    val config: ConfigReader,
    val grant: PhotoGrantRead,
    val host: String,
    val appVersion: String,
)

/**
 * **The app's uploader** (capability `background-upload`): the app-driven tier on every iOS version — the core tail's
 * units ② and ③ over the shared upload cycle, which `uploadCore` assembles over the app's own [AppPorts.appUpload]
 * (a background `URLSession` on iOS). On iOS ≥26.1 under a full grant it runs **beside** the extension, both writing
 * the one App-Group ledger — every write a guarded single transaction, and an overlap a duplicate upload of the same
 * object, never a loss (decision record `changes/both-uploaders-active`).
 *
 * It was `:app:ios`'s `UrlSessionUploadController` until phase 11f: the root forwarded the core's reads into it and it
 * wired the cycle. Composed here, it reads [core] directly, and the root supplies only what [AppUploaderPorts] names.
 * It holds no trigger, no OS completion handler and no heartbeat — those are the core's.
 */
fun appUploader(core: AppCore, ports: AppUploaderPorts): AppUploadMechanism = ComposedAppUploader(core, ports)

private class ComposedAppUploader(
    private val core: AppCore,
    private val uploader: AppUploaderPorts,
) : AppUploadMechanism {

    private val app get() = core.ports
    private val log get() = app.log

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (`docs/architecture.md`, "One shared composition"):
     * the entry-gate translation, the device-manifest producer (on this tier the APP is its sole writer, and without
     * the PUT this tier's uploads would never appear in the event union) and the engine wiring are the same code the
     * ≥26.1 extension and the world harness run. Long-lived: each unit re-reads the membership.
     */
    private val cycle: UploadCycle by lazy {
        uploadCore(
            core.scope,
            core.process,
            UploadPorts(
                appVersion = uploader.appVersion,
                process = UploaderProcess.App(
                    core.appUploadAdmission,
                    PhotoGrantRead { app.photoAccess.permission.value },
                ),
                config = uploader.config,
                // Resolved per probe/use, never held: an unresolvable Keychain id must skip the cycle cleanly.
                deviceIdentity = app.deviceIdentity,
                host = uploader.host,
                ledger = app.uploadRecord.ledger,
                upload = app.appUpload,
                gallery = app.gallery,
                // The walk memo is the app uploader's alone (capability `photo-sharing`, "An unchanged library is
                // answered from the walk memo"); the extension walks bare.
                discovery = appUploadDiscovery(
                    walk = GalleryDiscovery(app.gallery),
                    changeToken = app.gallery,
                    grant = uploader.grant,
                    log = log,
                ),
                selectionScope = core::selectionScope,
                manifestStore = app.manifestStore,
                manifestPublisher = core.backend.manifest,
                // Echo-suppression: the download store IS the narrowed suppression read.
                suppression = app.downloadStore,
                // Denylisted-album membership, scoped by the cutoff — the SAME admit-on-doubt answer the own-device
                // status total gets, so the two consumers of the policy cannot diverge.
                albumManager = GalleryAlbums(app.gallery),
                albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
                albumCoordinator = core.albumCoordinator,
                token = { core.attestation.token() },
                // A retry's request re-reads the store of record (the extension may have cleared a rejected token).
                freshToken = { core.attestation.freshToken() },
                log = log,
            ),
        )
    }

    override suspend fun topUp(stopRequested: () -> Boolean): CycleResult =
        log.invocation(core.process.entryContext, "url-session.topUp", result = { "$it" }) {
            cycle.topUp(stopRequested)
        }

    override suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome =
        log.invocation(core.process.entryContext, "url-session.walkAndPublish", result = { "$it" }) {
            cycle.walkAndPublish(stopRequested)
        }

    /**
     * Cancel every in-flight transfer and delete its staged file — a **leave** only (a switch leaves first). A
     * cancelled transfer's terminal is recorded through the guarded write, which matches no row once the leave has
     * cleared the ledger.
     */
    override suspend fun cancelTransfers() = core.events.uploadTransfer.cancelAll()
}
