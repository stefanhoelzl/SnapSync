## Context

Status today is a projection over three sources combined in `ListingSyncStatusSource` (`:domain:status`):
`completed` from the per-device storage LIST (`CompletedAssetsSource`, an expected×present join),
`pending` from a read-only peek at the extension's shared App-Group ledger (`InFlightSource` →
`aggregates().pending`), and `total` from the PhotoKit gallery count. Liveness is **foreground-only**:
`refresh()` on `CompletedAssetsSource` and `InFlightSource` fires on scene `.active`. There is no
cross-process notification — the current arrangement is the outcome of `ledger-free-status` (2026-06-29),
which deleted the earlier `observed-completion-overlay` **and** its cross-process Darwin ding, moving
completeness onto storage truth so the ledger could become extension-private.

That redesign shipped with a documented **known trade-off**: uploads that complete while the app is
foreground (or backgrounded and never re-foregrounded) leave `completed` stale until the next
foreground re-LIST. This change closes that gap.

The two upload tiers matter here:
- **PhotoKit tier (iOS ≥26.1):** uploads run in a **separate extension process**; the app reads the
  shared ledger cross-process (WAL: one writer + concurrent readers).
- **App-driven tier (iOS 18–26):** the pump runs **in the main app process** (`BackgroundUploadPump`),
  so its ledger writes are in-process.

The load-bearing external constraint (owner-asserted): **storage is never reset or pruned while an
event is active.** Objects only ever accumulate during an active event.

## Goals / Non-Goals

**Goals:**
- The status line moves **live** while the app is foreground: the arrow pulses when work is in flight,
  goes static when work remains but nothing is uploading, and settles to "In sync" the instant a
  backup drains — with **no polling** and **no network** on the refresh path.
- A single source of sync truth for the upload direction: `completed` and `pending` both read from the
  extension's ledger in one `aggregates()` call.
- The status-line copy distinguishes "pending" (idle-but-incomplete) from "ongoing" (in-flight), and
  the arrow/LED colors unify on the theme primary.

**Non-Goals:**
- Sub-`process()`-run progress granularity (the `observed-completion-overlay`'s job — reading
  succeeded-but-unacknowledged upload jobs — stays deleted; per-run freshness is sufficient here).
- Any change to the **download** direction. Foreign-object completeness keeps its reconcile driven by
  the remote APNs silent push (`notify-driven-download`) + foreground.
- Refresh while backgrounded. The observer is foreground-only; a suspended app cannot act on a Darwin
  post, and it re-reads on the next foreground regardless.
- Full ledger privatisation / relocating any writer. The app remains a **read-only** ledger reader;
  the extension stays the sole `LedgerWriter`.

## Decisions

### D1 — `completed` is sourced from the ledger, not a storage LIST (Model B, reversing `ledger-free-status`)

`SyncProgress.completed` ← `aggregates().completed` (asset-counted: photos all of whose ledger rows are
`COMPLETED`, grouped by `assetId`), read in the **same round-trip** as `pending`. Classification
(`n >= total → COMPLETE`) reads this ledger-sourced `completed`. The app-side `CompletedAssetsSource`
(expected×present over `GET /devices/<id>/files`) is removed from the status path.

