## Context

The join surface (`JoiningEvent` loaded phase, `ReadyLayout`), the switch confirmation, and the
reconfigure surface all let a member choose a capture-date cutoff, but none of them tells the member how
many of their own photos that cutoff will actually share. The mission names the exact danger this leaves
open: a default inherited from the app's one-way-backup origins turns "back up everything of mine" into
"upload a guest's whole camera roll to a stranger's event". A live count makes the cutoff's consequence
visible at the moment it is chosen.

Three platform facts shaped the design and were verified against the code before proposing:

1. **Permission is resolved before the member works the cutoff.** For a first join with
   `NOT_DETERMINED`, the explain-access phase fires the request on its confirm and the iOS dialog lands
   modally over the loaded phase, so by the time the cutoff row is interactive the grant is the member's
   answer (GRANTED/LIMITED/DENIED). The request is not awaited, so the count must react to the permission
   `StateFlow` settling, not assume it. (`join-event` explain-access requirement; `StatusContainerHost`.)
2. **A count needs no per-asset resource read.** The ~110 ms/asset `assetResourcesForAsset` XPC builds
   upload keys, which a count does not need. `creationDate`, `mediaSubtypes`, `mediaType`, pixel
   dimensions and `hasAdjustments` are cheap in-memory `PHAsset` properties, and every origin exclusion
   runs off them. Only the denylisted-album lookup costs extra (O(albums)) and is cursor-independent, so
   it is cached once per surface. (`PhotoLibraryResourceEnumerator`; `photo-selection-policy` resolution
   floors / denylist.)
3. **Under LIMITED, only the held selection snapshot may be read.** An autonomous off-flow `PHAsset` read
   under `.limited` queues an app-killing alert storm. The count under LIMITED must re-filter the snapshot
   the observer already pushed — which is exactly the membership's own-photo scope. (`limited-photo-access`
   rule ①; `PhotoSelectionSnapshotSource`.)

A fourth fact turned a display feature into a correctness fix: **lowering the cutoff on reconfigure
back-shares older photos on the PhotoKit tier but silently does not on the URLSession tier**, because the
PhotoKit producer's `start()`→`stop()` clears the forward-only discovery cursor and the URLSession
producer's does not. Any count on the reconfigure surface is a lie on one tier until this is fixed, so the
fix is a prerequisite of showing the count there.

## Goals / Non-Goals

**Goals:**
- Show a live `XX photos from your gallery will be shared` count on the join loaded phase, recomputed as
  the cutoff / Share switch changes, with a zero-state gloss and a `counting…` state.
- Mean **photos shared to this event** (the in-scope set), computed locally with no network call and no
  per-asset resource read, identical to the set the upload cycle would admit.
- Show the same count on the switch and reconfigure surfaces.
- Fix the reconfigure cutoff-lowering backfill so it is tier-agnostic, making the reconfigure count
  truthful and closing a latent product inconsistency.
- Reuse the one selection policy (no fork); cover the logic in `commonTest`.

**Non-Goals:**
- Counting **bytes that will physically transfer** (in-scope minus already-stored). Rejected: it needs a
  network LIST at a latency-sensitive gate and, because stored bytes are device-scoped (event-independent),
  it under-reports what a returning member actually shares into a new event.
- A count on the joined **health** surface. `UiState`'s "no counts are carried" stance holds for the health
  surface; this change reverses it only for the join **decision** surface.
- Any new backend endpoint or API change. The count is local-only.
- Distinguishing Live-Photo primary vs paired-video (asset-granularity headline count is the right grain).

## Decisions

### D1 — The count is a parameterised query injected into the presentation host, not a passive read-model
The candidate cutoff is uncommitted local UI state, so the count cannot be a passive feature `StateFlow`.
It is a suspend query `(candidateCutoff, direction) → Int?` injected into `StatusContainerHost` exactly as
`loadJoinDetails` is (built only in `compose/`), called debounced on cutoff/Share change, its result
reduced into a new field on `JoinPhase.Ready` (and the switch/reconfigure state). Reads still do not cross
`flow/`. *Alternative rejected:* a feature-owned StateFlow keyed by committed config — cannot express a
candidate cutoff without committing it.

