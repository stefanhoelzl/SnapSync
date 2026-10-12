# Design

## Context

See proposal.md — Why. The current shape, from `changes/archive/2026-10-05-raise-event-capacity`:

- **Capacity** is stamped on each `events` row at creation from `policy.json`'s `eventCapacity` (40) and
  enforced by `ENROLL` (`api/src/db.ts`), one conditional insert that is exact under concurrency. A refused join
  answers `409 event full`, which the app maps through `JoinEventReply.Full` → `JoinResult.EVENT_FULL` →
  `JoinOutcome.EventFull` → the join screen's full state.
- **The wake fan-out** is `notifyMembers` (`api/src/routes/support.ts`). It is called for a byte that completes an
  asset, for a publish that makes one fetchable, and for the close. It reads every present member's push token in
  one statement (`pushTokensForEvent`) and sends one silent push per token, in parallel, under a 4 s timeout.
  Each push is an Edge subrequest. The byte route costs about N + 5 subrequests in total: the storage PUT, three or
  four statements, the token query and, sometimes, an FCM OAuth fetch. The publish costs one more.
- **The 50-subrequest limit** comes from bunny's limits page (`docs.bunny.net/scripting/limits`, read
  2026-10-12). That page also says: "Occasional spikes above a limit are tolerated. A script that consistently
  hits or exceeds one will be throttled or terminated." The record behind the cap assumed that a subrequest past
  50 fails as a fetch error. That was never measured.
- **The byte route's per-event loop** (`v2.ts`, `for (const id of completed)`) runs at most once:
  `eventsCompletedBy` filters on the request's own `event_id`, because bytes are stored per event.
- **Released apps** decode `Event` (create, lookup) with `capacity` declared `required` in `api/openapi.yaml`.
  Fabrikt makes that a non-null property, so a response without it fails to decode. Newer builds ignore
  unknown keys (`WireJson`).
