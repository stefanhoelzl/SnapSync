package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * The **event-link wire payload** carried by the invite QR: just the **event id**. Possession of
 * this high-entropy UUID is the upload capability — the edge endpoint authorizes by event id alone,
 * and the device holds no storage credential. The upload **host** and the event **name** are
 * deliberately NOT here: the host is fixed at compile time by the extension's
 * the baked `uploadBase`, and the name is fetched by id after joining (see [EventConfig]).
 *
 * [autoJoin] is a **dev/test** hint (default `false`): when `true`, the join gate auto-confirms
 * instead of waiting for a tap (capability `join-event`). [minPhotoDate] is likewise a **dev/test**
 * key (default absent): a capture-date cutoff (UTC `…Z` string, capability `photo-selection-policy`) that,
 * on an auto-confirmed join, forces a specific lower bound so a headless launch can observe date filtering.
 * [maxPhotoDate] is likewise a **dev/test** key (default absent): the capture-date **ceiling** (UTC `…Z`
 * string) that, on an auto-confirmed join, forces a specific upper bound — clamped to the event `endsAt` on
 * the far side in `JoinEvent`, exactly as [minPhotoDate] is clamped to the floor — so a headless launch can
 * exercise the upper end of the range. [direction] is likewise a **dev/test** key (default absent): a participation-direction override — one
 * of the [Direction.wire] tokens `both`/`upload`/`download` — that, on an auto-confirmed join, forces the
 * membership's direction (capability `join-event`) so a headless launch can exercise upload-only /
 * download-only without a tap. [saveToAlbum] is likewise a **dev/test** key (default absent): an override
 * that, on an auto-confirmed join, forces whether the membership gathers its synced photos into an event
 * album (capability `event-album`) so a headless launch can exercise album placement without a tap.
 * Because `encodeDefaults` is off, a `false`/absent value is not serialized, so the canonical
 * [encodeEventUrl] QR stays `eventId`-only; the strict decoder accepts
 * `autoJoin`/`minPhotoDate`/`maxPhotoDate`/`direction`/`saveToAlbum` as known optional keys but still
 * rejects any *other* extra key (and a `direction` outside the known tokens).
 *
 * This class is the wire DTO: its property name is the exact JSON key of the event-link payload.
 */
@Serializable
class EventLinkPayload(
    val eventId: String,
    val autoJoin: Boolean = false,
    val minPhotoDate: String? = null,
    val maxPhotoDate: String? = null,
    val direction: String? = null,
    val saveToAlbum: Boolean? = null,
)

/** Field-wise equality (not a data class, to match the prior payload's explicit-equality style). */
internal fun EventLinkPayload.sameAs(other: EventLinkPayload): Boolean =
    eventId == other.eventId

