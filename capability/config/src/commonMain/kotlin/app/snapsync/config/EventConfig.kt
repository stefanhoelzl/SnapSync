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
 * download-only without a tap. [saveToAlbum] is likewise a **dev/test** key (default absent): an override
 * that, on an auto-confirmed join, forces whether the membership gathers its synced photos into an event
 * album (capability `event-album`) so a headless launch can exercise album placement without a tap.
 * Because `encodeDefaults` is off, a `false`/absent value is not serialized, so the canonical
 * [encodeConfigUrl] QR stays `eventId`-only; the strict decoder accepts
 * `autoJoin`/`minPhotoDate`/`direction`/`saveToAlbum` as known optional keys but still rejects any
 * *other* extra key (and a `direction` outside the known tokens).
 *
 * This class is the wire DTO: its property name is the exact JSON key of the deeplink payload.
 */
@Serializable
class EventLinkPayload(
    val eventId: String,
    val autoJoin: Boolean = false,
    val minPhotoDate: String? = null,
    val direction: String? = null,
    val saveToAlbum: Boolean? = null,
)

/** Field-wise equality (not a data class, to match the prior payload's explicit-equality style). */
internal fun EventLinkPayload.sameAs(other: EventLinkPayload): Boolean =
    eventId == other.eventId

/**
 * The **persisted, joined-event state** (distinct from the [EventLinkPayload] wire type): the joined
 * `eventId`, the human-readable event `name`, and this device's chosen capture-date [minPhotoDate]
 * cutoff for the event (capability `photo-date-cutoff`). The name is a **required, non-null** value: the
 * join gate only provisions from a loaded phase that carries a name (capability `join-event`), and the
 * backend enforces name-required on create (capability `event-creation`), so a nameless event cannot
 * exist. It defaults to `""` **only** so a legacy item persisted before the name was reliably set decodes
 * non-null (refreshed on the next foreground fetch), never a decode crash.
 * [minPhotoDate] is **nullable** (absent = whole-library scope): the per-device, per-membership cutoff,
 * a UTC `…Z` string. The extension reads the `eventId`, the `minPhotoDate` (the cutoff scopes its upload
 * cycle), **and** [saveToAlbum] (whether to add completed uploads to the event album) from the shared
 * Keychain item; the name is cosmetic, for the status-screen title.
 * [direction] is this device's chosen participation direction (capability `join-event`), **defaulting to
 * [Direction.Both]** so a config persisted before this field existed decodes to today's bidirectional
 * behavior. [saveToAlbum] is whether this membership gathers its synced photos into an event album
 * (capability `event-album`), **defaulting to `false`** so a config persisted before this field existed
 * decodes to today's no-album behavior. All fields flow whole-object through serialization.
 */
@Serializable
data class EventConfig(
    val eventId: String,
    val name: String = "",
    val minPhotoDate: String? = null,
    val direction: Direction = Direction.Both,
    val saveToAlbum: Boolean = false,
)
