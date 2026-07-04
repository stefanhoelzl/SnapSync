## Context

Today's `StatusScreen` is a precedence ladder inherited from the personal-backup era:

```
config == null ──────────────→ Create layer
  else permission ≠ GRANTED ──→ PermissionBlocked   (full-screen; no QR, no counts)
    else SyncStatus.Loading ──→ Loading
         Ready ─────────────→ InProgress / Completed / NothingToSync   ("n of N images synced")
```

The product is now **event photo sharing**, not personal backup. The invite is the point; the user
wants to (a) share it the moment they open the app, and (b) glance and know it's healthy. Permission
as a hero-replacing gate actively hides the invite (which needs no photo access), and raw counts are
noise for a "is it working?" read.

The backend already exposes everything needed: `GET /event/:id` returns `{eventId, name, createdAt}`,
and `POST /event` returns the name on create. No backend work is in scope. The visual target is the
approved mockup (green identity, QR-hero event home, flat one-line status, dark theme with a
light-card QR).

Constraints carried from `docs/design.md`: `App*` components are semantic (no M3 types in signatures);
`UiState` is Compose-free; status is a level-triggered snapshot; the extension is the sole ledger
writer and the app the sole download-store writer; every logic test runs on JVM **and**
`iosSimulatorArm64`.

## Goals / Non-Goals

**Goals:**
- Collapse the joined experience into a single **config-present** layer: name · QR · one-line status ·
  share · leave — always rendered once an event is configured.
- Make permission an **inline, tap-to-act status-line state**, not a gate; sharing works with no access.
- Replace counts with a single honest health line, with two **symmetric** direction arrows (shown by
  completeness, pulse by live activity).
- Show the **event name** to host and guests, fetched by id (not carried in the QR).
- Split the overloaded config type into a deeplink payload vs. persisted config.
- Land the green skin, flat status line/icons, and dark theme.

**Non-Goals:**
- In-app QR scanner (joining stays native-Camera → deeplink).
- Date-scoped upload (stays whole-library; `startDate` remains deferred).
- Multi-event membership.
- The deferred "waiting for the system" staleness state (see Decision 2).
- Any backend change — `GET /event/:id` and `POST /event` already exist.
- New upload/download orchestration — only a **read** signal is added on the download side.

## Decisions

### Decision 1 — The joined layer is gated on config-present; permission folds into the status line

The reduction becomes:

```
config == null ──→ Create layer (CreateEvent / CreatingEvent)   ← unchanged
  else ──────────→ Joined layer (always): name · QR · share · leave
                     status line =
                       permission ≠ GRANTED → NeedsAccess   ("⚠ Turn on photo access ›")
                       SyncStatus.Loading    → a neutral first frame
                       work remaining        → Syncing(up, down)
                       all settled           → InSync
```

`UiState` collapses to: `Loading`, `CreateEvent(error?)`, `CreatingEvent`, and a single **`Joined`**
carrying a health descriptor. `PermissionBlocked`, `InProgress`, `Completed`, and `NothingToSync` are
removed. The invite URL and event name ride alongside as screen params (the existing `inviteUrl`
pattern), so they don't enter the reduction.

*Why:* the old ladder made permission mutually exclusive with the invite. Keying invite/share/leave
off `config-present` — and demoting permission to a status-line mood — is what lets a host share
instantly and lets the screen answer "healthy?" in one glance. *Alternative rejected:* keep
`PermissionBlocked` but add the QR to it — this special-cases permission everywhere and keeps two
"joined-ish" shapes.

### Decision 2 — Permission is the sole attention state (Q1)

The status projection's only operational signal is permission: `sync-status` sets
`active = (permission == GRANTED)`, and there is no "extension enabled", network, or "OS idle" input.
A standalone `✗ Not syncing` would therefore never fire on its own — every non-permission scenario is
either `Syncing…` (work remaining) or `In sync` (settled). So there is **one** attention state,
rendered as the honest, actionable `⚠ Turn on photo access ›`.

*Alternative rejected:* build the deferred `currentChangeToken`-mismatch "waiting for the system"
detection now to justify a distinct neutral state — real scope, and it reads as a flavor of
`Syncing…` anyway. Left deferred.

### Decision 3 — Status line: shown by completeness, pulse by live activity (Q2)

```
✓ In sync   ⇔  completed == total  AND  downloaded == total   (no arrows)
↑↓ Syncing… ⇔  otherwise (any work remaining)
   ↑ shown  ⇔ completed  < total      ↑ pulse ⇔ pending  > 0    (ledger REQUESTED, existing)
   ↓ shown  ⇔ downloaded < total      ↓ pulse ⇔ inFlight > 0    (NEW download signal)
```

"Shown" tracks completeness so the screen never lies about whether everything is up/received — and
because `total` is the live gallery size, snapping a photo flips the screen to `Syncing…` instantly.
"Pulse" tracks genuine in-flight motion, so a queued-but-OS-idle photo shows a **static** arrow rather
than faking movement. The direction derivation lives entirely in presentation.

