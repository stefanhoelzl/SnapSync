# Tasks

Every integration test claims the scenario it verifies with `@Verifies(spec, requirement, scenario)`, one
behaviour per scenario. It is written in the integration suite's behaviour DSL (`behaviour { given / whenever /
then }`, typed event matchers, `Settled` for an outcome that must NOT happen), drives the fault with the backend
mock's per-operation lever (`backend.fail(operation, fault, times)`) through the control channel, and asserts only
what a member observes. In each group the tests come first: write them, see them fail against today's app, then
make the fix that turns them green.

## 0. Before the apply

- [x] 0.1 Confirm the three phases the apply builds on are on `main`: the typed control channel with the backend's
  per-operation fault lever for every `BackendFault` (reachable from `RigClient`), the integration-coverage check
  and its default, and the behaviour DSL with mocked-clock timers. If one is missing, stop and say which; do not
  add a private lever or helper. Verify with a `:test:control` call that faults one `getEvent` and sees it
  answered.
- [x] 0.2 Rebase this branch onto `main`, and re-read the code `design.md`'s Context cites, since the domain-layer
  refactor moved files. Verify that `./gradlew build` is green before any test is written.

## 1. The one classification

- [x] 1.1 Decode an empty-schema success (`Joined`, `Left`, `Published`, `Recorded`) as success only when the
  body is what the operation declares; anything else becomes `Malformed` (design D2). Verify with generator and
  services tests: a `200` carrying an HTML body is `Malformed` for each of the four, and an empty `200` is still
  success.
- [x] 1.2 Classify every failed exchange into one of the three conditions at the gateway (design D1), and record
  the latest outcome as the service-condition fact the status line will read (design D4). Verify with services
  tests: 502 is trouble, 503 is updating, no answer and `Malformed` are unreachable, a success clears it, and a
  `401` that recovers leaves no condition behind.
- [x] 1.3 deviceFiles stops treating `Malformed` as a permanent shape error; it is "cannot be reached", logged as
  transient. Verify with a services test, and with an integration test that a join whose device listing comes
  back unreadable still completes (photo-sharing, "An offline join may upload again, never duplicate").
- [x] 1.4 Give the three conditions their words, in English and German, in `:ui:screens`' string resources, plus
  "the event is over" for the rename dialog. Only *cannot be reached* may keep "check your connection". Verify
  with `./gradlew build` (`HardCodedUiText`, both locales present).

## 2. Create

- [x] 2.1 Integration tests: create meets `SERVER_ERROR`, `MAINTENANCE`, `UNREACHABLE` and `MALFORMED` in turn.
  Claims: create-event "A failed create says so and changes nothing" — "A service in trouble is reported as
  such", "An update in progress is reported as brief", "An unreachable server is reported as such", "A failed
  create keeps what the host entered", "The failure message stays until the next attempt"; app-experience "A
  failing service is told as what it is" (all four scenarios); privacy-security "No answer is not a refusal"
  (the challenge or mint unanswered); create-event "A retry after a failure asks again"; app-experience "A
  failure does not freeze the app" and "A submission is refused". Verify the tests fail first.
- [x] 2.2 Carry the condition into the create screen's message, and make sure no create fires without a tap.
  Verify that 2.1 passes.

## 3. Join screen: loading the event

- [x] 3.1 Integration tests: the load meets each fault, and maintenance ends while the screen is open.
  Claims: join-event "The join screen verifies the event before offering to join" — "A service in trouble can
  be retried", "An update in progress loads by itself", "An unreachable server can be retried", "An unexpected
  failure never strands the user". Verify the tests fail first.
- [x] 3.2 Show the condition on the load failure. On maintenance, reload by itself after the service's
  suggested wait, bounded so that a missing, malformed or huge hint can neither spin nor stall the screen
  (design D3), with Retry kept. Verify that 3.1 passes, the reload driven by the mocked clock's timers and
  not by real waiting, plus a presentation test of the bound.

## 4. Join screen: confirming the join

- [x] 4.1 Integration tests: Join meets each fault; an unreadable answer is followed by a Retry the event
  accepts. Claims: join-event "Joining happens only on confirmation and needs a connection" — "A join the
  service fails says which way", "An answer the app cannot read is not a join"; join-event "A choice survives a
  failed join". Verify the tests fail first.
- [x] 4.2 Show the condition on a failed join, and make an unreadable answer a failed join. Verify that 4.1
  passes.

## 5. Rename

- [x] 5.1 Integration tests: rename meets `SERVER_ERROR`, `MAINTENANCE` and `ENDED_FOR_GOOD`. Claims:
  manage-membership "A failed rename keeps the dialog and never ends the membership" — "A rename the service
  fails says which way", "A rename that meets a deleted event says it is over"; manage-membership "Any member
  renames the event for everyone until its photos are deleted" (the deleted event offers no rename afterwards).
  Verify the tests fail first.
- [x] 5.2 Show the condition in the dialog. A `410` records the event's deletion (design D5); the dialog says
  the event is over, the joined screen turns to "over", and rename is no longer offered. Verify that 5.1
  passes.

## 6. Joined screen: the service line

- [x] 6.1 Integration tests, with photos waiting to be shared or received and the background upload, listing
  or manifest calls faulted. Claims: sync-status "A failing service is shown while photos wait" (all six
  scenarios); sync-status "One status line in a fixed priority" — "A failing service outranks progress"; sync-status
  "The app says when it cannot reach the network" — "The server is down, not the network"; and a check that the
  offline and refusal lines still outrank it. Verify the tests fail first.
- [x] 6.2 Add the line to the status read-model and presentation from the service-condition fact (1.2), ranked
  after "cannot be verified". It is not shown on opening for an older failure, reusing the verification line's
  opening rule (design D4), and clears on the first success. Verify that 6.1 passes, plus presentation
  priority tests.

## 7. Verification blamed on the service no more

- [x] 7.1 Integration tests: the proof has expired, and renew, challenge or mint meets `UNREACHABLE`,
  `MAINTENANCE` and `SERVER_ERROR`; then a case where the service answers and no proof is obtained. Claims:
  sync-status "A device that cannot be verified is shown, and never blamed on the member" — "Unreachable server
  with an expired verification", "An update with an expired verification", "The phone cannot give its proof",
  "A failure without a verdict names no cause"; privacy-security "A renewal fails while the proof is still
  valid". Verify the tests fail first.
- [x] 7.2 Split the attestation outcome so that a service condition (no answer, trouble, updating) feeds the
  service line, and only an answered failure feeds "cannot be verified". Refusals are unchanged. Verify that
  7.1 passes, and that the existing refusal tests still pass.

## 8. Background promises

- [x] 8.1 Integration tests. Claims:
  - event-lifetime "An event's photos are deleted once it is finished, and after 30 days at the latest" — "A
    leave the service failed to take still counts" (leave faulted, then lifted; the backend records the leave)
    and "A member who cannot say they are done holds deletion back" (the received-report call faulted; the
    event is not finished at the service until it is lifted);
  - delivery "A photo is never lost on the way" — "Photos shared while the service fails reach the others
    later" (the manifest publish faulted, `MALFORMED` included; the other member's union gains the photos once
    it is lifted, and loses none meanwhile);
  - delivery "Only new photos are announced by a silent wake, and never only by it" — "The service could not
    set up wakes" (push registration faulted; the photo still arrives at the next opening).

  Verify the tests fail first, at least for the `MALFORMED` cases.
- [x] 8.2 With 1.1 in place, confirm that leave, publish and report stay pending on any non-success, and are
  resent at the next wake. Verify that 8.1 passes.

## 9. A deleted answer to the member's sharing

- [x] 9.1 Integration test: publishManifest answers `ENDED_FOR_GOOD` during a background pass. Claims:
  event-lifetime "The app never leaves an event on its own" — "Sharing into a deleted event marks it over",
  plus "A server error never ends a membership" (`SERVER_ERROR` and `MALFORMED` never mark it over). Verify the
  tests fail first.
- [x] 9.2 A `410` to publish records the event's deletion through the same fact as 5.2: the joined screen shows
  it as over, nothing more is shared, and the member stays. Verify that 9.1 passes.

## 10. Cross-checks

- [x] 10.1 Confirm that every scenario this change adds or rewrites is claimed by a test, and so is every fault
  scenario that was specified before it but claimed by none. `./gradlew :test:architecture:verifiesReport` lists
  each as claimed. The one exception is manage-membership "A failed save applies nothing": a settings change is
  saved on the device, so the backend's lever cannot drive it, and it belongs to the storage-fault phase. Say so
  in the report.
- [x] 10.2 Confirm the change and the tree validate, and the build is green:
  `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict`, the change's own `validate --strict`, and
  `./gradlew build`.

## 11. Coverage and archive

- [x] 11.1 Measure integration coverage after the change, and raise the shared default for every core module to
  what it measures, never lower. A module that drops is fixed by a test, not by lowering the default. Verify that
  the integration-coverage check passes at the raised default.
- [x] 11.2 Only at the user's word: sync and archive the change. Verify that
  `npx --yes @fission-ai/openspec@1.13.2 list --json` shows no open change (the ship gate), and that the synced
  specs carry no code identifier (`openspec/config.yaml`'s archive gates).
