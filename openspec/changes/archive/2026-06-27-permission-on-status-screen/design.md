## Context

`StatusContainerHost.reduceFrom` currently treats the setup gate as absolute: whenever `config == null`
**or** `permission != GRANTED`, it returns `UiState.Setup`, shadowing every join/sync state
("regardless of the snapshot"). This means a user who has joined an event and is mid-backup, then
revokes photo access in system Settings, is bounced to the same cold two-card onboarding gate a
first-time user sees.

Relevant facts established while scoping this:
- The permission seam is **level-triggered and synchronous** — `PermissionStatusSource.permission`
  always holds the current truth, refreshed on app foreground (the Settings round-trip). So the
  reduction never has to guess permission state or show a placeholder for it.
- On revocation the **live gallery `total` collapses to 0** (PhotoKit `fetchAssets` returns 0 without
  access), while the **ledger counts survive** (local SQLite). This ruled out "show the frozen
  progress numbers" without extra caching — and the chosen UX (error-only hero) sidesteps it entirely.
- Engine and background-upload enablement already gate on `GRANTED` (`enableBackgroundUploadOnGrant`),
  so nothing operational depends on this change — it is display-only.

## Goals / Non-Goals

**Goals:**
- After an event is connected, surface a permission problem as an actionable error on the status
  screen, not as a fallback to the onboarding gate.
- Keep the reduction stateless: a function of the three current source values (config, permission,
  snapshot) with no event history.
- Ensure there is always a path to grant permission — including the case where the QR is scanned
  before permission was ever requested.

**Non-Goals:**
- No change to the permission seam contracts (`permission-gate`): three states, the two ports, full-
  library-only grant, and foreground liveness are all unchanged.
- No change to the engine, the ledger, background-upload scheduling, or join/reconciliation.
- No persistence of a "was joined" flag and no caching of the last-known gallery total.

## Decisions

### D1: The gate guard narrows to `config == null` only
The gate stops being a function of permission. It is shown **iff** no event is connected. Permission
state, when config is present, is handled by the status screen instead.

- *Why:* it cleanly separates the two concerns — "no storage to back up to" (gate) vs. "we have an
  event but can't read photos" (a running-screen error). It also makes the reduction a strict
  precedence with no overlap.
- *Alternative considered (rejected):* keep `permission != GRANTED` in the guard but carve out a
  "was joined" exception using the ledger (`completed + pending > 0`) to distinguish revoked-after-join
  from denied-at-first-prompt. This works but is stateful-feeling, needs the snapshot to be `Ready`
  before deciding (a Loading-window edge), and is strictly more complex than D1 for no UX gain once
  permission moves onto the status screen wholesale.

### D2: One new `UiState` variant carrying the non-granted status
`data class PermissionBlocked(val permission: PermissionStatus)`, where `permission ∈ {NOT_DETERMINED,
DENIED}`. The screen switches on it exactly like the gate's existing permission card does.

- *Why one parameterized state over two data objects:* mirrors `SetupGate`'s `when (state.permission)`
  block, keeps the reduction trivial (`PermissionBlocked(permission)`), and avoids an
  `IllegalState` for the unreachable `GRANTED` arm (it is simply never constructed).
- *Rendering* (in `StatusScreen`, a `StatusHero` + `PrimaryButton` in the column — no new design-system
  component; both are existing `App*`):
  - `NOT_DETERMINED` → `StatusIndicator.Photos`, "Allow photo access", "SnapSync needs your photo
    library to back it up.", **Allow access** → `onRequestPermission`.
  - `DENIED` → `StatusIndicator.Error`, "Photo access turned off", "SnapSync needs photo access to
    continue backing up your library.", **Open Settings** → `onOpenSettings`.

### D3: Error-only hero — no progress counts while blocked
The blocked hero shows no "n of N". This honours the live-`total`-collapses-to-0 reality without
caching or persisting the last total, and keeps `PermissionBlocked` free of sync payload.

- *Alternative considered (rejected):* freeze/persist the last-known total to keep "n of N" visible.
  Rejected for the added state and the relaunch-while-denied gap (no in-memory cache survives).

### D4: `NOT_DETERMINED + config` gets a status-screen priming CTA
Because the only permission request site used to be the gate's "Allow access" button, removing
permission from the gate would orphan the first-grant path for anyone who scans the QR first. D2's
`NOT_DETERMINED` rendering restores it on the status screen, preserving the existing CTA-only,
no-auto-request rule.

### D5: Resulting reduction precedence
```
config == null            → UiState.Setup
permission != GRANTED      → UiState.PermissionBlocked(permission)
else                       → existing chain (Joining / JoinFailed / Loading / InProgress / …)
```
`UiState.Loading` is now reachable only under config-present + `GRANTED` (unchanged intent); the old
clause "any non-GRANTED permission short-circuits to the gate" is replaced by the `PermissionBlocked`
branch above.

## Risks / Trade-offs

- **`UiState.Setup.storageConnected` becomes vestigial** (always `false`, since config-present means
  the gate is gone). → Keep the field for now to minimise churn; an optional follow-up can drop it.
  The storage card simply always renders its pending "scan your QR" state.
- **Behaviour change visible to the user mid-session.** A revoke now keeps them on the status screen
  with an error rather than the gate. → This is the intended change; covered by integration + UI tests.
- **Spec contract widens what the status screen may render** (it now hosts a permission CTA). → Both
  `setup-gate` and `sync-status-screen` deltas are updated together so the precedence stays
  single-sourced and consistent.
