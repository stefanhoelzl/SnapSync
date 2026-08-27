## ADDED Requirements

### Requirement: The producer tops up from the ledger, not from the walk's output

On this tier the upload cycle SHALL enqueue work from the ledger's rows that need a job (capability
`sync-ledger`), resolving each row's resource on demand. It SHALL NOT enqueue from the discovery walk's
return value: the walk's job is to **record** what it found, and creating jobs from what it happens to
be holding is what made the cycle unable to resume work it had already seen.

A cycle SHALL still consult the change feed, because that is the only way to learn what the library
did — there is no cheaper oracle, and the cursor is not one: `discoverResources(token)` **is** the
question. What changes is the cost of asking. Because the cursor now advances once the walk's facts are
durable, that consultation is an incremental change-token fetch rather than a full enumeration.

This is what makes the tier's concurrency cap a throughput bound rather than an architectural one.
Before it, the only source of work was the walk's return value, so freeing one slot cost a full library
enumeration to refill it: measured on device (build 0.3(605), iPhone11,2 / iOS 18.7.9), 6.1–7.2 seconds
of PhotoKit XPC over 224 candidates to enqueue two to four resources, repeated 26 times in two hours
without ever draining.

#### Scenario: A completion-triggered cycle enqueues from the ledger

- **WHEN** an upload completes, freeing a concurrency slot, and rows needing a job exist in the ledger
- **THEN** the cycle enqueues from those rows, whether or not that cycle's change feed reported anything

#### Scenario: A cycle with nothing new to discover still makes progress

- **WHEN** a cycle's change feed reports no change and the ledger holds rows needing a job
- **THEN** the cycle enqueues those rows rather than treating an empty change set as no work

#### Scenario: A failed row is retried on a device whose cursor is settled

- **WHEN** a transfer fails and its row is recorded `FAILED`, on a device whose discovery cursor is
  settled and whose library has not changed since
- **THEN** the next cycle re-enqueues that row from the ledger, without waiting for a full enumeration
  to re-derive it

### Requirement: Ledger keys resolve to uploadable resources

The adapter SHALL expose a seam that resolves a set of ledger keys to uploadable resources — the
platform handles `createJob` requires, which a ledger row cannot carry — scoped to those keys and
never by walking the library. Both upload tiers SHALL implement it, since both consume the shared
cycle.

The resolution SHALL be **partial-tolerant**: a key whose asset is no longer in the library resolves to
nothing, and the cycle SHALL treat that as the asset having departed rather than as a failure to
upload. Under a partial photo grant the resolution SHALL be served from the selection snapshot already
in hand, so it performs no library read (capability `limited-photo-access`).

#### Scenario: Keys resolve without a library walk

- **WHEN** the cycle asks the adapter to resolve a set of ledger keys
- **THEN** the adapter fetches only those assets' resources, and enumerates nothing else

#### Scenario: A departed asset resolves to nothing

- **WHEN** a key's asset has been deleted from the library since its row was recorded
- **THEN** the resolution returns nothing for that key, and the cycle records the asset absent rather
  than reporting an upload failure

#### Scenario: A partial grant resolves from the snapshot

- **WHEN** photo permission is `LIMITED` and the cycle resolves ledger keys
- **THEN** the resolution is served from the current selection snapshot, with no platform read
