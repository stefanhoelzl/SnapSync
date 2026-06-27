## Context

An event's membership is held in three independent stores (`event-rejoin-reconciliation` design):

| Store | Holds | Lifetime |
| --- | --- | --- |
| Keychain (`deeplink-config`) | the `eventId` | survives reinstall |
| App-Group `ledger.db` (`sync-ledger`) | the per-upload rows | wiped on reinstall / destructive migration |
| App-Group `NSUserDefaults` | the discovery cursor | wiped on reinstall |

Joining is already specified as the inverse-direction lifecycle: `JoinEvent` seeds the ledger from
storage and `SnapSyncRoot.reconcileThenEnable` gates the extension. The extension is the **single
`LedgerWriter`**; the app holds only the `LedgerBackend` and uses its **reset family** (`clear`,
`resetTo`) — never a writer. `resetTo(entries)` already exists (added by
`event-rejoin-reconciliation`) as an atomic delete-all-then-insert-all that signals `changes` once.

Leaving is the reverse traversal of that same lifecycle:

```
                         ┌──────────── onProvision (QR / deeplink) ────────────┐
                         │                                                      │
                         ▼                                                      │
   ┌────────┐   join   ┌─────────┐  fetch ok  ┌────────┐                        │
   │  Idle  │────────▶ │ Joining │──────────▶ │ Joined │◀───────────────────────┘
   │(no evt)│          └─────────┘            └────────┘
   └────────┘               │ fetch fail          │
        ▲                    ▼                     │
        │              ┌───────────┐               │
        │              │ JoinFailed│               │
        │              └───────────┘               │
        │                                          │
        └──────────────── LEAVE ◀──────────────────┘
            disable ext → resetTo([]) + clear cursor → ConfigStore.clear() → Idle
```

The leave sequence mirrors `reconcileThenEnable`'s **disable-first** discipline: with the extension
disabled there is never a concurrent ledger writer while the app resets the store. Leave needs **no
new `EventStatus`** (it returns to `Idle`) and — crucially — **no new `UiState`**: after
`ConfigStore.clear()`, `config == null`, and the existing `reduceFrom` already yields
`Setup(storageConnected = false)`. So the presentation layer gains only an intent, not a state.

## Goals / Non-Goals

**Goals:**
- A user can leave the configured event from within the app, returning to the setup gate.
- Leaving is **local-only**: already-uploaded objects in storage are untouched; re-scanning the same
  event re-joins and reconciles them back as `COMPLETED` (existing `JoinEvent` behavior).
- The leave orchestration is a **tested** use-case; `:app:ios` stays wiring-only (platform
  side-effects injected as lambdas, as `JoinEvent` already does).
- No new `UiState`, no reduction change — the dialog is local UI state, visibility is a function of
  the rendered state.

**Non-Goals:**
- Deleting the event's stored objects (no backend delete path; leave is local).
- A leave escape from `JoinFailed` (transient network state; recovery is re-scan / relaunch).
- A "leaving…" transient status or any progress for leave (it is fast and local).
- Cancellation of an in-flight join (made unnecessary by the joined-layer-only visibility).

## Decisions

### D1 — `leave-event` is its own capability (not folded into `event-rejoin-reconciliation`)

`event-rejoin-reconciliation` was itself carved out as the join half of the lifecycle; leave reads
cleanly as a parallel capability (disable-first ordering, local-only guarantee, joined-layer
visibility). `event-rejoin-reconciliation` takes only a clarifying note that a leave + re-scan
re-joins fresh. Alternative — extending the rejoin capability — keeps the whole membership lifecycle
in one spec but mislabels it ("rejoin" naming) and bloats its requirements; rejected.

### D2 — Visibility scoped to the joined layer kills the leave-during-join race

The screen is a four-layer progression:

```
   layer        UiState(s)                          Leave button?
   ─────────────────────────────────────────────────────────────
   loading   →  Loading                                 ✗
   gate      →  Setup(storage, permission)              ✗
   joining   →  Joining · JoinFailed                    ✗
   joined    →  InProgress · NothingToSync · Completed   ✓
```

If the button were shown in `Joining`, a confirmed leave could land its `resetTo([])` + `clear()`
**before** the in-flight `JoinEvent.runJoin` finishes its own `resetTo(seeds)` — leaving `config ==
null` but the ledger re-seeded with the old event's rows. Restricting the button to the joined layer
(where no join is in flight) removes the window entirely, so `LeaveEvent` needs no cancellation and
`JoinEvent` needs no config re-check. Cost: no leave from `JoinFailed`, accepted per Non-Goals.

### D3 — `ConfigStore.clear()` over a nullable `save()`

