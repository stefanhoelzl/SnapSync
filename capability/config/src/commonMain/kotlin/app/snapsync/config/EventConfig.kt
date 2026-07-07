package app.snapsync.config

import kotlinx.serialization.Serializable

/**
 * The **deeplink wire payload** carried by the `snapsync://` QR: just the **event id**. Possession of
 * this high-entropy UUID is the upload capability — the edge endpoint authorizes by event id alone,
 * and the device holds no storage credential. The upload **host** and the event **name** are
 * deliberately NOT here: the host is fixed at compile time by the extension's
 * `BackgroundUploadURLBase`, and the name is fetched by id after joining (see [EventConfig]).
 *
 * [autoJoin] is a **dev/test** hint (default `false`): when `true`, the join gate auto-confirms
 * instead of waiting for a tap (capability `join-event`). [minPhotoDate] is likewise a **dev/test**
 * key (default absent): a capture-date cutoff (UTC `…Z` string, capability `photo-date-cutoff`) that,
 * on an auto-confirmed join, forces a specific cutoff so a headless launch can observe date filtering.
 * [direction] is likewise a **dev/test** key (default absent): a participation-direction override — one
 * of the [Direction.wire] tokens `both`/`upload`/`download` — that, on an auto-confirmed join, forces the
 * membership's direction (capability `join-event`) so a headless launch can exercise upload-only /
 * download-only without a tap. Because `encodeDefaults` is off, a `false`/absent value is not serialized,
 * so the canonical [encodeConfigUrl] QR stays `eventId`-only; the strict decoder accepts
 * `autoJoin`/`minPhotoDate`/`direction` as known optional keys but still rejects any *other* extra key
 * (and a `direction` outside the known tokens).
 *
 * This class is the wire DTO: its property name is the exact JSON key of the deeplink payload.
 */
@Serializable
class EventLinkPayload(
    val eventId: String,
    val autoJoin: Boolean = false,
    val minPhotoDate: String? = null,
    val direction: String? = null,
)

/** Field-wise equality (not a data class, to match the prior payload's explicit-equality style). */
internal fun EventLinkPayload.sameAs(other: EventLinkPayload): Boolean =
    eventId == other.eventId

/**
 * The **persisted, joined-event state** (distinct from the [EventLinkPayload] wire type): the joined
 * `eventId`, the human-readable event `name`, and this device's chosen capture-date [minPhotoDate]
 * cutoff for the event (capability `photo-date-cutoff`). The name is **nullable** because it is fetched
 * by id *after* joining (`GET /events/:id`) and may not be available yet — joining never blocks on it.
 * [minPhotoDate] is **nullable** (absent = whole-library scope): the per-device, per-membership cutoff,
 * a UTC `…Z` string. The extension reads the `eventId` **and** the `minPhotoDate` from the shared
 * Keychain item (the cutoff scopes its upload cycle); the name is cosmetic, for the status-screen title.
 * [direction] is this device's chosen participation direction (capability `join-event`), **defaulting to
 * [Direction.Both]** so a config persisted before this field existed decodes to today's bidirectional
 * behavior. The extension does **not** read it (the upload arm is gated app-side by whether the producer
 * is enabled); it is persisted as part of the whole-object serialization regardless.
 */
@Serializable
data class EventConfig(
    val eventId: String,
    val name: String? = null,
    val minPhotoDate: String? = null,
    val direction: Direction = Direction.Both,
)
