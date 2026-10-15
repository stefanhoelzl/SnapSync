# Proposal

## Why

What a member sees when the phone's protected stores cannot be read or written — a phone not yet unlocked since it
restarted, a phone restored without the event's key, a store that refuses a write — is promised by seven scenarios no
test claims, and three such outcomes have no scenario at all. The test channel cannot produce these states: its one
"locked" lever seals the databases alone (a phone no device can be in), and the Keychain and preferences faults exist
only when a mock is built. One of the uncovered outcomes is also wrong today: when the mobile-data choice cannot be
read, the menu shows it on while photos wait for Wi-Fi.

## What Changes

- **Spec:** three new scenarios — an unreadable mobile-data choice (`mobile-data`), a wake before the first unlock
  that does not make the phone a new member (`delivery`), and a join whose event key the phone cannot keep
  (`join-event`) — and one sentence added to the mobile-data requirement: a choice the phone cannot read is treated as
  off, on screen and for every transfer.
- **Fix — one mobile-data decision:** the menu and the transfers read the stored choice through one decision, so an
  unreadable choice shows off and holds photos to Wi-Fi.
- **Fix — no half-join on a refused key:** a join whose event key cannot be kept fails before the event enrols the
  device, so a failed join leaves nothing at the event.
- **Test levers** (the integration channel, no production surface):
  - a new `device` service with `lock()` / `unlock()` — a phone before its first unlock since restart, sealing every
    mocked protected store at once; it replaces the databases-only `databases.lock`, which is deleted;
  - `keychain.fail(item, kind, times?)` and a new `preferences.fail(item, kind, times?)`, each addressed by a named
    item the channel maps to the app's own address, each failure told as a world event.
- **Removal:** the process's "is protected data available" read, which nothing in the app has read since the entry
  points were introduced, with its contract clauses, device recording, mock and lever.
- **Tests:** one behaviour test per scenario — the six the brief listed, the existing unclaimed "A change that cannot
  be saved", and the three new ones.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mobile-data`: a choice the phone cannot read is treated as off — photos wait for an unrestricted Wi-Fi and the menu
  shows it off (requirement "The member chooses for the device whether photos may use mobile data", new scenario).
- `delivery`: a new scenario under "A locked phone delays uploads and never ends the membership" — a wake before the
  first unlock does not make the phone a new member.
- `join-event`: a new scenario under "Joining happens only on confirmation and needs a connection" — a join whose
  event key the phone cannot keep fails with Retry and leaves the device in no event.

## Impact

- `:domain:services` — `MobileDataSetting` (one decision); the preference keys gathered where the channel can map them.
- `:domain:feature` — `JoinEvent` keeps the key before enrolling.
- `:domain:ports` and the ProcessInfo adapters (`:adapter:ios:app-only`, `:adapter:android`) — `protectedDataAvailable`
  removed; `ProcessInfoContract` loses two clauses and the `LOCKED` recording.
- `:adapter:generic:mock` — the Keychain and preferences mocks' faults become operator state; the databases' and
  files' sealed state is driven by the device lock.
- `:test:levers`, `:test:rig`, `:test:control` — the `device` and `preferences` services, `keychain.fail`, new world
  events; `databases.lock` and `process-info.protectedData` removed.
- `:test:integration` — the behaviour tests; `CycleEntryGateIntegrationTest` moves to `device.lock()`.
- `docs/testing.md`, the `rig-channel` skill, the architecture diagrams and the coverage exceptions in the root build
  file.
- Phase ⑤ ("an update from an older install") touches the same preferences mock; a rebase may be needed.
