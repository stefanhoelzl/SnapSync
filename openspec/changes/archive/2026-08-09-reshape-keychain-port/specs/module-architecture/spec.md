# module-architecture — delta for reshape-keychain-port

> **Ordering:** this delta modifies *Absence is never silent*, which is already in the main spec and
> is **not** touched by the two unsynced changes ahead of it (`enforce-port-boundary` modifies *Ports
> are the I/O boundary named for the need* and *Dispatcher lanes are fixed by the composition*;
> `retire-legacy-config-fallback` touches this spec not at all). There is therefore no ordering
> dependency here — unlike the `architecture-guards` delta in this same change.

## MODIFIED Requirements

### Requirement: Absence is never silent

A seam that can answer "nothing" SHALL distinguish *nothing* from *could not tell* **wherever the
two have different consequences**. Where they are deliberately collapsed, the collapse SHALL name
the consequence that makes it safe **for every cause it absorbs** — not only for the cause its
author had in mind. An entry point SHALL never collapse into silence: a driver's arrival and its
outcome are recorded before and after any decision, because a lost trigger is invisible and
unfixable while a spurious log line is harmless and visible (the same asymmetry the
`photo-selection-policy` capability uses to admit on doubt).

This law describes existing practice. `ConfigFileRead` admits only the not-found error class as
absence and defers on every other failure; `ConfigRead` carries distinct sentinels so a device log
can tell two unreadables apart; `SecureStoreRead` separates `Absent` from `Unavailable` and
`readExisting` throws on the latter rather than returning null; `JoinLoad` keeps `NotFound`
distinguishable from `Failed`; `SwitchDecision` returns a named answer where a null would do. The
law names the rule those seams already follow so that a violation is a defect rather than a
discovery.

Separating the two answers is a requirement on the seam's **shape**, not on what it carries with
them. `SecureStoreRead.Unavailable` carries an opaque adapter-formatted diagnostic rather than the
platform's error code, precisely so that no caller can classify it: the three-state shape is what
every decision reads, and the diagnostic exists only to reach a device log. A seam SHALL NOT be
read as satisfying this law by carrying a rich failure payload while collapsing the answers, nor as
violating it by carrying a poor one while keeping them apart.

The test is **consequence asymmetry, not nullability**. A nullable return is not itself a
violation: `DiscoveryStore.loadToken` collapses absent and unreadable correctly, because a cold
start with no cursor re-enumerates the whole library and the ledger makes that harmless, and it
says so. The violation is a collapse whose stated consequence does not cover every cause it
absorbs, or a collapse with no stated consequence at all.

The law is enforced mechanically at the two seams where enforcement is possible — platform entry
points and the `ports/` boundary (capability `architecture-guards`) — and is otherwise a design
discipline, like *Necessity claims carry forcing proofs*.

The law governs **absence**, not staleness. A seam that returns a confidently wrong non-null value
is a different defect and is out of its scope.

#### Scenario: A seam collapses two answers with different consequences
- **WHEN** a seam returns a single "nothing" value for both a genuine absence and a failure to
  determine, and the two lead to different downstream behavior
- **THEN** the collapse is a defect: the answers are separated into distinct values, or the
  collapse is retained with the consequence that makes it safe stated for every cause it absorbs

#### Scenario: A justified collapse absorbs an unconsidered cause
- **WHEN** a collapse carries a written justification that holds for one cause, and a second cause
  reaching the same collapse has a materially different consequence
- **THEN** the justification is incomplete and the seam is corrected — either by separating that
  cause or by recording it, never by leaving it silent

#### Scenario: An entry point declines to act
- **WHEN** a platform entry point receives a driver and decides to do nothing with it
- **THEN** the reason is recorded, so an absent downstream effect is never ambiguous between "the
  platform never called" and "the call was discarded"

#### Scenario: A nullable seam is not automatically a violation
- **WHEN** a seam returns a nullable value and the absent and undeterminable cases lead to the same
  downstream behavior
- **THEN** the collapse is legitimate, and the requirement on it is that the shared consequence is
  stated

#### Scenario: A three-state read is narrowed to a platform-free failure payload
- **WHEN** a port's "could not tell" answer stops carrying the platform's error code and carries an
  opaque diagnostic instead
- **THEN** the law is still satisfied, because the separation the law requires is between the
  answers, not in what the failing one reports
