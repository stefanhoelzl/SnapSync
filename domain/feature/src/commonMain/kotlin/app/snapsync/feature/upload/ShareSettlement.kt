package app.snapsync.feature.upload

import app.snapsync.domain.event.EventId
import app.snapsync.model.DeviceManifest
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.membership.MembershipProjection
import co.touchlab.kermit.Logger
import kotlinx.serialization.SerializationException

/**
 * The end of a wake for this device's share: once the membership's OWN range has ended and its last published manifest
 * is not yet marked final, publish it once more — the publish marks it final. The upload cycle marks it then, but under
 * a partial grant a wake itself publishes nothing, so this is what makes sure every device eventually says "settled".
 * A range that ends before the event settles early; one that ends after it settles late. Nothing while not joined, or
 * once the event's photos are deleted.
 */
class ShareSettlement(
    private val config: MembershipProjection,
    /** The last manifest this device published, as recorded: `"<eventId> <json>"`. */
    private val manifestRecord: DeviceManifestService,
    /** Publish this device's manifest once more — the uploader's discovery → publish. */
    private val publishFinal: suspend () -> Unit,
    private val log: Logger = Logger.withTag("ShareSettlement"),
) {
    suspend fun settle() {
        val current = config.config.value?.takeIf { !it.deleted } ?: return
        if (!config.shareHasEnded(current) || published(current.eventId)?.final == true) return
        log.i { "event ${current.eventId}: the share's range has ended — publishing it final" }
        publishFinal()
    }

    /** The last published manifest for [eventId], or `null` when none is recorded for it or it cannot be read. */
    private fun published(eventId: EventId) = manifestRecord.loadLastUploaded()
        ?.takeIf { it.eventId == eventId }
        ?.let { readable(it.manifest) }

    // A record that does not decode is no settled share: it is published once more, which records a readable one.
    private fun readable(manifest: String): DeviceManifest? = try {
        deviceManifestFromJson(manifest)
    } catch (_: SerializationException) {
        null
    }
}
