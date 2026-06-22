package app.snapsync.config

import kotlinx.serialization.Serializable

/**
 * The runtime config carried by the `snapsync://` deeplink and persisted in the Keychain: just the
 * **event id**. Possession of this high-entropy UUID is the upload capability — the edge endpoint
 * authorizes by event id alone, and the device holds no storage credential (the v1 S3 keys are
 * gone). The upload **host** is deliberately NOT here: it is fixed at compile time by the
 * extension's `BackgroundUploadURLBase`. The consuming iOS composition root combines this `eventId`
 * with the baked host and the App-Group device id into the edge upload URL.
 *
 * This class is also the wire DTO: its property name is the exact JSON key of the deeplink payload.
 */
@Serializable
class EventConfigPayload(
    val eventId: String,
)

/** Field-wise equality (not a data class, to match the prior payload's explicit-equality style). */
internal fun EventConfigPayload.sameAs(other: EventConfigPayload): Boolean =
    eventId == other.eventId
