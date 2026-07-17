# ship-command (delta)

## MODIFIED Requirements

### Requirement: Client-side merge queue

The command SHALL wait via a client-side FIFO merge queue (ordered by auto-merge enablement time): wait for PRs ahead, skip dead ahead-PRs (closed, dirty, or with a failing **required** check — the required set is derived at run time from the default branch's protection rules, so a red non-required check such as the red-by-design migration beacon never skips a PR; an unresolvable required set degrades in the strict direction, treating every check as required), and when first in queue, rebase onto the default branch, apply committed rulesets, wait for the required CI checks (`gh pr checks --required` — the checks auto-merge itself waits on), and confirm the merge.

#### Scenario: No PRs ahead proceeds immediately
- **WHEN** no other auto-merge PR was enabled before ours
- **THEN** the queue reports it is our turn and the command proceeds to rebase, CI, and merge

#### Scenario: PRs ahead are waited on
- **WHEN** one or more auto-merge PRs were enabled before ours and are still mergeable
- **THEN** the command polls and waits until none remain ahead

#### Scenario: Our PR merging ends the wait
- **WHEN** our PR reaches MERGED state
- **THEN** the command exits success (0)

#### Scenario: A red informational check does not fail the ship
- **WHEN** the PR carries a failing non-required check while every required check passes
- **THEN** the queue does not skip ahead-PRs for it and the watch reports success
