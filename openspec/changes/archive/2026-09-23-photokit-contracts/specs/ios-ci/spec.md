## RENAMED Requirements

- FROM: `### Requirement: The merge gates are exactly the two parallel jobs`
- TO: `### Requirement: The merge gates are exactly the three parallel jobs`

## MODIFIED Requirements

### Requirement: The merge gates are exactly the three parallel jobs

The iOS workflow's **merge gates** SHALL be exactly the three parallel jobs `ios-build`, `ios-test` and `ios-contracts`, and these are the only three iOS status-check contexts required by the branch ruleset on `main`. Adding a `needs:` dependency between any two of them is forbidden: a failing gate would then *skip* the one depending on it, whose required check would never be posted, freezing merges.

The delivery job `ios-deliver` SHALL NOT be a required status check. It runs only on `refs/heads/main`, so it is never posted on a pull-request branch; requiring it would block every merge on a check that can never appear. Its purpose is to gate **delivery**, not merges — it depends on `ios-build` and `ios-test`, so it simply does not run when either is red.

#### Scenario: The gates stay independent
- **WHEN** the iOS workflow runs on any ref
- **THEN** `ios-build`, `ios-test` and `ios-contracts` each run and report regardless of the others' outcomes, so a red test still tells you whether the device app compiles and whether the in-app contracts hold

#### Scenario: The delivery job is not a merge gate
- **WHEN** the branch ruleset's required status checks are applied
- **THEN** they include `ios-build`, `ios-test` and `ios-contracts` but NOT `ios-deliver`, which never runs on a pull-request branch and would freeze merges if required

## ADDED Requirements

### Requirement: Run the in-app port contracts on a simulator on every push

The system SHALL run a job `ios-contracts` in `.github/workflows/ios.yml` on every push, on a `macos-26` hosted runner, in parallel with `ios-build` and `ios-test`. It SHALL build the app with `-Psnapsync.rig=true` for the iOS simulator, ad-hoc sign it with `scripts/sim-sign`, install it on a **freshly created** simulator, grant photo access to the app's bundle with a version-pinned `applesimutils` before the first launch, launch it, and run every contract in the simulator-app registry through the rig's contract verb (capability `port-contracts`, "In-app hosts CI can reach are run live over the rig"). It SHALL post the `ios-contracts` status-check context, concluding as failure when any clause reads `Failed`, when a run is refused, when the registry is empty, or when the app never answers on the rig port — capturing a simulator screenshot and the app's log in that last case, because a pending system alert is visible only there.

#### Scenario: A contract clause fails in the simulator app
- **WHEN** a clause run in the simulator app reads `Failed`
- **THEN** the `ios-contracts` check concludes as failure and its log carries the full outcome table

#### Scenario: The grant did not take
- **WHEN** photo access was not granted before launch and the contract verb refuses the run
- **THEN** the `ios-contracts` check concludes as failure rather than reporting the unrun clauses as passed

#### Scenario: The app never answers
- **WHEN** the launched app does not answer on the rig port
- **THEN** the job fails with a screenshot and the app log attached, rather than timing out without evidence
