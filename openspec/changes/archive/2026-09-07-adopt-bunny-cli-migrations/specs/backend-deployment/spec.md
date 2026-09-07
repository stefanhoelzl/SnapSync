## ADDED Requirements

### Requirement: A deploy asserts the deployed store's shape against the committed schema

A deploy SHALL compare the deployed store's actual schema against the committed generated schema
(capability `database`) and SHALL fail when they differ. The comparison SHALL be made in one of two
positions, chosen by the same pending answer the maintenance window already branches on:

- when **no migration is pending**, the assertion SHALL run **before** the window decision. The store is
  already expected to match, so a mismatch fails the run before anything is published and before any
  window is opened.
- when **a migration is pending**, the assertion SHALL run **after** the migration is applied and
  **before** the real bundle is published, inside the window, where the existing restore path lifts it.

Splitting on the pending answer is structural rather than a convenience: while a migration is pending the
deployed store legitimately does not yet match the committed schema, so asserting beforehand would fail
every migrating deploy.

This exists because nothing else observes the **deployed** schema. Every other check in `database` runs
against a locally-replayed store on a different engine, so a difference between what the deployment
produced and what the repository expects is otherwise invisible. It is also the only thing that detects a
schema edited outside the migrations — the platform's interactive SQL shell can write to the store, so a
hand-edit would otherwise diverge the store from every migration silently and permanently.

The comparison SHALL normalise away spelling that carries no meaning — comments, existence guards,
identifier quoting, and whitespace — and SHALL exclude the runner's own bookkeeping table, which exists
only on the deployed side.

#### Scenario: A deploy with nothing pending asserts before opening anything

- **WHEN** the pending check reports that every migration is already applied
- **THEN** the deployed store's schema is compared with the committed schema before any bundle is
  published, and a difference fails the run with no window opened

#### Scenario: A migrating deploy asserts after applying

- **WHEN** a migration is applied inside the maintenance window
- **THEN** the deployed store's schema is compared with the committed schema before the real bundle is
  published, and a difference fails the run

#### Scenario: A hand-edited store is caught

- **WHEN** the deployed store's schema has been changed outside the migrations
- **THEN** the next deploy fails, naming the object whose definition differs

#### Scenario: Harmless spelling differences do not fail the assertion

- **WHEN** the deployed store and the committed schema differ only in comments, existence guards,
  identifier quoting or whitespace
- **THEN** the assertion passes
