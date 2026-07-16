## 1. App-info listing fields (data only)

- [x] 1.1 Add `"subtitle": "Group photos, shared instantly"` and
      `"privacyPolicyUrl": "https://snapsync.stho.net/#privacy"` to `metadata/app-info/en-US.json`,
      keeping the existing `name`. No script or workflow edits — the apply already covers app-info.
- [x] 1.2 Verify the required gate passes offline against the pinned binary:
      `bash .github/scripts/asc_fetch.sh "$TMP/asc" && "$TMP/asc" metadata validate --dir metadata`
      → expect `"valid": true`, `errorCount: 0`.
- [x] 1.3 Confirm the subtitle is exactly 30/30 characters, so the copy is known to sit at the limit and
      any future edit must re-count.

## 2. Copyright at version creation

- [x] 2.1 In `.github/scripts/appstore_release.py`, add a module constant
      `COPYRIGHT = "2026 Stefan Hoelzl"` with a comment stating the format is `YYYY Name` where the year
      is that of **first publication** and must NOT roll with the calendar year.
- [x] 2.2 Add `"copyright": COPYRIGHT` to the `attributes` of the `POST /appStoreVersions` payload in
      `_find_or_create_version`, alongside `platform` and `versionString`.
- [x] 2.3 Confirm by inspection that the existing-record path still returns early and issues no PATCH, so
      version `1.0`'s hand-set copyright is untouched.
- [x] 2.4 Byte-compile the script to catch syntax errors without a release run:
      `python3 -m py_compile .github/scripts/appstore_release.py`.

## 3. Verify against App Store Connect

- [x] 3.1 Dry-run the apply exactly as CI runs it, against the edited files, and confirm the plan contains
      both app-info adds and no unintended version-localization writes:
      `asc metadata push --app 6781692480 --version 1.0 --platform IOS --dir metadata --include localizations --dry-run`
- [x] 3.2 Confirm the plan's `apiCalls` shows only an app-info `update_localization` — proving the change
      is scoped to app-info and does not disturb the description/keywords already live.

## 4. Verify

- [x] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` (matching `build.yml`).
- [x] 4.2 `python3 -m py_compile .github/scripts/appstore_release.py` — the release script has no test
      harness, so byte-compilation is the only pre-merge check of the edit.

<!-- The first `main` run's acceptance check — the two warnings clearing, and the fallback if Apple
     rejects the fragment privacy URL at push time — is in design.md under "Verify on the first `main`
     run". It is not implementation work and cannot be observed before the merge that enables it. The
     copyright edit is unobservable even then: it applies only to a version record CREATED by a future
     `vX.Y` tag, and `1.0` already exists. -->

