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
 * is not yet marked final, it is due to be published once more — the publish marks it final. The end of the wake runs
 * that publish ([due] says whether it is owed). The upload cycle marks it then, but under
 * a partial grant a wake itself publishes nothing, so this is what makes sure every device eventually says "settled".
 * A range that ends before the event settles early; one that ends after it settles late. Nothing while not joined, or
 * once the event's photos are deleted.
 */
class ShareSettlement(
    private val config: MembershipProjection,
    /** The last manifest this device published, as recorded: `"<eventId> <json>"`. */
    private val manifestRecord: DeviceManifestService,
    private val log: Logger = Logger.withTag("ShareSettlement"),
) {
    /** Whether this device's manifest is owed one more publish, which marks it final. */
    fun due(): Boolean {
        val current = config.config.value?.takeIf { !it.deleted } ?: return false
        if (!config.shareHasEnded(current) || published(current.eventId)?.final == true) return false
        log.i { "event ${current.eventId}: the share's range has ended — publishing it final" }
        return true
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