*Alternative rejected:* activity-only (`Syncing…` only while bytes move) — flashes a false "In sync"
over a queued backlog, breaking the trust the screen exists to provide.

### Decision 4 — Download in-flight signal mirrors the upload ledger (Q2 tail)

The upload arrow has both signals already (`completed<total`, `pending`); the download arrow lacked a
live-activity signal. We add one that mirrors the ledger's `REQUESTED → COMPLETED` shape:

```
UPLOAD    ledger:   REQUESTED ───────────────▶ COMPLETED      pending  = REQUESTED
DOWNLOAD  store:    ENQUEUED ──▶ STAGED ──▶ IMPORTED           inFlight = ENQUEUED-not-staged
```

The app-written `DownloadStore` records a per-resource **enqueued** marker when a download task is
sent to the OS (superseded at staged) and exposes `inFlightCount()`. `DownloadProgress` gains
`inFlight: Int`, populated by `StoreDownloadStatusSource` on foreground refresh — the exact analogue
of `SyncProgress.pending` (display-only, foreground-driven). Single-writer invariant preserved (the
app writes the store; the extension still only reads the suppression projection).

*Alternatives rejected:* (a) never pulse `↓` — a pure-download phase would show a dead static arrow;
(b) query the background `URLSession` for live tasks — async, fuzzy, and not `commonMain`-testable;
the store marker is deterministic and testable.

### Decision 5 — Event name by fetch, split config types (Q3)

Split the overloaded `EventConfigPayload`:

```
EventLinkPayload { eventId }              EventConfig { eventId, name? }
  ── QR / deeplink wire format              ── persisted joined-event state
  ── encode/decode (v=3, unchanged)         ── ConfigStore.save / ConfigSource
                                            ── name from GET /event/:id (scan) or POST /event (create)
```

The name is **not** in the QR (keeps it minimal and lets the name stay server-authoritative). The
**create** path already has the name from `POST /event` and saves it directly — no fetch. The **scan**
path saves `EventConfig(eventId, name = null)` immediately (joining never blocks on a cosmetic name),
then a best-effort `GET /event/:id` fills the name, refreshed on foreground reconcile like the download
counts. `inviteUrl` derives from `encodeConfigUrl(EventLinkPayload(config.eventId))`.

*Alternative rejected:* bolt `name` onto `EventConfigPayload` — conflates "what the QR carries" with
"what we fetched after joining", and invites the question "why doesn't the decoder set name?".

### Decision 6 — Visual system: green skin, flat status line, dark-theme QR on a light card

Green replaces the interim red as the brand accent; the destructive **Leave** confirm stays red by iOS
convention. The status line is flat text except the attention state, which carries a soft background.
Share and Leave are flat icon buttons. In dark theme the QR stays a **light "pass" card**
(dark-on-light) — inverted (light-on-dark) QR was prototyped and **rejected: it does not scan
reliably**. New semantic `App*` components: a status-line component (arrows + label, no exposed
appearance params) and flat icon buttons; the QR library and Material 3 stay contained in
`:domain:ui:components`.

## Risks / Trade-offs

- **`UiState` collapse is a breaking reshape** touching reducers, both harnesses, and Compose tests →
  Mitigation: the reduction stays a pure function of the latest snapshot + permission + config; port
  the existing reducer/UI tests to the new shape in the same change; harness presets updated alongside.
- **Static-but-shown arrow could read as "stuck"** (work remains, OS idle) → Mitigation: it's the same
  visual vocabulary as any queued-upload UI, and pairs with the honest `Syncing…` label; uploads and
  downloads usually overlap at an event, so the pair pulses together much of the time.
- **Best-effort name fetch can leave a null title** (offline scan) → Mitigation: `name` is nullable;
  the screen renders a neutral title until a foreground refresh fills it; sharing/sync are unaffected.
- **Download `enqueued` marker adds store state that must be cleared correctly** (at staged, and on
  leave/switch cancel) → Mitigation: model it as a per-resource flag superseded by the existing
  `markStaged`, and cleared by the existing leave/switch non-terminal-row drop; cover with
  `DownloadStore` contract tests.
- **Losing counts removes a debugging affordance** for the operator → Mitigation: the desktop world
  harness still exposes raw counts/ledger; the product screen intentionally does not.

## Migration Plan

Single change, shipped whole (no phased rollout — TestFlight app, no feature flag). Sequence within
the change: (1) config type split + name fetch; (2) `DownloadProgress.inFlight` + store marker;
(3) `UiState` collapse + reduction + screen params; (4) event-home re-layout + status-line component +
green/dark skin; (5) update both harnesses; (6) port tests. Rollback is a straight revert of the
branch (persisted `EventConfig` gains a nullable field; a downgrade reading the old shape is not a
concern for a single-user TestFlight install).

## Open Questions

- None blocking. The neutral **Loading** first-frame copy for the joined status line (before the first
  snapshot) can be finalized during implementation (candidate: no arrows, a quiet "Checking…" or an
  empty status slot).
