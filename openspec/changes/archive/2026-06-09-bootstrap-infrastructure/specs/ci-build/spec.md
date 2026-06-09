## ADDED Requirements

### Requirement: Build on every push

The system SHALL run a GitHub Actions workflow on every push that builds the project with `./gradlew build` on JDK 25 and reports a status check named `build`.

#### Scenario: A push triggers the build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `build` workflow runs `./gradlew build` on `ubuntu-latest` with JDK 25 and reports a `build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the push builds successfully
- **THEN** the `build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the push fails to build
- **THEN** the `build` status check concludes as failure (red)

### Requirement: Concurrency control

The workflow SHALL cancel an in-progress run on the same ref when a newer push arrives, so rapid pushes do not pile up runs.

#### Scenario: A newer push cancels the in-progress run
- **WHEN** a new commit is pushed to a ref that already has a running `build`
- **THEN** the in-progress run for that ref is cancelled and the new one proceeds
