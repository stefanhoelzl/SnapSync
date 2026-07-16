## Context

The release channel works up to the point where it would matter and stops. `asc validate` reports 2 blocking
checks on version `1.0`, both closable here:

| check | closed by |
| --- | --- |
| `build.required.missing` | the dispatch building `1.0` and attaching it (no tag was ever pushed, so no build exists) |
| `review_details.missing` | the new review-details step |

A third, `availability.missing`, was closed by hand before this change: `asc pricing availability` commands
*"operate on existing availability records. For initial bootstrap, use App Store Connect"* — verified live
(`app availability not found for app "6781692480"`), so no automation was possible. The operator set
Germany-only and blocking went 3 → 2.

### The trigger question was never asked

`changes/archive/2026-07-15-add-appstore-release-pipeline` argues carefully for deriving the **version** from
the tag — and never once weighs the **trigger**. `workflow_dispatch` appears nowhere. So this change reopens
an unasked question rather than reversing a considered decision.

**The justification is re-runnability, not data.** A flaked release currently needs
`git tag -d && git push --delete && git tag && git push`; a dispatch is a button. That is the whole case. It
is worth being explicit that there is **no per-submission data** to pass — the idea that there was is what
originally motivated dispatch, and it died under examination (below).

### The event link: proposed, then dropped

The initial proposal was to pass a live SnapSync "review event" link as a dispatch input, on the theory that
App Review cannot test a photo-sharing app alone. Two findings killed it:

- **A solo reviewer can self-serve.** `event-creation-ui` names an event and **auto-joins** it, and
  `startsAt` *is* the capture-date cutoff — so create an event, take a photo, and it uploads while the status
  screen counts to "In sync". The upload half of the product demos from a cold install with no setup. Only
  *receiving* another person's photos needs a second device, and the notes offer a live event via Resolution
  Center if a reviewer ever asks — which costs nothing until asked.
- **It would have leaked the upload capability.** The `event-link` spec puts the payload in the URL fragment
  precisely because *"the `eventId` ... is the upload capability"* and a browser never transmits a fragment.
  A `workflow_dispatch` input renders in the Actions UI, which is **public** on this repo — so anyone could
  join the event under review, upload into it, and download the reviewer's photos. Offensive content injected
  into the event Apple is reviewing is a plausible rejection. The same reasoning puts the contact details in
  secrets rather than inputs or the repo.

## Goals / Non-Goals

**Goals:**

- Make a release re-runnable: dispatch, not a tag push.
- Keep a durable record of what shipped — the workflow creates the tag itself.
- Close both remaining blocking checks from the repo.
- Let the operator submit for review deliberately, and refuse to submit something unready.

**Non-Goals:**

- The App Privacy questionnaire (console-only, not API-verifiable), category, and availability (done by hand).
- The Unlisted App Distribution request — an Apple form with no API, which *follows* a submission.
- Changing the alpha channel, `MARKETING_VERSION` injection, or the shared archive composite.
- Arbitrary-SHA releases (see Risks).
- Proving the path end to end: no `vX.Y` tag has ever been pushed, so nothing here has run.

## Decisions

### 1. Dispatch with `version` + `submit`; always build

`version` is validated `^\d+\.\d+$` and the tag is `v` + version. `submit` defaults false. There is
deliberately **no `skip_build`**: one code path, always correct, and a fresh build off the released commit is
what you want anyway. A metadata-only resubmit therefore costs a rebuild — accepted.

*Alternative rejected:* an `sha` input to restore arbitrary-commit releases. It would run the workflow **file**
from the dispatched ref while checking out **code** from another commit — two commits in one run, a confusing
failure mode when they diverge. The ref dropdown lists branches, so pointing a branch at a commit recovers the
capability without the ambiguity.

### 2. Check the tag first; create it last

The tag must not already exist, and a collision is **always** fatal — a released tag is never moved, because a
moved tag lies about what shipped.

