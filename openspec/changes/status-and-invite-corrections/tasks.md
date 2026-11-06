# Tasks

Ships in the phase-4.9 pull request (label `bug`), as its last commits. It is built on the phase's restructured status
screen, whose structural tasks live in the phase's review document. Group 1 runs against the code as it stands just
before these commits, after the phase's move. Each scenario's test carries `@Verifies` for its corrected requirement.

## 1. The four corrections: pin what the app already does

- [x] 1.1 `sync-status`, "The joined screen shows one status line in a fixed priority": an integration scenario "A
  known problem shows before the app has looked": no photo access, state not yet read, so the line asks for access with
  no neutral frame first. The presentation test "permission outranks a loading snapshot" carries the same claim.
  Verify: green; the ladder's order test matches the corrected order.
- [x] 1.2 `sync-status`, "'Up to date' is never claimed before the app has looked": the cold-launch scenario runs with
  access granted and the network up. Verify: green.
- [x] 1.3 `app-experience`, "The first frame is never a guessed state", scenario "Joined launch": a launch without
  access, whose first frame asks for access. Verify: green.
- [x] 1.4 `app-experience`, "Failures are told calmly…": the create-failure test asserts the message sits below Create.
  "Picking a date range…": the shared-range dialog tests keep their claims. "Text entry sheets…": a Compose test that
  the seeded report sheet's confirm is enabled unchanged. Verify: all green, each carrying `@Verifies`.

## 2. Receiving switched off keeps what arrived

- [x] 2.1 Integration scenario "Receiving switched off keeps what arrived": 40 received, receiving switched off,
  nothing arriving. Verify: FAILS before 2.2 (the counts read "40 received").
- [x] 2.2 The received count is `Off` with the kept number once nothing is arriving, and the line reads "Not receiving
  · 40 received" in English and German. Photos still draining keep their progress. Verify: 2.1 green; unit tests for
  nothing ever received, still draining, and drained.
- [x] 2.3 The UI harness shows the line on the smallest screen without truncation. Verify: a captured screenshot.

## 3. A shown QR code stays until it's closed

- [ ] 3.1 Integration scenario "A shown QR code stays while the key cannot be read". Verify: FAILS before 3.2.
- [ ] 3.2 The overlay captures the whole invite when the QR code opens and shows it until it's dismissed. A deleted
  event, a leave and a switch clear it; the share and QR actions still follow the key. Verify: 3.1 green, and
  `KeyLostTest`'s "a shown QR closes" becomes "stays".

## 4. An unreadable membership says so

- [ ] 4.1 Integration scenario "An unreadable membership is told, not taken for a leave", with the membership's
  database unreadable from launch: the screen, then Try again after the database recovers. Verify: FAILS before 4.2
  (the create screen shows).
- [ ] 4.2 The new layer: title and line in English and German, the Try again button (the existing retry string)
  showing that it is working while it runs, and the app menu in reach. A membership row this build refuses still shows
  the create screen. Verify: 4.1 green; unit tests for refused and not refused.
- [ ] 4.3 Try again runs as its own request: the membership re-read, then the foreground's work, answered on its
  status. A second tap while one runs does nothing. Verify: an integration test with a double tap, and the entry
  point's unit test.

## 5. Integration

- [ ] 5.1 `openspec validate status-and-invite-corrections --strict` passes. Verify: the command's output.
- [ ] 5.2 Archive gates from `openspec/config.yaml`: no `TBD` Purpose in the tree, and no code identifier in the three
  touched specs. Verify: both checks print nothing.
- [ ] 5.3 `./gradlew build` green, and `:test:architecture:verifiesReport` shows every requirement of the three specs
  verified. Verify: the report.
