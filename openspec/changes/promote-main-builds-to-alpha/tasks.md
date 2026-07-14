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

## 4. Verification — done, on a throwaway branch run

Rather than wait for merge, the four `main`-only guards were temporarily relaxed onto `refs/heads/alpha` (commit `8c962a7`, reverted by `c88e1a7`) and the full chain `ios-build → ios-deliver → ios-promote` was run for real on [run 29353145285](https://github.com/stefanhoelzl/SnapSync/actions/runs/29353145285), producing **build 318**. All four jobs green.

- [x] 4.1 Confirmed against the live App Store Connect API, not just a green check: build 318 is **in the `alpha` group**, `externalBuildState: BETA_APPROVED`, `autoNotifyEnabled: false`. The suppression held — no tester was notified. (The group had **0 testers** at the time, so nothing was actually distributed.)
- [x] 4.2 Measured — **and the estimate in `design.md` was badly wrong, in the safe direction.** `ios-promote` did its work in **8 seconds**: the build was discoverable on the *first* lookup (zero retries) and already `VALID` 96 s after upload. The predicted "3–15 min of waiting" does not exist for an app this size. `FIND_TIMEOUT_S = 600` and `--max-build-processing-wait 20` are therefore vastly slack — **left as-is deliberately**: they cost nothing on a fast day and are the only thing standing between a slow day at Apple and a red run. Tightening them would trade real safety for no gain. The *reasoning* for Ubuntu still holds (no Apple toolchain needed), but the "keep the idle off a macOS runner" premise it was sold on was inflated.
- [x] 4.3 Re-ran the completed `ios-promote` job: `resolve` ran, then **all four mutation steps skipped** and the job concluded green. The idempotency guard behaves exactly as specified.

Fallout cleaned up: build 318's release note (which read *"TEMP: DO NOT MERGE…"*, taken from the throwaway commit subject) was rewritten to the real `main` subject, since 318 is the highest build number and would be what a new public-link joiner installs. The build itself is left in place — it is binary-identical to `main` apart from the reverted CI YAML, and expiring a TestFlight build stops it launching for anyone who already has it.