### D2 — Meaning: "shared to this event" (in-scope), local, no network
Chosen over "bytes that will transfer". For a first-time joiner the two are identical; they diverge only
for returning members and reconfigure, where "shared to the event" is the number the member actually cares
about (their footprint in the event) and needs no network round-trip or the device-scoped-bytes caveat.

### D3 — A count-only enumerator path on the ext-safe adapter
Add a count/set path to the gallery enumerator that issues the cutoff-bounded fetch and reads only cheap
`PHAsset` properties, skipping `assetResourcesForAsset`. It applies the same origin exclusions and the
cached denylist set, then counts distinct assets, off the main thread. The existing
`OwnDeviceGalleryStatusSource.compute` already counts on `assetId` + `creationDate` only; the new path
feeds it (or a shared helper) a cheaper fetch instead of the resource-materialising `enumerate`.

### D4 — Permission branching lives in the count source
GRANTED → cheap cutoff-bounded fetch. LIMITED → re-filter the held selection snapshot in memory (no fresh
read). DENIED / unresolved NOT_DETERMINED → return `null` (unavailable) so the surface omits the row. The
source subscribes to the permission `StateFlow` so a late-resolving grant makes the count appear.

### D5 — The reconfigure backfill fix is a domain side-effect, tier-agnostic
`ReconfigureEvent`, when the new `minPhotoDate` is earlier than the current one, invalidates the discovery
cursor through a port seam, so the next cycle full-re-enumerates at the new cutoff on **both** tiers.
Ledger `COMPLETED` rows keep already-shared photos from re-uploading. *Alternative rejected:* making each
producer's `start()` clear the cursor — leaves correctness dependent on per-producer behaviour, which is
how the tiers diverged in the first place. Putting it in the shared domain path fixes both at once and is
testable in `commonTest`.

### D6 — Same policy, one universe (no fork)
The count calls the same admitted-set logic as the cycle and `N`, over a `Contribution` built from the
candidate cutoff and direction. `photo-selection-policy` gains a requirement pinning this. Cheap-property
evaluation is an optimisation of the same policy, exactly as the cycle already permits a narrowed platform
fetch that does not change the admitted set.

## Risks / Trade-offs

- **A far-back Custom cutoff scans much of the library** → the count runs off the main thread and shows
  `counting…`; the fetch reads only cheap properties (µs each), so even thousands of assets settle fast and
  the scene watchdog is never on the compute path.
- **Recompute churn as the member scrubs a Custom date** → debounce the query; the denylist is cached, so a
  recompute is one fetch + in-memory filter.
- **LIMITED count only reflects the current selection** → this is correct, not a limitation: under LIMITED
  the selection *is* the upload scope. Copy stays "from your gallery", which reads correctly for the picked
  set.
- **The backfill fix forces a full re-enumeration on cutoff-lowering** → that is the intended (and already
  PhotoKit-tier) behaviour; it is bounded by the new cutoff and gated on a deliberate reconfigure, and
  `COMPLETED` rows prevent re-upload.
- **Reversing `UiState`'s "no counts" stance** → scoped to the join decision surface only; the health
  surface is untouched, and the decision-record rationale is captured in the modified `join-event` behaviour.

## Migration Plan

No data migration. `EventConfig` and the backend are unchanged. The backfill fix changes runtime behaviour
on the URLSession tier only (it starts back-sharing on cutoff-lowering, which the spec already intends);
there is nothing to roll back beyond reverting the change. Ships in one PR through `/ship`.

## Open Questions

- Exact debounce interval and the `counting…` show-threshold — settle on-device against real SE2 timings.
- Confirm a clean discovery-cursor-clear seam is reachable from `feature/membership` (a port method on the
  discovery store, exposed to `ReconfigureEvent` via `compose/`), and that `ios-url-session-upload`'s
  cursor requirements do not need their own delta once invalidation is externally triggered (account for it
  at archive time under the delta-completeness gate).
