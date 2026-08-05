# Duplicate foreign-photo import → echo upload — root cause (SNAPSYNC-6)

Investigation only. No code changed, no OpenSpec change opened.

Sources: Bugsink issue `4f1e770a-c2b4-4bed-ad4a-ac937a32a798` (event `c3bfecf2-…`, release 0.2 /
dist 542 / production, `app_log` 187,418 bytes, `ext_log` absent — url_session tier);
`GET /events/881326aa-…/files` (read-only); the repo at `fix-duplicate-import-on-restart`.

---

## 1. Re-verification of the established chain

Every step holds. One step is **stronger than stated**, and one is **wrong in the prior write-up**.

### 1.1 Code (all confirmed)

| Claim | Verified at |
|---|---|
| `createdLocalId` written **inside** the change block, state left `PENDING` | `IosPhotoLibraryImporter.kt:85-96`; `recordCreatedLocalId` is `UPDATE downloadAsset SET createdLocalId` only (`DownloadStore.sq:57-60`); wired to the **concrete** store at `SnapSyncRoot.kt:349` |
| `markImported` is the only `state='IMPORTED'` setter, called after the completion callback | `DownloadStore.sq:49-52`; `DownloadController.kt:114` |
| `selectImportableAssets` ignores `createdLocalId` | `DownloadStore.sq:144-156` — gates on `a.state != 'IMPORTED'` + staging completeness only |
| the fake has the same hole | `InMemoryDownloadStore.kt:73-79` |
| one `createdLocalId` column per `(sourceDeviceId, sourceAssetId)`; `markImported` overwrites it | `DownloadStore.sq:8-15`, `:49-52` |
| `deleteNonTerminalAssets` deletes exactly the crash-window row shape | `DownloadStore.sq:83-84` (`state != 'IMPORTED'`) |
| reachable on leave / switch / reset | `SnapSyncApp.kt:648` (leave), `:402` (`cancelDownloads`, switch), `ResetDeviceState.kt:67` |
| `plan()` does **not** clobber the handle | `upsertAsset` is `INSERT OR IGNORE` — not a gap |
| the suppression projection itself is correct | `suppressedLocalIds` = `WHERE createdLocalId IS NOT NULL` — it *does* include a `PENDING` row's handle |

### 1.2 Log evidence

- **09:02:07** — five `onResourceStaged` invocations enter. Four log `imported foreign asset …`;
  four exits (495 ms / 766 ms / 1012 ms / **89990 ms**). The `7CD3AF64-…_L0_001` invocation logs
  **neither** `imported` **nor** `import deferred`, and never exits. ✅
  (Note the 89990 ms exit: a sibling `performChanges` took 90 s. PhotoKit was already degraded.)
- **09:03:38–09:06:27** — `reconcile` blocks 169 s on `GET …/files` (socket timeout), then
  `union fetch failed — keeping last state`. ✅
- **09:06:27** — `→ platform.discoverResources`, no matching `←`. Watchdog kill (SNAPSYNC-1,
  `2026-08-01T09:06:27.789Z`, dist 542, iPhone11,2, foreground). New process **09:06:45**. ✅
- **09:06:47** — `imported foreign asset 7CD3AF64-… as 236BC157-…` — second PHAsset. ✅
- **09:06:50** — `discovered 4 candidate(s)` → `admitted 1` → `Upload key=BB4F7765-…-primary.heic`
  → `success=true` → manifest PUT `201` → `notify 202`. ✅ `photos_completed: 1` is this echo.

### 1.3 Backend (read-only `GET /files`)

The union listing's per-resource `filename` is the manifest `originalFilename`:

