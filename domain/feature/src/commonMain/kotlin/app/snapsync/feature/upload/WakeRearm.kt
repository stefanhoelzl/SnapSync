package app.snapsync.feature.upload

import app.snapsync.model.CycleResult
import app.snapsync.model.GalleryAccess
import app.snapsync.scheduling.Pass
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.upload.ExtensionRegistration
import app.snapsync.services.wake.Heartbeat
import co.touchlab.kermit.Logger

/**
 * The re-arm a wake owes the next one, so photos upload without the app being opened (decision record
 * `changes/timely-background-receiving`, D1): made once the wake's own uploads have run — or were cut short, which
 * counts as work left — and before the wake answers the operating system.
 *
 * After every wake whose uploads ran for a membership that contributes — any outcome but `SKIPPED` — the
 * library-change wake is re-requested first: a standing "wake me when a photo is added", so a device that is caught
 * up still notices the next photo. Where one stands (Android) the heartbeat need not look for new photos itself;
 * where none can (iOS, which answers it `Unsupported`) the confirmed OS uploader is what stands in for it. Then
 * [heartbeatCadence] picks busy, idle or nothing, and the heartbeat is armed at that cadence — replacing the pending
 * one. A heartbeat stays pending — busy or idle — for as long as the device is joined, whatever the upload units
 * answered.
 */
class WakeRearm(
    private val heartbeat: Heartbeat,
    /** The staged downloads not yet imported: work left, which keeps the heartbeat busy until imported. */
    private val downloads: DownloadService,
    /** What the device is, for the cadence — read at each re-arm (see [CadenceFacts]). */
    private val facts: () -> CadenceFacts,
    private val log: Logger,
) {
    /** Re-arm after a wake whose own uploads came to [uploads]. */
    suspend fun after(uploads: Pass<CycleResult>) {
        val result = when (uploads) {
            is Pass.Completed -> uploads.result
            // Cut short: a stop says nothing about whether work remains, so the only safe reading is that it does.
            Pass.Cut -> CycleResult.PROCESSING
        }
        val contributes = result != CycleResult.SKIPPED
        val watched = contributes && heartbeat.watchLibrary()
        val importsRemain = downloads.importableAssets().isNotEmpty()
        if (importsRemain) log.i { "staged downloads remain to import — the heartbeat stays busy" }
        val cadence = heartbeatCadence(
            facts = facts(),
            leftWork = result.leftWork,
            importsRemain = importsRemain,
            contributes = contributes,
            libraryWatched = watched,
        ) ?: return
        heartbeat.arm(cadence)
    }
}

/**
 * What the heartbeat's re-arm reads after a tail (decision record `changes/timely-background-receiving`, D1, D3) — read
 * fresh each time. The OS uploader counts as confirmed only when it may be registered here ([osUploaderRegistrable]: a
 * full grant, an OS that carries it, the dev pin not off) **and** the OS's own answer says it is: whether registering
 * is allowed says nothing about whether it happened.
 */
fun cadenceFacts(
    config: ConfigService,
    permission: GalleryAccess,
    osUploaderRegistrable: Boolean,
    registration: ExtensionRegistration,
): CadenceFacts {
    val joined = config.config.value
    return CadenceFacts(
        joined = joined != null,
        ended = joined != null && config.hasEnded(joined),
        shares = joined != null && joined.direction.includesUpload,
        fullGrant = permission == GalleryAccess.GRANTED,
        osUploaderConfirmed = osUploaderRegistrable && registration.isRegistered() == true,
    )
}
