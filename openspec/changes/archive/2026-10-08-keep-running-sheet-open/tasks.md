# Tasks

## 1. A busy sheet refuses every way out

- [x] 1.1 Give the text prompt sheet's state a `confirmValueChange` that refuses hiding while busy (busy read through
      `rememberUpdatedState`), and verify with a test that swipes a busy sheet and finds it still displayed and not
      dismissed
- [x] 1.2 Set the sheet's properties to refuse a back press and a tap outside while busy, and verify with a test that
      taps outside a busy sheet and finds it still displayed and not dismissed
- [x] 1.3 Verify the idle routes still close it: the existing idle cancel and swipe tests pass, plus a new test that a
      tap outside an idle sheet dismisses it
- [x] 1.4 Update the sheet's KDoc to say how a busy sheet refuses each route, and verify `:ui:components` stays at
      zero (`./gradlew :ui:components:coverageZero`)

## 2. Integration

- [x] 2.1 Verify the change validates (`npx --yes @fission-ai/openspec@1.13.2 validate keep-running-sheet-open
      --strict`) and `./gradlew build` is green
