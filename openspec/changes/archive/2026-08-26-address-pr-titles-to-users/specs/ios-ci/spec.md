## MODIFIED Requirements

### Requirement: The merge gates are exactly the two parallel jobs

The iOS workflow's **merge gates** SHALL be exactly the two parallel jobs `ios-build` and `ios-test`, and these are the only two iOS status-check contexts required by the committed branch ruleset (`.github/rulesets/main.json`). Adding a `needs:` dependency between them is forbidden: a failing `ios-test` would then *skip* `ios-build`, whose required check would never be posted, freezing merges.

The delivery job `ios-deliver` SHALL NOT be a required status check. It runs only on `refs/heads/main`, so it is never posted on a pull-request branch; requiring it would block every merge on a check that can never appear. Its purpose is to gate **delivery**, not merges — it depends on both gates, so it simply does not run when either is red.

#### Scenario: The two gates stay independent
- **WHEN** the iOS workflow runs on any ref
- **THEN** `ios-build` and `ios-test` each run and report regardless of the other's outcome, so a red test still tells you whether the device app compiles

#### Scenario: The delivery job is not a merge gate
- **WHEN** the branch ruleset's required status checks are applied
- **THEN** they include `ios-build` and `ios-test` but NOT `ios-deliver`, which never runs on a pull-request branch and would freeze merges if required
