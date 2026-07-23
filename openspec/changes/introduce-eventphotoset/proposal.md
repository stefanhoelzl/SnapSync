## Why

An on-device run of the `decouple-event-window-from-lifetime` smoke test (task 11.1) found a real bug:
a member joining an event whose capture window has closed uploads their in-window photos correctly, but
the status screen pegs at **"Synchronization pending…"** forever, and two photos taken *after* the window
appear in the device manifest despite their bytes never uploading.

The cause is **not** a single missing line — it is an **architectural drift class**. `photo-selection-policy`
is "one policy, applied at one place," but the code (and the spec) re-state that policy at **four**
consumers — byte upload, device manifest, status total `N`, and the join-time shareable-count preview —
each assembling the rules by hand. When `2026-07-22-add-event-date-range` added the capture-date **ceiling**
(`until`), it reached the byte filter and the preview but **not** the manifest projection or `N`. Both were
left floor-only. The type system was happy: `Contribution.Since -> contribution.cutoff` (dropping `until`)
compiles as readily as the correct destructure. Every existing test passed because every fixture used
`until = null`. It took a closed-window event with a post-ceiling photo — the exact shape task 11.1
constructs — to surface it.

This change fixes the *class*, not the instance: it makes the admitted set a single thing every consumer
receives, rather than a policy every consumer re-applies.

**This is delivered as one change** covering the full architecture below. The design records the internal
ordering the tasks follow, and one hard **external** gate remains: the ceiling-required decode (D7) cannot
land on a device until `decouple-event-window-from-lifetime` has shipped and backfilled it — so this change
merges *after* decouple, and its migration section pins that.

## What Changes

- **Introduce `EventPhotoSet`** — the per-event *admitted set* as a first-class abstraction. Consumers
  ask it for the admitted assets (or their count, or their resources); they never see a disallowed asset,
  so they cannot forget to filter. Interface: `count()` · `assets()` · `Asset.resources()` (a cost ladder;
  resources fetched only for admitted assets).
- **One admission policy, applied once, at query.** `SelectionPolicy` (sealed `None | Admitting(rules)`)
  is applied inside `EventPhotoSet` over an injected `CandidateSource`. The four consumers stop
  re-implementing the filter; `Contribution` folds into `SelectionPolicy` and is deleted.
- **Platform owns native narrowing.** The `CandidateSource` (iOS) pattern-matches the policy's sealed
  domain rules and translates the ones it can into `PHFetchOptions`; the rest fall through to the
  authoritative in-memory filter. A second platform is a new translator over unchanged domain rules.
- **Neutral fact vocabulary.** The iOS adapter maps `PHAsset` → neutral `AssetFacts`
  (`isScreenshot`, `isVideo`, `imageArea`, …); `model/` never sees a PhotoKit bitmask.
- **Global value-class dates.** Distinct role types (`CaptureCutoff`, `CaptureCeiling`, `EventStart`, …)
  over a canonical `…Z` string, wire-transparent, so the date-role swap that caused this bug (and the
  `createdAt`-vs-`startsAt` backend trap) becomes a compile error. **The ceiling becomes required** — the
  unbounded fallback is removed. **BREAKING (client decode):** a pre-ceiling config fails to decode.
- **Eliminate the manifest accumulator.** The device manifest derives from an **enriched upload ledger**
  (adding `creationDate`/`role`/`contentType`/`filename`) rather than a parallel durable accumulator that
  duplicated the ledger's deletion-aware asset set. The union's byte-presence check downgrades to
  defense-in-depth.

## Capabilities

### New Capabilities

None. This is an internal restructuring of how existing capabilities are *implemented*; the observable
contracts are refined, not newly created.

### Modified Capabilities

- `photo-selection-policy`: restructure so the contract is a single **admitted set** (assembly point +
  ban on per-consumer re-enumeration), rather than the policy restated at each consumer; the ceiling
  applies to **every** consumer by construction. This is the core fix.
- `device-manifest`: the manifest is a projection of the (enriched) upload ledger's completed rows,
  date-filtered to the current event window — not a separate accumulator.
- `sync-ledger`: the ledger row gains `creationDate`/`role`/`contentType`/`filename` so it can back the
  manifest.
- `bunny-list-endpoint`: the union's completeness cross-check becomes defense-in-depth (the manifest now
  lists only completed resources).
- `limited-photo-access`: the fed selection snapshot is modelled as a `CandidateSource` carrying **facts
  only**; the sanctioned-read discipline lives in its construction, and resources are read lazily per
  admitted asset (device-measured storm-free — see below).
- `join-event` / `event-rejoin-reconciliation`: `EventConfig`'s ceiling becomes required (the unbounded
  fallback and the reconcile backfill of an *absent* ceiling are removed) — contingent on the ordering gate.
- `gallery-status`: `RawAsset` collapses into a neutral `AssetFacts`; the bitmask interpretation moves to
  `iosMain`.

## Impact

**`:domain`** — new `EventPhotoSet` + `CandidateSource` seam (`compose/`/`feature/`); `SelectionPolicy` as
sealed rules in `model/`; `Contribution` deleted; value-class date types across `model/`; the four
consumers (`UploadCycle`, `DeviceManifestProducer`, `OwnDeviceGalleryStatusSource`, `ShareableCountSource`)
reduced to `EventPhotoSet` calls.

**`:adapter:ios`** — the `PHAsset → AssetFacts` mapping and the sealed-rule → `PHFetchOptions` translation
move here; the facts-only snapshot source for LIMITED.

**`:adapter:generic:app`** — the enriched ledger schema + store; the manifest producer becomes a ledger view.

**Backend** — none required (the manifest wire shape is unchanged; the union keeps its byte-check).

**Migration gate:** the ceiling-required decode (D7) merges *after* `decouple-event-window-from-lifetime`
has shipped and reconciled every device (D7a) — a device that skips decouple's tolerant-decode backfill and
meets this change's strict decode loses its `eventId`. Controlled on the TestFlight-internal base;
`SNAPSYNC_RESET_STATE` clears any holdout.

**Resolved by device spike (was the one open item):** measured on the SE2 (iOS 26.5.2, `.limited`, plist
alert-suppression on) — 6 off-flow bursts of `assetResourcesForAsset` on already-held baseline refs (54
reads) produced **zero** limited-access alerts, both during the bursts and on the bare home screen after a
SIGKILL. The archived probe's storm is specific to library **fetches**, not resource reads. So
`Asset.resources()` is **one lazy path for both grants**, and the LIMITED snapshot carries **facts only**
rather than pre-captured resources. Caveats (possible PhotoKit cache hits across rounds; foreground-only
measurement) are recorded in design D10, and the backing stays behind the `Asset.resources()` seam so
reverting to pre-capture is a one-impl change.
