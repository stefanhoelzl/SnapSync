## 1. Wire the upload into the release workflow

- [x] 1.1 Add an "Install ImageMagick" step to `ios-appstore-promote.yml`'s job, mirroring
      `appstore-screenshots.yml`: `apt-get install -y imagemagick fonts-liberation`, then prove the binary
      runs with the `command -v magick || convert --version` form (never `... | head -1`, which masks a
      missing binary behind `head`'s exit status) and assert a bold sans font is present.
- [x] 1.2 Add an "Upload the listing screenshots" step running
      `bash .github/scripts/asc_screenshots_upload.sh` with `ASC: ${{ runner.temp }}/asc`, placed **after**
      "Attach the build to its App Store version record" and **before** "Refuse to submit an unready
      version". Pass no version — the script resolves the editable version itself.
- [x] 1.3 Confirm the job's existing `env:` block already supplies everything the script needs
      (`ASC_APP_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY`, `ASC_BYPASS_KEYCHAIN`), and add
      nothing new. No new secret.
- [x] 1.4 Extend the workflow's header comment to record that the release now uploads screenshots, why the
      step sits between the attach and the submit gate, and why `cancel-in-progress` deliberately stays
      `true` (design D4) — the file's comments are where its rationale lives.

## 2. Retire the push-triggered uploader

- [x] 2.1 Verify `asc_screenshots_upload.sh` and `compose_screenshots.sh` are unmodified — moving the caller
      must add no branch, argument, or environment assumption to either script.
- [x] 2.2 Re-read `asc_screenshots_upload.sh` in the promote context to confirm its assumptions still hold
      there: it composites with `RAW_DIR=screenshots` relative to the checkout, and writes to
      `${RUNNER_TEMP}/shots-out`.
- [x] 2.3 Delete `.github/workflows/appstore-screenshots.yml`.
- [x] 2.4 Confirm nothing else depended on it: `site-deploy.yml` triggers on `screenshots/**` itself (the
      landing page still rebuilds on merge), and `appstore.yml` owns the listing TEXT independently.
- [x] 2.5 Sweep live references to the deleted workflow — `CLAUDE.md` and the promote workflow's comments.
      Archived changes under `openspec/changes/archive/` are historical records and stay untouched.
- [x] 2.6 Correct `CLAUDE.md`'s screenshot runbook: a merge no longer ships the store listing, "a headline
      change needs NO dispatch" is no longer true for the store, and correcting an already-promoted
      version is a manual console upload.

## 3. Verify

- [x] 3.1 Lint the changed workflow (`actionlint` if available, otherwise a YAML parse) and confirm step
      ordering matches design D3.
- [x] 3.2 Confirm the change adds no entry to `.github/rulesets/main.json` — the release workflow must keep
      posting no required status check.
- [x] 3.3 Dry-check the gate's behaviour claim against the live account state: with only a
      `READY_FOR_SALE` version, `asc versions list --state PREPARE_FOR_SUBMISSION,DEVELOPER_REJECTED`
      returns nothing, which is why the push-triggered upload no-ops today. Record the observation in the
      PR description rather than adding a test.
- [x] 3.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and confirm it passes
      (structure only — it checks well-formedness, not truth).

## 4. Ship

- [ ] 4.1 Open the PR with the already-committed screenshot refresh, noting that merging it is a green
      no-op today and that the next promote is what carries those raws to the store.
- [ ] 4.2 After merge, the first real exercise is the next `ios-appstore-promote.yml` dispatch — confirm
      from its log that the dry-run lists the six composited images and the apply replaces the set.
