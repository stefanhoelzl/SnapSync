# Limited photo access — design (from interview 2, grounded in the device probe)

Depends on `PROBE-FINDINGS.md`. The one-line frame: **limited access reuses the app-driven URLSession
upload *mechanism*, with a new selection-driven *trigger model*.** Not a new upload engine — a new
ignition.

## Gate — device verification (observer probe, 2026-07-20)

Ran the observer probe on the SE2 (iOS 26.5). Results:

**✅ A single in-flow walk under limited does NOT storm.** Measured cleanly on an **unjoined** app (zero
autonomous walks) under `.limited`:
- **Cold-launch single walk** → clean, no dialog. Verified **twice** (3 assets, then 4 assets; both
  landed on the plain "Start an event" screen).
- **Several walks in one foreground** (`gallery.refresh` N=4 + raw fetch + subtype census + `ensureCreated`)
  → also no dialog.

**↻ Reframes finding 5.** The storm is **not** "any fetch under limited storms." A single foreground —
even with several walks — is clean. The earlier storm was a **joined** app doing autonomous walks across
**repeated foreground/background cycles** (the picker + Settings round-trips during the initial grant).
The storm correlates with **repeated re-foregrounding / many app lifecycles**, not with walk count in one
lifecycle. This is *good news* for the design: suppressing autonomous walks + walking only on
selection-change keeps each lifecycle to ≤1 library-access moment, which the plist key handles.

**✅ GATE CLOSED — the observer-callback walk does NOT storm (register-then-create spike, 2026-07-20).**
Reordered the probe to register the observer, then seed 5 assets in-process; each create fires the
observer → a walk, all headless (no Settings round-trip, no picker). Under limited + unjoined:
- iOS **coalesced** the 5 rapid creates into **3** observer fires (not 5) — the OS already collapses a
  burst somewhat.
- The 3 observer-fired walks (+ the cold-launch walk = 4 in-flow walks) → **clean "Start an event"
  screen, no dialog.**

So both halves of the gate pass: a single cold-launch walk *and* observer-callback walks (including a
coalesced burst) do not storm under limited.

**↻ Sharper reframing of finding 5.** The alert is not per-walk. Multiple walks within **one stable
foreground** are clean (verified: 4 here, ~5 in the earlier multi-walk run). The storm was a **joined**
app walking across **repeated foreground/background cycles** (the picker + Settings round-trips during
the initial grant) — i.e. the alert fires ~once per foreground and the plist key's suppression frays
under rapid re-foregrounding, not per library access. This is *more* favorable than finding 5 first read.

**Consequence for the debounce (thread 3):** the burst did **not** storm, so the debounce is **not**
strictly a storm-avoidance measure. Its justification shifts to (a) **efficiency** (3 walks + 3 upload
cycles for one logical change is wasteful) and (b) **breaking the import→observer→walk feedback loop**.
Still needed — but for correctness/cost, not to prevent an alert.

**🔴 New finding — the observer fires on the app's OWN writes, not just user selection changes.** In the
joined run the observer fired **6×** with no user interaction, as the app imported foreign photos (library
count grew 10→11). So `PHPhotoLibraryChangeObserver` is **not** a clean "user changed the selection"
signal — every download-import trips it. The design MUST debounce/filter (e.g. ignore changes the app
itself caused, or coalesce rapid fires), or an import → observer → walk → (import) feedback path forms.
This is a real change to the observer seam's contract.

## ↻ Major revision — consume the PHChange DELTA, don't re-walk (2026-07-20)

