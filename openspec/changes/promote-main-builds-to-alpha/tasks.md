## 1. The `ios-promote` job

- [x] 1.1 Add an `ios-promote` job to `.github/workflows/ios.yml`: `needs: ios-deliver`, `if: github.ref == 'refs/heads/main'`, `runs-on: ubuntu-latest`. No Java, no Gradle, no Konan cache, no keychain, no certificate imports — it compiles nothing and signs nothing.
- [x] 1.2 Wire the existing Admin App Store Connect secrets into the job as the env vars the `codemagic-cli-tools` CLI reads: `APP_STORE_CONNECT_ISSUER_ID` ← `ASC_ISSUER_ID`, `APP_STORE_CONNECT_KEY_IDENTIFIER` ← `ASC_KEY_ID`, `APP_STORE_CONNECT_PRIVATE_KEY` ← `ASC_API_PRIVATE_KEY`. Add no new secret.
- [x] 1.3 Locate the build: retry until App Store Connect returns the build whose build number is `github.run_number`, capped at ~10 minutes; fail red if it never appears (a freshly uploaded build is not immediately discoverable). Implemented as `testflight_promote.py resolve` — the CLI's `builds list` would return empty and exit 0, so it cannot express the retry.
- [x] 1.4 Idempotency guard: if the build is already `BETA_APPROVED` **and** already in `alpha`, log and exit 0 without mutating anything. Folded into `resolve` (one `filter[betaGroups]` query answers both halves) and published to later steps as `ALREADY_PROMOTED`.
- [x] 1.5 Write the release note: `app-store-connect builds add-beta-test-info <build-id> --whats-new "<commit subject> (<short sha>)"`.
- [x] 1.6 Submit: `app-store-connect builds submit-to-testflight <build-id> --expire-build-submitted-for-review --max-build-processing-wait 20` (this is what waits for `processingState: VALID`).
- [x] 1.7 Suppress the notification **before** group assignment: `PATCH /v1/buildBetaDetails/<build-id>` with `{"autoNotifyEnabled": false}` (the CLI has no flag for this) — `testflight_promote.py silence`. A failure here aborts before 1.8; this ordering is a spec requirement, not a style choice.
- [x] 1.8 Assign: `app-store-connect beta-groups add-build <build-id> --beta-group alpha`.
- [x] 1.9 Add a header comment to the job explaining, in the style of the file's existing comments: why it is a separate job on Ubuntu; why it must not be added to `.github/rulesets/main.json`; why the 1.7 → 1.8 order is load-bearing; why promotion is unfiltered; and why a `MARKETING_VERSION`-bump stall and a concurrency-cancelled promote are both expected rather than bugs. Also updated the file's four-job header.

## 2. Spec of record — performed by `/opsx:archive`, not here

`openspec archive` is what "updates main specs"; there is no standalone `sync` in the pinned CLI. Hand-applying the delta now would collide with it. 2.2 and 2.3 are the parts `archive` **cannot** do (deltas carry no `## Purpose`, and the archive id does not exist until archive time), so they are hand edits to make **during** the archive step.

- [ ] 2.1 Let `/opsx:archive` sync the delta into `openspec/specs/ios-testflight-delivery/spec.md` (the `ADDED` requirements + the `MODIFIED` "Delivery never blocks merges, and never fails silently").
- [ ] 2.2 Rewrite that spec's `## Purpose` by hand: it currently claims delivery happens "as a release trail (**no Beta App Review**)", which the build-310 probe falsified. It must now describe the full path — merge → archive → export/upload → promote into the public `alpha` external group — and state that Beta App Review is in the loop but auto-approves for an already-approved `MARKETING_VERSION`.
- [ ] 2.3 Add the `Decision record: changes/archive/<id>` citation for this change.

## 3. Documentation

- [x] 3.1 Update `CLAUDE.md`: every merge to `main` now lands in the **public** alpha TestFlight channel (<https://testflight.apple.com/join/pvqgV7Uz>), silently — testers are never notified and ride `main` via auto-update. Added as a new *"`main` is the public alpha channel"* section.
- [x] 3.2 Document the `MARKETING_VERSION` trap in `CLAUDE.md`: bumping it forces a genuine first-of-version Beta App Review (hours to days), during which each merge expires its predecessor's submission, **no build reaches testers, and nothing goes red**. The symptom is otherwise baffling.

## 4. Verification — post-merge (`ios-promote` is `main`-only and cannot run on this PR)

The call sequence itself is already proven: it was run by hand against build 310 with the same Admin key CI uses, and `testflight_promote.py resolve` was exercised against the live API on both branches of its guard (310 → green no-op, 308 → work to do). What remains can only be observed on `main`.

- [ ] 4.1 After merge, watch the first real `ios-promote` run: confirm the build lands in `alpha`, that `autoNotifyEnabled` is `false`, and that `externalBuildState` reaches `BETA_APPROVED`.
- [ ] 4.2 Read the actual find + processing durations off that run and tighten the `FIND_TIMEOUT_S` (script) and `--max-build-processing-wait` (workflow) values if the codemagic defaults are far off — they are estimates, not measurements (every build inspected during design was already `VALID`, and the API exposes no "became valid" timestamp).
- [ ] 4.3 Re-run the completed `ios-promote` job once to prove the 1.4 idempotency guard concludes green rather than erroring on an already-promoted build.
