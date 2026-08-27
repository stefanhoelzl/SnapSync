## ADDED Requirements

### Requirement: Settling with the platform is owed regardless of the cycle's other outcomes

The upload cycle SHALL settle with the platform — drain the outcomes it is holding and adjudicate them
— on **every** cycle that reaches a usable membership, before and independently of every later
decision the cycle makes. In particular it SHALL do so when the re-join reconciliation defers, exactly
as it already does when the direction gate declines.

The obligation is owed to the platform for work it has already presented, and it does not depend on
whether this membership still contributes, or on whether the ledger has been seeded yet. Measured on
iOS 26.6: with the extension still registered and jobs outstanding, a cycle that returned before the
acknowledgement pass caused the system to report `com.apple.photos.error Code=50008` ("appex failed to
acknowledge jobs for processing state"), **discard** the outstanding jobs, and record a failed attempt
against the upload-job configuration that defers the extension by ~300 seconds and escalates with the
attempt count. Expiry: re-measure at the next iOS major.

Settling creates no upload work and publishes nothing: it enumerates nothing, touches no discovery
cursor, and writes no manifest. Suppressing the manifest write on a deferred reconciliation stays
required (capability `device-manifest`) and is unaffected by this.

#### Scenario: A deferred reconciliation still settles

- **WHEN** the re-join reconciliation defers because the device's stored-file listing failed or timed
  out, on a contributing membership
- **THEN** the cycle still settles with the platform, and still writes no manifest, creates no upload
  job, and leaves the discovery cursor untouched

#### Scenario: A declined direction still settles

- **WHEN** the membership's direction excludes upload
- **THEN** the cycle still settles with the platform

#### Scenario: An unusable membership settles nothing

- **WHEN** the entry gate reports the membership unreadable, or definitively absent
- **THEN** the cycle settles with no platform at all, because settling requires the configuration the
  gate could not supply

### Requirement: The cycle's publication is decided by its outcome

The upload cycle SHALL decide what it publishes from its own stated outcome, in one place, rather than
by which statement returned. What it publishes means the event-album placement, the enumeration audit
line, the device manifest, the completion notify, and the promotion of uploaded rows. The decision
SHALL be exhaustive over the outcomes a cycle can have, so a new outcome cannot inherit a publication
policy nobody chose for it.

No path SHALL be able to return a cycle result without passing through that decision.

This exists because five publications were previously reachable only by falling through to the end of
the cycle, so any early return silently withheld all five — and the two early returns that a device
with a backlog takes on every cycle withheld them permanently, with no error and no log line.

#### Scenario: A new cycle outcome must state what it publishes

- **WHEN** a new outcome is added to the cycle's result vocabulary and the publication decision is not
  updated
- **THEN** the build fails, because the decision is exhaustive with no fallback branch

#### Scenario: Every exit publishes

- **WHEN** a cycle ends by any route — unreadable membership, no membership, deferred reconciliation,
  declined direction, job limit reached, or fully drained
- **THEN** the publication decision runs for that outcome, publishing exactly what that outcome calls
  for
