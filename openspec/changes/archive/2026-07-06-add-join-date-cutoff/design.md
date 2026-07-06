## Context

Joining an event provisions `EventConfig(eventId, name?)` and enables the upload producer, which
uploads the **whole library**: the shared `UploadCycle` filters discovered resources only by
echo-suppression and deletion, and the engine selects purely by ledger-key presence (it is
date-blind). Uploaded bytes land in the device-partitioned byte store, and the extension writes a
per-`(event, device)` **device manifest** listing the device's assets. That manifest is not
write-only in effect: the backend union `GET /events/:eventId/files` reads each device's manifest,
keeps the assets whose bytes are all present, and returns a download union that **other members'**
`DownloadController` imports. So today, joining an event shares a device's entire library with every
other member.

The manifest already carries a dormant date filter: `DeviceManifestProducer.produce(startDate)` and
`projectDeviceManifest` keep `creationDate >= startDate`, but every call site passes `null`
(whole-library). `device-manifest` specs this as "the date-filtered projection … the assets whose
capture date is at or after the event's start." The union trusts the manifest as pre-filtered and
applies no date logic of its own. `GET /events/:id` already returns `{ eventId, name, createdAt }`;
the client's `MetaDto` currently drops `createdAt`.

Constraints: `commonMain` is limited to the common stdlib (iOS targets present); implementations are
chosen by DI, never `expect`/`actual`; only `:domain:ui:components` may import Material 3, and no
Material 3 type may appear in an `App*` signature. `creationDate` is produced by a bare
`NSISO8601DateFormatter()` → UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`, second precision, no offset, no
fractional seconds; the manifest cutoff is compared **lexicographically**.

## Goals / Non-Goals

**Goals:**
- Let a joining device choose a capture-date cutoff so it uploads and shares only photos taken from
  that moment onward.
- Reuse the existing join confirmation gate for both create and QR-join, without bending its state
  contract.
- Keep the cutoff a device-local decision that never reaches the backend, with zero backend change.
- Shape the data model and filter placement so **editable cutoffs** and **multi-event membership with
  different cutoffs** drop in later without rework.

**Non-Goals:**
- Editing a cutoff after join (v1 is set-once, immutable).
- Joining more than one event at a time.
- Any host-set floor or event-wide minimum a joiner inherits or cannot undercut.
- Any backend/API change (`createdAt` is already returned).
- Sub-second precision or timezone-of-capture semantics beyond the UTC lexicographic compare.

## Decisions

### D1 — The cutoff is per-device, per-membership; there is no event-start concept
Each device chooses its own cutoff at join; it is stored on the membership (`EventConfig.minPhotoDate`
in v1, generalizing to a list of memberships later) and **never sent to the backend**. The event
supplies only a **default value** (its `createdAt`), not an inherited policy. The host has no floor: a
guest may narrow to "now" and their earlier photos simply never enter the union.
*Alternative rejected:* a per-event minimum carried on the marker that clamps joiners — reintroduces a
backend field and a policy the product does not want. The `device-manifest`/`bunny-list-endpoint`
prose "the event's start" is reworded to "the device's configured start for that event."

### D2 — One cutoff drives both byte upload and manifest listing
The same `minPhotoDate` gates (a) which bytes `UploadCycle` hands to the engine and (b) which assets
the manifest projection lists — hence what the union exposes to other members. Coupling is required:
the union's completeness rule already assumes the manifest equals the uploaded set. The cutoff
therefore governs cross-device **download visibility**, not merely local backup.

### D3 — Create `POST`s first, then auto-routes into the join gate
The create button `POST`s `/events` (event exists immediately), then routes the returned `eventId`
into the **same** `JoiningEvent` gate a scanned QR uses (as an auto-routed, non-auto-confirmed pending
join). Because the event exists, the gate keeps a real `eventId`, a real `GET` load, and a real
enroll — **no bent state, no parallel create-confirm family**. The creator picks a cutoff on the same
screen every joiner sees. This supersedes `event-creation-ui`'s "provisions directly like a scanned
QR."
- *Consequence:* the default cutoff **unifies to the fetched `createdAt` for everyone** (for the
  creator, `createdAt ≈ now`), removing a creator/joiner special-case.
- *Consequence:* a cancelled create (or app death) leaves a member-less event marker. Accepted as a
  harmless orphan — the union returns `200 []` for it and nobody holds its QR. No rollback path.
- *Alternative rejected:* deferring the `POST` until confirm (the interview's first idea) — forces the
  gate to carry a not-yet-minted event, bending `JoiningEvent`.

### D4 — Filter placement: preserve the device-global accumulator (Option B)
`UploadCycle` gains a byte filter `creationDate >= min(cutoffs across memberships)` (v1: the single
cutoff), applied at the engine hand-off and covering both the full and incremental discovery walks and
both upload tiers. The **device-global accumulator is kept**; the manifest remains its **per-event
projection**, now fed `startDate = that event's cutoff`.
- *Rationale:* the two future requirements need exactly this. **Editable cutoff** (lowering it) must
  resurface older assets — only possible cheaply if they are still retained in a device-global
  accumulator. **Multi-event** means N per-event projections off one accumulator, with bytes uploaded
  once per photo for the union of cutoffs (the `min`). A single upstream cutoff-scoped filter (Option
  A) would have to be torn out for either future.
