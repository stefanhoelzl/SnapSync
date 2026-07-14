## ADDED Requirements

### Requirement: Every main build is promoted to the public alpha channel

Promotion SHALL be performed by a dedicated `ios-promote` job in `.github/workflows/ios.yml` that declares `needs: ios-deliver` and is guarded by `if: github.ref == 'refs/heads/main'`. The job SHALL place the build that `ios-deliver` uploaded into the **`alpha`** App Store Connect beta group — an **external** group whose public link (`https://testflight.apple.com/join/pvqgV7Uz`) is open to anyone — so that the public alpha channel is, by construction, `main`.

The job SHALL run on `ubuntu-latest`. It compiles nothing, exports nothing, and SHALL NOT require Xcode, an IPA, a keychain, or any signing certificate: it acts purely against the App Store Connect REST API. It SHALL authenticate with the **existing** Admin App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) and SHALL NOT introduce a new secret.

Promotion SHALL NOT wait for Beta App Review. A build SHALL be added to the `alpha` group without regard to its `betaReviewState`: App Store Connect accepts a build into an external group while it is still `WAITING_FOR_REVIEW`, and a build carrying an already-approved `MARKETING_VERSION` auto-approves without human involvement.

#### Scenario: A push to main reaches the public alpha channel
- **WHEN** a commit is pushed to `refs/heads/main` and both merge gates and `ios-deliver` succeed
- **THEN** `ios-promote` submits the uploaded build to TestFlight and adds it to the `alpha` external group, so every public-link tester can install it

#### Scenario: A push to a non-main branch is never promoted
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** `ios-promote` does not run and no build is added to the `alpha` group

#### Scenario: A failed delivery promotes nothing
- **WHEN** `ios-deliver` fails on `refs/heads/main` (or either merge gate fails, so `ios-deliver` never runs)
- **THEN** `ios-promote` does not run and no build is added to the `alpha` group

#### Scenario: Promotion needs no Apple toolchain and no new credential
- **WHEN** `ios-promote` runs
- **THEN** it runs on `ubuntu-latest` using only the existing Admin App Store Connect API key, with no Xcode, no IPA, no keychain and no signing certificate

#### Scenario: Group assignment does not wait for review
- **WHEN** the build is still `WAITING_FOR_REVIEW` at the moment `ios-promote` assigns it
- **THEN** the assignment to the `alpha` group succeeds anyway, and the build auto-approves because its `MARKETING_VERSION` has already been approved once

### Requirement: The promoted build is identified by the CI run number

`ios-promote` SHALL locate the build to promote by its build number, which equals `github.run_number` (see *Monotonic build numbers from the CI run*), and SHALL NOT depend on an IPA artifact, a file hash, or any hand-off from `ios-deliver` beyond the job dependency. Because `ios-promote` runs in the **same** workflow run as `ios-deliver`, this number identifies exactly the build that run uploaded.

A freshly uploaded build is **not immediately discoverable** in App Store Connect. The lookup SHALL therefore be retried until the build appears, up to a bounded timeout, and the job SHALL fail (red) if it never appears.

#### Scenario: The build is found by run number
- **WHEN** `ios-promote` runs in a workflow run whose `github.run_number` is N
- **THEN** it promotes the App Store Connect build whose build number is N — the build `ios-deliver` uploaded in the same run

#### Scenario: A not-yet-discoverable build is waited for
- **WHEN** the build is not yet visible in App Store Connect immediately after upload
- **THEN** `ios-promote` retries the lookup until the build appears, and fails red if it does not appear within the timeout

### Requirement: No alpha tester is ever notified

`ios-promote` SHALL set `autoNotifyEnabled` to `false` on the promoted build's `buildBetaDetail`, so TestFlight sends **no** notification to any alpha tester. Builds arrive silently and continuously; alpha testers ride `main` via TestFlight auto-update.

Because TestFlight raises the notification at the moment a build becomes **available to the group**, the suppression SHALL be applied **before** the build is added to the `alpha` group. A failure to suppress SHALL abort the job **before** group assignment, so a build can never reach the group un-suppressed. This ordering is a **requirement**, not an implementation detail: a reordering that looks harmless would silently break the promise made to testers.

The App Store Connect CLI exposes no flag for this attribute; a direct `PATCH` of `buildBetaDetails` is the sanctioned mechanism.

#### Scenario: A promoted build notifies nobody
- **WHEN** a build is promoted to the `alpha` group
- **THEN** its `autoNotifyEnabled` is `false` and no alpha tester receives a TestFlight notification

#### Scenario: Suppression precedes group assignment
- **WHEN** `ios-promote` promotes a build
- **THEN** it sets `autoNotifyEnabled` to `false` strictly before adding the build to the `alpha` group

#### Scenario: A failed suppression promotes nothing
- **WHEN** setting `autoNotifyEnabled` to `false` fails
- **THEN** the job fails red **without** adding the build to the `alpha` group, so no un-suppressed build can reach a tester

### Requirement: Every main build is promoted, unfiltered

`ios-promote` SHALL promote **every** build produced by `main`, and SHALL NOT filter on changed paths, on commit type, or on whether the compiled binary actually differs from the previously promoted one. `ios.yml` triggers on an unfiltered `push`, so docs-only and backend-only merges produce iOS builds too; those builds SHALL be promoted like any other, even though they are binary-identical to their predecessor apart from the build number.

