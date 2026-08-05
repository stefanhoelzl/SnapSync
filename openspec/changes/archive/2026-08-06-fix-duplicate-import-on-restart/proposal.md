## Why

A foreign photo can be imported into a device's library **twice**, and the orphaned first copy then
loses its echo-suppression handle and is **uploaded back into the event**, where every other member
imports it as a photo they have never seen. Reported from TestFlight as *"some photos are 2 or 3 times
in the gallery"* (Bugsink `SNAPSYNC-6`, iPhone11,2 / iOS 18.7.9 / release 0.2 build 542 / production).

An import is **committed** in one place and **recorded as done** in another, and every idempotency query
reads only the second:

```
  inside the change block:  createdLocalId = "BB4F…"   ← durable, ~instant
  ── block returns ──
  PhotoKit commits          ⏱ the slow, killable part
  completion callback:      state = 'IMPORTED'         ← the only thing anything reads
```

`selectImportableAssets` gates on `state != 'IMPORTED'` plus staging completeness and never consults
`createdLocalId`, so a row whose asset already exists is indistinguishable from one never imported.
`downloadAsset` holds **one** `createdLocalId` per `(sourceDeviceId, sourceAssetId)` and `markImported`
overwrites it — so the first copy silently leaves `suppressedLocalIds()` and becomes uploadable.
`pruneNonTerminal` (`DELETE … WHERE state != 'IMPORTED'`) deletes exactly this row shape, reachable on
any leave, switch, or `SNAPSYNC_RESET_STATE`.

Measured, not inferred (full chain in `INVESTIGATION.md`): at 09:06:46 — after the process died and
*before* the re-import — `BB4F7765` was still suppressed (`admitted 0 of 5`, own-count `N=0`), proving
the handle survives SIGKILL. At 09:06:47 `markImported` overwrote it. At 09:06:50 it was admitted and
uploaded. The backend confirms it: that object carries `filename=7CD3AF64-…-primary.heic`, the staged
name of the photo it duplicates. **A second device did the same thing the same day.**

**The window is now opened deliberately.** `hold-os-receipts-until-work-completes` (merged, `22f782bd`)
bounds each import's wait at 5 s and abandons the transaction while it may still commit — its own
comment: *"every abandoned transaction may still commit, which is a duplicate photo."* So this row is a
**routine designed outcome**, not a crash artifact, and that change deliberately left the repair here.

Nothing repairs it today, and nothing can: identity is `(sourceDeviceId, sourceAssetId)` end to end with
no content-level dedup anywhere, and imports are terminal on every member — so each duplicate propagates
to everyone, permanently.

## What Changes

The invariant: **an asset already created for a ref is never created again, and the record that it was
created is never destroyed while it is still needed.**

- **`PENDING` + a non-null `createdLocalId` *is* the unconfirmed state** — it can arise exactly one way,
  and a confirmed row is never revisited. **No column, no third state, no migration**, and installs
  already stuck in this state heal on their next import pass.
- **The importer undoes its own record on an observed failure** — the mirror of the in-block write.
  Never on `TimedOut`: that transaction may still commit, and clearing there is how the first copy gets
  orphaned.
- **A presence guard on the import path, in two phases** — the blocking PhotoKit fetch runs *outside*
  `DownloadController.mutex`; only the verdicts and the drain run under it. `PRESENT` → settle ·
  `ABSENT` → clear the handle, then import · `UNKNOWN` → skip.
- **The presence port answers by grant**, because a miss is trustworthy only under full access: under
  `LIMITED` a real asset can answer *absent* (auto-add is creation-time only), and with no grant at all
  the fetch returns empty for an asset that exists.
- **Handle-carrying rows survive the prune** — the invariant becomes *"handle-carrying rows are
  permanent"*, not *"terminal rows are permanent"*.
- **Staged bytes are released on confirmation, and only then** — nothing deletes them today, so every
  received photo is stored twice, permanently (~200 MB on one field device). Released after
  `markImported` commits, before a prune drops the rows, and by a self-extinguishing backlog pass. The
  ordering is load-bearing: release *before* the confirming write loses the photo permanently.

Not in scope, deliberately: the six other ungated `PhotoKit` reads under `LIMITED` (measured not to be a
storm risk — see `correct-limited-access-read-premise`), and any repair for duplicates already in the
wild, for which no mechanism exists.

## Status

Design settled and rationale complete (`design.md`); investigation complete (`INVESTIGATION.md`).
**Spec deltas and tasks are still to be written** — they are the next step, not an omission.
