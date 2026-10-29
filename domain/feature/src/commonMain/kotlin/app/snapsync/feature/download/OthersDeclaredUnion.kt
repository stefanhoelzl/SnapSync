package app.snapsync.feature.download

import app.snapsync.domain.event.EventId
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.manifest.DeviceManifestService

/**
 * The event's union as the download arm reads it, its declared total net of what this device had declared to the event
 * at the read (change `count-declared-photos`): what the OTHERS declared, which [DownloadController] stores with the
 * position and the joined screen counts as still to receive.
 *
 * Netted here, at the read, because the two numbers must describe one backend state. Subtracted later — at the screen's
 * refresh, as it was — a publish in between showed the member's own photos as awaited: switching sharing off withdraws
 * them from what the device declares at once, while the total the last read stored still holds them.
 *
 * [manifest] answers how many photos this device last declared to the event ([DeviceManifestService.publishedCount]):
 * `0` for none, `null` when it cannot be read — which, like a backend that answers no total, nets to none, and awaits
 * nothing. Clamped at 0: a publish crossing the read can make the subtraction briefly short.
 */
class OthersDeclaredUnion(
    private val union: EventUnionSource,
    private val manifest: DeviceManifestService,
) : EventUnionSource {
    override suspend fun union(eventId: EventId, cursor: Long?, trigger: UnionTrigger): Result<UnionPage> =
        union.union(eventId, cursor, trigger).map { page ->
            val others = page.declared?.let { total ->
                manifest.publishedCount(eventId)?.let { own -> (total - own).coerceAtLeast(0L) }
            }
            UnionPage(page.assets, page.cursor, others)
        }
}
