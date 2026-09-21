## MODIFIED Requirements

### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm's **lifecycle** as a platform-free `UploadProducer` seam in `:domain`'s
`feature/upload` zone (package `app.snapsync.feature.upload`) with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy **dedup state**: it SHALL NOT clear the ledger and
  SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. No lifecycle transition — provision, re-provision,
event switch, permission change, direction change, or leave — SHALL clear the ledger. Durable dedup state
is device-global (`sync-ledger`), and divergence from storage is repaired by reconciliation
(`upload-state-reconciliation`), never by a lifecycle wipe.

The trigger surface ("Triggers are delivered to the mechanism and declined explicitly") SHALL be a
**separate** seam on the same object, so this lifecycle seam keeps exactly the two verbs above and the
orchestrator is given no trigger to invoke.

The property being defended is **dedup**: the proof that a photo is already in the event. Destroying it
re-uploads a member's whole post-cutoff library — the failure this project exists to prevent. The ledger's
`COMPLETED` rows and the stored bytes are that proof, and nothing else a mechanism persists is.

No mechanism needs a destructive verb as a repair either: the damage a stop can leave behind is
`REQUESTED` rows no transfer will settle, and each mechanism repairs those in its own **`start()`** by demoting them to `DISCOVERED`
(`ios-photokit-upload`, `ios-url-session-upload`), which the ledger's work read returns without a walk. A
repair belongs to the start because the start is the one moment a mechanism knows no other transfer is still
carrying those rows, and because every path back to uploading passes through one.

(This seam previously permitted a `stop()` to clear its discovery cursor as a repair for jobs its own
mechanism wiped. Its only instance was the PhotoKit disable, whose bulk *delete* of `REQUESTED` rows could
not be recovered without a re-enumeration. With the rows demoted instead of deleted, the permission had no
use and was withdrawn; the discovery cursor itself has since been removed.)

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger through it

#### Scenario: Stopping preserves dedup state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and every stored object is left intact

#### Scenario: Stopping touches no ledger row

- **WHEN** `stop()` is called on either tier, including as part of a switch or a leave
- **THEN** every ledger row is left exactly as it was

#### Scenario: Rows a stop leaves stranded are repaired by the next start

- **WHEN** a `stop()` leaves `REQUESTED` rows that no transfer will settle, and a mechanism is later started
- **THEN** that start demotes those rows to `DISCOVERED`, and the next cycle re-creates their uploads from the
  ledger's work read, without the walk re-deriving them