`PHPhotoLibraryChangeObserver`'s callback carries a `PHChange`. Via
`change.changeDetailsForFetchResult(heldFetchResult)` you get the exact
inserted/removed delta — the observer *pushes* what changed; re-walking to rediscover it is
redundant. This is the canonical WWDC "Handle the Limited Photos Library" pattern, and it **collapses
the debounce/self-caused-filter thread** (there's no expensive walk to debounce; the delta is cheap).

**Spike results (register-then-create, delta mode, limited + unjoined):**
- **✅ Caveat 1 — consuming the delta does NOT storm.** `changeDetailsForFetchResult` on the observer
  callback → clean screen, no dialog.
- **⚠️ Caveat 2 — resource read UNTESTED here.** The delta reported **0 inserted** for the seeded
  creates (see below), so there were no inserted assets to read resources from. Resource-read safety is
  instead **strongly supported by prior data**: every production walk already calls
  `assetResourcesForAsset` and none stormed, and finding 3 uploaded resources under `.limited`. A
  resource read is not a scope-query, so it should not prompt — but it is not *isolated*.
- **🔵 New finding — itemized inserts need a properly-configured baseline.** `changeDetailsForFetchResult`
  against a `fetchAssetsWithOptions(null)` (no sort descriptor) returned `0 inserted` even though the
  library grew. This matches PhotoKit's contract: when `hasIncrementalChanges` is false you must **reload
  from `fetchResultAfterChanges`** rather than read `insertedObjects`. So the design's observer seam
  should: use `insertedObjects` when `hasIncrementalChanges`, else enumerate `fetchResultAfterChanges`
  and let the **ledger** dedup (which it does anyway). **Either path reads a handed-to-you result object,
  NOT a fresh scope-query fetch** — so still alert-safe (consistent with caveat 1).

**Net:** the observer seam becomes a **change-delta source**, not a walk-trigger. `PhotoSelectionChangeSource`
emits `PHFetchResultChangeDetails`-shaped deltas (inserted/removed, or a reload signal); the domain
enqueues inserted-and-policy-passing-and-not-echo-suppressed assets. No debounce, no self-caused filter,
no re-walk. One baseline walk on cold launch; deltas thereafter.

### The refined conclusion (2nd spike, sorted baseline)

Even with a `creationDate` sort descriptor, a **bulk create** (5 assets at once) arrived as
`incremental=false` → empty `insertedObjects` → reload from `fetchResultAfterChanges`. Bursts (downloads,
camera bursts) will do the same, so **the design must NOT rely on `insertedObjects`** — the reliable path
is: on any observer fire, read `fetchResultAfterChanges`, enumerate, and let the **ledger dedup**. That's
*exactly the current walk+ledger*, just **observer-triggered** and sourced from the pushed result.

This collapses the delta-vs-walk fuss: **what avoids the storm is the observer TRIGGER (in-flow reads),
not how the change is extracted.** Verified: consuming the delta + reading `fetchResultAfterChanges` →
no storm (twice). And the resource read on the reload path is the same `assetResourcesForAsset` the
production walk already performs without storming (+ finding 3 uploaded resources under `.limited`), so
**caveat 2 is answered by walk-equivalence** — not separately isolated (bulk creates stay non-incremental,
so the spike couldn't populate inserts to read), but it is the identical call on a granted asset.

So the seam is simply: **observer-triggered read (in-flow) → `fetchResultAfterChanges` → ledger-dedup →
enqueue.** `insertedObjects` is an optional fast-path when `hasIncrementalChanges`; the ledger-dedup
reload is the load-bearing path. Debounce/self-caused-filter remain unnecessary (an import shows in the
reload, the ledger drops it — one cheap check, no storm).

**Still unmeasured:** the **background** regime (observer firing during a silent-push download import).
An in-flow read is verified safe; a *background* read is not, though it is even less of a scope-query
than a fresh fetch. Flag as an implementation-phase check.

## The design (settled)

### 1. Model — 4th state `LIMITED`
`PermissionStatus` gains `LIMITED`. Audit the four `!= GRANTED` boolean gates so `LIMITED` lands on the
**granted** side (`StatusContainerHost.kt:545`, `SnapSyncApp.kt:242`/`:377`,
`LedgerBackedSyncStatusSource.kt:58`).

### 2. Mechanism — compose both on ≥26.1, arm picks by current permission
- **≥26.1:** compose **both** producers (PhotoKit + URLSession). `UploadArm` becomes permission-aware:
  `full → start PhotoKit / stop URLSession`; `limited → start URLSession / stop PhotoKit`, reading
  current permission at each transition. **Exactly one started at a time.**
- **18–26.0:** URLSession only (unchanged).
- **Fresh install (`NOT_DETERMINED`):** no producer started; the first grant resolves which to start.
- Structural mutual-exclusion (today: PhotoKit producer not even constructed off-tier) is **downgraded
  to behavioral** — forced by finding 2 (PhotoKit won't run under limited). Guard: a
  `:test:architecture` assertion that no path starts both, + a decision record citing finding 2.
- The arm's `stop(PhotoKit)` already deregisters the OS extension (`setUploadJobExtensionEnabled(false)`),
  so switching full→limited actively deregisters — no orphan extension (see `app/ios/CLAUDE.md` warning).

### 3. Trigger model under limited (both OS versions)
- The autonomous read triggers — `Foreground` (`pumpForeground` + `refreshStatus`), `Provision`,
  `SilentPush` (upload), and the permission subscription — gate their **PHAsset-fetching** parts on
  `permission == GRANTED`. Under limited they no-op for the **read** path. Everything non-PHAsset still
  runs: HTTP reconcile, ledger-count polling, downloads/imports, attestation.
- Under limited, a read happens only via:
  - **`PHPhotoLibraryChangeObserver`** (registered **only while limited**) — covers the in-app picker,
    Settings-side edits, and iCloud sync.
  - **One walk on cold launch** (opening the app is user-initiated) — for the initial N + upload backlog.
- The observer fires → **one** upload cycle `run(discover = true)` → enqueue every selected job →
  **publish N from that same discovery**. Continuation cycles (`onUploadCompleted`, …) run
  `run(discover = false)` — **drain only, no re-walk**. One walk per selection-change.

### 4. New seam — `PhotoSelectionChangeSource` port
`ports/PhotoSelectionChangeSource { val changes: Flow<Unit> }`; iOS adapter in
**`:adapter:ios:app-only`** wraps `PHPhotoLibraryChangeObserver` (PhotosUI/observer is app-only API,
banned in ext-safe); `compose/` collects it and triggers the limited cycle; `:test:world` fake emits on
demand. **Probe-verified:** the observer fires on the app's OWN library writes (imports), not just user
selection edits — so the adapter/consumer MUST **debounce and/or filter self-caused changes** (else
import → observer → walk → import feedback). Debounce also coalesces the burst iOS emits per change.

### 5. Shared cycle change — `cycle.run(discover: Boolean = true)`
Default `true` keeps every existing tier untouched. Under limited the pump passes `true` on the
observer/launch cycle and `false` on continuations. **N under limited is derived from the cycle's
discovery** (coalesced to one walk; the spec already requires N and the upload walk to share one policy,
so this is arguably more correct).

### 6. Downloads — unchanged, carved out
A `DownloadOnly` limited membership does **no** fetch and only creates (alert-safe, finding 4a) → works
today once `LIMITED` maps to the granted side. Explicitly **not** part of the read-path redesign.

### 7. UX
The **"Choose more photos"** status row → `presentLimitedLibraryPicker` (already built on the probe
branch) → observer → walk. `N` = selected-and-in-scope count; "In sync" when the ledger drains. Under
`LIMITED`, the row is a resting affordance, not an attention state (from interview 1).

### 8. Denylist — inert under limited (spec note)
One sentence: `assetIdsInAlbums` returns ∅ under `.limited` (finding 4b — album structure isn't
readable); the resolution floors (read off the asset) still exclude received media. No code.

## Decomposition — ONE change (decided, interview 2 / thread 4)

**A single OpenSpec change** delivers the whole feature. Considered a two-change split (1: `LIMITED`
state + receive-only; 2: the selection-driven upload) — and the product call that **receive-only under
limited is a valid use case** made both halves independently shippable — but chose one change because
the halves are being implemented back-to-back, which evaporates the ship-early and revert-granularity
benefits, and saves the interim status copy plus a second proposal/archive cycle.

The one-change downsides are mitigated **inside** the change:
- **Task order mirrors the split**: the `LIMITED` state + the four-`!= GRANTED`-gate audit land as the
  FIRST tasks, reviewed and device-validated (receive-only works) before the read-path/compose-both
  tasks stack on top. The risky half never obscures the foundational half in review.
- **The law downgrade gets its own prominent `design.md` decision** (structural→behavioral
  mutual-exclusion, forcing proof = finding 2, the never-both-started guard) — not a footnote.
- Receive-only-limited is specced as a **valid resting state** (a member may allow limited, receive
  everything, and never select an upload) — not a transitional artifact.

## OpenSpec surface (specs that change)
- **permission-gate** — 4th `LIMITED` state; rewrite Purpose (the selection *defines* the scope, so "In
  sync" over the chosen set is true).
- **photo-selection-policy** — denylist-inert-under-limited note; N-from-cycle under limited.
- **upload-lifecycle** / **ios-url-session-upload** — the selection-driven trigger model, compose-both,
  arm permission-awareness, the `discover` flag.
- **ios-photokit-upload** — finding 2 (won't run under limited) as the forcing proof for compose-both.
- **module-architecture** / **architecture-guards** — the never-both-started guard; the new port;
  the structural→behavioral mutual-exclusion decision record.
- **sync-status-screen** — the "Choose more photos" row; `LIMITED` rendering.
- Possibly a new umbrella capability tying it together.

## Verification/probe backlog
1. Observer-callback single walk → storm? **(gate, before proposing)**
2. Cold-launch single walk → storm? **(gate, before proposing)**
3. Drain-only continuation truly performs no PHAsset fetch (during impl).
