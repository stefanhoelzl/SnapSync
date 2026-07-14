## Context

`ios.yml` today has three jobs: `ios-build` and `ios-test` (the two required merge gates) and `ios-deliver` (`main`-only, `needs: [ios-build, ios-test]`), which exports the App Store IPA from `ios-build`'s archive and uploads it with `apple-actions/upload-testflight-build`. Upload is where the pipeline ends. The build lands in App Store Connect and is visible to the **internal** `development` group (which has `hasAccessToAllBuilds: true`, so every build is instantly `IN_BETA_TESTING` internally) — and to nobody else.

The **external** `alpha` group already exists: public link `https://testflight.apple.com/join/pvqgV7Uz`, `publicLinkEnabled: true`, `publicLinkLimitEnabled: false`, feedback on. It contains exactly one build, 293, added by hand. The one-time App Store Connect prerequisites are already satisfied: `betaAppReviewDetail` carries the review contact, and the `en-US` `betaAppLocalization` carries the feedback email and the beta app description. `privacyPolicyUrl` is `null` — and 293 was approved anyway.

Everything below was established empirically by probing the live App Store Connect API against build 310 before the design was written, not reasoned about from Apple's documentation.

### What the probe established

Running the four candidate calls by hand against build 310 (`cd31e9c0-…`), with the same Admin ASC key CI already holds:

| Question | Answer |
|---|---|
| Does a same-version build need Beta App Review? | A submission is created, but it **auto-approves instantly**. `externalBuildState` was `BETA_APPROVED` immediately after `submit-to-testflight` returned `WAITING_FOR_REVIEW`. 0.1.0 had already been approved once (293, 2026-07-09). |
| Must a build be approved before joining an external group? | **No.** `beta-groups add-build` succeeded on a build still in `WAITING_FOR_REVIEW`. |
| Can the CLI suppress the tester notification? | **No.** `autoNotifyEnabled` lives on `buildBetaDetails` and codemagic exposes no flag. A raw `PATCH /v1/buildBetaDetails/<id>` with `{"autoNotifyEnabled": false}` returns `200`. |

Consequences: promotion is **fire-and-forget** — it never waits for a reviewer, never waits for approval, and needs no human. The only thing it ever waits on is Apple finishing the build's *processing* (`PROCESSING → VALID`), which `beta-groups add-build` requires.

## Goals / Non-Goals

**Goals:**

- Every build that reaches `main` reaches the public `alpha` TestFlight channel, automatically, with no human step.
- Promotion never blocks a merge and never fails silently — the same structural decoupling `ios-deliver` already has.
- No alpha tester is ever notified. Builds arrive silently; testers ride `main` via TestFlight auto-update.
- Re-running a promotion is always safe.
- No new secrets, no new signing material, no new Apple credentials.

**Non-Goals:**

- **Filtering which builds get promoted.** Docs-only and backend-only merges will ship binary-identical builds. Accepted; see Decisions.
- **Handling TestFlight's 90-day build expiry.** A quiet quarter would leave testers with an expired app. Out of scope.
- **Capping or authenticating the public channel.** The public link is uncapped and the bunny upload endpoint is unauthenticated, so anyone who taps the link can upload photos to the storage zone. Accepted as-is; a real fix belongs in the backend, not in CI.
- **Backfilling the stranded builds** (295, 297, 300, 302, 308). 310 supersedes them.
- **App Store release.** This is TestFlight only. Nothing here submits to App Store review.

## Decisions

### A separate `ios-promote` job on `ubuntu-latest`, not extra steps in `ios-deliver`

Promotion touches only the App Store Connect REST API against a build identified by its number. It needs no Xcode, no IPA, no keychain and no certificates — so it has no business on a macOS runner. Folding it into `ios-deliver` would burn a macOS runner **idling on Apple's processing wait**, and would couple "the upload failed" to "the promotion failed" inside one red job.

