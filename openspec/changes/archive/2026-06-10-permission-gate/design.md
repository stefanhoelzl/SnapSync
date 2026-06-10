# Design: permission-gate

## Context

The status screen renders six sync states from `UiState`, reduced in `StatusContainerHost` from a `Flow<SyncStatus>` seam. The container today only observes — it has no intents — and its initial state is a hardcoded `NeverSynced` guess. The desktop harness forges snapshots through `PanelController` (one `MutableStateFlow`, seven preset methods). Backup is always-on: permission is the only user-facing switch, so the gate is the last v1 screen surface. Design locked via interview + explore session on 2026-06-10.

## Goals / Non-Goals

**Goals:**

- Permission contracts the slice-2 engine can reuse (skip pass when not granted).
- The status screen never renders a state that wasn't derived from real source values (kills the cold-start guess).
- Gate flows fully walkable in the desktop harness: ask→granted, ask→denied, denied→settings→granted, revoked-and-restored.

**Non-Goals:**

- iOS `PHPhotoLibrary` adapter (later slice; contracts only now).
- Limited-library support ("full access required" is the v1 product decision).
- Manual sync trigger, enable toggle (dropped from v1 entirely).
- Restricted-device UX (folded into Denied; ⚠️ accepted dead-end CTA).
- Loading/in-flight UI states (made unnecessary by the StateFlow seam and fire-and-forget commands).

## Decisions

### 1. Three-state `PermissionStatus`; full access only

`NOT_DETERMINED` / `DENIED` / `GRANTED`. iOS mapping (future adapter): `.notDetermined`→NOT_DETERMINED; `.denied`, `.restricted`, **`.limited`**→DENIED; `.authorized`→GRANTED. Limited-as-denied was chosen over limited-as-granted because a limited grant would render "Sync complete" over an unsynced library — a data-loss lie; the Denied gate's Settings CTA is exactly where limited becomes full. Alternative (five-state fidelity) rejected: Restricted and Limited UX is real design work with no v1 consumer; `when` exhaustiveness over the enum makes adding states a compiler-guided change later.

### 2. Seams are `StateFlow` (state holders, not event streams)

`SyncStatusSource.status` and `PermissionStatusSource.permission` become `StateFlow`. Both seams are already documented as level-triggered whole-truth snapshots; `StateFlow` is that contract in the type system. The container computes its **initial state from `source.value`s at construction** instead of guessing — no first-frame lie, no Loading state, no gate flash on granted devices. Constraint accepted: implementations must know the truth synchronously at construction (iOS permission read is sync; the engine must read its bookkeeping store before container construction — composition-root ordering: read store → construct sources → construct container).

### 3. Command/state ports are separate interfaces, CQS-strict

`PermissionRequester` (`fun request()`, `fun openSettings()`) returns nothing and never suspends: truth arrives only via `PermissionStatusSource`. A `request(): PermissionStatus` return was rejected — permission also changes without a request (Settings), so the flow path must exist, and a second reduce path invites ordering bugs. No in-flight guard needed: duplicate `request()` is harmless on iOS and idempotent in the fake. Every implementation (harness `PanelController`, future iOS adapter) is one object implementing both interfaces; they stay separate so the slice-2 engine can depend on the source alone.

### 4. Gate replaces the hero; UiState extended

`UiState` gains `PermissionAsk` and `PermissionDenied`; reduction precedence is permission-first (any non-granted permission wins over any sync snapshot — without permission, sync status is stale noise). One screen, no navigation, same single-state testing story. Copy locked:

| State | Indicator | Headline | Detail | CTA |
|---|---|---|---|---|
| PermissionAsk | Photos (neutral) | "Sync your photos" | "SnapSync needs access to your photo library" | `PrimaryButton("Allow access")` → `onRequestPermission` |
| PermissionDenied | Error | "Photo access denied" | "Turn on photo access in system settings" | `PrimaryButton("Open Settings")` → `onOpenSettings` |

CTA-only priming: the system dialog fires only from the button, never automatically on launch (preserves the one-shot system ask for a primed user). Known copy blemish, accepted: a limited-library user reads "denied" after granting partially; the action line is correct for them.

### 5. Design system: `PrimaryButton` + `StatusIndicator.Photos`

Distinct `PrimaryButton(label, onClick)` component per the existing "design-time variants are distinct components" rule (no emphasis enum; `SecondaryButton` arrives with its first caller). `StatusIndicator.Photos` added so the first-launch ask isn't styled as a fault (Warning/Waiting both misstate it). The gate is StatusHero + PrimaryButton arranged by the screen — no bespoke gate component, no action slot widening StatusHero's API for one caller.

### 6. Harness: asymmetric preset writes, armed requester

`PanelController` holds two state cells (permission, sync) plus an armed-outcome knob; still the single mutation path. Panel groups:

- **Permission** (NotDetermined / Denied / Granted): write the permission cell only — the sync cell is invisible behind the gate, and leaving it untouched enables the revoked-and-restored walk (forge Failed → Denied → grant → Failed hero re-emerges).
- **Sync** (existing seven): write the sync cell **and force permission to Granted** — a preset's intent is "show me this screen", which is impossible while gated.
- **Next request →** (grants / denies): read by the fake `PermissionRequester.request()`; `openSettings()` logs only — the user plays "the user in Settings" via the Permission buttons.

No "current permission" readout in the panel: the phone frame already shows the truth.

## Risks / Trade-offs

- [Restricted devices get a dead-end "Open Settings" CTA] → ⚠️ accepted; revisit only if a restricted-device report ever appears.
- [`StateFlow` seam forbids sources that can't know their value synchronously] → accepted; both target platforms have synchronous reads here, and the constraint is the same whole-truth discipline the seam already claims.
- [Sync presets silently writing two cells could surprise a panel user] → mitigated by visible grouping/labels; the alternative (dead buttons while gated) is worse.
- [Spec amendment touches merged `sync-status-screen` scenarios that say `Flow`] → delta spec explicitly modifies that requirement rather than only adding new ones.

## Open Questions

None — all branches resolved in the 2026-06-10 interview/explore session.