**Why (over C' — keep storage-truth, ding a re-LIST):** Under the no-deletion invariant the ledger and
storage carry the **same** completeness truth during an active event (they can only diverge under silent
storage loss, which the invariant excludes; (re)join is reconciled by `event-rejoin-reconciliation`).
Given equivalence, the ledger is the better single source: it is **local** (no GET on the refresh path,
offline-tolerant), it **ends the split** where `pending` already comes from the ledger but `completed`
did not, and both counts arrive from one consistent SQL read. `completed` = "the engine has uploaded
it"; `total` = gallery count — so undiscovered photos (no ledger row) keep `completed < total` →
`IN_PROGRESS`, with no false "In sync".

**Alternative considered — C' (storage-truth + ding a debounced re-LIST):** keeps the destination store
as the authority the app queries and preserves the app↔extension decoupling `ledger-free-status` bought,
at the cost of a network GET per ding and a second, parallel derivation of "synced". Rejected in favour
of the single-source-from-ledger model under the invariant; the coupling cost is accepted.

`:domain:status` stays **engine-free at compile time**: the composition root injects the read as a
`suspend`-returning value carrying both counts (the existing `InFlightSource` injection pattern,
widened from `Int` to both aggregates). The iOS read calls only `iosLedgerBackend().aggregates()` —
no write — so the single-writer invariant holds and `:app:ios` constructs no `LedgerWriter`.

### D2 — Extension → app liveness via a composition-root Darwin post, foreground-only observer

The **PhotoKit extension** posts a named cross-process Darwin notification (`CFNotificationCenter`
Darwin center) at the **end of every `process()` run**, unconditionally — after `cycle.run()`, in the
extension composition root (`UploadExtensionRoot`), **not** in `LedgerBackend` (the backend's "posts no
cross-process notification" contract is preserved). No payload; the promise is only "re-read the truth".

The **main app** registers an observer on scene `.active` and **unregisters on `.background`**. On each
post it re-reads the ledger aggregates and re-emits `SyncStatus`. Foreground-only because a suspended
app cannot act on the post, and the next foreground re-read is the backstop.

**Why unconditional (over gating on aggregate change):** the ding is a cheap "re-read the truth" signal;
a bounded local SQL read absorbs redundant posts (conflation-safe by construction), and firing on every
run captures both `pending` going up (jobs created) and down (drained) without the extension tracking
prior state.

### D3 — App-driven tier refreshes in-process (no Darwin)

For iOS 18–26 the pump runs in the app process, so after each pump cycle it invokes the **same**
in-process status refresh directly. No cross-process hop; the Darwin path is PhotoKit-tier only. Both
tiers thus converge on one refresh entry point (re-read aggregates → re-emit).

### D4 — Refresh reads only the ledger (no storage LIST on the live path)

The ding/pump refresh re-reads `aggregates()` only. `total` (gallery) already re-emits on
`photoLibraryDidChange`. No `GET` is issued on the refresh path. The download direction's reconcile is
untouched and keeps its own (push + foreground) network refresh.

### D5 — Status-line copy split, derived from the arrow levels

The `sync-status-screen` "Joined-layer health descriptor and status line" arrow derivation is unchanged
(upload arrow hidden when `completed >= total`, else pulsing when `pending > 0`, else static; download
symmetric on `inFlight`). Only the **label** changes, derived from the combined arrow state already
present in the status line: any arrow **pulsing** → `"Synchronization ongoing…"`; else (any arrow shown,
none pulsing) → `"Synchronization pending…"`; both hidden → `"In sync"`. The label is owned by the
`AppStatusLine` component (consistent with it already owning "Syncing…"/"In sync"); no new `UiState`
field is introduced — the arrow levels already encode the distinction.

### D6 — Arrow/LED colors on the theme primary

In `:domain:ui:components` (the Material 3 skin only), the static arrow tints **gray**
(`onSurfaceVariant`), the pulsing arrow tints **primary** (keeping the infinite fade), and the LED
indicator's green is remapped to **primary**. No color leaks into any `App*` signature — the mapping
stays skin-local.

## Risks / Trade-offs

- **[Re-couples app↔extension through the ledger]** → the exact coupling `ledger-free-status` removed
  returns. Accepted per single-source-of-truth. Kept as narrow as possible: read-only, aggregates-only,
  cross-process WAL read (already done for `pending`), injected so `:domain:status` stays engine-free.
- **["In sync" trusts the ledger, not the destination store]** → the one error class a backup app must
  refuse (false "In sync" → user trusts an incomplete backup). Mitigated by the **no-deletion invariant**
  (ledger cannot over-count during an active event) + `event-rejoin-reconciliation` (aligns ledger↔storage
  at the only divergence point). Residual exposure — a silent partial storage loss — is **excluded by
  the invariant**; if the invariant is ever violated it surfaces only at the next (re)join. This premise
  must be stated load-bearingly in `docs/design.md §2.4`.
- **[Ledger lag was the reason the overlay existed]** → the overlay masked *sub-run* lag (per-byte
  progress between coarse runs). This change dings **per run** and only needs *per-run* freshness for
  `completed`/"In sync", so the sub-run lag the overlay fought is out of scope. If finer progress is
  wanted later it is a separate concern.
- **[Darwin only helps while foreground]** → the live update lands only if the extension runs while the
  app is foreground (or, for the app-driven tier, whenever the in-process pump cycles). The common
  background case is unchanged (foreground re-read on return). This is inherent to the topology, not a
  regression.
- **[Partial revert churns two recently-archived changes]** → `ledger-free-status` +
  `dedup-files-device-manifests` (upload path). Handled by making the reversal **explicit** in the spec
  deltas and design so history stays coherent; the download-side listing and manifest infrastructure are
  untouched.