The ordering is load-bearing and non-obvious. The existence check runs **first**, so a doomed release fails in
seconds rather than after a ~30-minute build. The tag is created **last**, after submit. Were it created after
attach but before submit, a failed submit would leave the tag behind and every retry would then die on the
existence check — **the submission could never be retried**. Tag-last means a failed submit leaves no tag and a
retry is clean.

### 3. Review details are repo-owned, applied every run

Find-or-create (`details-for-version` → `details-create` else `details-update`), applied on **every** run
rather than only when submitting, so a build-only release leaves the version submit-ready instead of one manual
step short. This mirrors the listing text's declarative stance: the file wins, drift is overwritten.

- **Notes** live in `metadata/review/notes.md`. Verified that a new `metadata/review/` subtree is invisible to
  the required gate (`filesScanned: 2, valid: true`) — the same trick `metadata/screenshots/` uses, and for the
  same reason: an unknown key in a *canonical* metadata file fails `appstore-metadata-validate`, a **required**
  check, and freezes every merge.
- **Not `<locale>.json`.** `appStoreReviewDetail` is version-scoped and **not localized** — one per version, no
  locale — so a per-locale filename would imply a structure that does not exist. Prose markdown also keeps the
  text Apple actually reads readable in a diff, instead of `\n\n`-escaped JSON.
- **Contact** comes from four new secrets (`ASC_REVIEW_CONTACT_FIRST_NAME` / `_LAST_NAME` / `_EMAIL` /
  `_PHONE`). This repo is public: committing them publishes a legal name and phone number irrevocably into git
  history, and a dispatch input publishes them into the Actions UI. Secrets are the only home that doesn't.
- **`--demo-account-required` is false.** The product has no account and no sign-up, so there are no
  credentials to give. The capability's "no new secret" boast is spent here, deliberately.

**The notes' cutoff paragraph is load-bearing, not padding.** A reviewer on a device with a stock photo library
creates an event, sees zero photos, and may read correct behavior as broken — a plausible 2.1 App Completeness
rejection caused purely by not knowing that only photos taken *after* the event starts are shared.

### 4. Submit behind an input **and** a doctor gate

`asc review doctor` must report `summary.blocking == 0` or the workflow refuses, printing the blocking
messages. The gate is **ours**, not the tool's — the same stance the metadata apply takes by resolving the
editable version itself. It converts "submit failed somewhere inside Apple's API" into a named blocker list
*before* anything irreversible happens.

### 5. Two jobs: macOS builds, ubuntu talks to App Store Connect

The workflow splits into `build` (macos-26: guards → archive → export → upload) and `finish`
(ubuntu-latest, `needs: build`: attach → review details → optional submit → tag).

This is **forced by a real blocker**: every new step needs `asc`, but `asc_fetch.sh` hardcodes
`asc_<v>_linux_amd64` and verifies it with `sha256sum`, which does not exist on macOS (`shasum -a 256`
there). The release job is macOS.

*Alternative rejected:* making `asc_fetch.sh` platform-aware (the release does ship `asc_<v>_macOS_arm64`).
It would edit the script the **required** `appstore-metadata-validate` check depends on — where a mistake
freezes every merge — and it doubles the pinned-checksum maintenance on every version bump. The split
touches `asc_fetch.sh` not at all.

The split needs **no job outputs**: the store version is an input, and `appstore_release.py` already resolves
the build by `github.run_number`. It also matches the metadata capability's stance that App Store Connect
record-wrangling belongs "on ubuntu with no Apple toolchain", and moves the REST work off macOS minutes.

**It also improves the security posture.** `contents: write` (needed for the tag push) lands on the **ubuntu**
job, which holds no signing certificates — the macOS job that does keeps `contents: read`. Had the workflow
stayed a single job, the write permission would have sat alongside the certs and the Admin key.

### 6. Two fixes the switch forces

Neither is a preference:

- **Concurrency must key on `inputs.version`.** The current group is `ios-release-${{ github.ref }}` with
  `cancel-in-progress: true`, safe under tags because distinct versions had distinct refs — the file's own
  comment says so. Dispatched from `main`, every release shares `ios-release-refs/heads/main` and a second
  release **cancels the first mid-flight**.
- **`contents: write`** for the tag push, scoped to the `finish` job only (the workflow is `contents: read`
  today).

### 7. Unlisted distribution: recorded here because it was got wrong

Not implemented by this change (it is an Apple form), but it shapes the notes file and one operator decision,
and the intuition about it is backwards:

- Apple requires the app to be **already on the App Store or ready for final distribution and submitted to App
  Review**. Requests are **declined** if the app has not been submitted, or is in beta/prerelease. Unlisted
  therefore **follows** the first submission; it does not gate it.
- It requires a line in the Review Notes declaring the intent — which is why `notes.md` ends with one.
- **App Store Connect's "Private" is NOT unlisted.** It is Custom Apps (Apple Business Manager): *"select
  Private. Under Type, choose either Organization ID or Apple Account"* — org-only distribution no consumer can
  install. And it is a one-way door: *"Once your app is approved, the distribution method can't be changed...
  To switch from private to public distribution—or vice versa—you must create a new app record and resubmit
  your binary."* Selecting it would burn app record `6781692480` and every artifact configured on it.
- **Public → unlisted is explicitly allowed.** So the route is: stay Public → submit with the intent note →
  request unlisted after approval.

## Verify on the first dispatch

Nothing in this path has ever run, so acceptance cannot be observed before the merge that enables it:

1. **Dispatch with `submit=false`.** Expect: `1.0` built, uploaded, attached; review details created; tag
   `v1.0` pushed. Then `asc validate --app 6781692480 --version 1.0` should report **0 blocking** — both
   `build.required.missing` and `review_details.missing` cleared.
2. **Re-dispatch the same version.** Expect a fast failure on the tag-existence guard, before any build.
3. **Dispatch with `submit=true`** only when step 1 reports zero blocking. `review doctor` gates it; a refusal
   prints the blockers and changes nothing.

## Risks / Trade-offs

- **Nothing here has ever run.** No tag was ever pushed, so the *existing* build→attach flow is itself
  unproven — and this change rewrites its trigger while adding an irreversible submit on top. → `submit`
  defaults false and the doctor gate refuses an unready version, so the first dispatch exercises only the
  reversible half.
- **`contents: write` is a new permission on the release workflow.** → Scoped to the `finish` job, which holds
  no signing certificates; the macOS job that does keeps `contents: read` (Decision 5). No recursion:
  `GITHUB_TOKEN` tag pushes do not trigger workflows, and `build.yml`/`ios.yml` already exclude tags from
  their push triggers.
- **The spec's Purpose is not reachable by a delta.** Verified by archiving into a throwaway copy: the Purpose
  survives untouched, still asserting "Makes a pushed **`vX.Y` git tag the trigger**" and "stopping short of
  submit-for-review" — both false after this change. → It must be hand-edited at archive time, as the
  decision-record citation was in `2026-07-16-close-appstore-submission-gaps`. Carried as an explicit task.
- **Arbitrary-SHA release is lost** — dispatch selects a ref, not a commit. → Point a branch at the commit;
  rare enough not to design for, and better than the two-commits-in-one-run confusion of an `sha` input.
- **A submission that half-fails** may leave a review submission needing manual cancellation
  (`asc submit cancel --version-id ... --confirm`). → The doctor gate makes the common causes fail *before*
  submitting.
- **Unlisted may be declined.** Apple frames it for limited audiences ("partner sales tools, employee
  resources, or research studies") while the listing pitches a consumer app for trips and weddings. → Costs
  time only; the app stays approved and listed in Germany. The `Private` alternative would cost the app record.
- **The ancestor-of-`main` guard becomes load-bearing.** Under tags it was near-incidental; dispatch can run
  from any ref, so it is now the only thing stopping a release off an unmerged branch. → It already exists and
  is unchanged; only its importance rises.
