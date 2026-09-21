## MODIFIED Requirements

### Requirement: A reset on a running process may be overtaken by work already in flight

The operation SHALL be usable on a running process, and every step SHALL be one that some runtime path
already performs on a live app — emptying the upload ledger and then clearing the config is what a leave
does (capability `leave-event`), and the download prune already runs under the download controller's lock.
It SHALL NOT require a relaunch to leave the process coherent.

Since a leave now clears the upload ledger too, the operation is no longer distinguished from a leave by
what it clears: both empty the upload ledger, clear the membership config, and drop only the prunable
download rows, retaining every handle-carrying one ("Rows carrying an import handle are retained"). The
behavioural difference that remains is that the operation issues **no backend notify** — the membership
belongs to the backend the device is leaving behind, and the newly baked backend never knew this device —
whereas a leave notifies the backend it is leaving.

An upload cycle **already in flight** when the operation runs MAY complete and write rows into the ledger it
just emptied. This is stated rather than prevented: the cycle gate re-reads the membership config each run,
so the *next* cycle skips, the rows are visible in the reported ledger counts, and a second reset clears
them.

#### Scenario: A reset needs no relaunch
- **WHEN** the operation runs on a foregrounded app
- **THEN** the membership clears, the status reduces to the unjoined resting state, and no relaunch is
  required for the process to be coherent

#### Scenario: An in-flight cycle may outlive the reset
- **WHEN** an upload cycle is mid-run as the operation clears the ledger
- **THEN** that cycle may write rows after the clear, the next cycle skips on the cleared membership, and
  the surviving rows are visible in the reported ledger counts
