## ADDED Requirements

### Requirement: Delivery gates on the test suite

TestFlight delivery SHALL be performed by a dedicated `ios-deliver` job in `.github/workflows/ios.yml` that declares `needs: [ios-build, ios-test]`. The job SHALL run **only** when **both** merge gates conclude successfully on that commit; if either the device build or the simulator test suite fails, `ios-deliver` SHALL NOT run and **nothing SHALL be uploaded to TestFlight**.

This closes a hole in the previous shape, where export and upload lived inside `ios-build` — a job with no dependency on `ios-test`. A commit whose test suite was red on `main` was still delivered to testers, because the build job neither knew nor cared about the test job's result.

#### Scenario: A red test suite stops the release
- **WHEN** a commit on `refs/heads/main` compiles (so `ios-build` is green) but the `ios-test` simulator suite fails
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: A red build stops the release
- **WHEN** a commit on `refs/heads/main` fails to compile
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: Both gates green delivers
- **WHEN** a commit on `refs/heads/main` has both `ios-build` and `ios-test` green
- **THEN** `ios-deliver` exports an `app-store-connect` signed IPA from `ios-build`'s archive and uploads it to TestFlight via App Store Connect

## MODIFIED Requirements

### Requirement: Signed device build delivered to TestFlight on main only

The system SHALL deliver a signed iOS build to **TestFlight** only on pushes to **`refs/heads/main`** (the `ios-deliver` job is guarded by `if: github.ref == 'refs/heads/main'`); on any **other** ref no export and no upload occur. The signed **archive** itself SHALL still be produced on **every** ref (it is the `ios-build` merge gate — see capability `ios-ci`).

The device (`iosArm64`) app SHALL be compiled exactly **once** per push: `ios-deliver` consumes the archive `ios-build` published as a workflow artifact and **re-signs and packages** it, and SHALL NOT recompile the app. Per-branch device installability before merge is **not** served by TestFlight; it is served **out of band** by the interactive ssh-mac build loop (dev infrastructure — `.github/workflows/ssh-mac.yml`; see the runbook in `CLAUDE.md`), not by any CI artifact. Both jobs SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A push to a non-main branch does not upload to TestFlight
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** `ios-build` still archives the device app (the merge gate) but publishes no archive artifact, and `ios-deliver` does not run

#### Scenario: The device app is compiled only once per push
- **WHEN** a commit is pushed
- **THEN** the device (`iosArm64`) framework is compiled exactly once — as `ios-build`'s signed archive — and `ios-deliver` re-signs and packages that same archive rather than compiling a second time

### Requirement: Delivery never blocks merges, and never fails silently

Delivery SHALL be decoupled from the merge gates **structurally**: it lives in a separate `main`-only job that posts **no required status check** (capability `branch-protection` requires `build`, `ios-build` and `ios-test`, and SHALL NOT require `ios-deliver` — a job that never runs on a pull-request branch would, if required, freeze every merge). Because it can block nothing, `ios-deliver` SHALL NOT use `continue-on-error`: a failed export or a failed App Store Connect upload SHALL conclude the job as **failure (red)**, so a broken delivery is visible rather than hidden inside an otherwise-green run.

This replaces the previous `continue-on-error` convention, under which a transient delivery failure left the run green and could pass unnoticed.

#### Scenario: A delivery flake is red but blocks nothing
- **WHEN** both gates are green on `main` but the export or the TestFlight upload fails
- **THEN** the `ios-deliver` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (the commit is already merged and `ios-deliver` is not a required check)

#### Scenario: A compile failure still fails the gate
- **WHEN** the signed archive fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

### Requirement: Cloud-managed code signing

