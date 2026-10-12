# Proposal

## Why

An event admits at most 40 devices. That number was derived in `raise-event-capacity` from one assumption: a
new photo wakes every other member, each wake is an Edge subrequest, and bunny refuses the 51st. The assumption
was never measured, and bunny's own limits page describes the 50 as tolerated in occasional spikes and policed
only when a script exceeds it consistently — so the cap is guarding the wrong thing. What grows with the device
count is the wake fan-out, and that can be bounded directly. Once it is, nothing that breaks depends on the
number of devices, and the cap only turns guests away.

## What Changes

- **A new photo, a manifest publish that makes a photo available, and the event's close wake at most 40 of the
  other members**, picked at random each time — one push more than a full 40-device event sends today, so the
  per-request cost stays inside the budget it was sized for. An event of 41 or fewer devices behaves exactly as
  today. Members
  not woken catch up as they already do when a wake is lost: a later wake, their own next upload, the hourly
  background wake, or opening the app.
- **BREAKING (backend) — no device limit.** Every event, including those already created, admits any number of
  devices. The join never answers "full".
- **The app's "event is full" path is removed**: the join screen's full state, its strings (English and German),
  and the refusal's handling through the join flow.
- **The per-event capacity is removed** from the deployment policy, the join, the mock backend and the
  backend contract. Two remnants stay as recorded shims (`PENDING_CLEANUPS.md`):
  - released app builds decode the event description with `capacity` as a required field, so the backend keeps
    sending a fixed placeholder until the version floor passes the first release that no longer requires it;
  - the store's `capacity` column stays, written with that placeholder and read by nothing, so a rollback to
    the previous bundle still joins and creates. A later deploy drops it.
- **The "paid events" named future is dropped** from `openspec/config.yaml` and the CLAUDE.md mission:
  monetisation is undecided, and device count is no longer a lever for it.
- The verification probe the interview considered (a throwaway Edge Script measuring the 50) is **not run**:
  with the fan-out bounded, its result decides nothing.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `event-lifetime`: the requirement "A limited number of devices ever take part, and leaving frees no place" is
  removed (rejoining until the close is already `manage-membership`'s), "Only the name changes after creation"
  stops naming a device limit, and the Purpose's sentence about the limit goes (a hand-applied Purpose edit,
  `purpose-edits/event-lifetime.md`).
- `join-event`: the requirement "A full event is reported as full" is removed.
- `delivery`: "New photos are announced by a silent wake, and never only by it" says a new photo and the close
  wake some or all of the members; the scenario "The close wakes every member once" becomes a bounded one.
- `invite-link`: "Whoever holds the invite can join and see everything" stops naming the device limit.

## Impact

- **api/**: `deployments/components/policy.json` and `src/config.ts` (`eventCapacity` removed); `src/db.ts`
  (`ENROLL` loses its capacity subquery, `insertEvent` writes the placeholder, `pushTokensForEvent` gains the
  random bound); no migration; `src/routes/shared.ts` (create/lookup answer the
  placeholder), `src/routes/support.ts` and `src/routes/v2.ts` (the fan-out constant and comments; the
  per-completed-event loop flattened); `openapi.yaml` (`joinEvent`'s `409` removed, `Event.capacity` no longer
  required and deprecated); api tests.
- **App**: the generated `joinEvent` reply loses `Full`, which the compiler then walks through
  `services/backend/Membership.kt`, `model/JoinResult.kt`, `feature/membership/JoinEvent.kt`,
  `presentation/StatusContainerHost.kt`, `ui/state/UiState.kt`, `ui/screens/JoinFlowScreens.kt` and their tests;
  `event_full_*` strings in both locales.
- **Mocks and contracts**: `BackendMock`'s capacity, `JoinOutcome.FULL` and its `MockState` field;
  `BackendState.EVENT_FULL` and its membership clause in `:test:contracts`, plus both bindings.
- **Docs**: `docs/deployment.md` (policy table, "Edge Scripting limits worth knowing"), `docs/architecture.md`
  (events table, enrolment gate), `CLAUDE.md` and `openspec/config.yaml` (mission's named futures),
  `PENDING_CLEANUPS.md` (the placeholder).
- No app behaviour changes for events of 41 devices or fewer; released builds keep working against the new
  backend (they never see a `409`, and still receive `capacity`).
