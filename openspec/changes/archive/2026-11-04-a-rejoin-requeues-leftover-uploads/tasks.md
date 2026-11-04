# Tasks

Prerequisite: phase 4.4b's move II has landed on the branch — `ShareSetLoad` reads `DeviceListing` and resets through
`UploadRowsReset`.

## 1. The failing behaviour, against today's code

- [x] 1.1 Add the integration scenario "A photo whose upload a leave stopped is shared after rejoining" (`@Verifies`
  delivery, "A photo is never lost on the way", that scenario): extension uploads only, joined, a photo's job created,
  the member leaves, the backend's device listing fails, the member rejoins the same event, a `cycle()` → a job is made
  for the photo, and `completeJob` stores its bytes. Verify: it FAILS on the branch before task 2 (no job is made — the
  row stays in flight), and `./gradlew :test:architecture:verifiesReport` lists the scenario once the delta is synced.

## 2. The join's kept reset

- [x] 2.1 A `Ledger.sq` statement returns an event's `REQUESTED` rows to `DISCOVERED`, clearing their destination.
  Verify: the manifest version does not advance on it (D1); the generated query compiles.
- [x] 2.2 `UploadRowsReset.purgeExcept` runs the purge and that statement in one transaction (D1); its KDoc and
  `ShareSetLoad`'s describe the kept path's demotion and why it is safe (D2). Verify: unit tests in `:test:data` —
  another event's rows are purged, the joined event's `REQUESTED` row is `DISCOVERED` with no destination, a `COMPLETED`
  row stays `COMPLETED`, and a failed write leaves the store as it was.
- [x] 2.3 `ShareSetLoadTest`'s kept-path cases assert the in-flight leftover returns to needing a job. Verify: the
  listed path's cases are unchanged (D3); `./gradlew build` green; task 1.1 passes.
