## 1. The domain seam

- [x] 1.1 Add `ProcessMetricSource` to `:domain:ports` — generic over providers, yielding reports as
      `Map<String, String>`, with no window in the type and no platform framework named. KDoc states
      why it is generic (the vendor's successor API is unreachable from Kotlin, so the provider will
      be replaced) and why a report is an open bag.
- [x] 1.2 Add the report key constants to `:domain:model`, shared by the adapter that writes them and
      the rule that reads them, so there is exactly one spelling of each key.
- [x] 1.3 Add the threshold constants to `:domain:model` as named values — the hang ceiling (1 s) and
      the set of counters that constitute a crossing — each with a KDoc naming what would loosen it.
- [x] 1.4 Add the pure rule to `:domain:model`: report in, **emissions** out (severity + text + tags).
      It must be total and hold every decision, so the dispatcher downstream has no conditional.

## 2. Domain tests (`commonTest`) — write these against 1.4 before any iOS code

- [x] 2.1 Flattening: a nested map becomes dotted keys; nesting depth and collisions behave.
- [x] 2.2 A report with only normal exits produces exactly one `Info` emission and no crossing.
- [x] 2.3 Each non-normal counter, foreground and background, produces a crossing.
- [x] 2.4 Several crossing counters in one report produce exactly **one** crossing emission carrying
      all reasons in its attached context — the report is the unit.
- [x] 2.5 Hang ceiling edges: below, at, and above 1 s; and a report with no hang data at all.
- [x] 2.6 Message text is stable across differing counts, so occurrences group into one issue.
- [x] 2.7 An absent counter and a zero counter produce the same verdict (the D1 consequence, pinned).
- [x] 2.8 A report carrying keys the rule does not recognise still yields its line, unchanged.

## 3. The reporting channel

- [x] 3.1 Add the context-attachment operation to `DiagnosticsReporter` in `:domain:ports`, named for
      the need rather than for the SDK's vocabulary, and a no-op when reporting is unconfigured.
- [x] 3.2 Seat it in the reporter in `:adapter:ios:ext-safe`, attaching to the **global scope** so it
      survives onto fatal events, beside the existing process tag.
- [x] 3.3 Extend the fake reporter in `:adapter:generic:fake` to record attached contexts, keeping it
      honest per the fake-honesty gate.

## 4. The iOS adapter

- [x] 4.1 Add the MetricKit adapter to `:adapter:ios:app-only`: `NSObject` + the subscriber protocol,
      both callbacks marked as platform entry points and opening with the invocation wrapper.
- [x] 4.2 Flatten each payload's own serialized representation to dotted keys. Read **no** call-stack
      data — reference the measurement in the KDoc so nobody re-adds it.
- [x] 4.3 Handle inline, before the delivering call returns; retain the subscriber for the process
      lifetime.
- [x] 4.4 Dispatch the rule's emissions: log each, attach the report as context, transmit on crossing.
      No conditional — a loop over emissions.

## 5. Shell wiring

- [x] 5.1 Construct and register the adapter from `SnapSyncRoot`'s own initialization, so it runs on
      every process start and never forces the deferred application graph.
- [x] 5.2 Confirm the extension links no MetricKit surface. **No gate edit needed** — the
      extension-safety gate is now an ALLOWLIST derived from the appex's dependency closure, which
      "fails closed on novelty", so MetricKit is already forbidden there by construction. The task's
      premise (a forbidden list to extend) no longer exists.
- [x] 5.3 Verify `./gradlew build` is green, including the shell complexity gate and the platform
      entry-point guard.

## 6. Dev surface

- [x] 6.1 Add a `/device` rig route accepting a synthetic report and feeding it through the same rule
      and the same three channels.
- [x] 6.2 Delete the temporary probe: `MetricKitProbe` and the rig `crash` route.
- [x] 6.3 Move `PROBE-FINDINGS.md` from the repo root into this change directory.

## 7. Specs and docs

- [ ] 7.1 Update `openspec/specs/crash-reporting/spec.md` from the delta, citing this change as its
      decision record.
- [ ] 7.2 Update `openspec/specs/diagnostic-logging/spec.md` and
      `openspec/specs/ios-app-shell/spec.md` from their deltas.
- [ ] 7.3 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 7.4 Run `./gradlew architectureDiagrams` and commit the result.

## 8. On-device verification

- [x] 8.1 Build and install a rig build; fire the synthetic-report route and confirm all three
      channels behave (log line, context attached, event on crossing).
- [ ] 8.2 Wait one report cycle and confirm a **real** payload flows end to end — the one part the
      synthetic route cannot exercise.
- [x] 8.3 Record what was measured, with device, OS point release and an ⏰ re-measure trigger. Do not
      claim device-verified what was not run. **Recorded in PROBE-FINDINGS §7**, including the two
      things this run did NOT prove (the Bugsink half — no DSN on a dev build — and the ObjC decode,
      which the rig route enters below by design).

## 9. The deliberate experiment (settles an Open Question)

- [ ] 9.1 Force-quit the app by swipe a known number of times, deliberately.
- [ ] 9.2 After the next report, check whether `cumulativeAbnormalExitCount` rose by that number.
- [ ] 9.3 Record the answer, and narrow the threshold if ordinary force-quit turns out to trip it.
