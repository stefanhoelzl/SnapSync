## Context

Two workflows write the App Store listing, split deliberately:

- `appstore.yml` — listing **text**. Hosts `appstore-metadata-validate`, a **required** status
  check, which is why its trigger is an unfiltered `push:` (a required check that is skipped is
  never posted, and merges freeze). Runs `cancel-in-progress: true` on `appstore-${{ github.ref }}`,
  deliberately: a newer text apply should supersede an older one.
- `appstore-screenshots.yml` — listing **screenshots**. A separate file precisely because it
  needs the two properties `appstore.yml` cannot give it: a `paths:` trigger (safe here, since no
  required check lives in the file) and `cancel-in-progress: false`.

Both applies share one safety rule, stated in `ios-appstore-metadata`: they touch **only** a
version in an editable state (`PREPARE_FOR_SUBMISSION` / `DEVELOPER_REJECTED`), never one in
review, and **never create** a version. `asc_screenshots_upload.sh` implements it by resolving the
editable version itself and exiting 0 with a message when there is none.

The third writer is `ios-appstore-promote.yml` (capability `ios-appstore-release`): a dispatched,
single-`ubuntu` job that promotes an already-uploaded build, **find-or-creates** the `X.Y` version
record, attaches the build, applies review details, optionally submits, and tags last. It is the
only thing in the system that *brings an editable version into existence*.

That is where the hole is. The screenshot upload is triggered by its **inputs** changing, but
whether it can do anything depends on a **version state** it does not observe and cannot wait for.
Measured today: the app has exactly one version record, `0.1` / `READY_FOR_SALE`. A capture
refresh merged now runs the upload, finds nothing editable, and concludes green having done
nothing. Promote later creates `0.2` — and no push occurs, so nothing re-uploads. Every run is
green; the shipping version has stale screenshots.

## Goals / Non-Goals

**Goals:**

- The version that ships carries the screenshots committed in the repo, without an operator
  remembering to re-push after a promote.
- One upload implementation, one editable-version gate — a second caller, not a second behaviour.
- No new secret, no new credential, no Apple toolchain in the promote job.

**Non-Goals:**

- Keeping an automated path for correcting a version the release already prepared. That is
  manual, by decision (D5).
- Changing the editable-version gate, or either script it lives in.
- Creating version records outside `ios-appstore-promote.yml`.
- Capturing screenshots. The capture workflow stays dispatch-only; the committed raws remain the
  source of truth.

## Decisions

### D1: Promote uploads, rather than the screenshots workflow learning to wait

Promote is the only workflow that knows an editable version now exists — it just created it. Every
alternative has to *infer* that state:

- **Auto-create the version from a screenshots push** (the operator's first instinct, and the one
  that looks most direct). Rejected: `ios-appstore-metadata` says "SHALL NOT create a version" in
  two places, and the safety argument behind it is real — the tool's behaviour on an in-review
  version is undefined. Worse, it does not fix the bug, it moves it. The version string would be a
  **guess** (`max(floor, latest vX.Y tag + minor 1)`, computable, but a guess), and the documented
  major-bump path — a manual `Config.xcconfig` floor bump — makes builds carry `1.0` while the
  screenshots sit on an orphaned `0.2` record that never ships. Same invisible green run, now with
  a stray record behind it. It would also duplicate the create path: `appstore_release.py` applies
  `COPYRIGHT` only when *it* creates the record.
- **Add `workflow_dispatch` to `appstore-screenshots.yml`.** Smaller, and it works — but it keeps
  the operator in the loop for a step whose omission is silent, which is the property that made
  this a bug rather than an inconvenience. (D5 goes further and deletes that workflow entirely.)

### D2: Reuse `asc_screenshots_upload.sh` unchanged

The script already resolves the editable version, dry-runs, then applies. Called from promote
*after* the attach, its resolution finds the record promote just created. So the second caller
needs no new logic and no new argument, and the editable-version gate cannot drift between the two
paths — there is only one copy of it. The no-editable-version branch also stays meaningful in
promote: if the attach step somehow left no editable version, the upload declines rather than
inventing a target.

### D3: Ordering — after the attach, before the submit gate

The record must exist before the upload can resolve it, and the screenshots must be in place
before `asc review doctor` runs and before `review submit`, or an opt-in submit would send a
version whose listing images are stale. That pins the step between "Attach the build to its App
Store version record" and "Refuse to submit an unready version". Placing it relative to "Apply the
App Review details" is free; adjacent to the attach reads better and keeps the two ASC-listing
mutations together.