```
dev=2AF1F74D asset=7CD3AF64 size=1359470 filename=IMG_6875.HEIC      ← the original capture
dev=2AF1F74D asset=89CAB946 size=1359470 filename=IMG_6875.HEIC      ← 2AF1F74D's own re-upload (out of scope)
dev=1F6D9B8B asset=BB4F7765 size=1359470 filename=7CD3AF64-…-primary.heic   ← THE ECHO
dev=1671B734 asset=05F77304 size=1928768 filename=IMG_2014.HEIC      ← the original capture
dev=8827E99E asset=295F5835 size=1928768 filename=05F77304-…-primary.heic   ← the SAME BUG, device 2
```

Two more same-size clusters on 08-02 (`7C19FFE8`/`A0B59784`/`59D99DA6`;
`1AF70F38`/`BF711A11`/`979F956D`; `FFDD99BF`/`7F258662`) — the pattern kept running. ✅

**This fully accounts for the report.** Device 1F6D9B8B's gallery ends up with the 08:09:25 photo
**three** times (the orphan `BB4F7765`, the re-import `236BC157`, and `89CAB946`→`BCF24DBE` at
11:45) and the 08:44:00 photo **twice** (`05F77304`→`85E5301E` and 8827E99E's echo
`295F5835`→`C15AEF3B` at 13:45). "Some photos are 2 or 3 times in the gallery" is *mostly other
devices' echoes arriving as legitimate foreign assets* — the propagation, not the local fault.

### 1.4 The one correction

**Forensic marker note:** build 542's importer called `addResourceWithType(type, url, null)`, so an
imported asset takes the staged file's name. `2cef6d44` (2026-08-02, *after* 542) now names each
resource explicitly from the capturing device's filename. So on any future build **this marker
disappears** — a future echo will carry the original camera filename and be indistinguishable in
`/files` from a genuine capture. Future diagnosis of this class will need size + `creationDate`
collision instead. Worth knowing before this is closed.

---

## 2. What makes an import idempotent — and every place it is missing

**The only idempotency token in the system is `state = 'IMPORTED'`.** Exactly two reads consult it —
`isImported(ref)` (the reconcile plan-skip, `DownloadController.kt:76`) and `selectImportableAssets`
(the import gate). Neither consults `createdLocalId`. So the actual guarantee is:

> An import is idempotent **iff the process survives from PhotoKit's commit to `markImported`.**

That window is unbounded: `performChanges` is a synchronous XPC round-trip into `photolibraryd`
that was measured at 90 s in this very log, and the foreground watchdog is 10 s.

Against the four scenarios asked:

| | idempotent? | why |
|---|---|---|
| **relaunch** | ✗ | the window above — the row is byte-indistinguishable from "never imported" |
| **re-reconcile in one process** | ✓ | `DownloadController.mutex` + `markImported` already ran |
| **duplicate silent push** | ✓ | same — every trigger funnels through `importReadyLocked` under the mutex |
| **leave / switch** | ✗✗ | *worse* than non-idempotent: `pruneNonTerminal` **deletes** the row, taking the handle **and** the staged-resource rows, so the asset is re-downloaded and re-imported on the next join and the first copy is permanently unsuppressed |

Enumerated gaps:

1. **`DownloadStore.sq:144` `selectImportableAssets`** — no `createdLocalId IS NULL`. *The* gap.
2. **`InMemoryDownloadStore.kt:73` `importableAssets`** — same gap, so the fake is faithful to the
   bug; a fix must land in both plus `DownloadStoreContract`.
3. **`DownloadStore.sq:62` `isImported`** — `state='IMPORTED'` only, so reconcile re-plans a row
   that already owns a created asset.
4. **`markImported`** — blind overwrite. The schema cannot represent "this ref owns two created
   assets", so the model has no way to keep the first handle even in principle.
5. **`deleteNonTerminalAssets` / `pruneNonTerminal`** — deletes handle-carrying rows.
   `ResetDeviceState`'s KDoc reasons explicitly that imported rows are kept *because* they carry the
   handle; that reasoning is right for `IMPORTED` and silently wrong for `PENDING`-with-handle. The
   `download-store` spec's requirement is worded "Terminal rows are permanent" — the invariant it
   actually needs is "**handle-carrying** rows are permanent".
