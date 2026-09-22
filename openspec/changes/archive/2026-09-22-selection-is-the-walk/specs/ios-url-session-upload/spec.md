## MODIFIED Requirements

### Requirement: The producer tops up from the ledger, not from the walk's output

On this tier the upload cycle SHALL enqueue work from the ledger's rows that need a job (capability
`sync-ledger`), resolving each row's resource on demand. It SHALL NOT enqueue from the discovery walk's
return value: the walk's job is to **record** what it found, and creating jobs from what it happens to
be holding is what made the cycle unable to resume work it had already seen.

A cycle SHALL still walk the library, because that is the only way to learn what the library holds.
Every walk is a full enumeration (capability `ios-photokit-upload`, "In-extension discovery by full
enumeration"; this tier binds the same `IosDiscovery`), including the walk of a cycle a completion
triggered. What bounds its cost is that it reads resources only for the assets the ledger does not fully
know (capability `sync-ledger`, "A walk re-reads only the assets the ledger does not fully know"), so a
completion-triggered cycle over a fully-recorded library pays the fetch and the per-asset facts, and no
resource read.

This is what makes the tier's concurrency cap a throughput bound rather than an architectural one.
Before it, the only source of work was the walk's return value, so freeing one slot cost a full library
enumeration to refill it: measured on device (build 0.3(605), iPhone11,2 / iOS 18.7.9), 6.1–7.2 seconds
of PhotoKit XPC over 224 candidates to enqueue two to four resources, repeated 26 times in two hours
without ever draining. (That per-walk figure is situational, not intrinsic: the same operation
measured 145 ms for 1084 candidates on an idle iPhone12,8 / iOS 26.6. What the requirement rests on
is the **repetition**, not the cost of any one walk.) With every walk a full enumeration, the fetch is
repeated per cycle again; what is no longer repeated is the resource read of every admitted asset, and
no refill depends on the walk at all.

What is CREATED SHALL be bounded only by **the platform's own refusal**, never by a guess at its capacity.
The cycle SHALL walk the admitted rows **one at a time**: resolve the row through `UploadDiscovery`, create
its job, and stop the **whole pass** at the first `LIMIT_EXCEEDED`, before resolving the next row. There
SHALL be no capacity read, no fixed batch and no resolve chunk. Both transports refuse honestly: this tier's
`createJob` counts the session's live tasks, so its cap binds across a relaunch, and PhotoKit refuses at its
own job limit.

Resolving a row costs a synchronous platform round-trip that nothing can interrupt, measured at **~4.5 ms per
request plus ~3.45 ms per photo** (rig probe, SE2 / iOS 26.6, 2026-09-22; 100 distinct images per run, two
rounds plus a warm repeat, no cache effect). For 100 photos that is 0.80–0.94 s one at a time, against
0.46 s in fours and 0.37 s in sixteens. The difference is accepted for simplicity. It applies only under a
full grant: under a partial grant keys resolve from the selection snapshot already in hand, with no platform
call (see "Ledger keys resolve to uploadable resources"). The earlier figure of "11 ms for one key"
understated the per-photo cost this call carries.

Decision records: `changes/both-uploaders-active` (D9), and `changes/selection-is-the-walk` (D5), which
retired the resolve chunk.

This bounds creation, never the read: the work-source read and the admission stay unbounded, because bounding
the read starves.

An enqueue pass SHALL report the cycle **truncated** exactly when the platform refused a creation
(`LIMIT_EXCEEDED`) — or the settle hit its own cap — because the rows it did not reach still need a job.
Reporting the refusal as an absence of work would publish a completed cycle over a non-empty backlog, leaving
the pump nothing to re-arm on.

#### Scenario: A completion-triggered cycle enqueues from the ledger

- **WHEN** an upload completes, freeing a concurrency slot, and rows needing a job exist in the ledger
- **THEN** the cycle enqueues from those rows, whether or not that cycle's walk found anything new

#### Scenario: A cycle with nothing new to discover still makes progress

- **WHEN** a cycle's walk returns no asset the ledger does not already know, and the ledger holds rows
  needing a job
- **THEN** the cycle enqueues those rows rather than treating a walk with nothing new as no work

#### Scenario: A failed row is retried without re-reading its asset

- **WHEN** a transfer fails and its row is recorded `DISCOVERED`, on a device whose library has not changed
  since
- **THEN** the next cycle re-enqueues that row from the ledger, and its walk does not read that asset's
  resources

#### Scenario: The top-up creates until the platform refuses

- **WHEN** a cycle enqueues more admitted rows than the platform will accept
- **THEN** it resolves and creates row by row, stops the pass at the first `LIMIT_EXCEEDED`, resolves no
  later row, and the work-source read itself stays unbounded

#### Scenario: A refusal truncates rather than reporting no work

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` while admitted rows remain without a job
- **THEN** the cycle is reported truncated and publishes `PROCESSING`, so the trigger's re-arm policy is
  applied to a cycle that knows work remains

#### Scenario: A refusal wastes no resolve

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` for a row
- **THEN** no further row is resolved in that pass, and every row not yet created remains `DISCOVERED` for a
  later cycle

#### Scenario: A backlog the platform accepts is not truncated

- **WHEN** every admitted row's job is created without a refusal
- **THEN** the pass is not reported truncated by the top-up

