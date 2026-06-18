# branch-protection Delta Specification

## MODIFIED Requirements

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