6. **`IosPhotoLibraryImporter`** — no read-back and no durable trace that a commit happened other
   than `createdLocalId`, which nothing consults for idempotency. A `performChanges` that captures
   the placeholder and then genuinely **fails** produces the same row shape with no crash at all —
   a third path to the same state.
7. **Not a gap:** `UploadCycle.suppressedAssetIds` (`UploadCycle.kt:104` ← `UploadCore.kt:155` /
   `SnapSyncApp.kt:264,275`) reads the correct projection. It is emptied by the overwrite and the
   prune, not by its own predicate.

---

## 3. Is `recordCreatedLocalId`'s write durable across SIGKILL? — **Yes, proven on this device**

The log answers it directly, and the answer changes the fix.

- **09:06:46** (relaunch, **before** the re-import): `gallery: fetched 9 candidate(s)` …
  `gallery: N=0 own admitted asset(s)`; `discovered 5 candidate asset(s)` →
  `selection policy admitted 0 of 5`.
  The 9 in-window library assets = 4 pre-existing policy-excluded + the 4 imports of 09:02:08 +
  **`BB4F7765`**. The 5 changed-since-token = those 4 imports + `BB4F7765`. **All five suppressed**,
  and `N=0` own admitted proves it independently.
  → `BB4F7765`'s `createdLocalId` row **survived the SIGKILL**.
- **09:06:47** — `markImported(7CD3AF64, 236BC157)` **overwrites** it.
- **09:06:50** — `discovered 4` → `admitted 1` = `BB4F7765`. Uploaded.
- **09:08:13** — `gallery: fetched 11 … N=1 own admitted`. The own-count flipped `0 → 1` exactly
  across the overwrite.

**Conclusion:** the overwrite is the **sole** orphaning path in this incident, not one of two.
Mechanically this is what SQLite gives you: the bare `UPDATE` auto-commits, WAL is the native
driver's default journal mode (`IosLedgerStore.kt:36-37`, `IosDownloadStore.kt:20`), and a WAL
commit at `synchronous=NORMAL` is durable against **process** death. It is *not* guaranteed against
kernel panic / power loss — so a genuine loss-of-write path exists, but it is far rarer than
watchdog death and did not occur here.

---

## 4. The ≥26.1 PhotoKit tier, and `LIMITED`

**Tier: identical exposure.** The importer lives in `:adapter:ios:app-only` and `DownloadController`
is composed by `snapSyncApp`, not `uploadCore` — so **both tiers import in the app process**. The
duplicate-import defect is tier-independent. The echo leg differs only in *who reads* the suppression
set: on ≥26.1 the extension opens the app-written store read-only through the narrowed
`SuppressionSource` (`UploadExtensionRoot.kt:108`, `iosSuppressionSource()`), on 18–26.0 the app's
own cycle reads it. Both read the same `WHERE createdLocalId IS NOT NULL` projection, so an orphan is
admitted on both. If anything ≥26.1 is **more** exposed: `process()` runs on OS cadence independent
of app lifecycle, so the orphan is discovered whenever the OS next schedules the extension.

