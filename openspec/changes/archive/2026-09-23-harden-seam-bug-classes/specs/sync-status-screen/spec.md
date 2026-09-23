## ADDED Requirements

### Requirement: A non-idempotent command is in flight before it first suspends

A non-idempotent command SHALL be marked in flight synchronously, and its result SHALL apply only to the surface that started it.
A presentation intent that fires a command which is not idempotent — create, rename, the switch's leave —
SHALL move its surface into an in-flight state **before its first suspension point**, so a second tap
cannot fire the command again, and the controls that would fire or cancel it SHALL be disabled or absent
while it is in flight. When the command's result arrives, the intent SHALL apply it only if the state it
started from is still current; a result for a surface the member has since cancelled or left SHALL be
discarded. A terminal result (success or failure) SHALL NOT outlive the surface it belongs to.

#### Scenario: Create is double-tapped

- **WHEN** the member taps Create twice before the screen recomposes
- **THEN** one event is minted

#### Scenario: Cancel during a switch

- **WHEN** the member confirms a switch to another event and cancels while the leave is still running
- **THEN** the cancel stands: when the leave finishes, the join surface for the new event is not shown

#### Scenario: A rename finishes after the sheet was dismissed

- **WHEN** a rename's result arrives after the member dismissed the rename sheet
- **THEN** the next time the sheet opens it stays open and shows no stale result

### Requirement: Membership-scoped surface state belongs to the membership

Surface state of one membership SHALL NOT survive a change of membership.
State that describes a surface of one membership — whether the settings surface is open, a rename's
status — SHALL be keyed by that membership's event id or reset whenever the membership changes, in one
place. It SHALL NOT depend on each exit path (leave, switch, self-leave, config clear) remembering to clear
it.

#### Scenario: Settings were open when the membership changed

- **WHEN** the member opens Settings, then accepts an invite to another event and switches
- **THEN** the new membership opens on the status screen, not the settings surface
