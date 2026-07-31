## Why

The operator-initiated diagnostic dump ships a device's log and sync state, but nothing about **what
the person was doing when it went wrong**. A log tail answers *what the app did*; it cannot answer
*what the user expected*, *which photo they were looking at*, or *when they noticed* — and on a
device reached only by this gesture, there is no second channel to ask. Every dump today arrives as
an occurrence of one issue titled `diagnostic dump`, so two reports about entirely different problems
are indistinguishable until someone reads both logs in full.

## What Changes

- The hidden double-tap on the app-name label opens a **bug-report sheet** carrying a **required**
  free-text description (multi-line, 200-character cap) instead of a bare confirmation dialog. The
  sheet replaces the dialog as the consent moment and the sole disclosure of the payload; Send is
  disabled until the trimmed description is non-empty.
- The dump gains a **fifth section**, `note`, carrying the description verbatim.
- **BREAKING (reporting-side grouping):** the dump event's message becomes `Bug Report: <note>`
  instead of the constant `diagnostic dump`. Dumps therefore group as **one issue per distinct
  description** rather than a single issue. This reverses a requirement decided in
  `changes/archive/2026-07-29-add-diagnostic-dump`; the rationale is in `design.md`.
- The redaction carve-out widens from *contexts only* to **the message as well**, gated by an
  explicit `non-redacted` event tag rather than by where the payload happens to sit. The tag name and
  the exemption predicate live in `domain` `model/` beside `redactUuids`, unit-tested in
  `commonTest`; the guard that protects the carve-out is **re-pointed** at the tag.
- `AppTextField` gains a multi-line capability; the design system gains `AppBugReportSheet`, the
  app's **first** bottom sheet.
- Both desktop harnesses render the sheet — the world harness wires the real command (the world's
  reporter records the dump), the forge harness renders it UI-only and logs the description to the
  engine console.
- The affordance stays **hidden** and stays **absent on a build with no reporting configuration**:
  both properties are unchanged, and the second is what keeps the forge/screenshot composition on
  device from offering a sheet that could send nothing.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `diagnostic-logging`: the operator affordance becomes a sheet collecting a required description;
  dump contents grow a fifth `note` section; the "dumps group as one issue" requirement is replaced
  by per-description grouping; the scrub-widening guard scenario is re-pointed at the exemption tag.
- `crash-reporting`: the dump's exemption from UUID redaction now covers the **message** as well as
  the context sections, carried by an explicit `non-redacted` tag on the event.
- `design-system`: `AppTextField` gains a multi-line capability (its requirement enumerates the exact
  parameter set today); a new `AppBugReportSheet` semantic component is added, the first bottom sheet
  in the app.
- `desktop-test-harness`: the forge harness renders the bug-report sheet UI-only, echoing the
  description to the engine console — matching how Leave and the invite affordances are already
  rendered UI-only there.
- `full-stack-harness`: the world harness wires the real dump command, so the sheet collects a
  description and the world's reporter records the assembled dump.

## Impact

**Code**

- `:domain` — `model/DiagnosticDump.kt` (new `note` field), `model/` redaction (tag name + exemption
  predicate beside `redactUuids`), `model/UserCommands.kt` (`sendDiagnostics` takes the description),
  `feature/diagnostics/CollectDiagnosticDump.kt` (`collect(note)`), `compose/SnapSyncApp.kt` (command
  wiring).
- `:adapter:ios:ext-safe` — `SentryDiagnosticsReporter` (message prefix, `note` context, the
  `non-redacted` scope tag, and `scrubbedEvent` consulting the predicate).
- `:ui:components` — `AppTextField` multi-line, new `AppBugReportSheet`.
- `:ui:screens` / `:ui:presentation` — `StatusScreen` opens the sheet instead of the dialog;
  `StatusContainerHost.onSendDiagnostics` carries the description.
- `:app:desktop` — both harness panes wire the affordance.

**Tests**

`DiagnosticDumpGestureTest`, `CollectDiagnosticDumpTest`, `DiagnosticDumpIntegrationTest`,
`RedactionTest`, and `DumpScrubExemptionTest` (re-pointed at the tag rather than at context
avoidance). New `commonTest` coverage for the exemption predicate. `./gradlew architectureDiagrams`
regenerated and committed.

**Dev infrastructure (not a spec delta)**

`.claude/skills/bugsink/SKILL.md` hardcodes `Log Message: 'diagnostic dump'` as the "this is not a
crash" rule; the new message prefix orphans it, so it ships with this change.

**Verification**

One ssh-mac session with `SENTRY_DSN` injected on the `xcodebuild` line: send a report whose
description contains a known UUID, then read it back via `/bugsink`. That single run proves the tag
reaches `beforeSend` (the description arrives un-mangled) and shows whether the keyboard covers the
sheet's Send button on an SE2.

**Not changed**

The affordance stays hidden with no accessibility semantics; there is still no post-send feedback and
no rate limit; the 700 KB log budget is unchanged and the description does not count against it.
