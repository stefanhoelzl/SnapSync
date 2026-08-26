## Why

A migration was written that created the new table and then `DROP TABLE`d the old one, leaving its rows to
be carried across by a separate one-time program. It would have destroyed seventeen live push
registrations on the next ordinary push to `main` — because CI applies migrations on every deploy, and
nothing stood between the deploy and the drop but the operator remembering to run the data program first.

It was caught by review, not by anything mechanical. Nothing in `openspec/specs/` says a migration must
carry its rows, so nothing could have caught it: the correction lives in a code comment and a README
paragraph, which is exactly the state that let it be written in the first place. This repository's own
standard is the opposite — *"Every law is mechanically gated in `./gradlew build`; a violation is a red
build, not a review note."*

The near-miss also exposed a second rule that existed only in a decision record. The relational
migration's **D13a** — *the cutover's programs are throwaway, and are not committed* — was cited by that
same change's design and then not followed: a single-use script, its test, and a single-use workflow were
committed, the workflow to sit in `.github/workflows/` indefinitely. A rule stated once, in the design
record of a past change, is not a contract; a reader has no reason to expect it to bind them.

## What Changes

- **`database` gains the preservation rule.** A migration that rebuilds a table SHALL carry its rows. A
  migration that NARROWS a constraint — the case that cannot carry every row — SHALL declare a
  precondition and REFUSE, leaving the store on the previous version, rather than discarding the rows it
  cannot carry.
- **The rule becomes a gate**, not prose: a test asserts that no migration drops a table it has not first
  copied out of, and that every constraint-narrowing migration declares a precondition. Prose is what
  failed here.
- **`database` gains the permanence split**: the migration mechanism is permanent and lives in the repo;
  a one-time data cutover is throwaway, is not committed, and runs from a scratchpad. This promotes D13a
  from one change's decision record into the capability's contract, and states the test that separates
  the two — does it run again on the next deploy, or did it run once against one store on one day?

**No behaviour changes.** The migration list already satisfies both rules; they were applied as a defect
fix in `internal(api): a migration migrates its data, it does not drop it`. This change records the
contract that fix should have been derived from, and adds the check that would have caught its absence.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `database`: adds the data-preservation rule for migrations (rebuilds carry their rows; narrowing
  migrations refuse rather than discard), and the split between the permanent migration mechanism and a
  throwaway one-time data cutover.

## Impact

- **`openspec/specs/database/spec.md`**: two added requirements.
- **`api/test/migrations.test.ts`**: the gate — a migration that drops a table must first copy from it,
  and a migration that narrows a column must declare a precondition.
- **No production code changes.** `api/src/migrations.ts` already conforms; the gate is expected to pass
  on the first run, which is the point of writing it now rather than after the next near-miss.
- **Out of scope**: the one-time attestation backfill itself, which is already running from a scratchpad
  and is deliberately not in the repo.