A new `suspend fun clear()` reads as the explicit inverse of `save()` and is a localized addition
(call sites and fakes implement one new method). Making `save(config: EventConfigPayload?)` accept
`null` would overload one verb with two meanings and ripple through every existing caller; rejected.
`KeychainConfigStore.clear()` reuses the existing `SecItemDelete` it already performs inside
`writeUrl`, then sets `state.value = null`. `clear()` when already absent is an idempotent no-op.

### D4 — Best-effort sequence, disable-first, no rollback

Order: **disable extension → `resetTo([])` + clear cursor → `ConfigStore.clear()` → `EventStatus =
Idle`**. Each step is logged on failure; there is no transaction across the Keychain, the App-Group
DB, and `NSUserDefaults` (three different stores — a true transaction is impossible). The sequence
is chosen so the worst partial state self-heals: if `clear()` fails after the wipe, `config` is
still present and an empty ledger, so the next launch's join gate simply re-joins (idempotent). The
operations involved (SQL delete, Keychain delete, defaults removal) are individually reliable, so
the partial-failure surface is small.

### D5 — `LeaveEvent` is pure; platform effects are injected lambdas

Following `JoinEvent(clearDiscoveryCursor: suspend () -> Unit)`, `LeaveEvent` takes both
`disableExtension: suspend () -> Unit` and `clearDiscoveryCursor: suspend () -> Unit` as lambdas
resolved in `SnapSyncRoot` (where `PHPhotoLibrary` and `NSUserDefaults` live). The use-case's tested
core is: call `disableExtension`, `ledger.resetTo(emptyList())`, `clearDiscoveryCursor`,
`config.clear()`, `status.set(Idle)`. `JoinEvent`'s in-memory session flags need no explicit reset —
with `config == null`, `ensureJoined()` short-circuits to `false`, and a later `onProvision` clears
them on the next scan.

### D6 — Dialog is local screen state; the intent is the only presentation addition

The confirm dialog's open/closed flag is `remember { mutableStateOf(false) }` inside `StatusScreen`
(the same pattern `MainViewController` uses for the transient invalid-link error). The button's
`onClick` opens it; Confirm calls the injected `onLeaveEvent` callback and dismisses; Cancel
dismisses. So no `UiState` variant, no `SetupEffect`, no reduction branch. `StatusContainerHost`
gains `onLeaveEvent()` delegating to an injected **`leave: suspend () -> Unit = {}` lambda** whose
default is a no-op (mirroring the `observed` / `eventStatusSource` defaults). The lambda — rather than
the `LeaveEvent` type — is what the container takes, because `:domain:presentation` is Compose-free
**with no engine dependency**; injecting `LeaveEvent` (which pulls `engine`/`gallery`) would breach
that boundary. `SnapSyncRoot` binds the lambda to `LeaveEvent::leave`. Presentation tests and the
desktop host keep the default, so they construct unchanged and the harness Confirm is inert.

### D7 — Flat icon-only Logout button + `AppConfirmDialog`, both semantic

The leave affordance is a flat (no-fill) icon-only button using Material
`Icons.AutoMirrored.Filled.Logout`, rendered through a new semantic `App*` component (emphasis is a
design-time choice, so it is a distinct component, per the `PrimaryButton` convention — not a
parameter). `ScreenLayout` gains an optional bottom-right action slot to host it without the screen
hardcoding geometry (the "semantic containers own arrangement" rule). `AppConfirmDialog(title,
confirmLabel, cancelLabel, onConfirm, onDismiss)` keeps the Material 3 dialog contained. The icon
artifact (`compose.materialIconsExtended`) is added to `:domain:ui:components` only — the import of
`Icons.*` never escapes the components module, so no `App*` signature carries a Material type.

## Risks / Trade-offs

- **No leave from `JoinFailed`** (D2). If an event's file-list fetch keeps failing, the only exits
  are re-scan or relaunch. Accepted: `JoinFailed` is a transient network state, and a persistent bad
  state is recoverable by reinstall. Revisitable by also showing the button in `JoinFailed` later
  (which would re-introduce the join-race question for that state only).
- **Three-store partial failure** (D4). Mitigated by ordering for self-heal and by the individual
  reliability of each delete; not eliminated. Logged for diagnosis.
- **`materialIconsExtended` footprint.** Pulls the extended icon artifact for one glyph. Accepted
  over hand-rolling an `ImageVector` (user preference for the canonical Material Logout glyph);
  contained to the components module.

## Migration Plan

Additive. `ConfigStore.clear()` is a new method on the seam — every implementer (Keychain adapter,
in-memory/fake stores) gains it; no existing call site changes. `StatusContainerHost`'s new
`LeaveEvent` parameter defaults to a no-op, so existing constructions compile unchanged.
`StatusScreen` gains an `onLeaveEvent` callback parameter (defaulted), so existing call sites and the
status-screen tests compile unchanged. No data migration.

## Open Questions

- None blocking. A future "delete remote objects on leave" would be a separate, backend-touching
  change (out of scope here).
