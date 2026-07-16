## Why

The App Store release channel stops one step short of shipping. `asc validate --app 6781692480 --version 1.0`
now reports **2** blocking checks, and they are precisely the two the release path could close but doesn't:
`build.required.missing` (no `vX.Y` tag has ever been pushed, so no build is attached) and
`review_details.missing` (nothing in the repo owns the text App Review reads). Availability — the third —
was set by hand, because the API can only *edit* an availability record and could not bootstrap one.

Two separate problems keep it there:

- **The trigger is a git tag, and a tag is hard to redo.** A flaked release costs
  `git tag -d && git push --delete && git tag && git push`. For the one step that reaches App Review, being
  re-runnable matters more than trigger purity. The original decision record argues at length for deriving
  the *version* from the tag but is **silent on the trigger itself** — dispatch was never weighed, so this
  reopens a question rather than overturning an answer.
- **Submission was a non-goal for a reason that has expired.** It was deferred because "the listing,
  screenshots and privacy that App Review requires are owned elsewhere". Screenshots landed, listing text
  landed, and the privacy policy URL and subtitle landed in
  `2026-07-16-close-appstore-submission-gaps`. What blocked submission is now done.

## What Changes

- **`ios-release.yml` becomes `workflow_dispatch`-driven**, with a `version` input (`^\d+\.\d+$`) and a
  `submit` boolean defaulting to false. The workflow **creates the `vX.Y` tag itself**, last, so the tag
  becomes the record of what shipped rather than the command that ships it.
- **Two new guards**: the tag must not already exist (checked *first*, before a ~30-minute build, and
  always fatal), and — gating `submit` only — `asc review doctor` must report **zero** blocking checks.
  The three existing guards (ancestor-of-`main`, every-check-run-green, version shape) survive.
- **Review details become repo-owned**, applied declaratively on **every** run so a build-only release is
  left submit-ready: notes from a new committed `metadata/review/notes.md`, contact from four new secrets,
  and no demo account (the product has no sign-up).
- **The workflow submits when explicitly asked** — reversing the current "SHALL NOT submit" — behind both
  the input and the doctor gate.
- **The workflow splits into two jobs**: `build` on macOS (guards, archive, export, upload) and `finish` on
  ubuntu (attach, review details, optional submit, tag). Forced, not cosmetic: every new step needs `asc`, and
  `asc_fetch.sh` fetches a **Linux** binary verified with `sha256sum` — neither of which works on the macOS
  runner. The split leaves that script, which the required `appstore-metadata-validate` check depends on,
  untouched.
- **Two fixes the switch forces**, neither optional: the concurrency group must key on the `version` input
  (dispatched from `main`, every release would otherwise share one group and **cancel** its predecessor
  mid-flight), and `contents: write` is needed to push the tag — scoped to the `finish` job, so the job
  holding the signing certificates never gains write access to the repo.

Not breaking for any consumer: the alpha channel, `MARKETING_VERSION` injection, and the shared archive
composite are untouched.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-appstore-release`: the trigger requirement changes from a pushed tag to a dispatch that *creates*
  the tag; the attach requirement's "never submits" clause becomes "submits when explicitly asked, and
  refuses when `review doctor` reports a blocker"; and a new requirement makes review details repo-owned.

## Impact

- `.github/workflows/ios-release.yml` — trigger, inputs, guards, concurrency key, permissions, and two new
  steps (review details, conditional submit + tag).
- `metadata/review/notes.md` — **new**. Invisible to `appstore-metadata-validate` (verified), because an
  unknown key in a *canonical* metadata file would fail a required check and freeze merges.
- **Four new secrets**: `ASC_REVIEW_CONTACT_FIRST_NAME`, `_LAST_NAME`, `_EMAIL`, `_PHONE`. This repo is
  public, so the App Review contact details can be neither committed nor passed as dispatch inputs (which
  render in the public Actions UI).
- `CLAUDE.md` — documents the tag-driven channel in several places; all must be rewritten.
- App Store Connect — closes both remaining blocking checks; enables a submission that has never been made.
- **Out of scope**: the App Privacy questionnaire (console-only), category, availability (set by hand), and
  the Unlisted App Distribution request itself (an Apple form with no API — and one that *follows* a
  submission rather than gating it).