**`LIMITED`:** creation is unrestricted and an app-side import **auto-joins the limited selection at
creation time**, firing the observer (`limited-photo-access` spec, "The app's own import does not
re-upload"). So the bug is unchanged under `LIMITED`. The consequential fact is the *other* one the
same spec records: on a selection transition, **previously-imported foreign assets are no longer
visible to the app** — "iOS auto-adds app-created assets to the selection only at creation time
(measured)". That fact is what disqualifies PhotoKit resolution as an oracle under `LIMITED`
(§5, option 3). CLAUDE.md fact ① (never add an autonomous `PHAsset` read under `LIMITED`) applies to
any new resolve site; note `logImportedDate` already calls `fetchAssetsWithLocalIdentifiers`, but
**in-flow, on an asset created microseconds earlier** — a resolve at reconcile/import-selection time
is a different, autonomous site.

---

## 5. Fix direction (for review — not written)

Target invariant: **a `downloadAsset` row that already owns a created PHAsset is never imported
again, and never loses its suppression handle.** Two separable sub-problems: **(A)** don't import
twice; **(B)** don't orphan the first copy if we do.

### Option 1 — gate `selectImportableAssets` (and `importableAssets`) on `createdLocalId IS NULL`
Solves **(A)** at the one place the double-import is decided. Tiny, pure SQL + fake, tier-independent,
no PhotoKit read, correct under `LIMITED`.
**Risks the worse failure.** A `performChanges` that captured the placeholder and then genuinely
failed leaves a handle for an asset that does not exist → the row becomes **permanently
unimportable** → *the photo silently never arrives*, and `downloads_imported` never reaches
`downloads_assets`. This is exactly the inversion the `photo-download` spec already warns about
("permanently unimportable and permanently retried… the photo never arrives"). **Mandatory
companion:** on `ImportResult.Failed`, **clear** the handle (the block ran, the commit did not — the
id matches nothing). With that, only the crash window leaves a stuck handle, and in that case the
asset genuinely exists. Residual: the row stays `PENDING` forever, so download progress never
settles — needs option 3 or an "adopt the handle as `IMPORTED`" rule.

### Option 2 — let a row own more than one created asset
A `downloadAssetLocalId` child table (or append-only set) that `suppressedLocalIds` unions.
Solves **(B)** completely: no copy is ever orphaned, the echo is closed however many times we import.
Does **not** solve **(A)** — the user still sees 2–3 copies locally, which is the reported symptom.
Strictly additive, no new failure mode, risks nothing worse than today. Needs a `3.sqm` and must be
paired with option 4 or the prune deletes the whole thing anyway. Best as the *belt* under option 1.

### Option 3 — resolve the unresolved row against PhotoKit
`fetchAssetsWithLocalIdentifiers([createdLocalId])`: exists → `markImported` and skip; absent →
clear the handle and import. The only option that resolves **truth** rather than guessing, and the
only one that **repairs** the state so progress settles. `logImportedDate` already makes this exact
call, so the API and the id form (`/`→`_` normalization) are proven here.
**Costs:** a new PhotoKit read at a new site — one XPC round-trip per stuck row under `GRANTED`
(rare, bounded, fine). Under `LIMITED` it both violates fact ① *and* **lies**: a de-selected
previously-imported asset resolves to absent, so "absent → import" re-duplicates and "absent → skip"
strands a genuinely-failed import. → usable **only under `GRANTED`**, with a permission-blind
fallback under `LIMITED`. That conditional belongs in a tested feature rule fed by the permission
port, not an adapter `if`.

### Option 4 — preserve handle-carrying rows in `pruneNonTerminal`
`DELETE … WHERE state != 'IMPORTED' AND createdLocalId IS NULL` (+ the fake). Independent of the
others and unambiguously right: a row whose handle is the only thing preventing an echo must not be
deleted by a leave, a switch, or a `SNAPSYNC_RESET_STATE`. Keeping the asset row also keeps its
resource rows (`deleteNonTerminalResources` is parent-scoped), which is what a later repair needs.
Requires re-wording `ResetDeviceState`'s KDoc and the `download-store` spec requirement from
"terminal rows are permanent" to "handle-carrying rows are permanent".

### Which risks the worse failure — a photo that silently never arrives
- **Option 1 alone** — yes, unless the clear-on-`Failed` companion ships with it.
- **Option 3 under `LIMITED`** — yes on the "absent → skip" branch; re-duplication on the other.
- **Options 2 and 4** — no. They only ever *add* handles and *keep* rows; the worst case is the
  status quo.

### Recommended shape (to review)
**4 + 2 as the safety floor** (never orphan, never delete a handle) · **1 as the correctness gate,
with clear-on-`Failed`** · **3 under `GRANTED` only**, as the state repair that lets a stuck row reach
`IMPORTED` so download progress settles.
Design choice to settle first: `createdLocalId IS NOT NULL` as the predicate vs. a third
`DownloadState`. The enum has a deliberate "no terminal failure state" posture and a third state must
thread through `countImported`, `inFlightCount`, `isImported`, `pruneNonTerminal`, and a migration —
the predicate says the same thing with no new vocabulary.

---

## 6. Where the regression test belongs, and what it must assert

**Primary: `:test:integration` `commonTest`** (JVM + `iosSimulatorArm64`), a new test beside
`FullStackIntegrationTest`, composing the real core over `:test:world`.

A blocker to note up front: **`:test:world`'s `FakePhotoLibraryImporter` cannot express this state
today** — it returns `ImportResult.Imported(createdLocalId)` atomically and has no
`recordCreatedLocalId`-shaped hook, so the "handle written, completion never ran" state is
unreachable. The fake must gain that lambda to mirror the real seam (and per `FakeHonestyTest` the
*lever* — `crashAfterCreate` / "record then never resume" — belongs in a `:test:world` wrapper, not
in `:adapter:generic:fake`).

It must assert, in one run:
1. handle recorded, `markImported` never called (the simulated kill);
2. re-drive `reconcile` / `importReady` → **exactly one** import for that ref
   (`FakePhotoLibraryImporter.imported` size 1; one gallery asset for it);
3. run an upload cycle afterwards and assert the world backend holds **no** object under the
   orphan's key — this is the assertion that pins the actual reported harm, not just the duplicate;
4. the same, with a `World.leave()` interposed between (1) and (2) — pinning the `pruneNonTerminal`
   leg;
5. `downloads_imported` reaches `downloads_assets`, or the progress-never-settles failure ships
   silently.

Supporting, cheaper pins:
- **`DownloadStoreContract`** (`:test:world` `commonMain` — run against both the fake and the
  SQLDelight store): a `PENDING` row with `createdLocalId` set is (a) absent from
  `importableAssets()`, (b) present in `suppressedLocalIds()`, (c) still present after
  `pruneNonTerminal()`. This is the one that catches the SQL and the fake diverging.
- **`DownloadControllerTest`** (`:adapter:generic:fake` `commonTest`): the existing
  `a_failed_import_stays_importable_for_retry` **must keep passing** — it is precisely the test that
  fails if option 1 lands without clear-on-`Failed`. That is a feature, not a nuisance.

---

## 7. Flagged, not solved — the standing identity gap

Identity is `(sourceDeviceId, sourceAssetId)` end to end: the union key, the download-store PK, the
upload key `<assetId>-<role>.<ext>`, the ledger key (bare filename), and the suppression set. **No
content-level dedup exists anywhere** — no hash, no size+capture-date match, nothing compares bytes.

- A duplicate, once uploaded, is a *distinct asset id* to every member. Each member's `isImported`
  says "never seen", so each downloads and imports it. The backend already shows this: the 08:09:25
  photo exists as **three separate 1359470-byte objects** under three asset ids across two devices.
- Imports are **terminal and permanent by design** ("a photo the user deletes locally is never
  re-imported"), so **there is no repair path** for copies already in members' libraries. Hand-deleting
  them is the only remedy — and the store will correctly refuse to re-import them, which is the one
  thing working in our favour.
- The backend does not dedup either, so the duplicate objects are permanent in the zone absent a
  per-object delete (CLAUDE.md forbids a whole-zone reset — the zone is shared with real users).
- Deleting the orphan's object would clean the *union* going forward but un-imports nothing already
  pulled, and this device's manifest projection would still list it (ledger `COMPLETED`).

Its own investigation, not this one.
