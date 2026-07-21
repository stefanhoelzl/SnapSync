## ADDED Requirements

### Requirement: Delivered builds identify their source change

Every build `ios-deliver` uploads SHALL carry a TestFlight "What to Test" note identifying the change that produced it: the **pull-request title**, the **pull-request number**, and the **short commit SHA**, in the form `<PR title> (#<num>, <short sha>)`. The PR SHALL be resolved from the delivered commit via the commits→pulls association (`GET repos/{repo}/commits/{sha}/pulls`) using the workflow's default token (the repo is rebase-merge-only, so the head-commit subject may be a trailing commit of the PR rather than its summary — the PR title is authoritative). WHEN no PR resolves, the note SHALL fall back to `<head-commit subject> (<short sha>)` — a degraded note never degrades the delivery. The PR title SHALL cross into the shell only via an environment variable (never workflow-template interpolation into a `run:` command line). Setting the note SHALL follow the job's existing failure posture: no `continue-on-error`, so a note failure is a visibly red, non-gating run.

#### Scenario: A delivered build names its PR and commit

- **WHEN** a merge to `main` with an associated pull request is delivered to TestFlight
- **THEN** the build's "What to Test" note reads `<PR title> (#<num>, <short sha>)` for that merge's head commit

#### Scenario: No associated PR degrades the note, not the delivery

- **WHEN** the delivered commit resolves no associated pull request
- **THEN** the note falls back to the head-commit subject plus the short SHA, and the upload proceeds normally

### Requirement: The upload and the note are one atomic publish

`ios-deliver` SHALL upload the signed IPA and attach the "What to Test" note via a single `app-store-connect publish` invocation (codemagic-cli-tools) passing `--whats-new`, which owns the bounded wait for the freshly uploaded build to become discoverable in App Store Connect. The invocation SHALL NOT pass `--testflight` or any submission flag: it uploads and sets build metadata only — no beta-review submission, no beta-group assignment (distribution remains App-Store-only, capability `ios-appstore-release`). No bespoke find-build retry logic SHALL live in the repo.

#### Scenario: Publish uploads and annotates without distributing

- **WHEN** `ios-deliver` runs on a green `main` commit
- **THEN** one `publish` invocation uploads the IPA and sets the note, and no beta-review submission or beta-group change is made

#### Scenario: A discovery timeout is a red run

- **WHEN** the uploaded build does not become discoverable within the publish wait bound
- **THEN** `ios-deliver` concludes as failure (red) and blocks nothing

## MODIFIED Requirements

### Requirement: Cloud-managed code signing

**Every job that invokes `xcodebuild` with `-allowProvisioningUpdates`** — `ios-build`'s archive and `ios-deliver`'s export — SHALL sign using **two persistent certificates imported into that job's shared, ephemeral keychain** — an Apple **Distribution** certificate and an Apple **Development** certificate (sourced from GitHub Secrets) — combined with an App Store Connect API key with the **Admin** role, which **cloud-manages the App Store provisioning profile** for the TestFlight export. Both certs are imported deliberately, and in **both** jobs: an empty runner keychain makes automatic signing mint a **new** cert every run, exhausting Apple's per-account cert cap; `xcodebuild archive` provisions a **development identity in addition to the distribution one**, so persisting only Distribution still churned Development certs — the Development cert is therefore imported even though `ios.yml` no longer exports a development (sideload) IPA. The pipeline SHALL NOT use fastlane or `match`. The signed App Store IPA SHALL be uploaded to TestFlight via codemagic-cli-tools' `app-store-connect publish`, authenticated by the same App Store Connect API key (see the requirement "The upload and the note are one atomic publish").

#### Scenario: Signing reuses imported persistent certs, mints none

- **WHEN** the device app is archived (`ios-build`) or the archive is exported (`ios-deliver`)
- **THEN** signing uses the two imported persistent certificates (Distribution and Development) and `xcodebuild -allowProvisioningUpdates` obtains the App Store provisioning profile via the Admin App Store Connect API key, without minting any new certificate

#### Scenario: Development cert import prevents cert-cap churn

- **WHEN** either the `ios-build` job archives the device app on any ref, or the `ios-deliver` job exports the archive on `main`
- **THEN** the imported Apple Development certificate satisfies the development identity that `xcodebuild -allowProvisioningUpdates` provisions, so no new Development certificate is minted in either job, even though no development IPA is exported

#### Scenario: Upload authenticates with the App Store Connect API key

- **WHEN** the signed App Store IPA is ready on `main`
- **THEN** it is uploaded to TestFlight via `app-store-connect publish` authenticated by the App Store Connect API key
