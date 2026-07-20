# architecture-guards — delta

## ADDED Requirements

### Requirement: The upload producers are never both started

A `:test:architecture` guard SHALL pin the exactly-one-started invariant of `upload-lifecycle`: with
both upload producers composed (iOS ≥26.1), no path through the tier-neutral orchestrator starts both
producers, and every mechanism switch stops the outgoing producer before starting the incoming one.
The guard SHALL drive the orchestrator over fake producers through every transition row of the
lifecycle table — provision under each permission, the `GRANTED` ↔ `LIMITED` flips in both directions,
grant-with-no-membership, and leave — asserting after each step that at most one producer is in the
started state and that a switch observed stop-before-start.

This guard is the enforcement half of the structural→behavioral move recorded in `upload-lifecycle`
("Exactly one producer started per process"): construction-time exclusion was the previous guarantee,
runtime permission-dependence made it inexpressible, and a build-gating test is what replaces the
compile error.

#### Scenario: No transition sequence starts both producers
- **WHEN** the guard drives the orchestrator through every lifecycle transition row, in sequence and in
  permission-flip combinations
- **THEN** at no observed point are both producers started, and the build fails if any sequence
  violates this

#### Scenario: A switch that starts before stopping fails the build
- **WHEN** an orchestrator change makes a permission flip start the incoming producer before the
  outgoing producer's stop completes
- **THEN** the guard fails the build