A separate job also inherits the reasoning the workflow header already spells out for `ios-deliver`: because it runs only on `main` and posts no required status check, it is free to fail **red** without blocking anything. That is strictly better than hiding a failure behind `continue-on-error`.

*Alternative rejected — a cron reconciler* that periodically sweeps any `VALID` build not yet in `alpha`. It has a genuinely attractive property: since `ios-deliver` is the only uploader and it is `main`-only, **every build in App Store Connect came from `main` by construction**, so a reconciler needs no git context to know what is eligible. It is also self-healing. But it does not get builds to testers any sooner (a 5-minute poll is traded for up to 15 minutes of cron latency), it needs a build-number → commit lookup to write the release note, and a build would appear in `alpha` with no CI run linking it to the merge that caused it. The idle poll is free on a public repo; the traceability is not worth giving up to save it.

### The build is located by `github.run_number`, with a retry

`CURRENT_PROJECT_VERSION` is already `github.run_number` (see the existing "Monotonic build numbers" requirement), and `ios-promote` runs in the *same* workflow run as `ios-deliver`. So `builds list --app-id <app> --build-version-number ${{ github.run_number }}` identifies exactly the build this run uploaded. No artifact hand-off, no IPA, no parsing.

The catch: a freshly uploaded build is **not immediately discoverable**. `builds list` returns nothing for a while after `upload-testflight-build` returns. The job therefore retries the lookup (cap ~10 minutes, matching codemagic's own `--max-find-build-wait` default) and fails red if the build never appears.

### Order is load-bearing: suppress the notification *before* joining the group

The tester notification fires when the build becomes **available to the group**. So the sequence is not arbitrary:

```
1. locate build (retry until discoverable)
2. already BETA_APPROVED and in alpha?  ──yes──►  exit 0 (idempotent no-op)
3. builds add-beta-test-info   --whats-new "<commit subject> (<short sha>)"
4. builds submit-to-testflight --expire-build-submitted-for-review
       └─ waits for processingState VALID; auto-approves
5. PATCH buildBetaDetails/<id>  autoNotifyEnabled=false      ◄── MUST precede 6
6. beta-groups add-build --beta-group alpha                  ◄── this is what would notify
```

Step 5 must **abort the job** on failure, before step 6 ever runs. Under `set -euo pipefail` this is automatic, but it is stated as a requirement because "never notify" is a promise to testers, and a reordering that looks harmless would silently break it.

### Promote everything; filter nothing

`ios.yml` triggers on bare `on: push:` with no path filter, so docs-only and backend-only merges to `main` produce full iOS builds. Five of the last six commits on `main` were exactly that. Promoting every build therefore ships **binary-identical** apps to alpha testers most of the time.

Every filter considered fails in the same direction:

| Gate | Cost of being wrong |
|---|---|
| Path allowlist (`iosApp/`, `app/ios/`, `domain/`, `capability/`, `gradle/…`) | miss a path → a real fix **silently never ships** |
| Compare archive/IPA hash to the last promoted | the build number is baked in, so the bytes always differ |
| Gate on conventional-commit type (`docs:`, `chore:`) | a mislabeled commit **silently never ships** |
| **Promote everything** | **testers see noise** |

Only the last one fails toward an outcome that is merely annoying. For an alpha channel, "a fix reached nobody" is far worse than "a tester got a build with nothing in it", so the noisy option is the correct one.

A path filter on the *trigger* is additionally a merge-freezing footgun: `ios-build` and `ios-test` are **required** status checks, so a docs-only PR that skips the workflow never posts them and the ruleset waits forever. Any such gate would have to live inside a non-required job — which is the same trap the workflow header already documents for `ios-deliver`.

### Never notify

`autoNotifyEnabled: false` on every promoted build. With 2–3 merges a day, a push per merge is spam, and — because most promoted builds are binary-identical — a notification would carry no information anyway. The channel's contract is: *alpha testers ride `main`; builds arrive silently and continuously via TestFlight auto-update.*

The "what to test" note (step 3) is still written, from the commit subject and short SHA. It is not a push, but it *is* what a tester sees in the TestFlight build list, so it earns its keep.

### Newest wins on a review pile-up

`--expire-build-submitted-for-review` expires any build sitting in review before submitting the new one. Because same-version builds auto-approve instantly, a pile-up is only reachable after a `MARKETING_VERSION` bump, which forces a genuine first-of-version Beta App Review (hours to days). During that window each merge expires its predecessor's submission and takes its place — which is the behavior we want: the newest `main` build is always the one that should be in front of testers, and a build expired while still in review was never visible to a tester anyway.

### Concurrency cancellation is benign, not a bug

`ios.yml` sets `concurrency: ios-${{ github.ref }}` with `cancel-in-progress: true`. Two merges landing inside the promote window means the second run cancels the first **mid-poll**, and build N is never promoted. This is correct: build N+1 gets promoted instead, and nothing is lost. It is documented as an explicit requirement precisely because someone reading a cancelled promote will otherwise file it as a bug.

### Extend `ios-testflight-delivery` rather than add a capability

One pipeline should have one contract of record. The existing spec already owns "how a `main` build reaches testers"; promotion is the missing leg of that story, not a separate concern. A new capability would split the story across two specs and force cross-references — and would still leave the old spec's Purpose asserting *"as a release trail (no Beta App Review)"*, which the probe has now falsified.

## Risks / Trade-offs

- **A tester with TestFlight auto-update turned off will never learn a build exists.** → No mitigation; this is the accepted cost of "never notify". On a public link some fraction of testers will do this. If it becomes a real complaint, the escape hatch is to notify selectively (e.g. only on `feat:`/`fix:` commits), which inverts the failure mode to "a mislabeled commit ships silently but *still ships*".
- **Alpha testers see mostly-empty builds.** → Accepted, per the filtering decision above. The alternative fails toward losing real fixes.
- **A `MARKETING_VERSION` bump silently stalls the channel.** The first build of a new version faces a real Beta App Review taking hours to days, and during that window each merge expires the last one's submission. Nothing reaches testers and CI stays green. → Mitigated only by documentation: this must be written into the spec and `CLAUDE.md`, because the symptom ("builds stopped arriving, but nothing is red") is otherwise baffling.
- **The public link is uncapped and the upload endpoint is unauthenticated.** Anyone who taps the link can push photos into the bunny storage zone. → Explicitly out of scope and accepted. It is the one thing in this design that can cost real money without warning.
- **The job is only ever exercised on `main`.** Being `main`-only, it cannot run on the PR that introduces it. → Mitigated by the probe: the exact call sequence has already been run green by hand against build 310 with the same Admin key CI uses, so the PR only ports a proven sequence into YAML. The first CI run is post-merge, and if it breaks it is red and blocks nothing. This matches the existing convention — `ios-deliver` is likewise only ever exercised on `main`.
- **Apple's processing wait is an unmeasured estimate.** The retry caps (~10 min to find, ~20 min to process) are taken from codemagic's defaults, not from SnapSync data — every build inspected was already `VALID`, and the API exposes no "became valid" timestamp. → Read the real duration off the first `ios-promote` run and tighten if warranted. The cost of being wrong is a job that idles for free on a public runner.

## Migration Plan

No migration. The change is additive: one new job. `ios-build`, `ios-test` and `ios-deliver` are untouched, and `.github/rulesets/main.json` stays as-is.

Rollback is deleting the job. Builds would resume accumulating unseen in App Store Connect, exactly as they do today; already-promoted builds stay in `alpha` and remain installable.

One-off state note: build 310 was promoted **by hand during the probe**, and was added to `alpha` *before* the `autoNotifyEnabled=false` PATCH was discovered — so a notification for 310 probably did go out. Self-correcting; no action needed.

## Open Questions

None. The load-bearing unknowns (does review gate promotion, does group-add need approval, can the notification be suppressed) were all resolved empirically against the live API before this design was written.
