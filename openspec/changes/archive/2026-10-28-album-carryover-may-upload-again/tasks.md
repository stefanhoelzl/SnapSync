# Tasks

## 1. Claim the corrected scenario

- [x] 1.1 In `AlbumIntegrationTest`, the test of a photo shared to an earlier event asserts only the corrected outcome:
  after the join and the device's first pass, the photo is in the new event's album, with no assertion about uploads.
  Give it `@Verifies(spec = "event-album", requirement = "Turning the album on gathers what is already there",
  scenario = "A photo shared before the join arrives shortly after")`, and verify that it passes with
  `./gradlew :test:integration:test --tests 'app.snapsync.integration.AlbumIntegrationTest'`.

## 2. Archive

- [x] 2.1 Sync the delta into `openspec/specs/event-album/spec.md` by archiving, and verify that the main spec's scenario
  no longer contains "without being uploaded again", that `git diff` of the main spec shows only that change, and that
  both gates in `openspec/config.yaml` (placeholder Purpose, no code identifiers) print nothing.
- [x] 2.2 Verify that `./gradlew build` passes, so `VerifiesGateTest` accepts the claim against the archived heading.
