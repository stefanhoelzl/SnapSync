# Tasks

## 1. Archive the corrected contract

- [x] 1.1 Archive the change, which syncs the deltas into `openspec/specs/app-experience/spec.md`,
  `openspec/specs/manage-membership/spec.md` and `openspec/specs/sync-status/spec.md`.
  - **Verify the diff:** `git diff` of the three main specs shows only the lines of the three deltas. The four
    replaced requirements stay where the old ones stood.
  - **Verify the gates:** both gates in `openspec/config.yaml` print nothing:
    - placeholder Purpose, checked over the whole tree;
    - no code identifiers, checked on the three touched specs.
- [x] 1.2 In the same commit, re-point every `@Verifies` claim that names a replaced requirement to its new name,
  scenario names unchanged, and update the counts requirement's name in the comment of `DownloadStore.sq`.
  - **Verify:** a grep of the repository outside `openspec/changes/archive` finds none of the four old names.
  - **Verify:** `./gradlew :test:architecture:test :test:integration:test` passes, so `VerifiesGateTest`
    accepts every re-pointed claim.

## 2. Claim the corrected scenarios

Each test is in `:test:integration`. It uses the `behaviour { given / whenever / then }` DSL, waits on events,
and judges state via `settled`.

- [x] 2.1 Claim app-experience "The app keeps its place across backgrounding" / "Returning after the event
  finished". The test:
  - joins an event and settles it finished (every member shared and received, its range ended);
  - backgrounds the app, then returns it to the foreground;
  - opens another event's invite;
  - expects no switch question, the new event's join screen, and no membership in the new event until the join
    is confirmed.

  Verify that it passes with `./gradlew :test:integration:test --tests 'app.snapsync.integration.SwitchQuestionIntegrationTest'`.
- [x] 2.2 Claim manage-membership "A settings change takes effect as soon as it applies" / "Turning sharing off withdraws
  the upload under way". The test:
  - starts with a photo whose upload transfer was created;
  - runs `reconfigure` to receive-only and confirms;
  - completes the upload session;
  - expects that no other member's union ever lists the photo and that no new transfer is created.

  Verify that it passes.
- [x] 2.3 Claim sync-status "The joined screen shows one status line in a fixed priority" / "Switching both directions off mid-upload is
  said at once". The test:
  - starts with a photo uploading;
  - runs `reconfigure` to neither and confirms;
  - expects `settled` to read "Not sharing or receiving" before the upload session completes.

  Verify that it passes.
- [x] 2.4 Claim sync-status "Direction arrows show the work left and the transfer running" / "Switching sharing off
  mid-upload clears the upload arrow". The test:
  - starts with a photo uploading and everything to receive arrived;
  - switches to receive-only;
  - expects `settled` to show no upload arrow and "Up to date" before the upload session completes.

  Verify that it passes.
- [x] 2.5 Claim sync-status "The joined screen counts the photos shared and received" / "Switching sharing off
  mid-upload counts nothing as shared". The test:
  - starts with 3 photos to share, one of them uploading;
  - switches to receive-only;
  - expects `settled` counts that say not sharing.

  Verify that it passes.
- [x] 2.6 Verify that `./gradlew build` passes, so `VerifiesGateTest` accepts every claim against the archived
  headings.

## 3. Record the follow-up

- [ ] 3.1 Record cancelling the operating system's transfer of a withdrawn upload as a follow-up in phase 22's
  report to the design session. Verify that the report names it.
