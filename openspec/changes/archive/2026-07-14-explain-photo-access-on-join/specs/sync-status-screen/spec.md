## REMOVED Requirements

### Requirement: Status screen renders UI state

**Reason**: Dead text. It describes a state space (`Loading`, `Joining`, `JoinFailed`, `InProgress`,
`NothingToSync`, `Completed`) that was **removed** by `2026-06-27-permission-on-status-screen` and no
longer exists in `UiState`. The same spec's live requirement — "Sync status snapshots reduce to UI
state" — already says so explicitly ("The prior joined states `InProgress`, `Completed`,
`NothingToSync`, the permission state `PermissionBlocked`, and the standalone `Loading` state are
**removed**"), so the spec has been contradicting itself since that change archived. This change adds a
third permission surface; leaving the contradiction would leave the repo describing three permission
surfaces, two of them fiction.

**Migration**: None — no code implements this. The live contract for what the status screen renders is
carried by the two surviving requirements in this spec: "Sync status snapshots reduce to UI state"
(the `CreateEvent` / `CreatingEvent` / `JoiningEvent` / `Joined` families and the reduction rungs) and
"Joined-layer health descriptor and status line" (the one-line `SyncHealth` status and its rendering).

### Requirement: Status screen renders permission-blocked states

**Reason**: Dead text. It describes a hero-replacing `UiState.PermissionBlocked` gate — a `StatusHero`
plus a single `PrimaryButton` — that was **removed** by `2026-06-27-permission-on-status-screen` and
replaced by the inline `NeedsAccess` status-line affordance on the joined layer. No such state or screen
exists in code. `permission-gate` records the replacement directly: "permission is no longer a
hero-replacing gate; the joined layer (name, QR, share, leave) renders regardless of permission."

**Migration**: None — no code implements this. Its two still-live rules are **re-homed, not dropped**:

- **CTA-only priming** ("the system permission dialog SHALL fire only from the button; the screen MUST
  NOT auto-request on observing `NOT_DETERMINED`") is carried forward by `join-event`'s new requirement
  "The join gate explains photo access before the first system dialog", which states it for the
  explainer and forbids any other join-gate phase from raising the dialog.
- **The no-"backup"-framing copy rule** ("the detail copy SHALL use sync/share framing — it MUST NOT
  describe the app's function as 'backing up' the user's library") is carried forward by the same new
  `join-event` requirement, and continues to hold independently in `event-creation-ui`.

The permission affordance that *does* exist — the tappable `NeedsAccess` status line, requesting on
`NOT_DETERMINED` and opening Settings on `DENIED` — remains specified by this spec's surviving
requirement "Joined-layer health descriptor and status line" and by `permission-gate`. This change does
not alter it.