This is deliberate. Every filter considered — a path allowlist, a binary hash comparison, a conventional-commit-type gate — fails toward *"a real fix silently never reaches testers"*, which is the worst outcome an alpha channel can have. Promoting everything fails only toward tester-visible noise, which is merely annoying. The noisy option is therefore the correct one.

A path filter on the workflow **trigger** SHALL NOT be used to achieve this: `ios-build` and `ios-test` are **required** status checks, so a push that skips the workflow never posts them and merges would freeze.

#### Scenario: A docs-only merge is still promoted
- **WHEN** a docs-only or backend-only commit is merged to `main`, producing a build binary-identical to the last one apart from its build number
- **THEN** that build is promoted to the `alpha` group like any other

#### Scenario: The workflow trigger stays unfiltered
- **WHEN** `ios.yml` is configured
- **THEN** its `push` trigger carries no path filter, so the required `ios-build` and `ios-test` checks are posted on every ref and no merge can freeze

### Requirement: Promotion is idempotent

`ios-promote` SHALL be safe to re-run against a build it has already promoted. Before submitting, it SHALL read the build's state; if the build is already `BETA_APPROVED` **and** already in the `alpha` group, the job SHALL conclude **successfully** without re-submitting or re-assigning it.

Re-running a failed job is the first thing an operator reaches for when a promotion flakes, so a re-run must never turn red merely because the desired state was already reached.

#### Scenario: Re-running an already-promoted build is a green no-op
- **WHEN** `ios-promote` is re-run for a build that is already `BETA_APPROVED` and already in the `alpha` group
- **THEN** the job makes no further App Store Connect mutation and concludes successfully

### Requirement: The newest main build wins a review pile-up

When `ios-promote` submits a build to TestFlight it SHALL expire any build already queued in, or waiting for, Beta App Review (`--expire-build-submitted-for-review`), so the newest `main` build takes the queued build's place.

A pile-up is only reachable after a **`MARKETING_VERSION` bump**, which forces a genuine first-of-version Beta App Review taking hours to days; builds sharing an already-approved version auto-approve instantly. During such a window each merge expires its predecessor's submission — which is the desired behavior: the newest `main` build is always the one that should be in front of testers, and a build expired while still in review was never visible to a tester anyway.

Operators SHALL be able to recognise this window: while it lasts, **no build reaches the alpha channel and nothing is red**.

#### Scenario: A newer build displaces one waiting for review
- **WHEN** a build is `WAITING_FOR_REVIEW` and a newer commit is merged to `main`
- **THEN** the newer build's submission expires the waiting one and takes its place, so the newest `main` build is the one under review

#### Scenario: A version bump stalls the channel without failing
- **WHEN** `MARKETING_VERSION` is bumped and the first build of the new version enters a genuine Beta App Review
- **THEN** no build reaches the `alpha` channel until that review concludes, and `ios-promote` nevertheless concludes green — the stall is expected, not a failure

### Requirement: A cancelled promotion is benign

`ios.yml` sets `concurrency: ios-${{ github.ref }}` with `cancel-in-progress: true`, so a merge landing while an earlier run's `ios-promote` is still polling **cancels** that promotion, and the earlier build is never promoted. This SHALL be treated as **correct behavior, not a defect**: the newer run promotes the newer build, and nothing that a tester should have received is lost.

#### Scenario: A rapid second merge cancels the first promotion
- **WHEN** a commit is merged to `main` while the previous run's `ios-promote` is still waiting for its build to process
- **THEN** the previous run (including its `ios-promote`) is cancelled, the newer run promotes the newer build, and the cancelled promotion is not a failure to investigate

## MODIFIED Requirements

### Requirement: Delivery never blocks merges, and never fails silently

Delivery **and promotion** SHALL be decoupled from the merge gates **structurally**: they live in separate `main`-only jobs (`ios-deliver` and `ios-promote`) that post **no required status check** (capability `branch-protection` requires `build`, `ios-build` and `ios-test`, and SHALL NOT require `ios-deliver` or `ios-promote` — a job that never runs on a pull-request branch would, if required, freeze every merge). Because they can block nothing, `ios-deliver` and `ios-promote` SHALL NOT use `continue-on-error`: a failed export, a failed App Store Connect upload, or a failed promotion SHALL conclude the job as **failure (red)**, so a broken delivery is visible rather than hidden inside an otherwise-green run.

This replaces the previous `continue-on-error` convention, under which a transient delivery failure left the run green and could pass unnoticed.

#### Scenario: A delivery flake is red but blocks nothing
- **WHEN** both gates are green on `main` but the export or the TestFlight upload fails
- **THEN** the `ios-deliver` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (the commit is already merged and `ios-deliver` is not a required check)

#### Scenario: A promotion flake is red but blocks nothing
- **WHEN** `ios-deliver` succeeds on `main` but the promotion to the `alpha` group fails
- **THEN** the `ios-promote` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (`ios-promote` is not a required check)

#### Scenario: A compile failure still fails the gate
- **WHEN** the signed archive fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)
