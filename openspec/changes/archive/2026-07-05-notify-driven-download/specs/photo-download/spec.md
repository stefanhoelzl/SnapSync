## REMOVED Requirements

### Requirement: Foreground-only discovery of later additions

**Reason**: Discovery is no longer foreground-only — a silent push for the active event now triggers
background discovery (capability `push-registration` / `event-notify-endpoint`). Superseded by
"Event-driven discovery of later additions".

**Migration**: The new requirement is a strict superset: both prior scenarios (foreground pickup of
later additions, initial-join transfers completing in background) are preserved, and push-triggered
background discovery is added. No client that relied on foreground pickup breaks — that path still
exists; a push is simply an additional, earlier trigger.

## ADDED Requirements

### Requirement: Event-driven discovery of later additions

The client SHALL re-read the union on join/(re)provision, on foreground entry, **and** when it receives
a silent push for its **active event** (capability `push-registration`). It SHALL NOT run a background
**poll** of the union (no timer, no periodic background fetch); background discovery is **event-driven**
(woken by a push), not polled. Assets contributed by others **after** the initial read SHALL be
discovered on the next of: foreground entry, or a silent push for the active event. A push whose event
is **not** the active event SHALL NOT trigger discovery (the active-event guard lives in the receive
seam, capability `push-registration`). Transfers and imports already enqueued SHALL continue in the
background regardless of foreground state. Because push delivery is best-effort (OS-throttled and
coalesced), foreground entry remains the standing backstop, so no asset is lost — only, at worst,
delayed to the next foreground visit.

#### Scenario: A push for the active event triggers background discovery

- **WHEN** another contributor adds photos and a silent push for this device's active event arrives
  while the app is not foregrounded
- **THEN** the client reconciles in the background — reading the union, enqueueing the new foreign
  resources' downloads, and importing any already-staged asset — without a foreground visit

#### Scenario: Later-added foreign photos still appear on next foreground

- **WHEN** another contributor adds photos while this app is not foregrounded and no push is delivered
  (throttled/coalesced/dropped)
- **THEN** those photos are discovered and enqueued on the next foreground entry (the backstop)

#### Scenario: No background poll

- **WHEN** the app is backgrounded and no silent push arrives
- **THEN** the client runs no periodic union poll; discovery happens only on a push or the next
  foreground entry

#### Scenario: Initial-join transfers complete in background

- **WHEN** the app reads the union on join and is then backgrounded
- **THEN** the enqueued downloads and imports complete in the background without reopening the app
