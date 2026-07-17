# ship command Specification

## Purpose

The `/ship` slash command: ships the current branch via PR + auto-merge with a client-side merge queue, then cleans up the workspace.

## Requirements

### Requirement: Ship preconditions

The `/ship` command SHALL refuse to proceed and report actionable guidance when any precondition fails: uncommitted changes, being on the default branch, un-archived openspec changes, or `allow_auto_merge` being disabled on the repository. These checks are read-only — ship SHALL NOT mutate repository settings.

#### Scenario: Uncommitted changes abort the ship
- **WHEN** `/ship` runs with a dirty working tree
- **THEN** it aborts and tells the user to commit first

#### Scenario: Shipping from the default branch aborts
- **WHEN** `/ship` runs while on `master`
- **THEN** it aborts with "Cannot ship from master branch"

#### Scenario: Un-archived openspec changes abort the ship
- **WHEN** `openspec list` reports one or more active (un-archived) changes
- **THEN** `/ship` aborts and lists them, instructing the user to archive first

#### Scenario: Auto-merge disabled aborts with guidance
- **WHEN** the repository's `allow_auto_merge` setting is off
- **THEN** `/ship` aborts and instructs the user to enable it once, without changing the setting itself

### Requirement: Create PR with auto-merge

The command SHALL rebase onto the default branch, run `./gradlew build`, push with `--force-with-lease`, create a PR with a conventional-commit title and a category label (`enhancement`/`bug`/`internal`, optionally `resolves #N`), and enable auto-merge using rebase with branch deletion.

#### Scenario: A clean branch produces an auto-merging PR
- **WHEN** `/ship` runs on a committed, rebased branch that builds successfully
- **THEN** a PR is created with the appropriate label and auto-merge is enabled with `--rebase --delete-branch`

#### Scenario: An existing open PR is resumed
- **WHEN** `/ship` runs and an open PR already exists for the branch
- **THEN** it skips creation and proceeds to wait for the merge (idempotent)

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

### Requirement: Workspace cleanup

On a successful merge the command SHALL delete the codehydra workspace unless `--keep-workspace` was passed.

#### Scenario: Successful merge deletes the workspace
- **WHEN** the PR merges and `--keep-workspace` was not passed
- **THEN** the codehydra workspace is deleted

#### Scenario: Keep-workspace flag preserves the workspace
- **WHEN** the PR merges and `--keep-workspace` was passed
- **THEN** the workspace is kept and reported as kept