- *Alternative rejected:* Option A (one upstream filter, cutoff-scoped accumulator) — simpler for a
  strictly single-event, immutable-cutoff v1, but precludes both stated futures.

### D5 — Time source: `kotlinx-datetime` + an injected `Clock`
Activate the already-declared-but-unused `kotlinx-datetime` in the relevant `commonMain` modules; get
"now" and convert manual local picks to the UTC `…Z` string via an **injected `Clock`** (DI, per the
`expect`/`actual` prohibition), so it is unit-testable in `commonTest`. The joiner default reuses the
fetched `createdAt` **verbatim** (already `…Z`), so only "now" and manual picks need formatting.

### D6 — Format invariant
The cutoff string MUST be UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`, second precision — byte-identical to the
enumerator's `NSISO8601DateFormatter()` output — because the manifest/upload compare is lexicographic.
A `commonTest` pins the exact shape; the shared formatting path is the single source of the string.

### D7 — UI: a new `App*` date/time component + a cutoff row on the join gate
`:domain:ui:components` gains an `App*` component wrapping M3 `DatePicker` + `TimePicker` (no M3 type
in its signature). The join screen's loaded phase renders a **date row** (prefilled default, an "Only
from now" shortcut, tap-to-open manual date+time picker); the picked value threads through the confirm
intent into `JoinEvent.join`. Bounds are fully open (a future cutoff is an accepted footgun).

### D8 — Dev/test deeplink cutoff key
`EventLinkPayload` gains an optional dev/test cutoff key alongside `autoJoin` (strict decoder updated).
On `autoJoin` the confirm auto-fires with the default cutoff = fetched `createdAt`, unless the key
supplies an explicit cutoff — so the headless loop can force and observe date filtering. Inert in
production (only injectable via a developer launch).

## Risks / Trade-offs

- **Format drift** between the cutoff string and `creationDate` → lexicographic compare silently
  wrong. → Single shared formatter path; `commonTest` pins the `…Z` shape; the joiner default reuses
  `createdAt` verbatim rather than reformatting.
- **Assets with no `creationDate`** (enumerator emits `""`) compare `"" >= cutoff` = false → excluded
  from upload/union. → Acceptable (rare); documented behavior — an undated asset is treated as before
  any cutoff.
- **Orphan event markers** from cancelled creates accumulate backend-side. → Accepted; they are inert
  (union `200 []`, no QR shared). A future member-less-event GC is compatible but out of scope.
- **`kotlinx-datetime` on the iOS compile** — a newly-activated dependency. → Already in the version
  catalog; verify with `compileIosMainKotlinMetadata`.
- **`min(cutoffs)` is trivially the single cutoff in v1** — risk of hard-coding a scalar. → Express the
  filter as a reduction over memberships from day one so multi-event needs no reshape.
- **Open bounds allow a future cutoff** → nothing uploads until a later photo exists. → Accepted,
  explicitly chosen; no clamp.
