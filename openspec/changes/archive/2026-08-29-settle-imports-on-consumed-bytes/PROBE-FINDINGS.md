# Probe findings — the residual, reproduced; and identifier reuse

Host: iOS 26.5 simulator (23F77), Xcode 26.6, macOS 26.5.2 runners — **two independent runners agreeing**.
Standalone throwaway probe app (`app.snapsync.probe`, ~145 lines of Swift), `shouldMoveFile = true` to match
production. Never committed; it linked no SnapSync code.

⏰ Simulator only, one runtime, one day. Re-measure on a device and at the next iOS major.

## 1. Can a relaunch adjudicate while a surviving commit is still in flight? YES

**Method.** Build a noise JPEG; inside `performChanges`, write the placeholder `localIdentifier` to disk
(where production writes its marker); `kill(getpid(), SIGKILL)` 0.2 s later, so the transaction is submitted
and no completion is delivered. Relaunch, and immediately fetch that identifier — the earliest a startup
sweep could ask.

```
816.000  CREATE: placeholder id = 5A4B6CEE-…/L0/001  (marker written in-block)
816.209  CREATE: SIGKILL now (+0.2s from block entry)
817.952  CHECK:  first fetch = 0   (1.647 s after block entry, 0.168 s after process start)
818.265  CHECK:  >>> APPEARED AFTER RESTART <<<  0.311 s after the first fetch
```

| asset | first fetch after relaunch | verdict | appeared |
|---|---|---|---|
| 1.07 MB | +1.446 s | PRESENT | commit beat the kill |
| 1.09 MB | +1.652 s | PRESENT | commit beat the kill |
| 1.92 MB | +1.344 s | PRESENT | commit beat the kill |
| 25.8 MB | +1.647 s | **ABSENT** | +0.311 s |
| 25.7 MB | +2.301 s | **ABSENT** | +0.985 s |
| 25.8 MB | +1.717 s | **ABSENT** | +0.785 s |
| 25.6 MB | +1.547 s | **ABSENT** | +0.383 s |
| 43.5 MB | +2.807 s | **ABSENT** | +1.843 s |
| 43.4 MB | +3.090 s | PRESENT | — |
| 43.5 MB | +2.155 s | **ABSENT** | +1.065 s |
| 43.5 MB | +2.531 s | **ABSENT** | +0.868 s |
| 43.3 MB | +2.756 s | **ABSENT** | +0.722 s |

**8 of 9 runs at 25-43 MB** reproduced the window; **3 of 3** at 1-2 MB took the safe path — a control, not
an absence of evidence. The crossover is between ~2 MB and ~25 MB, **not** the several hundred MB that
`take-imports-off-the-download-lock` predicted would be required.

## 2. Killing the client widens the commit window 2-3x

With a live client, an asset became visible 0.418-1.109 s after block entry (1-22 MB), and visibility
coincided with the completion callback to within tens of milliseconds. After SIGKILL the same sizes took
2.1-4.9 s. `photolibraryd` finishes an orphaned transaction more slowly — precisely in the case that
matters. **Every commit duration previously on record was measured with a live client and therefore
understates the crash window.**

## 3. The staged file is consumed even when the app dies — and before the asset is visible

```
CREATE: src BEFORE performChanges:        exists=true  size=43457425
CREATE: src AFTER addResource (in block): exists=true  size=43457425
CREATE: SIGKILL now (+0.2s from block entry)
CHECK:  src AFTER RESTART:                exists=false
```

6 of 6. Note the middle line: ingest happens **after the block returns**, not at `addResource`. At the
moment a sweep looks, the file is already gone while the asset is not yet visible — which is exactly the
state this change reads as evidence.

## 4. The re-import that today's *absent* branch performs cannot succeed

```
REIMPORT: second placeholder = 040C785B-…/L0/001
REIMPORT: ok=false domain=PHPhotosErrorDomain code=3302
```

2 of 2. Production maps 3302 (`InvalidResource`) to `consumedResources = true`, so the row settles
`UNIMPORTABLE` with `createdLocalId` NULL: terminal, never adjudicated again, asset unsuppressed. The
predicted code was `MissingResource` (3303); the measurement says 3302. Same production branch, but the
prediction was wrong and the measurement is what settles it.

