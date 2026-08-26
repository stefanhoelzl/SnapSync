## Context

The attestation cutover shipped a migration shaped like this:

```sql
CREATE TABLE devices (…);
DROP TABLE device_records;
```

with the rows to be carried across afterwards by a separate one-time program. `api-deploy` applies
migrations on every push to `main`, so the next unrelated `api/**` change would have run that pair on its
own and destroyed seventeen live push registrations. It was caught by a reviewer asking *"why drop and not
rename?"* — not by any check.

**Why the wrong shape is the easy one.** SQLite cannot alter a column's constraints in place. Every such
change is therefore a create-new / drop-old rebuild, and the `INSERT … SELECT` in the middle is the one
step it is possible to simply not write. The diff of a migration that preserves its rows and one that
destroys them differ by a statement that looks like boilerplate.

**Why prose did not prevent it.** The rule existed nowhere. After the fix it exists in a KDoc comment on
`V2` and a paragraph in `api/README.md` — which is the state this repository explicitly rejects for
anything load-bearing: *"Every law is mechanically gated in `./gradlew build`; a violation is a red build,
not a review note."*

**The second rule.** The same change committed the one-time program, its test, and a single-use workflow,
directly against the relational migration's **D13a** — *the cutover's programs are throwaway, and are not
committed* — which that change's own design cited elsewhere. A rule that lives in one past change's
decision record does not bind the next author, because nothing tells them to go and read it.

**Constraint.** The behaviour is already correct: the migration list carries its rows and the narrowing
migration declares a precondition. This change adds no production code. It records the contract and the
check, so the next migration cannot be written the wrong way without something going red.

## Goals / Non-Goals

**Goals:**

- State the preservation rule where a migration author will meet it.
- Make it a check. A rule that could only be enforced by review already failed once.
- Promote D13a from a past change's decision record into the capability's contract.

**Non-Goals:**

- Any production code change. `api/src/migrations.ts` already conforms.
- A general migration framework. The mechanism stays the ordered list plus a version record.
- Extending the rule beyond the relational store. It is stated for `database`, which is the only store
  with migrations; the on-device SQLDelight schema has its own arrangement (`.sq` + `.sqm`, with
  SQLDelight's own verify task) and is not in scope.
- Retrospectively deleting the committed cutover program — that already happened as part of the fix.

## Decisions

### D1 — The rule is two clauses, because the second cannot obey the first

"Carry your rows" is not achievable by a migration that narrows a constraint: rows that violate the new
shape cannot be carried into it, and the only ways forward are to discard them or to refuse.

Refusing is correct here for a reason specific to this backend: a store that refuses stays on its previous
version, `api-deploy` fails before publishing, and the previous bundle keeps serving. That is a recoverable
state that costs a deploy. Discarding is unrecoverable and costs data.

*Alternative considered — narrow-and-discard, with the discard logged.* Rejected: a log line is read after
someone goes looking, which is after the data is gone. The refusal is read by the person who caused it, at
the moment they caused it.

### D2 — The precondition is a function on the migration, not a convention

It is declared beside the statements it guards and runs before any of them, so a migration that cannot
carry its data leaves the store untouched rather than half-applied. Putting it in the migration rather
than in the runner keeps the condition next to the reason it exists — the runner cannot know which column
is being narrowed or what "ready" means for it.

### D3 — The gate reads the migration list, not the database

A test walks `MIGRATIONS` and asserts two textual properties: a migration containing `DROP TABLE x`
also contains an `INSERT … SELECT … FROM x` ahead of it, and a migration whose statements declare a
column `NOT NULL` that an earlier migration left nullable declares a `precondition`.

*Why textual.* The alternative — build a store, populate it, replay, and compare row counts — is a better
test of one migration and a worse gate over all of them: it would need representative data for every table
a future migration touches, which is exactly the thing an author writing a bad migration would not supply.
The textual check needs nothing from the author and cannot be satisfied vacuously.

*What it cannot catch, stated rather than left to be discovered.* A copy that names the wrong columns, or a
`WHERE` clause that silently filters. The gate establishes that a copy is present, not that it is complete;
completeness is what the accompanying migration test asserts for each actual migration.

### D4 — D13a is promoted rather than restated

The permanence split is written as a requirement in `database` rather than repeated in a new decision
record, because the failure mode was precisely that it lived in a decision record. The wording keeps
D13a's own test — *what does this program do on the next deployment?* — since that is the part an author
can apply without knowing the history.

It is **not** gated. A check would have to distinguish a one-time program from a permanent one by intent,
which is not visible in source. This is a rule that lives at review time, and stating so is more honest
than a gate that pattern-matches on filenames.

## Risks / Trade-offs

- **The textual gate can be satisfied without being obeyed** → It proves a copy exists, not that it is
  correct. Named in D3, and the per-migration tests cover completeness for the migrations that exist.
- **A future migration may legitimately drop a table whose rows are genuinely dead** → It states so by
  copying nothing and failing the gate, which forces the case into review rather than letting it pass as
  ordinary. If that becomes common the gate should gain an explicit opt-out with a stated reason; it is
  not common yet, and inventing the escape hatch before the case exists is how gates get soft.
- **The permanence split is ungated** → Accepted and stated in D4. Its enforcement is a reviewer noticing
  a single-use program in a diff, which is what failed last time — but the rule being in the spec is what
  gives that reviewer something to point at.

## Migration Plan

None. No production code changes and no deployed state is touched; the gate is expected to pass against
the migration list as it stands, which is the reason to add it now rather than after the next near-miss.

## Open Questions

- **Should the on-device SQLDelight schema adopt the same stated rule?** It has the analogous hazard —
  `2.sqm` needed `ALTER TABLE … DROP COLUMN` — and the analogous protection in SQLDelight's verify task,
  but no stated rule about preserving rows. Out of scope here; `sync-ledger` would own it.
