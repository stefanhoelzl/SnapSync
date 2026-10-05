# Tasks

## 1. v1 notify: one token read

- [x] 1.1 In `api/src/routes/v1.ts`'s `/events/:eventId/notify`, replace the active-member enumeration plus per-member `readPushToken` calls with one `pushTokensForEvent(db, eventId)` (no exclusion), keeping the 404/502 gates, the best-effort 202 and the log line's member/token/sent counts; delete `readPushToken` once unused. Verify: the existing `notify →` tests in `api/test/v1.test.ts` pass unchanged (`cd api && deno task test`).
- [x] 1.2 Add a `notify →` test in `api/test/v1.test.ts` asserting the token read is ONE statement regardless of member count (e.g. 40 active members with tokens → one DB execute for tokens, 40 pushes), and update the route's header comment to say so. Verify: the test fails against the old per-member read and passes after 1.1.

## 2. Raise the capacity

- [x] 2.1 Set `eventCapacity` to 40 in `deployments/components/policy.json`. Verify: `cd api && deno task test` (incl. `config.test.ts`) passes and a locally served `POST /events` answers `capacity: 40`.
- [x] 2.2 Set `BackendMock.DEFAULT_CAPACITY` to 40 (`adapter/generic/mock/.../BackendMock.kt`) and update its KDoc if it names 10. Verify: `./gradlew build` passes.
- [x] 2.3 Rewrite the `FANOUT_TIMEOUT_MS` comment in `api/src/routes/v2.ts` so it no longer cites "capacity is 10" and points at the subrequest budget in `docs/deployment.md`. Verify: `deno lint` / `deno task test` pass.

## 3. Documentation

- [x] 3.1 `docs/deployment.md`: beside `policy.json`'s `eventCapacity`, and in "Edge Scripting limits worth knowing", state that capacity is bounded by the 50-subrequest limit (v2 upload/publish fan-out ≈ capacity + 5, v1 notify ≈ capacity + 3), so 40 leaves headroom and anything past ~45 loses wakes silently. Verify: the sentence names both paths and the numbers match design.md D1/D2.
- [x] 3.2 `docs/architecture.md`: replace "`capacity = 10` ever-enrolled devices" with the policy-set capacity (40) and a pointer to the deployment note; leave the "10 racing devices for 3 slots" measurement as historical. Verify: `grep -n "capacity = 10" docs/` prints nothing.

## 4. Integration

- [x] 4.1 Run `./gradlew build` and `npx --yes @fission-ai/openspec@1.13.2 validate raise-event-capacity --strict`. Verify: both green.
- [x] 4.2 At archive: apply `purpose-edits/event-lifetime.md` to `openspec/specs/event-lifetime/spec.md` by hand and diff (the only removed Purpose line is the "At most 10 devices" sentence); then run the two archive gates from `openspec/config.yaml` over `event-lifetime`. Verify: no `TBD` placeholder, no code identifier, and `grep -n "10 devices" openspec/specs` prints nothing.
