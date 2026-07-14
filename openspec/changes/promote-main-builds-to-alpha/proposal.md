## Why

`ios-deliver` uploads a signed build to App Store Connect on every push to `main` and stops there. Nothing assigns the build to a tester group, so builds accumulate in ASC unseen: at the time of writing, builds 295, 297, 300, 302, 308 and 310 all sit `VALID` / `READY_FOR_BETA_SUBMISSION` in no external group. The `alpha` group — external, public link `https://testflight.apple.com/join/pvqgV7Uz`, no tester limit — exists and holds exactly one build (293), put there by hand.

The gap between "the build exists in App Store Connect" and "an alpha tester can install it" is currently closed by a human, or not at all. This change closes it in CI, so the public alpha channel simply *is* `main`.

The spec's own Purpose describes delivery "as a release trail (no Beta App Review)" — that framing is now obsolete, and the evidence says the fear behind it was misplaced. A probe against build 310 established that a same-`MARKETING_VERSION` build auto-approves **instantly** once that version has been approved once (293 was, on 2026-07-09), and that `beta-groups add-build` accepts a build while it is still `WAITING_FOR_REVIEW`. Beta App Review is therefore not a gate in practice, and promotion needs no human and no waiting on Apple's reviewers.

## What Changes

- Add a third job, **`ios-promote`**, to `.github/workflows/ios.yml`: `needs: ios-deliver`, `main`-only, on `ubuntu-latest`. It promotes the build `ios-deliver` just uploaded into the `alpha` external group. It compiles nothing, needs no Xcode, no IPA and no signing certificates — it is pure App Store Connect REST against the build identified by `github.run_number`.
- **Every** `main` build is promoted, with no filtering on paths or commit type. Docs-only and backend-only merges therefore ship a binary-identical build to alpha. This is deliberate: every filter we considered fails toward *"a real fix silently never reaches testers"*, which is the worst outcome an alpha channel can have, whereas promoting everything fails only toward noise.
- **No tester is ever notified.** `buildBetaDetails.autoNotifyEnabled` is set to `false` on every promoted build. Alpha testers ride `main` continuously via TestFlight auto-update; TestFlight never pushes. **The suppression must land before the build joins the group** — the notification fires on group availability — so this is an ordering invariant, not a nicety.
- Promotion is **idempotent**: a build already `BETA_APPROVED` and in `alpha` is a green no-op, so re-running a flaked job is always safe.
- Newest build wins: `--expire-build-submitted-for-review` expires any build queued in review, so a pile-up (only possible after a `MARKETING_VERSION` bump, which forces a genuine first-of-version review) resolves toward the newest `main` build.
- `ios-promote` posts **no branch-protection status check** and is free to fail red without blocking anything — the same structural decoupling `ios-deliver` already uses.
- Builds 295–308 are left stranded. 310 supersedes them; backfilling would dump five superseded builds on the group.
- No new secrets: `ios-promote` reuses the existing Admin `ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_API_PRIVATE_KEY`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-testflight-delivery`: today the capability ends at "uploaded to App Store Connect". It gains the promotion leg — the `ios-promote` job, the invariant that every `main` build reaches the `alpha` external group, the never-notify rule and its ordering constraint, idempotency, newest-wins on review pile-up, and the fact that a concurrency-cancelled promotion is benign rather than a bug. Its Purpose is rewritten: the "no Beta App Review" claim is now false.

## Impact

- `.github/workflows/ios.yml` — one new job (`ios-promote`).
- `.github/rulesets/main.json` — **unchanged**, deliberately. `ios-promote` never runs on a PR branch, so requiring it would freeze every merge (the same trap the file's header already documents for `ios-deliver`).
- App Store Connect — the `alpha` external group becomes CI-fed. Its public link is uncapped and the bunny upload endpoint is unauthenticated; that exposure is accepted, not addressed here.
- `CLAUDE.md` — the TestFlight paragraph should note that `main` now feeds the public alpha channel.
- No application code, no Gradle module, no test changes. The `ios-build` and `ios-test` merge gates are untouched.
