## MODIFIED Requirements

### Requirement: Default branch ruleset

The default branch SHALL be protected by a committed ruleset (`.github/rulesets/main.json`) that requires the `build` status check, **the iOS build status check reported by GitHub Actions** (context `ios-build`), **the iOS test status check reported by GitHub Actions** (context `ios-test`), **the App Store metadata validation status check reported by GitHub Actions** (context `appstore-metadata-validate`), **the architecture-diagrams freshness check reported by GitHub Actions** (context `diagrams` — stale committed diagrams block the PR; capability `architecture-diagrams`), **and the changelog-label check reported by GitHub Actions** (context `check-label` — a pull request carrying none of `enhancement`/`bug`/`internal` blocks the PR; capability `changelog-labels`), allows only rebase merges, requires linear history, requires a pull request, and forbids branch deletion and non-fast-forward (force) pushes.

The `appstore-metadata-validate` check is safe to require because it runs **offline on every ref** (no App Store Connect credentials — capability `ios-appstore-metadata`), so it always posts on a pull-request branch; unlike the `main`-only metadata apply job, it can never freeze merges the way a required check that never runs on a PR branch would. The `check-label` check is safe to require for the same reason: it is triggered by pull-request events, so it always posts on a pull request, and `/ship` applies a label as it opens one (capability `ship-command`), so requiring it costs a shipped change nothing.

#### Scenario: Direct push to the default branch is rejected
- **WHEN** someone attempts to push directly to `main`
- **THEN** the push is rejected because a pull request is required

#### Scenario: A PR cannot merge without the build check passing
- **WHEN** a PR's `build` status check has not passed
- **THEN** the PR cannot be merged

#### Scenario: A PR cannot merge without the iOS build check passing
- **WHEN** a PR's iOS build status check (`ios-build`, reported by GitHub Actions) has not passed
- **THEN** the PR cannot be merged

#### Scenario: A PR cannot merge without the iOS test check passing
- **WHEN** a PR's iOS test status check (`ios-test`, reported by GitHub Actions) has not passed
- **THEN** the PR cannot be merged

#### Scenario: A PR cannot merge without the App Store metadata validation check passing
- **WHEN** a PR's App Store metadata validation status check (`appstore-metadata-validate`, reported by GitHub Actions) has not passed
- **THEN** the PR cannot be merged

#### Scenario: A PR cannot merge without the changelog-label check passing
- **WHEN** a PR's changelog-label status check (`check-label`, reported by GitHub Actions) has not passed
- **THEN** the PR cannot be merged

#### Scenario: Non-rebase merges are disallowed
- **WHEN** a merge is attempted with a method other than rebase
- **THEN** it is rejected, preserving linear history