**Every job that invokes `xcodebuild` with `-allowProvisioningUpdates`** — `ios-build`'s archive and `ios-deliver`'s export — SHALL sign using **two persistent certificates imported into that job's shared, ephemeral keychain** — an Apple **Distribution** certificate and an Apple **Development** certificate (sourced from GitHub Secrets) — combined with an App Store Connect API key with the **Admin** role, which **cloud-manages the App Store provisioning profile** for the TestFlight export. Both certs are imported deliberately, and in **both** jobs: an empty runner keychain makes automatic signing mint a **new** cert every run, exhausting Apple's per-account cert cap; `xcodebuild archive` provisions a **development identity in addition to the distribution one**, so persisting only Distribution still churned Development certs — the Development cert is therefore imported even though `ios.yml` no longer exports a development (sideload) IPA. The pipeline SHALL NOT use fastlane or `match`. The signed App Store IPA SHALL be uploaded to TestFlight via `Apple-Actions/upload-testflight-build`.

#### Scenario: Signing reuses imported persistent certs, mints none
- **WHEN** the device app is archived (`ios-build`) or the archive is exported (`ios-deliver`)
- **THEN** signing uses the two imported persistent certificates (Distribution and Development) and `xcodebuild -allowProvisioningUpdates` obtains the App Store provisioning profile via the Admin App Store Connect API key, without minting any new certificate

#### Scenario: Development cert import prevents cert-cap churn
- **WHEN** either the `ios-build` job archives the device app on any ref, or the `ios-deliver` job exports the archive on `main`
- **THEN** the imported Apple Development certificate satisfies the development identity that `xcodebuild -allowProvisioningUpdates` provisions, so no new Development certificate is minted in either job, even though no development IPA is exported

#### Scenario: Upload uses the official Apple action
- **WHEN** the signed App Store IPA is ready on `main`
- **THEN** it is uploaded to TestFlight via `Apple-Actions/upload-testflight-build` authenticated by the App Store Connect API key

### Requirement: Signing credentials are never stored in the Actions cache

All signing and upload credentials — the App Store Connect API key and the two certificate bundles (Distribution and Development `.p12` + passwords) — SHALL exist only as **encrypted GitHub Secrets** and SHALL NOT be written to, or restored from, the GitHub Actions cache. The signing keychain SHALL be ephemeral (created per run, dies with the runner). Only the Kotlin/Native (`~/.konan`) toolchain is cached.

#### Scenario: No credentials in cache
- **WHEN** the `ios-build` job runs on any ref, or the `ios-deliver` job runs on `main`
- **THEN** the App Store Connect API key and both certificate bundles are sourced from GitHub Secrets and are never stored in or restored from the Actions cache; only `~/.konan` is cached

### Requirement: Signing and upload credentials are configured as secrets

The `ios-build` job (on every ref) and the `ios-deliver` job (on `main`) SHALL each source all Apple credentials from GitHub Secrets — the **Admin** App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_API_PRIVATE_KEY` holding the raw `.p8` PEM contents) and the two signing certificates (`SIGNING_CERT_P12_BASE64` / `SIGNING_CERT_PASSWORD` for Distribution and `SIGNING_DEV_CERT_P12_BASE64` / `SIGNING_DEV_CERT_PASSWORD` for Development). The Apple **Team ID** SHALL be committed in `Config.xcconfig` (it is not a secret).

#### Scenario: Credentials come from secrets, Team ID from config
- **WHEN** the `ios-build` job signs, or the `ios-deliver` job exports and uploads
- **THEN** the App Store Connect API key and both certificate bundles are read from GitHub Secrets, and the Team ID is read from the committed `Config.xcconfig`

## REMOVED Requirements

### Requirement: Signed device build delivered to TestFlight on every push

**Reason**: retitled and rewritten as *Signed device build delivered to TestFlight on main only*, and split — the gating behaviour it implied is now stated explicitly by the new *Delivery gates on the test suite* requirement. The old text located export and upload inside the `ios-build` job, which is precisely the shape this change removes.

### Requirement: Delivery is decoupled from the merge gate

**Reason**: replaced by *Delivery never blocks merges, and never fails silently*. The old requirement mandated `continue-on-error` on the export and upload steps to keep a delivery flake from reddening the `ios-build` check. With delivery moved to its own `main`-only job that posts no required status check, the decoupling is structural and the suppression is not merely unnecessary but harmful — it made failed deliveries conclude green.
