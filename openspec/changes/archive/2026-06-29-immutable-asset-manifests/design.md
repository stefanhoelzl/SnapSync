## Context

Today every qualifying photo is fanned out to **all** its `PHAssetResource`s — originals *and* edit
artifacts (`fullSizePhoto`, `adjustmentData`, Live Photo videos) — and uploaded under iOS-shaped flat
keys `<eventId>/<assetId>-ios.<kind>.<ext>`. `docs/design.md` resolved this as "full resource
fidelity." A download/restore client can list the objects but cannot tell when an asset is *whole*:
nothing declares how many resources an asset should have, and edits append resources over time so the
set never settles. The model is also iOS-specific.

The fix is to make each asset an **immutable, original-only, generically-typed set of resources
described by a per-asset manifest**, and to compute completeness **when the list endpoint is read**
(it can read the manifest) rather than by upload ordering — which iOS makes impossible, since the OS
owns background-job scheduling and the manifest rides a separate `URLSession`.

This is **Change 1 of two**. It delivers the producer + storage + read-time-completeness contract while
leaving the ledger-backed status stack in place. Change 2 (documented under Non-Goals / Follow-on)
decouples the app's status projection to read the same completeness listing.

## Goals / Non-Goals

**Goals:**
- Immutable, original-only resources keyed by generic roles (`primary`/`motion`); edits never uploaded.
- One manifest per asset declaring the authoritative resource set.
- `GET /event/<id>/files` returns only **complete** assets, computed by reading manifests against stored
  objects, cacheable because complete assets are permanent.
- Rejoin seeds from that same endpoint; no separate raw-object-list endpoint.

**Non-Goals (this change):**
- The download/restore client itself (the manifest format is its contract; the client is built separately).
- Decoupling the app's status projection from the ledger — **Change 2**:
  - app derives status from the completeness LIST + on-disk manifest files + PhotoKit total;
  - re-LIST on app open and on each manifest `URLSession` completion (liveness);
  - delete `observed-completion-overlay`, rewrite `sync-status`, shrink `sync-ledger` (drop the
    watcher/aggregates half; reader/writer go private to the extension), keep `gallery-status`.
- Migration of existing objects — **none**; the layout change is breaking (see Migration Plan).

## Decisions

### D1 — Completeness at read time, not by upload order
The OS schedules `PHAssetResource` uploads on its own cadence and the manifest rides a separate
`URLSession`, so the producer cannot guarantee the manifest lands last. Instead the **list endpoint**
reads each manifest and returns an asset only when every named resource is present. *Alternative
rejected:* sentinel-last manifest — unachievable on iOS and would force the extension to gate the
manifest on per-asset resource completion.

### D2 — Manifest is a standalone side channel, not an engine/ledger resource
The manifest is in-memory JSON; the OS `PHAssetResourceUploadJob` API accepts only a `PHAssetResource`,
so the manifest *must* use a vanilla background `URLSession` regardless. Given that, modeling it as an
engine `Resource` buys nothing and costs: it would force `createJob` to branch on payload type, force a
ledger `COMPLETED` write whose only completion signal arrives in the **app** (via
`handleEventsForBackgroundURLSession`) — violating the single-`LedgerWriter` invariant. So the manifest
stands alone: the App Group file is its dedup/retry marker, completion is **observed via storage**, and
the engine stays photo-only. *Alternative rejected:* "synthetic bytes-backed Resource through the
engine" (an earlier idea) — superseded once completeness moved to read time.

### D3 — Generic roles `primary` / `motion`; original-only
`photo`/`video`/`audio` → `primary`; `pairedVideo` → `motion`. All edit artifacts and the RAW
`alternatePhoto` are dropped, so the set is fixed at capture. `contentType` carries the media kind.
*Alternatives rejected:* `image`/`video`/`motionVideo` type names (less general than a role), and
keeping the RAW alternate (extra large uploads for marginal v1 value).

### D4 — Single endpoint, reused for rejoin
`GET /event/<id>/files` changes shape (objects → complete assets) and is reused by reconciliation.
Rejoin seeds `COMPLETED` for the resources of each **complete** asset and lets partially-stored assets
re-upload idempotently. *Alternative rejected:* a second raw-object-list endpoint for exact partial
seeding — unnecessary, since re-uploading a partial asset's few present resources is harmless.

### D5 — Manifest contents kept minimal
`version`, `assetId`, `creationDate`, and `resources[]{role, contentType, filename, originalFilename}`.
No subtypes, GPS, favorite/hidden, or dimensions: everything needed to *reconstruct* an asset
(including Live Photo re-pairing via the files' embedded content identifiers) is intrinsic to the
original bytes; the rest is display convenience deferred behind `version`.

## Risks / Trade-offs

- **Extension may not be allowed to spawn a background `URLSession` on the iOS 27 beta** → the whole
  manifest mechanism is unverified there. Mitigation: device-only verification (the project's norm); the
  fan-out/role/key changes are independent and land regardless.
- **Manifest completion delivered to the app, not the extension** → in Change 1 nothing depends on
  recording it (it's not ledgered); the app only needs `handleEventsForBackgroundURLSession` wired to
  satisfy the OS. The liveness use of that signal is Change 2.
- **List endpoint reads N manifests** → cost on large/shared events. Mitigation: immutability makes a
  "complete" verdict permanent, so cache it and only re-read incomplete/new assets.
- **A permanently-unuploadable resource blocks its asset's completeness forever** → correct (the asset
  genuinely can't be fully backed up); it simply never appears in the listing.
- **Phase-1 status vs server completeness can momentarily disagree** → the app's ledger-based completed
  count (resources only) can read "complete" a beat before the manifest lands. Accepted for Change 1;
  Change 2 aligns them by reading the server listing.
- **Breaking layout, no migration** → old `…-ios.<kind>` objects are orphaned and old manifests don't
  exist. Acceptable: TestFlight/dev only, the dev loop uses fresh event ids, and rejoin re-uploads.

## Migration Plan

No data migration. The key scheme changes (`…-ios.<kind>.<ext>` → `…-<role>.<ext>` plus
`<assetId>.manifest.json`), so old objects are orphaned; optionally wipe the storage zone. A re-joined
old event finds no matching keys, seeds nothing, and re-uploads under the new layout. Rollback is
reverting the change; any objects written under the new layout become orphans under the old code.

## Open Questions

- None blocking. The iOS-27-beta background-`URLSession`-from-extension feasibility is the one item to
  confirm on a real device during implementation; if it proves blocked, fall back to the main app
  uploading manifests (weaker reliability) without changing the storage/endpoint contract.
