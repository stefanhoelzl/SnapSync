package app.snapsync.config

import kotlinx.serialization.Serializable

/**
 * The **deeplink wire payload** carried by the `snapsync://` QR: just the **event id**. Possession of
 * this high-entropy UUID is the upload capability — the edge endpoint authorizes by event id alone,
 * and the device holds no storage credential. The upload **host** and the event **name** are
 * deliberately NOT here: the host is fixed at compile time by the extension's
 * `BackgroundUploadURLBase`, and the name is fetched by id after joining (see [EventConfig]).
 *
 * This class is the wire DTO: its property name is the exact JSON key of the deeplink payload.
 */
@Serializable
class EventLinkPayload(
    val eventId: String,
)

/** Field-wise equality (not a data class, to match the prior payload's explicit-equality style). */
internal fun EventLinkPayload.sameAs(other: EventLinkPayload): Boolean =
    eventId == other.eventId

/**
 * The **persisted, joined-event state** (distinct from the [EventLinkPayload] wire type): the joined
 * `eventId` plus the human-readable event `name`. The name is **nullable** because it is fetched by
 * id *after* joining (`GET /events/:id`, or received directly from `POST /events` on create) and may
 * not be available yet — joining never blocks on it. The extension reads only the `eventId` from the
 * shared Keychain item; the name is cosmetic, for the status screen title.
 */
@Serializable
data class EventConfig(
    val eventId: String,
    val name: String? = null,
)
