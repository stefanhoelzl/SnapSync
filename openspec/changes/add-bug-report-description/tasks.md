## 1. Domain: the description and the exemption marker

- [x] 1.1 Add the exemption marker's name and predicate to `:domain` `model/` beside `redactUuids` — the tag name constant and a pure `fun` deciding, from an event's tags, whether redaction applies
- [x] 1.2 Cover the predicate in `commonTest` (runs on JVM and iosSimulatorArm64): present marker → exempt, absent marker → redacted, unrelated tags → redacted
- [x] 1.3 Add `note: String` to `DiagnosticDump`, leaving `logBytes` counting the two log tails only
- [x] 1.4 Take the description as a parameter on `CollectDiagnosticDump.collect(note)` and place it in the dump unchanged (no truncation, no trimming — the sheet already trimmed and bounded it)
- [x] 1.5 Change `UserCommands.sendDiagnostics` to accept the description, keeping it nullable — null remains the "no reporting channel" signal
- [x] 1.6 Thread the description through `compose/SnapSyncApp.kt`'s command wiring; the `isConfigured` gate stays the single place the affordance's existence is decided
- [x] 1.7 Update `CollectDiagnosticDumpTest` for the new section and parameter

## 2. Reporting adapter: message, note section, marker

- [x] 2.1 In `SentryDiagnosticsReporter.send`, build the message as the fixed prefix plus the description, and set the `non-redacted` scope tag on the event
- [x] 2.2 Add the `note` context section beside state, ledger, app_log and ext_log
- [x] 2.3 Make `scrubbedEvent` consult the domain predicate against the event's tags and skip redaction entirely for an exempt event
- [x] 2.4 Replace the `DIAGNOSTIC_DUMP_MESSAGE` constant with the prefix, and update its documentation to explain that grouping is now per-description and why
- [x] 2.5 Re-point `DumpScrubExemptionTest`: assert that `send` sets the marker and that `scrubbedEvent` consults the predicate, replacing the "scrub never reaches contexts" assertion, with a failure message naming the exemption
- [x] 2.6 Update `RedactionTest` if it pins message-scrub behaviour that the exemption now bypasses

## 3. Design system

- [x] 3.1 Add a line-mode parameter to `AppTextField`, defaulting to single-line so every existing call site is unchanged
- [x] 3.2 Add `AppBugReportSheet` — Material 3 `ModalBottomSheet`, the input, keyboard avoidance and scrolling all contained; signature carries strings, a max length, callbacks, and no `Modifier`, slot, or Material 3 type
- [x] 3.3 Disable the confirm action while the trimmed input is empty, and pass the trimmed value to the confirm callback
- [x] 3.4 Route cancel, scrim and dismissal gesture to one dismiss callback

## 4. Screen and presentation

- [x] 4.1 Replace the diagnostic confirm dialog in `StatusScreen` with the bug-report sheet, opened by the same double-tap and gated on the same nullable command
- [x] 4.2 Carry the description through `StatusContainerHost.onSendDiagnostics`
- [x] 4.3 Rewrite `DiagnosticDumpGestureTest` for the sheet: opens on double-tap, send disabled while empty, sends the trimmed description exactly once, cancel sends nothing, absent entirely when the command is null, label still exposes no click action, and the affordance still works on the joined surface

## 5. Harnesses

- [x] 5.1 Wire the real command into the `:app:desktop` world pane so the sheet assembles a real dump the world's reporter records
- [x] 5.2 Wire a UI-only command into the `:app:desktop` forge pane that echoes the description to the engine console and mutates nothing
- [x] 5.3 Confirm `forgeStatusHost` in `:ui:presentation` is untouched, so the on-device forge composition still offers no affordance
- [x] 5.4 Drive the world harness headlessly via `:test:harness-driver` — double-tap the label, type a description, send — and confirm the recorded dump

## 6. Integration and cross-cutting

- [x] 6.1 Extend `DiagnosticDumpIntegrationTest` to assert the description reaches the assembled dump and the reporter through the real composed core
- [x] 6.2 Update `.claude/skills/bugsink/SKILL.md` — its "this is not a crash" rule keys on the old constant message and must key on the new prefix
- [x] 6.3 Run `./gradlew architectureDiagrams` and commit the regenerated `architecture/` output
- [x] 6.4 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green

## 7. Device verification (acceptance)

- [x] 7.1 Build a dev IPA over ssh-mac with `SENTRY_DSN` injected on the `xcodebuild` line, re-sign inside-out, and install
- [x] 7.2a Prove the marker reaches `beforeSend` — done better than planned, by `ScrubExemptionSdkTest` on the iOS simulator (permanent, gating on `ios-test`) rather than by one device observation
- [x] 7.2b Send a report whose description contains a known UUID; read it back with `/bugsink` and confirm the UUID arrived in full end-to-end (ingest + storage, which the simulator test cannot cover)
- [x] 7.3 Fallback to the message-prefix check — NOT NEEDED: the measurement closed the risk it hedged
- [x] 7.4 Confirm on an SE2 that the keyboard does not cover the sheet's send action; if it does, adjust the component's keyboard avoidance and re-verify
- [x] 7.5 Confirm two reports with different descriptions arrive as two distinct issues, each titled by its description behind the prefix

## 8. Screen label (added after the device session)

- [x] 8.1 Record the surface in the dump's `state` section — `CollectDiagnosticDump.collect(note, screen)`, the label kept opaque (the domain enumerates no screens)
- [x] 8.2 Carry it through `UserCommands.sendDiagnostics`, the compose wiring, and `StatusContainerHost`
- [x] 8.3 Derive the label in `StatusScreen`, where the screen-local surfaces (reconfigure, pending switch, join phase) are the only thing that knows them
- [x] 8.4 Wire it through both harness panes; cover it in the collector, integration and gesture tests
- [x] 8.5 Verify through the real stack in the world harness — the console records `bug report [CreateEvent] → …`