### D4: Promote keeps `cancel-in-progress: true` (operator decision)

`--replace` deletes the set before uploading, so a cancel mid-flight can leave a partial set — the
exact hazard that gave `appstore-screenshots.yml` its own file with `cancel-in-progress: false`.

The operator's call is that the hazard does not transfer, and the reasoning holds: promote's target
is a `PREPARE_FOR_SUBMISSION` version, which is **not on the storefront**. A partial set there is
invisible to the public and is restored by the next run. Promote's concurrency group is also
per-`build_number`, so only a concurrent re-promote of the *same* build can cancel — rare, and
self-correcting on retry.

Alternative considered and not taken: flip promote to `cancel-in-progress: false`. It would remove
even the unpublished-partial window, at the cost of making two dispatches of the same build queue
rather than supersede. Not worth it for a window that is not publicly observable.

### D5: `appstore-screenshots.yml` is deleted, and corrections are manual

The question this turns on is what the push-triggered uploader still covers once the release
uploads. The answer is narrow, and it is **not** "the release path": it is *correcting a version
the release has already prepared*.

That window is real, because **a promote is single-shot per version**. `Require the release tag to
be absent` runs first, and `Create the release tag` has no `if:`, so it runs on success and pushes
`vX.Y`. Re-dispatching that build then fails at the guard — and so does any other build, since the
store version is derived from the build's marketing version, which only advances past `X.Y` once
`vX.Y` exists. Meanwhile the version stays editable: `PREPARE_FOR_SUBMISSION` until submitted, and
`DEVELOPER_REJECTED` again after a rejection. So a promote cannot fix its own version's
screenshots, in either window.

The operator's decision is that those corrections are **manual console uploads**. That is a
deliberate trade, and it is worth naming what it costs:

- **A headline fix after a promote-without-submit** no longer lands by pushing. (This is the path
  CLAUDE.md advertised as "a headline change needs NO dispatch" — that sentence is now wrong for
  the store and is corrected in the same change.)
- **A post-rejection screenshot fix** is manual.
- **Console drift is no longer auto-reverted between releases.** The declarative-overwrite property
  — "a screenshot added by hand in the console does not survive" — now holds *at each release*
  rather than continuously. Since the only hand-edits will be the operator's own deliberate
  corrections, reverting them automatically was arguably the wrong behaviour anyway.

What the deletion buys is proportionate: the workflow's *observable behaviour today* was to run
green and do nothing, which is precisely what hid this bug for a release cycle. Removing it removes
a misleading green signal, one of two writers of a destructive `--replace`, and a file whose whole
existence was a workaround for constraints (`paths:` triggers, `cancel-in-progress: false`) that
the release path does not have.

Alternative considered: keep it as a safety net. Rejected — a second writer that is silent when it
matters is worse than no writer, and "the store is updated by a release" is a rule with no
exceptions to remember.

## Risks / Trade-offs

- **A promote now depends on ImageMagick and a font being installable** → the composite script
  fails loudly on a missing raw or wrong output size, and the job already fetches a pinned `asc`;
  an apt failure is a red release, not a silent one. The step is carried over verbatim from the
  deleted workflow, including its `command -v magick || convert` handling of Ubuntu's ImageMagick 6
  and its refusal to pipe the version check through `head` (which would mask a missing binary).
- **A cancelled promote can leave an unpublished version's screenshot set partial** (D4) → not
  public; restored by the next promote or by any inputs-changed push. Accepted deliberately.
- **The store listing is now only reachable by a release** → a wrong screenshot on a prepared
  version needs a console upload, and there is no automated recovery (D5). Mitigation is the
  runbook: the capture workflow is dispatched, the raws are eyeballed, and the release's dry-run
  lists the composited set before the apply.
- **CLAUDE.md advertised the removed behaviour** ("a headline change needs NO dispatch") → corrected
  in this change; a stale runbook line here would send an operator pushing at a listing that no
  longer listens.
- **A release run gets slower and has more to fail on** → it is dispatch-driven, posts no required
  check, and is idempotent on re-run; the attach step is already a green no-op when repeated.

## Migration Plan

No migration. The change is additive to a dispatched workflow: the next promote uploads the
screenshots, and any promote before it is unaffected. Rollback is deleting the two steps.

The already-committed capture refresh needs nothing special — merging it stays a green no-op today,
and the next promote is what carries those raws to the store.

## Open Questions

None.
