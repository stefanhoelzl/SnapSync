# Proposal

## Why

`event-lifetime` says an event's open-until date is its end unless the event was created after its end, and only
then the date the host chose. The backend has never held that rule: since `changes/back-dated-events` (D1) it
bounds only the span — at least the end, at most 30 days after the later of the end and the creation — and leaves
the 1/7/14/30 menu, and the question of when to ask, to the app. So the contract claims a promise to guests that
nothing enforces, and a host who creates an event that has not yet ended can, outside the app, keep it open for up
to 30 days past its end. An audit of the spec against the backend found the gap.

Enforcing the spec's rule on the server would add a refusal that a phone whose clock runs ahead can hit, and
would protect guests against a choice the design already gives the host — "the host decides how long data is
kept" was the reason D1 stores a chosen date at all. The bound that protects guests, at most 30 days of being
kept open and deletion no later than 30 days after that, holds either way. So the contract moves to what the
system does, rather than the system to what the contract claimed.

## What Changes

- **The open-until date is the host's, within a bound, for every event.** It is the end of the range unless the
  host chose a later day at creation; a chosen day is never before the end and never more than 30 days after the
  later of the end and the moment of creation. The open-until date stays fixed at creation.
- **The app does not change.** It still asks only when creating an event that is already over (`create-event`'s
  sheet); an event created before its end keeps its end as its open-until date in every app build. Only a host
  creating an event some other way than the app's create screen can choose a later day for an event still
  running.
- **The retention promise is restated, not weakened.** An event is still kept open at most 30 days past the later
  of its end and its creation, and deleted no later than 30 days after its open-until date — the bound a
  back-dated event already had.
- **No backend, mock or app code changes.** The backend and its mock already behave as the new wording says.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-lifetime`: the requirement fixing the open-until date ("An event created after its end stays open as long
  as the host chose") becomes the general rule — the host's chosen day within the bound, else the end — and is
  renamed accordingly; "The date range closes nothing", which cites it by name, follows the rename; the Purpose's
  description of the open-until date is reworded to match.

## Impact

- `openspec/specs/event-lifetime/spec.md` only. No other capability states the rule: `create-event` owns when the
  app asks (unchanged), `sync-status` shows whatever open-until date the event has (unchanged).
- Prose that restates the old rule outside the specs: the mission summary in `openspec/config.yaml`'s `context:`
  and in `CLAUDE.md` ("its end, or, for an event created after its end, the 1/7/14/30 days the host chose").
- No code, no wire change, no app release; `internal` changelog label.
