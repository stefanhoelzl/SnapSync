# Tasks

## 1. The label shows the end's own day

- [x] 1.1 Remove the midnight adjustment from the design system's date-range label, so the last day is the end's
      local date; verify `:ui:components`' label tests pass with the midnight cases rewritten to the new outcome
      ("Sun 12 Jul – Tue 14 Jul" for 12 Jul 14:00 → 14 Jul 00:00, and 13 Jul 18:00 → 14 Jul 00:00 read as two days)
- [x] 1.2 Update the label's KDoc to state that the range reads as chosen, and verify no comment or doc elsewhere still
      describes the midnight rule (`grep -rn "midnight" ui/ docs/`)
- [x] 1.3 Verify `:ui:screens` still passes and stays at zero (`./gradlew :ui:screens:coverageZero`), since its joined
      screen tests render this label

## 2. Integration

- [ ] 2.1 Verify the change validates (`npx --yes @fission-ai/openspec@1.13.2 validate show-chosen-end-day --strict`)
      and `./gradlew build` is green