## 5. Is a `localIdentifier` ever reused? No evidence of it

240 creations in one session.

| test | result |
|---|---|
| 40 ids minted by creations that **failed**, vs 200 later real creations | **0 reissued** |
| 240 identifiers total | **240 distinct**, 0 UUID-prefix duplicates |
| shape | `<UUID>/L0/001` — suffix constant across all 240 |
| ids of **deleted** assets reused later | **NOT MEASURED** |

Identifiers are minted UUIDs, not pool-allocated handles, so nothing is returned on failure. The
delete-recycling path could not be measured: `PHAssetChangeRequest.deleteAssets` raised a system
confirmation alert that a simulator has no way to answer (bounded at 25 s, reported `ok=false`). It is a
different mechanism from the one that produces a leftover marker, but it is unmeasured.

## What this supports, and what it does not

Supports: a marker left naming an asset that never committed is **inert** in the suppression set, which is
what makes settling safe when the guess is wrong (design D3).

Does not support: any claim about device timing, about behaviour across library rebuilds or long time
spans, or about identifier recycling after deletion.

---

# On-device acceptance (task 6.3) — SE2, iOS 26.6, 2026-08-29

Real device, real backend (local rig behind a tunnel), real download pipeline, this branch's build
(`snapsync.rig=true`, Debug). 40 foreign assets of ~30 MB seeded as another device; the app was
SIGKILLed with **18 imports open**, and relaunched **1.3 s later**.

## The new branch fires, and every one of its verdicts was right

```
adjudicated BIG30: absent, but its staged bytes were consumed — commit not yet visible,
                   settled against marker C43A8632-A67D-44A3-8BFD-F7AF6BD28D9E_L0_001
adjudicated BIG31 / BIG37 / BIG38: same
```

All **4 of 4** assets settled on consumed-byte evidence were afterwards present in the library, so all
four kept the suppression handle they would otherwise have lost. Verdict tally for the 13 rows the
sweep inherited: **2** *present*, **4** *consumed bytes* (the new branch), **7** *bytes intact → cleared*.

## 6.4 — launch to adjudication, measured

| | |
|---|---|
| process start | 13:57:26.419 |
| sweep entered | 13:57:26.494 (**75 ms**) |
| first verdict applied | 13:57:27.999 (**1.58 s** after process start) |

That is **far** below the ~5 s the `take-imports-off-the-download-lock` design assumed as the floor
(it measured `dvt launch` from a laptop, not an OS relaunch). Against a commit window of seconds, the
exposure is **wider** than that change believed, not narrower.

## ⚠️ A LIMIT THIS CHANGE DOES NOT CLOSE, found by this run

Two rows — BIG33 and BIG39 — were cleared as *bytes intact* and are **orphans**: their assets exist in
the library and carry no suppression handle.

`58828452-427F-4886-AC10-5E04173EF9D5`, `80EF0D9A-A0E1-4FA4-9078-8FD0573AABB5`

The cause is a window the design's D1 does not cover. D1 establishes *absence ⇒ a creation was
submitted*, which held 4/4. The **clear** branch relies on the converse — *presence ⇒ nothing was
created* — and that is **false**: `performChanges` submits the transaction when the block RETURNS,
while `photolibraryd` ingests the file later still. A process that dies in between leaves a submitted
transaction whose bytes are untouched. With 18 transactions queued, that gap is seconds wide.

Both orphaned rows then failed their re-import with `3302` — the bytes had been consumed by the
original transaction in the meantime — and settled `UNIMPORTABLE` with no handle, which is the
`SNAPSYNC-9` end state.

**This change is therefore a strict reduction, not an elimination**: without it all 11 *absent* rows
would have been cleared and 6 would have orphaned; with it, 2 did.

Closing the remainder needs a record of **submission**, not of ingest — e.g. a durable flag written
immediately after `performChanges` returns, making "this ref has a transaction in flight" survive the
process the way the marker already does. Out of scope here; recorded rather than inferred.
