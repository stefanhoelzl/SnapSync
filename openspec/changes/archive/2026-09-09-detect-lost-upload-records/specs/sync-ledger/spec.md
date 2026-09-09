## MODIFIED Requirements

### Requirement: The needs-job set is decided in Kotlin

Which `LedgerState` values **need an upload job** SHALL be decided by a single exhaustive `when` in
`:domain` `model/`, and bound into the work-source read as a parameter — never written as a literal
inside a query. It is one of several independent classifications over the same state set: a state may
be neither done nor in need of a job (`REQUESTED`, `UPLOADED`), and every state SHALL be classified on
**every** axis.

Each axis answers a different question, and no axis implies another:

- **done** — is anything still owed for this key?
- **needs a job** — is nothing in flight and are the bytes not on the backend?
- **bytes believed stored** — does this row assert that the upload landed? `UPLOADED` and `COMPLETED`
  both do. This is the axis a comparison against the backend's own listing takes (capability
  `event-rejoin-reconciliation`), and it includes `UPLOADED` deliberately: on the OS-driven tier the
  returned upload job carries no HTTP status, so that state is recorded without the device being able to
  distinguish a stored `201` from a `502` — which makes it the tier where a wrong belief is most likely
  and the one an axis that skipped it could not see.

A state added without classifying it on **every** axis SHALL fail to compile, rather than landing
silently on one side of any of them. The axes are therefore open-ended by construction: adding one is
ordinary, and it is what stops a new state from being filed by a query's string comparison instead of by
a decision.

#### Scenario: A new state must be classified on every axis

- **WHEN** a value is added to `LedgerState` and any one of the classifications is not updated
- **THEN** the build fails, because each decision is an exhaustive `when` with no `else` branch

#### Scenario: The classifications are independent

- **WHEN** the classifications are applied to `REQUESTED` and `UPLOADED`
- **THEN** neither is done and neither needs a job, and `UPLOADED` alone is believed stored — so a read
  of one set never implies another
