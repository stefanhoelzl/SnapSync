# Proposal

## Why

An event admits at most 10 devices, which is too few for the events SnapSync is for: a wedding, a class
trip, a club weekend. The 10 was an initial value chosen in `add-event-limits` with no cost argument
behind it, and the storage-era caveat that came with it (overshoot under simultaneous joins) has been
gone since the database migration made the cap one exact statement. The cap is a technical bound of the
service, not a promise to users, yet the spec states the number as if it were one.

## What Changes

- Raise the device capacity of every **newly created** event from 10 to **40**. Events already created
  keep the capacity they were stamped with.
- The `event-lifetime` contract stops naming a number: an event admits a **limited number of devices
  set by the service**. What a user relies on stays: departed devices still count, a device that left
  rejoins in its own place, the limit holds exactly under simultaneous joins, and a full event is
  reported as full (`join-event`, unchanged).
- The legacy v1 notify route reads every member's push token in one statement instead of one statement
  per member, so it stays inside the backend's per-request budget at the new capacity (otherwise members
  of a 25+ device event would silently miss wakes from pre-0.4 builds).
- The 40, and the per-request ceiling that bounds it, are documented beside the policy value.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `event-lifetime`: the requirement "At most 10 devices ever take part, and leaving frees no place"
  becomes a number-free "A limited number of devices ever take part …", and the Purpose sentence
  naming 10 is rewritten (a hand-applied Purpose edit, `purpose-edits/event-lifetime.md`).

## Impact

- `deployments/components/policy.json` (`eventCapacity` 10 → 40).
- `api/src/routes/v1.ts` (notify's token read), `api/src/routes/v2.ts` (the fan-out timeout's
  "capacity is 10" rationale).
- The mock backend's default capacity (`BackendMock.DEFAULT_CAPACITY`), kept equal to the policy.
- `docs/architecture.md` and `docs/deployment.md`.
- No app change, no API shape change, no migration: capacity is already stamped per event at creation.
