## MODIFIED Requirements

### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerStore`. There SHALL be no separate
reader type: the writer is constructed only by the composition root that owns the engine (one per
platform), and components that must not record are simply never handed a writer — app-side read
access goes through `LedgerStore`'s read operations (`aggregates()`, per `sync-status`), never
through a writer instance.

**The invariant is that exactly one PROCESS records**, and its process placement is a platform binding — the
extension on iOS ≥26.1, the app on iOS 18–26.0. Handing a writer instance only where recording is intended is
the **mechanism** that codifies it, not the invariant itself. That mechanism is deliberately relaxed for one
operation: `markTerminal` (see "Guarded terminal write") is declared on **`TransferRecord`** — a narrow
interface `LedgerStore` extends, carrying only `markTerminal` and the read `entryForDestination` (see "The
ledger records the destination a job was sent to") — because the party the platform tells that an upload
terminated is a platform callback inside the record-writing process, and it cannot suspend. The invariant
holds — that callback belongs to the one recording process — while the type-level codification does not cover
it. A spec or a review that reads the type-level rule as the invariant will reach the wrong conclusion about
this call, which is why both are stated.

A **transport** — an implementation of the upload transfer lifecycle (`BackgroundTransfer`) — SHALL receive a
`TransferRecord` and SHALL NOT receive a `LedgerStore`. What a transport may touch in the ledger is therefore
exactly the one guarded terminal write and the one destination lookup; every other read and write, including
the decision which in-flight rows a transport has lost, belongs to the cycle.

No record operation other than `markTerminal` SHALL be added to `TransferRecord` or to `LedgerStore` on this
argument; a further record operation belongs on the writer.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the root's `LedgerWriter`
- **THEN** it has no record operation available beyond `markTerminal` — it can otherwise read the ledger
  only through `LedgerStore`'s read operations

#### Scenario: One process records

- **WHEN** the platform callback records a terminal upload through `TransferRecord` and the cycle records
  through the `LedgerWriter`
- **THEN** both are inside the single record-writing process for that tier, and no second process records

#### Scenario: A transport holds only the narrow surface

- **WHEN** a transport adapter is composed
- **THEN** it is handed a `TransferRecord`, and no other ledger read or write is reachable from it

### Requirement: Guarded terminal write

`TransferRecord` — which `LedgerStore` extends — SHALL declare `markTerminal(key, outcome): Boolean` — a
**single guarded statement** that sets a row's state **only while that row is still `REQUESTED`**, and answers
whether it applied. On the SQLDelight backend it SHALL be one
`UPDATE … WHERE key = :key AND state = 'REQUESTED'` whose applied/not-applied answer is read inside that
statement's own transaction.

`outcome` SHALL be a `TerminalOutcome` — `COMPLETED` or `FAILED`, declared in `:domain` `model/` — and not a
`LedgerState`, so the only states this write can record are the two an upload can terminate in. This is the
one record operation a platform callback reaches through `TransferRecord` rather than through the writer (see
"Reader and writer capability split"), which is why the set it may record is fixed by its type rather than by
convention. `COMPLETED` means the platform reported the upload succeeded; no further work is owed for the key.

It SHALL be **non-suspending**, so a platform callback that cannot call a suspending function may record
through it directly.

The guard is the operation's purpose, not a defence: two writers reach this row with no shared lock — a
platform callback on the platform's own queue, and the upload cycle on the composition lane — and a
read-then-write pair is not atomic against the one that does not take the lock. Every other column
(`assetId`, `attempt`, `eventId`, and the manifest detail) SHALL be preserved by the statement rather than
re-supplied by the caller.

A write that applies to no row SHALL be reported to the caller and **SHALL NOT be silent**: "the row moved
on" and "this fact was recorded" have different consequences.

#### Scenario: A REQUESTED row is flipped

- **WHEN** `markTerminal(key, COMPLETED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `COMPLETED`, every other column is unchanged, and the call answers that it applied

#### Scenario: A row that moved on is not clobbered

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is no longer `REQUESTED`
- **THEN** no row is changed and the call answers that it did not apply

#### Scenario: An absent row is reported, not assumed

- **WHEN** `markTerminal` is called for a key with no row
- **THEN** no row is created and the call answers that it did not apply

#### Scenario: A non-terminal state cannot be recorded

- **WHEN** a caller attempts to record `DISCOVERED` or `REQUESTED` through `markTerminal`
- **THEN** the build fails, because the parameter's type admits only `COMPLETED` and `FAILED`