/**
 * The **persisted, joined-event state** (distinct from the [EventLinkPayload] wire type): the joined
 * `eventId`, the human-readable event `name`, and this device's chosen capture-date [minPhotoDate]
 * cutoff for the event (capability `photo-selection-policy`). The name is **required, with no default**:
 * the join gate only provisions from a loaded phase that carries a name (capability `join-event`), and the
 * backend enforces name-required on create (capability `event-creation`), so a nameless event cannot
 * exist.
 *
 * Carrying no default is what makes [name] a required *constructor* parameter, so every present and
 * future construction site must supply one under compiler enforcement. That — not decode strictness — is
 * why the former `""` default is gone; the decode side follows because the `@Serializable` plugin derives
 * the decode default from the constructor default, and the two cannot be separated.
 *
 * ⚠️ This requires the **key**, not a non-blank **value**: `{"name":""}` decodes perfectly well (pinned
 * by `EventConfigTest`, so the next reader is not misled by the declaration). The blank-name guard lives
 * at `HttpEventDirectory`, and it is the ONLY one — nothing downstream re-checks.
 * Decision record: `changes/archive/…-remove-nameless-config-fallback`.
 *
 * [minPhotoDate] is **required and non-null**, with **no default** (capability `photo-selection-policy`): the
 * per-device, per-membership capture-date cutoff, a UTC `…Z` string. A membership with no cutoff is not a
 * representable state — an absent cutoff once meant whole-library scope, which under event photo sharing
 * uploads a guest's entire camera roll to another person's event.
 *
 * It carries **no default on purpose**, unlike [direction]/[saveToAlbum]. A legacy item lacking the
 * key therefore fails to decode and reads as *no config* (the config store adapters), so the device returns to
 * the setup gate and the user re-joins. Do **not** "fix" that by defaulting it to `""`: the cutoff compare
 * is `creationDate >= minPhotoDate`, and every string is `>= ""`, so an empty cutoff silently restores
 * whole-library scope while presenting as a present, non-null value. (The mirror case is safe and is why
 * `""` looks tempting: an undated *asset* — `creationDate == ""` — is correctly excluded by any real
 * cutoff.)
 *
 * [startsAt] is the **event's** start date (capability `event-creation`) — set once by the host at
 * creation, immutable, and the same canonical `…Z` shape as [minPhotoDate]. It is both the **default**
 * and the **floor** for this membership's cutoff: the persisted [minPhotoDate] is always
 * `max(chosen, startsAt)` (the clamp lives in `JoinEvent`), so the invariant `minPhotoDate >= startsAt`
 * holds for every config. It is what the not-started status line compares against (`startsAt > now`,
 * capability `sync-status-screen`).
 *
 * Unlike [minPhotoDate], [startsAt] **defaults** — to [minPhotoDate] — so a config persisted before this
 * field existed decodes instead of failing. That asymmetry is deliberate and the reasoning is *not*
 * transferable: [minPhotoDate]'s no-default harshness buys protection against uploading a whole camera
 * roll, whereas [startsAt]'s would buy a status line. And the blast radius is severe — this class is the
 * **only** holder of the `eventId`, and the invite QR is derived from it, so a decode failure destroys
 * the member's event id *and* their QR with nothing in the app to surface either back; a host who is the
 * only member yet would be locked out of their own event permanently, its uploaded photos stranded.
 * [minPhotoDate] is the right default because it is the only value **guaranteed** consistent with the
 * floor invariant (satisfying it with equality), and because it lands the not-started state correctly by
 * construction: a legacy member joined an event that had already begun, so their cutoff was at or before
 * "now" when they picked it, so the derived [startsAt] is never in the future.
 *
 * (The `@Serializable` plugin honours a default that references an **earlier** constructor parameter —
 * verified by `EventConfigTest`. [startsAt] must therefore stay declared *after* [minPhotoDate].)
 *
 * [endsAt] is the **event's** end date (capability `event-creation`) — the host's declared, immutable event
 * window ceiling, same canonical `…Z` shape as [startsAt]. It is the ceiling the membership's upper bound is
 * clamped to, the default upper bound a joiner sees, and what the "Event ended" status line compares against
 * (`now > endsAt`, capability `sync-status-screen`). [maxPhotoDate] is this membership's chosen capture-date
 * **upper** bound, always clamped to `min(chosen, endsAt)` at join (the clamp lives in `JoinEvent`), so
 * `maxPhotoDate <= endsAt` holds for every config that has one.
 *
 * [maxPhotoDate] is **required and non-null, with no default** — like [minPhotoDate], and this **reverses**
 * the allowance it shipped with. A membership persisted before the ceiling existed used to decode with a
 * `null` ceiling meaning *unbounded*, pending the reconcile backfill. That allowance is gone: a config
 * lacking the ceiling now fails to decode and reads as *no config*, returning the device to the setup gate.
 *
 * The reversal is safe **only by sequencing**, and the sequence has already run: the ceiling and its
 * reconcile backfill shipped together in `add-event-date-range` (commit `fd609cd5`, on `main` and therefore
 * on TestFlight), so every device that has foregrounded since has already persisted a concrete ceiling —
 * the reconcile fills `endsAt` and `maxPhotoDate` from the fetched details on any config that lacked them.
 * A device that has *not* foregrounded since then loses its membership on update; that is the accepted cost
 * on this controlled, internal install base, and `SNAPSYNC_RESET_STATE` clears any holdout.
 *
 * [endsAt] stays nullable, deliberately. It is the *event's* fact rather than the membership's bound: it
 * feeds the "Event ended" line and gives the reconfigure surface something to clamp against, and neither
 * failure is worth a decode failure. Its backfill stays.
 *
 * [deletesAt] is when the backend deletes the event's shared data (capability `event-limits`) — a
 * canonical `…Z` instant **derived server-side** (`max(createdAt, startsAt) + lifetime`) and served on the
 * details response. The device stores it, never computes it: duplicating the retention constant and the
 * anchor rule in the client would let a join gate confidently promise a date the backend will not honour,
 * and the drift would be silent.
 *
 * It exists for exactly one job — the **second witness** of the self-leave (capability `leave-event`). A
 * membership is torn down without user action only when the backend reports the event definitively absent
 * **and** this stored deadline has passed. One witness is offline, so no backend misconfiguration can
 * manufacture both: a zone-wide fault that 404s every event would otherwise destroy every membership in
 * the install base at once, unrecoverably, since this class is the only record of the join.
 *
 * It **defaults to `null`** like [endsAt], and a `null` means **never reached** — the self-leave cannot
 * fire on a membership that has not yet learned its deadline. Reconcile backfills it (capability
 * `event-rejoin-reconciliation`). Both defaults fail toward keeping the membership.
 *
 * The extension reads the `eventId`, the `minPhotoDate` (the cutoff scopes its upload cycle), **and**
 * [saveToAlbum] (whether to add completed uploads to the event album) from the shared Keychain item; the
 * name is cosmetic, for the status-screen title.
 * [direction] is this device's chosen participation direction (capability `join-event`), **defaulting to
 * [Direction.Both]** so a config persisted before this field existed decodes to today's bidirectional
 * behavior. [saveToAlbum] is whether this membership gathers its synced photos into an event album
 * (capability `event-album`), **defaulting to `false`** so a config persisted before this field existed
 * decodes to today's no-album behavior. All fields flow whole-object through serialization.
 */
@Serializable
data class EventConfig(
    val eventId: String,
    val name: String,
    val minPhotoDate: CaptureCutoff,
    val startsAt: EventStart = EventStart(minPhotoDate.at),
    val endsAt: EventEnd? = null,
    val maxPhotoDate: CaptureCeiling,
    val deletesAt: DeletesAt? = null,
    val direction: Direction = Direction.Both,
    val saveToAlbum: Boolean = false,
)
