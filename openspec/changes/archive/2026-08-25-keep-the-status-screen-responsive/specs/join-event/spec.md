## ADDED Requirements

### Requirement: The join gate never rests in a phase that offers no action

The join gate SHALL NOT come to rest in an in-flight phase. Whenever the work such a phase represents ends,
for **any** reason including a throwable escaping it, the gate SHALL move to a phase that offers the member
an action.

The in-flight phases are `Loading` and `Committing`: by design they pin no button, so a member looking at
either has nothing to tap. Every other phase — `ExplainAccess`, `Ready`, `NotFound`, `LoadFailed`,
`CommitFailed` — offers at least a Cancel.

Without this, a failure during a details load or a commit leaves a dead-end surface for the life of the
process — a full-screen spinner with no control when no event is configured, and an invisible pending join
when one is — recoverable only by force-quitting the app.

Where a commit fails, the phase the gate lands on SHALL be decided by **whether the membership was
persisted**, because a commit writes the config partway through its work and everything after that point is
follow-up the next foreground repeats:

- the config now names the event being joined → the join **landed**; the pending join SHALL be discarded, so
  the member sees the joined screen, which is the truth.
- otherwise → the join did **not** land; the phase SHALL become the retryable commit-failure phase, whose
  Retry re-runs the join.

A failure during the details load SHALL become the retryable load-failure phase, which is what a details
source reporting a transient failure already produces — so a throwing source and a reporting one converge on
the same surface.

#### Scenario: A commit that fails after the membership was persisted lands on the joined screen
- **WHEN** the commit for the pending event fails partway through, after the membership has been persisted
- **THEN** the pending join is discarded and the screen is the joined layer for that event, with no spinner and no dialog left behind

#### Scenario: A commit that fails before the membership was persisted stays retryable
- **WHEN** the commit for the pending event fails before the membership has been persisted
- **THEN** the gate shows the commit-failure phase, whose Retry re-runs the join with the choices already made

#### Scenario: A details load that fails abnormally is retryable
- **WHEN** the details load for the pending event fails by throwing rather than by reporting a failure
- **THEN** the gate shows the load-failure phase with its Retry, exactly as a reported transient failure does

#### Scenario: No in-flight phase outlives its work
- **WHEN** the work behind a `Loading` or `Committing` phase has ended, however it ended
- **THEN** the gate is in a phase that offers the member at least one action
