## 1. Reviewer notes

- [x] 1.1 Add `metadata/review/notes.md` with the agreed prose: no account/no demo credentials; the
      single-device test path (create event → take a photo → counts to "In sync"); the capture-date cutoff
      explanation (a reviewer on a stock library otherwise reads correct behaviour as broken); the
      second-device note plus the offer of a live event link via Resolution Center; and the closing
      Unlisted App Distribution intent paragraph (which Apple requires before an unlisted request).
- [x] 1.2 Confirm the required gate still ignores it: `"$ASC" metadata validate --dir metadata` →
      `filesScanned: 2`, `valid: true`. An unknown key in a *canonical* file would freeze merges; this file
      must stay outside that schema.

## 2. Workflow: split into build + finish

- [x] 2.1 Replace `on: push: tags: ['v*']` with `on: workflow_dispatch`, inputs `version` (required,
      string) and `submit` (boolean, default false).
- [x] 2.2 Set `concurrency.group` to key on `inputs.version` (NOT `github.ref`) — dispatched from `main`,
      every release would otherwise share one group and cancel its predecessor mid-flight.
- [x] 2.3 Split the single job into `build` (macos-26) and `finish` (ubuntu-latest, `needs: build`). Job-level
      permissions: `build` keeps `contents: read` + `checks: read`; `finish` gets `contents: write` for the
      tag push and holds no signing certificates.
- [x] 2.4 Rewrite the header comment: the trigger, the two-job rationale (asc is linux-only via
      `asc_fetch.sh`), and the tag-first-check/tag-last-create ordering.

## 3. Guards (job `build`, before anything is built)

- [x] 3.1 Validate `version` against `^\d+\.\d+$`; fail fast. Derive `STORE_VERSION` and `TAG=v$version`.
- [x] 3.2 NEW: fail if the tag already exists (`git rev-parse -q --verify "refs/tags/$TAG"` after
      `git fetch --tags`). Must run BEFORE the build so a doomed release fails in seconds, not ~30 minutes.
      Always fatal — a released tag is never moved.
- [x] 3.3 Keep the ancestor-of-`origin/main` guard; reword its messages away from "tag". It is now
      load-bearing: dispatch can run from any ref.
- [x] 3.4 Keep the every-check-run-green guard, rewording messages away from "tag" — and FIX its
      self-exclusion, which the original decision record deferred ("by job name vs by check-suite id —
      settle at apply against a real SHA"). It excluded only *in-progress* runs, so a release that failed
      would leave a completed non-success check-run of its own on the commit and refuse every retry
      forever. Exclude by the check-suites this workflow file's runs produced for that SHA: that covers
      every attempt, past and present, and survives a job rename.

## 4. Job `finish`: attach, review details, submit, tag

- [x] 4.1 `actions/checkout` + `bash .github/scripts/asc_fetch.sh "$RUNNER_TEMP/asc"` (linux path, unchanged).
- [x] 4.2 Move the attach step here: `appstore_release.py release "$ASC_APP_ID" "${{ github.run_number }}"
      "$STORE_VERSION"` — unchanged script; it resolves the build by number, so no job outputs are needed.
- [x] 4.3 Review details, applied on EVERY run: resolve the version id, then
      `asc review details-for-version` → `details-create` if absent else `details-update`, passing
      `--notes "$(cat metadata/review/notes.md)"`, the four contact secrets, and
      `--demo-account-required false`.
- [x] 4.4 Submit gate, only when `inputs.submit`: `asc review doctor --app "$ASC_APP_ID" --output json`;
      abort printing `.blockingChecks[].message` unless `.summary.blocking == 0`.
- [x] 4.5 Submit, only when `inputs.submit` and the gate passed: `asc review submit ... --confirm`.
- [x] 4.6 LAST step: create and push the tag on the released commit. Deliberately after submit — a tag created
      earlier would survive a failed submit and then block every retry on 3.2.

## 5. Secrets

- [x] 5.1 Add `ASC_REVIEW_CONTACT_FIRST_NAME`, `ASC_REVIEW_CONTACT_LAST_NAME`, `ASC_REVIEW_CONTACT_EMAIL`,
      `ASC_REVIEW_CONTACT_PHONE` via `gh secret set`. They are personal data and this repo is public — they
      can be neither committed nor passed as inputs (inputs render in the public Actions UI).
- [x] 5.2 Wire them into the `finish` job's `env`.

## 6. Docs

- [x] 6.1 Rewrite the CLAUDE.md "App Store releases are tag-driven (`git push vX.Y`)" section and every other
      mention of the tag channel: the trigger, the `submit` input, the tag-is-created-by-CI inversion, and the
      two-job split.
- [x] 6.2 AT ARCHIVE TIME: rewrite `openspec/specs/ios-appstore-release/spec.md`'s `## Purpose` by hand. A
      delta cannot reach it (verified: it survives the archive untouched, still asserting "Makes a pushed
      `vX.Y` git tag the trigger" and "stopping short of submit-for-review"). Also add this change to its
      `Decision record:` line.

## 7. Verify

- [x] 7.1 Parse both workflows to catch YAML errors without a run:
      `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/ios-release.yml`
- [x] 7.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` (matching `build.yml`).
- [x] 7.3 Confirm `.github/rulesets/main.json` still does NOT list `ios-release` — it must never gate a merge.

<!-- The first dispatch's acceptance check — build-only run clearing both blockers, the tag guard
     refusing a re-run, and only then a submit=true run — is in design.md under "Verify on the first
     dispatch". It is not implementation work and cannot be observed before the merge that enables it.
     Note that NOTHING in this path has ever run: no vX.Y tag was ever pushed, so even the pre-existing
     build→attach flow is unproven. -->
