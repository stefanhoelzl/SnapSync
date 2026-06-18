# branch protection Specification

## Purpose

Protection of the default branch via a committed ruleset (required build check, rebase-only, linear history, PR-gated), applied during ship.
## Requirements
### Requirement: Default branch ruleset

The default branch SHALL be protected by a committed ruleset (`.github/rulesets/main.json`) that requires the `build` status check, **the iOS build status check reported by GitHub Actions** (context `ios-build`), **and the iOS test status check reported by GitHub Actions** (context `ios-test`), allows only rebase merges, requires linear history, requires a pull request, and forbids branch deletion and non-fast-forward (force) pushes.

#### Scenario: Direct push to the default branch is rejected
- **WHEN** someone attempts to push directly to `master`
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

#### Scenario: Non-rebase merges are disallowed
- **WHEN** a merge is attempted with a method other than rebase
- **THEN** it is rejected, preserving linear history

### Requirement: Ruleset applied during ship

The ruleset SHALL be applied from the committed `.github/rulesets/*.json` during ship, when the PR is first in the merge queue, using the operator's authenticated `gh` (admin) — creating the ruleset if absent and updating it if present. No CI write-workflow or stored token is used. Repositories without a rulesets directory SHALL be unaffected.

#### Scenario: Ruleset is applied when first in queue
- **WHEN** the shipping PR becomes first in the merge queue and has been rebased
- **THEN** each `.github/rulesets/*.json` is applied (created if it does not exist, updated if it does)

#### Scenario: Repositories without rulesets are unaffected
- **WHEN** ship runs in a repository that has no `.github/rulesets` directory
- **THEN** the ruleset-apply step is a no-op

