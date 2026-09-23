## 1. The cap helper (`:domain` model/)

- [ ] 1.1 Add a pure UTF-8 byte cap beside `redactUuids`: cut on a code-point boundary, append `…[+<n> B]`,
      return a text within the cap untouched (marker included in the cap)
- [ ] 1.2 `commonTest`: ASCII and multi-byte cuts (never split a sequence), the exact-cap and one-over
      boundaries, the marker's dropped-byte count, the untouched short text
- [ ] 1.3 Add the event-size constants beside `DIAGNOSTIC_LOG_BUDGET_BYTES` (`MAX_EVENT_SIZE`, the breadcrumb
      count, the per-crumb cap and the event-text cap). Rewrite that KDoc's arithmetic as the whole-event sum
      from design D2

## 2. Apply the caps (`:adapter:ios:ext-safe`)

- [ ] 2.1 `SentryDiagnosticsReporter.start()`: set `options.maxBreadcrumbs` to the pinned count
- [ ] 2.2 `scrubbedBreadcrumb`: cap message plus string data values to the per-crumb cap combined,
      message first, after redaction
- [ ] 2.3 `scrubbedEvent`: cap the message (`message`/`formatted`) and each exception value, for
      non-exempt events only; the dump's exemption path stays untouched
- [ ] 2.4 `iosTest`: pin both caps on the scrub functions directly, including SDK-shaped breadcrumbs with
      several data keys, and that an exempt event is not capped

## 3. Prove the sum (`:test:contracts` + bindings)

- [ ] 3.1 `DiagnosticsReporterContract`: add `WIRE_WORST_CASE_DUMP_ARRIVES` (design D5): 100 over-cap lines
      through Kermit, then a dump with full-budget, escape-heavy tails, a 100-char event name and a
      maximal note; assert the dump arrives and then the sentinel
- [ ] 3.2 Bind it on the live `IOS_SIM_KEXE` binding. The fake answers `NotRunHere`, like the other wire
      clauses
- [ ] 3.3 Run it on the Mac runner (`ssh-mac-build`). Record the measured decoded size of the worst-case
      event, and whether a changed breadcrumb data map round-trips to the native crumb, in design.md
      (settles the Open Question; re-derive D2 if data does not round-trip)
- [ ] 3.4 Negative check, done once and not committed: raise the crumb cap until the sum passes 1 MiB and
      confirm the clause goes `NotWithin`, so it can fail

## 4. Docs and gates

- [ ] 4.1 Update CLAUDE.md's "Logging & errors" section: a sentence saying every outgoing event is bounded
      by construction (the rule lives in capability `crash-reporting`)
- [ ] 4.2 `./gradlew build`, `compileIosMainKotlinMetadata`, `architectureDiagrams` (commit if changed), and
      `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
