# Tasks

## 1. Backend: the open-until date and the computed lifecycle

- [x] 1.1 Add migration `api/migrations/0014_open_until.sql` (`events.open_until` backfilled from `ends_at`, `memberships.received_position` NULL), mirror it in the dev SQLite schema and `generate-schema`; verify `deno task test` and the `migration-rehearsal` gate pass
- [x] 1.2 `api/openapi.yaml`: `EventCreate.openUntil` (optional, `endsAt <= openUntil <= max(endsAt, now) + eventWindowMaxSeconds` as an `x-snapsync-*` rule), `Event.openUntil`, `MemberCounts.waiting`, and the `PUT …/devices/{deviceId}/received` operation; regenerate the wire and verify the generator and `wire-runtime` tests pass with a refusal test per new bound
- [x] 1.3 `createEvent` stores `open_until` (absent → `endsAt`); verify with api tests: an old-client create gets `openUntil == endsAt`, a back-dated create with 7 days is stored, an over-long one is refused `invalid openUntil`
- [x] 1.4 One SQL predicate in `db.ts` for a membership being **done** and an event being **finished** (design D3), and `memberCounts` → `{ active, waiting }`; verify with db tests covering own undeclared photo, a new gain, a late join and a departed member
- [x] 1.5 The received route (design D4): gated like the other device routes, monotonic `MAX`; verify api tests for refusal of a foreign device, a lower position ignored, a higher one stored
- [x] 1.6 Rewrite `sweepVerdict`/`deleteByMs`/`clockMs` in `lifecycle.ts` (design D2: DROP at `max(created, open_until) + lifetime`; COMPLETE on finished / `max(open_until, last join, last landing) + 3 d` / all departed; never-joined KEEP); verify `lifecycle` tests for every scenario of the `event-lifetime` delta's deletion requirement
- [x] 1.7 Remove the close for new events: the close stamp in the v2 publish and the leave, the `close` push, and the closed refusals of join/publish/rename — kept only for rows with a stored `closed_at`; verify api tests: joining, publishing and renaming an ended (unfinished and finished) event succeed, a legacy closed row still refuses
- [x] 1.8 Serve the derived `closedAt` (design D5) and `openUntil` on the event read; verify api tests: `closedAt` null while unfinished, set once finished, null again after a late join; legacy rows unchanged
- [x] 1.9 Add the two `PENDING_CLEANUPS.md` entries (legacy closed rows; derived `closedAt` for shipped builds) and update `docs/architecture.md`'s lifecycle/sweep section; verify the doc names each gate it points at

## 2. App: create a back-dated event

- [ ] 2.1 `CreateEvent`/`EventCreation`/the gateway carry `openUntil`; the presentation computes `now + N days` as an instant (`CutoffFormatter`); verify a presentation test that 7 days across a DST change stays within the server bound
- [ ] 2.2 The back-dated sheet: `onCreateEvent` shows it when `endsAt < now` at tap time, four choices + Cancel keeping the draft (design D9), new `UiState` overlay and intents in `:ui:state`, `statusActions` mapping in `:ui:screens`; verify presentation tests (end in the past → sheet; end a minute ahead → no sheet; Cancel keeps name and range) and a `:ui:screens` click test for each button
- [ ] 2.3 Strings EN + DE for the sheet, worded per `metadata/messaging.md` without promising deletion on day N; run `./gradlew nativeStrings` if an OS-shown key is added; verify `HardCodedUiText` passes in `./gradlew build`

## 3. App: settle, report, never leave

- [ ] 3.1 The download side reports its fully received union position (design D4) through a new `BackendGateway` need-shaped service; verify a feature test over the mocks that the report is sent only once the plan is empty and everything is in the library or deleted, and a contract clause in `BackendContract` against the real api/
- [ ] 3.2 `EventCompletion` keeps delivering pending leaves, settling and now reporting, and never calls `LeaveEvent` (design D7); verify feature tests: a finished event stays joined in the background and the foreground
- [ ] 3.3 `MembershipRefresh` turns a confirmed deletion into a local **deleted** flag on the membership (stored in ledger.db's membership row) instead of a leave, transfers stop; the safeguards stay; verify feature tests for completed, 404 past `deletesAt`, premature 404, offline and 5xx
- [ ] 3.4 Update `BackendMock` to the new lifecycle (no close, derived `closedAt`, `waiting`, received route, `openUntil`) so the JVM rig matches the real api/; verify the backend port contract runs green against both the mock and `LiveEdge`

## 4. App: switching and screens

- [ ] 4.1 `switchDecision` + the link handler read the current event once on a different invite and skip the dialog only per design D8; verify feature/presentation tests: finished + received → straight to join screen; one member waiting → dialog; offline → dialog; deleted → straight to join screen; cancel there → no event
- [ ] 4.2 The joined screen: deleted state (over, photos removed, counts, Leave only; Leave without confirm), the member count on the counts line, "Waiting for N members" / "Everyone has shared their photos", and "Guests can join until …" for a back-dated event before its open-until date; drop the closed variant and the closed-only invite rule; verify `:ui:screens` tests per state and presentation reducer tests
- [ ] 4.3 The join screen shows the member count from the event read; verify a presentation test and a `:ui:screens` test
- [ ] 4.4 Strings EN + DE for every new line; verify `./gradlew build` (HardCodedUiText, Compose rules) passes
- [ ] 4.5 World harness: the new states reachable through existing levers (a past range create, the sweep completing, a finished event); verify each renders via the `ui-harness` driver

## 5. Integration and contract of record

- [ ] 5.1 `:test:integration`: a back-dated create (clock past the end) → host joined, a guest joins on day 2 and shares, both receive, still joined after everyone is done; the sweep completes it and both see the deleted state; verify the tests with `@Verifies` on the delta's scenarios
- [ ] 5.2 Integration: an old-build-shaped member (no received reports) leaves on the derived `closedAt` and does not block the fast path; verify a rig test with the API driven directly
- [ ] 5.3 Edit the Purpose paragraphs of `event-lifetime`, `invite-link`, `manage-membership`, `photo-sharing`, `join-event`, `sync-status` in `openspec/specs/` to drop the close; update `openspec/config.yaml`'s mission paragraph and `CLAUDE.md`'s mission paragraph to the new lifetime; verify `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` and the two archive gates in `openspec/config.yaml`
- [ ] 5.4 Run `./gradlew build`, `./gradlew compileIosMainKotlinMetadata`, `cd api && deno task test`, and `./gradlew architectureDiagrams` (commit if changed); verify all green
