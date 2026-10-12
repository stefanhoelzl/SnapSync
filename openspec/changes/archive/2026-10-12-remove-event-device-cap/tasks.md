# Tasks

## 1. Bounded wake fan-out (api)

- [x] 1.1 Add `FANOUT_MAX_RECIPIENTS = 40` beside `FANOUT_TIMEOUT_MS` in `api/src/routes/support.ts`. Make `pushTokensForEvent` (`api/src/db.ts`) take the bound and select `ORDER BY RANDOM() LIMIT ?`. Rewrite both KDoc/comments to state the budget (D1). Verify: a new `api/test` case seeds 100 present members with tokens and gets 40 distinct recipients from one statement, and a seed of 41 members gets all 40 others.
- [x] 1.2 Add a route test that a byte completion, a publish gain and a close in a 100-member event each send exactly 40 pushes, still from ONE token statement. Add one that two consecutive wakes pick different selections, and assert only that both are subsets of size 40, never a specific order. Verify: `cd api && deno task test` passes.
- [x] 1.3 Flatten the byte route's per-completed-event loop in `api/src/routes/v2.ts` to its own event (D6), keeping `eventsCompletedBy`'s answer as a boolean. Verify: the existing byte-route wake tests pass unmodified, apart from the shape of `woken`.

## 2. No device limit (api)

- [x] 2.1 Drop the capacity disjunct from `ENROLL`, remove `"full"` from `EnrollOutcome`, and remove its `409` branch from the join route. Rewrite the `ENROLL` KDoc: a zero-row outcome is now only `closed` or `no-such-event`. Verify: `api/test/v2.test.ts`'s `capacity: 1` case is re-expressed to say a second device joins, and the simultaneous-join test admits every racer.
- [x] 2.2 Remove `eventCapacity` from `deployments/components/policy.json`, `api/src/config.ts` (type, key list, resolution) and `api/src/dev/config.ts`'s comment. Add `CAPACITY_PLACEHOLDER = 1_000_000` with a KDoc naming D4. Make event creation (`routes/shared.ts`) stamp and answer it, and stop accepting a client-sent `capacity`. Verify: `api/test/config.test.ts` and `shared-routes.test.ts` assert the placeholder, and `deno task test` passes.
- [x] 2.3 In `api/openapi.yaml`: remove `joinEvent`'s `409`, and keep `Event.capacity` in `properties` but drop it from `required` (released builds still get it; newer ones must not depend on it, D4), with its description saying it is a meaningless placeholder. Run `./gradlew :domain:wire:compileKotlinJvm` (or the build's wire generation) and confirm `JoinEventReply.Full` is gone. Verify: the api's openapi conformance tests pass.
- [x] 2.4 Update the api test fixtures that stamp `capacity: 10` only where the assertion depended on the limit, and leave the rest, since the column still exists. Verify: `cd api && deno task test` and `deno lint` pass.

## 3. The app forgets "full"

- [x] 3.1 Follow the compile errors from 2.3 through `services/backend/Membership.kt`, `model/JoinResult.kt` (`EVENT_FULL`), `feature/membership/JoinEvent.kt` (`EventFull`, `JoinCommit.Full`), `presentation/StatusContainerHost.kt` (the `EventFull` step), `ui/state/UiState.kt` and `ui/screens/JoinFlowScreens.kt`, removing each case. Verify: `./gradlew compileIosMainKotlinMetadata` and the JVM compile succeed.
- [x] 3.2 Delete `event_full_title` and `event_full_body` from both locales' `strings.xml`, and run `./gradlew nativeStrings` if any OS-shown key changed. Verify: no `event_full` reference remains (`git grep event_full`).
- [x] 3.3 Remove or re-express the tests that asserted the full path: `JoinScreenTest`, `StatusContainerHostTest`, `JoinGateEdgesTest`, `JoinSeamsTest`, `BackendServicesTest`, `ManifestDeviceEnrollerTest`, `JoinEventTest` and `DeviceManifestProducerTest`. Verify: `./gradlew :ui:screens:jvmTest :domain:presentation:jvmTest :test:feature:jvmTest` pass.

## 4. Mocks and contracts

- [x] 4.1 `BackendMock`: remove `capacity`, `DEFAULT_CAPACITY` and `JoinOutcome.FULL`. Have the router answer `capacity` as the placeholder, and bound the mock's fan-out to 40 random recipients (D5). Drop `capacity` from `MockState`'s encoding, keeping the decoder tolerant of an old state file that still carries it. Remove `MockConcurrencyTest`'s capacity lever. Verify: `./gradlew :adapter:generic:mock:jvmTest` passes.
- [x] 4.2 Remove `BackendState.EVENT_FULL` and its membership clause from `:test:contracts` (`BackendContract.kt`, `BackendMembershipClauses.kt`), and their entries in `BackendContractBindingTest` and `LiveEdgeContractsTest`. Verify: `./gradlew :test:architecture:test :adapter:generic:app:jvmTest` pass (`ClauseCoversTest`, `ContractCoverageTest`, the live edge contracts against the real api/).

## 5. Documentation and mission

- [x] 5.1 `docs/deployment.md`: remove `eventCapacity` from the policy table. Rewrite "Edge Scripting limits worth knowing"'s subrequest bullet: the fan-out is bounded by `FANOUT_MAX_RECIPIENTS`, costs ≤ 40 pushes + overhead whatever the member count, and the limit is documented as soft but unmeasured. Add the photo-volume ceiling as an unmeasured note. `docs/architecture.md`: change the events-table note and the enrolment-gate paragraph (lines around "capacity ever-enrolled devices"). Verify: `git grep -n "eventCapacity\|capacity = 10\|bounded by Edge's subrequest" docs/` prints nothing stale.
- [x] 5.2 `openspec/config.yaml`: drop "paid events (…)" from the named futures, and change "the event full or gone" to "the event gone". `CLAUDE.md`: drop "paid events (device count is the only lever) ·" from the mission paragraph. Verify: `git grep -n "paid events" openspec/config.yaml CLAUDE.md` prints nothing.
- [x] 5.3 `PENDING_CLEANUPS.md`: add the two D4 entries, (a) the wire's `Event.capacity` placeholder (unlock: `MIN_APP_VERSION` ≥ the first release built from this change) and (b) dropping `events.capacity` in its own deploy (unlock: (a) shipped). Each gets file anchors and removal steps. Verify: both entries name `CAPACITY_PLACEHOLDER` and the column.
- [x] 5.4 Regenerate `architecture/` if any diagram moved (`./gradlew architectureDiagrams`). Verify: `./gradlew :tools:diagrams:test` passes.

## 6. Integration

- [x] 6.1 Run `./gradlew build` and `cd api && deno task test && deno lint`. Verify: both green.
- [x] 6.2 Run `npx --yes @fission-ai/openspec@1.13.2 validate remove-event-device-cap --strict`. Verify: valid.
