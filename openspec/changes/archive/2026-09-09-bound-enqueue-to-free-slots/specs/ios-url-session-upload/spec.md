## ADDED Requirements

### Requirement: The platform reports the capacity it will accept

The `BackgroundTransfer` seam SHALL expose the number of jobs the platform will accept **right now**,
or the absence of that number for a platform that cannot answer it. Both upload tiers SHALL implement
it, since both consume the shared cycle.

The app-driven tier SHALL derive it from the concurrency cap it already measures against the session's
live task set, so the two cannot disagree — a separately configured batch size would be a second
number for one truth, in a different module, with nothing to catch a drift between them. It SHALL
clamp at zero: the cap does not bind across process death (see "The cap binds across a relaunch"), so
a relaunched session can hold more live tasks than the cap allows and the difference can be negative.

The OS-driven tier SHALL report the absence of a number. Its limit is the OS's durable job queue,
which it cannot read and which is unrelated to any local bound; reporting a guess would be worse than
reporting nothing. This mirrors that tier's siblings on the same seam, which already answer
`fetchRetryJobs` and `drainTerminals` with a constant empty result where the mechanism has nothing to
give.

The report SHALL be advisory, and both directions of staleness SHALL be safe: a value that is too low
resolves fewer rows than it could and the next cycle picks up the remainder from the ledger, and a
value that is too high is refused by the platform's own limit signal exactly as an unbounded read is
today. Nothing SHALL depend on it being exact.

#### Scenario: The app-driven tier reports its remaining slots

- **WHEN** the app-driven adapter holds live upload tasks below its concurrency cap
- **THEN** it reports the difference between the cap and the live task set

#### Scenario: A relaunched session over its cap reports zero, never negative

- **WHEN** the app relaunches while the OS still holds more live upload tasks than the cap allows
- **THEN** the adapter reports zero free capacity rather than a negative number

#### Scenario: The OS-driven tier declines to answer

- **WHEN** the OS-driven adapter is asked for its free capacity
- **THEN** it reports the absence of a number, and the cycle falls back to its fixed batch

## MODIFIED Requirements

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
without ever draining. (That per-walk figure is situational, not intrinsic: the same operation
measured 145 ms for 1084 candidates on an idle iPhone12,8 / iOS 26.6. What the requirement rests on
is the **repetition**, not the cost of any one walk.)

The read SHALL be bounded by **what the platform will accept right now**, not by a fixed batch. A
platform that knows its own capacity SHALL report it (see "The platform reports the capacity it will
accept"); where it reports a number, the cycle SHALL ask the ledger for no more rows than that.
Resolving a row costs a synchronous platform round-trip that nothing can interrupt, so every row
resolved beyond what the platform will accept is uninterruptible time spent on a job that is not
created — measured at 54 ms for sixteen keys against a cap of four (iPhone12,8 / iOS 26.6), where one
key costs 11 ms and three cost 19 ms. Where the platform reports no number, the fixed batch SHALL
remain the bound, because an unbounded read would try to resolve a whole backlog.

An enqueue pass whose work-source read **is filled to its bound** SHALL report the cycle
**truncated**, because the ledger may hold rows the pass could not take. Bounding the read removes the
signal that previously carried this: truncation was observed by the platform refusing a creation, and a
pass that never asks for more than the platform will accept is never refused. Without it, every
capacity below the backlog would publish a drained cycle over remaining work.

An enqueue pass that resolves nothing **because the platform reports no free capacity** SHALL likewise
report the cycle **truncated**, not drained. The platform being full while rows still need a job is
backpressure — the same fact the platform's own limit signal carries — and reporting it as an absence
of work would publish a completed cycle over a non-empty backlog, leaving the pump nothing to re-arm
on.

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

#### Scenario: The top-up asks for no more than the platform will take

- **WHEN** a cycle enqueues while the platform reports free capacity smaller than the fixed batch
- **THEN** the work-source read is bounded by that capacity, so no row is resolved for a job the
  platform would refuse

#### Scenario: A full platform truncates rather than reporting no work

- **WHEN** a cycle enqueues while the platform reports zero free capacity and rows needing a job
  remain in the ledger
- **THEN** no row is resolved, the cycle is reported truncated, and it publishes `PROCESSING` so the
  trigger's re-arm policy is applied to a cycle that knows work remains

#### Scenario: A saturated read reports work remaining

- **WHEN** a cycle enqueues, the work-source read returns as many rows as its bound allows, and every
  one of them is accepted by the platform
- **THEN** the cycle is reported truncated, so a backlog larger than one pass is never published as a
  drained cycle

#### Scenario: A platform that reports no capacity keeps the fixed batch

- **WHEN** a cycle enqueues on a platform that reports no free-capacity number
- **THEN** the fixed batch bounds the read exactly as before