- **Everything else that grows with N** was surveyed in the interview (a summary in this change's history).
  The union read grows with the event's total photos, not with its devices. Since `incremental-union` it signs
  nothing and costs about 0.2 KB per asset, compressed by the pull zone, and wakes read only deltas.

## Goals / Non-Goals

**Goals:**
- The per-request subrequest cost of every fan-out stays within one push of what a full 40-device event costs today,
  whatever the number of members.
- No device limit on any event, existing ones included, with no window in which a rollback breaks join or
  create.
- Released builds keep working, unchanged.

**Non-Goals:**
- Measuring bunny's subrequest behaviour (the probe considered in the interview). It no longer gates anything.
- A photo limit per event, or paginating the union. Photo volume is the next real ceiling (Risks), and it is
  unmeasured.
- Splitting a fan-out across requests so that every member of a large event is woken on every photo.
- Fairness or rotation in the wake selection.

## Decisions

### D1: The fan-out wakes at most 40 members, chosen at random by the token query

`pushTokensForEvent` gains `ORDER BY RANDOM() LIMIT ?`, bound to a constant `FANOUT_MAX_RECIPIENTS = 40` beside
`FANOUT_TIMEOUT_MS`. The selection costs no extra subrequest, and it applies to all three callers (gain, publish,
close) through the one helper. 40 is one push more than a full 40-device event wakes today (39): about 45–47 subrequests with the
route's own calls, the budget `raise-event-capacity` sized the cap for. Events of up to 41 devices wake everyone,
exactly as before.

A member's chance of being missed is `1 − 40/(N−1)` per wake. At N = 100 that is 60% for one photo, 7.5% across
five photos and 0.6% across ten. A member who is missed catches up through what already repairs a lost wake:
a later wake, its own upload's reconcile, the hourly background wake (heartbeat), and the foreground.

*Alternatives:*
- **Rotation (least recently woken):** guarantees everyone within ⌈(N−1)/40⌉ wakes, but costs a column and a
  write (a subrequest) on the hot path. Rejected; the random odds are adequate.
- **Splitting the fan-out across chained requests:** wakes everyone, but is a project in its own right.
  Rejected.
- **K in `policy.json`:** it is a technical budget, not a policy. Rejected.
- **39, the exact count a full event sends today:** a proven cost, but one recipient fewer for no measured
  gain; the owner kept 40.

### D2: The close wake is sampled like any other

> **Superseded at merge:** `back-dated-events` (merged first) removed the close and its wake altogether, so
> only the per-photo wake remains and is bounded by D1. The delivery spec was merged against that version.

The close goes through the same helper and the same bound. A member not woken leaves on its next background wake
or opening (capability `event-lifetime`'s leave rules already allow either). That costs some members a later
leave, which delays nothing user-visible except the moment the event's photos may be swept. That moment is bounded
anyway by the "few days after the close" fallback.

*Alternative:* wake everyone at close, in K-sized chunks from the nightly sweep. Rejected by the owner, for its
added machinery.

### D3: No device limit; the join stops counting

`ENROLL` drops its capacity disjunct and keeps the open-event check. A zero-row outcome now means only
`closed` or `no-such-event`, so `EnrollOutcome` loses `"full"`. `joinEvent`'s `409` is removed from
`openapi.yaml`, which removes `JoinEventReply.Full` from the generated wire. The compiler then walks the removal
through `JoinResult.EVENT_FULL`, `JoinOutcome.EventFull`, `JoinCommit.Full`, the presentation's `EventFull` step,
`UiState` and the join screen, and the `event_full_*` strings go in both locales. Released builds still carry
their full screen; they simply never reach it.

Existing events are uncapped by the same edit, because nothing reads their stamped value any more.

### D4: `capacity` stays on the wire and in the store as a placeholder, with no meaning

Released builds require `Event.capacity`. The backend keeps sending it, always. `openapi.yaml` keeps it in
`properties` but drops it from `required`, so the DTO generated for this and later builds is nullable and never
depends on it. Keeping it required would carry the requirement into the very release whose floor is meant to
retire it, and no `MIN_APP_VERSION` could ever unlock the removal. Its description says it is a placeholder with
no meaning. The value is a constant,
`CAPACITY_PLACEHOLDER = 1_000_000` in `api/src/config.ts` (inside a 32-bit Int, which is what the generated DTO
decodes). No released build reads the field, as checked over the app's sources. A large value, rather than 0,
means a rollback to the previous bundle, whose `ENROLL` still compares against the column, admits as before.

The `events.capacity` column also stays (it is `NOT NULL`). Creation writes the placeholder, and nothing reads
it. A failed deploy that republishes the previous bundle therefore still finds the column it reads and writes.
Dropping the column in this deploy was rejected, because a rollback after the migration would break join and
create until someone rolled forward by hand (`docs/deployment.md`: one migration per deploy, rollback republishes
the old bundle).

Two `PENDING_CLEANUPS.md` entries record this:
- **Wire:** drop `capacity` from `Event`. Unlocks once `MIN_APP_VERSION` ≥ the first release built from this
  change.
- **Store:** drop the column in its own deploy. Unlocks once the wire entry has shipped, so that no
  rollback-candidate bundle reads it.

### D5: Mocks and contracts follow the real api

`BackendMock` loses `capacity`, `DEFAULT_CAPACITY` and `JoinOutcome.FULL`. `MockState` stops encoding them, and
the router answers the placeholder. The Backend contract's `BackendState.EVENT_FULL` state and its membership
clause go, along with both bindings' entries (`BackendContractBindingTest`, `LiveEdgeContractsTest`). The cell
`Backend.joinEvent → Full` disappears from the grid with the generated reply, so `ClauseCoversTest` stays
consistent. `MockConcurrencyTest`'s `capacity = Int.MAX_VALUE` lever becomes unnecessary.

The mock's fan-out mirrors D1 (the same bound, random), so the JVM rig and the harness behave as production does.

### D6: The byte route's loop is flattened

`eventsCompletedBy` can answer at most the request's own event, so the byte route treats the completion as a
boolean for its own event. The loop suggested a multiplied fan-out that cannot happen, and it would have to be
re-read every time someone reasons about D1's budget.

### D7: Documentation and the named future

- `docs/deployment.md`: the policy table loses `eventCapacity`. "Edge Scripting limits worth knowing" says the
  fan-out is bounded by `FANOUT_MAX_RECIPIENTS`, gives the cost (≤ 40 pushes + overhead), and says the limit's
  enforcement past 50 is unmeasured and documented as soft.
- `docs/architecture.md`: the events table notes the placeholder, and the enrolment-gate paragraph is rewritten.
- `openspec/config.yaml`'s mission loses "paid events" from the named futures, and "the event full or gone"
  becomes "the event gone". CLAUDE.md's mission paragraph loses "paid events (device count is the only lever)".

## Risks / Trade-offs

- [In a very large event the tail is slow. The last photo before a quiet period reaches only 40 members by wake,
  and the rest wait for the heartbeat (about hourly, and less on iOS when the system defers it) or for an
  opening.] → Accepted. The spec has always called wakes best effort. Status counts on the joined screen show
  the gap honestly.
- [Members leave later after a sampled close, so the sweep may delete later.] → Bounded by the existing fallback
  after the close. The retention promise is unchanged.
- [Photo volume is the next ceiling. At about 100k assets, a full union read (join, foreground, leave check)
  approaches the 128 MB isolate. Full reads and planning also grow on the phone.] → Unmeasured, and deferred:
  nothing should be built for huge events before a measurement. Noted in `docs/deployment.md`.
- [The union read log and membership rows grow linearly with members.] → Both die with the event. Accepted.
- [Without a limit, a leaked invite can enrol many devices.] → The invite has always admitted anyone who holds
  it (`invite-link`), and the limit was never a security control. Attestation still gates every device.
- [`RANDOM()` scans and sorts the event's present members on every wake.] → It is one indexed scan of one event's
  memberships, the same rows the query already reads.

## Migration Plan

1. Merge. The api deploys with no migration (D4), and every event is uncapped at once.
2. The app's next release no longer knows "full". Nothing in released builds changes.
3. Later, per `PENDING_CLEANUPS.md`: drop `Event.capacity` from the wire once the floor allows it, then drop the
   column in its own deploy.

Rollback: republish the previous bundle. It reads and writes the still-present column. Events created in the
meantime carry the placeholder, so the old `ENROLL` admits on them as before. Events created before this change
fall back to their stamped 10 or 40 until roll-forward.
