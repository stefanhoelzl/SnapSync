## 1. Seed the committed listing files

- [x] 1.1 Resolve the current editable version (`asc versions list --app 6781692480 --state PREPARE_FOR_SUBMISSION`) and `asc metadata pull` it into `metadata/` (produces `metadata/version/<ver>/en-US.json` + `metadata/app-info/en-US.json`)
- [x] 1.2 Confirm the pulled files match the currently-live listing (they should — the manual bootstrap already set it), so the first apply is a no-op
- [x] 1.3 Place `metadata/version/1.0/en-US.json` (scoped to the version localization; app-info/app-name left out of v1); console is read-only by convention (documented in design.md). Git commit lands with the PR.

## 2. Pin the asc tool

- [x] 2.1 Choose the install path (hand-rolled `curl` + `sha256sum`, matching the cloudflared pattern); pinned release `2.8.2` + linux_amd64 SHA-256 recorded in `asc_fetch.sh`
- [x] 2.2 Add a reusable step/snippet that fetches `asc`, verifies the checksum, and aborts on mismatch (`.github/scripts/asc_fetch.sh`)

## 3. Validation gate (every ref, no secrets)

- [x] 3.1 Add the `appstore-metadata-validate` job: run `asc metadata validate --dir metadata` offline on every ref, failing on any schema / character-limit / URL / unknown-key violation
- [x] 3.2 Verify it posts a status check on a PR branch and requires no App Store Connect credentials (verified locally: offline `validate` → `valid:true` exit 0 on the real file; `keywords`>100 → `valid:false` exit 1)

## 4. Apply job (main only)

- [x] 4.1 Add the `appstore-metadata-apply` job, `main`-only (`if: github.ref == 'refs/heads/main'`), on `ubuntu`, authenticating with the existing Admin key (`ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_API_PRIVATE_KEY`); no Xcode, keychain, or cert
- [x] 4.2 Resolve the editable version at run time; if none is editable, skip and conclude green (never edit an in-review version, never create one) — `.github/scripts/asc_metadata_apply.sh`
- [x] 4.3 Apply the files to the resolved version's localizations declaratively, without any delete/confirm flags (absent field = no-op) — `metadata push … --confirm`, no `--allow-deletes`
- [x] 4.4 Ensure the job posts no required status check and does not use `continue-on-error` (a failure is red but blocks nothing)

## 5. Require the validator

- [x] 5.1 Add `appstore-metadata-validate` to the required status checks in `.github/rulesets/main.json`
- [x] 5.2 Confirm no merge-freeze risk: the validator runs on every ref and always posts (documented in the workflow header + branch-protection delta)

## 6. Verify end to end

- [x] 6.1 On a branch, make an intentionally invalid edit (e.g. keywords > 100 chars) and confirm the validator fails the check (verified locally — exit 1, "keywords exceed 100 characters")
- [ ] 6.2 Merge a real (valid) listing edit to `main` and confirm the apply updates the editable version's localization — REQUIRES A LIVE CI RUN (post-merge); cannot be exercised pre-merge
- [ ] 6.3 Confirm a merge while no version is editable (or one is in review) results in a green no-op that writes nothing — REQUIRES A LIVE CI RUN
