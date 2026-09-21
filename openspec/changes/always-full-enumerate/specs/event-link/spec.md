## MODIFIED Requirements

### Requirement: An unreadable config is not an absent config

The config seam SHALL distinguish three outcomes: a **readable** config, a **definitely absent**
config, and an **unreadable** config. An unreadable config SHALL NOT be reported as an absent
config.

The distinction is grounded on the App-Group config file **alone**: **definitely absent** SHALL mean
exactly that the file read failed with the **not-found error class**
(`NSFileReadNoSuchFileError` 260 / `NSFileNoSuchFileError` 4 / POSIX `ENOENT`), and nothing else is
consulted. A read that fails for **any other reason** — notably the permission-class failure of a
protected-file read before first unlock, a missing App-Group container, or file content this build
cannot positively interpret (a foreign envelope version or an undecodable current-version payload)
— SHALL be **unreadable**. The absence class is
a closed whitelist, deliberately: an unrecognized error shape lands on the unreadable side, where
the cost is a deferred cycle, not a false leave.

**The error classifier is the only vote.** Until the read-only legacy-Keychain fallback was deleted,
a misclassified not-found was caught downstream — the fallback found the legacy item and the device
stayed joined — so absence required a *second* answer to agree. It no longer does: the classifier
that decides whether an `NSError` belongs to the not-found class is now solely load-bearing for the
leave decision, and a wrong verdict is an unrecoverable, silent logout (capability
`upload-state-reconciliation` states the consequence). Widening that whitelist SHALL therefore be
treated as changing the leave decision itself.

A reader that acts on the absence of a config — in particular the re-join reconciliation, for
which "no event configured" means *the device left the event* and triggers clearing the persisted
`joinedEventId` marker (capability `upload-state-reconciliation`) — SHALL act **only** on a
definitely absent config. On an unreadable config **the upload cycle** SHALL skip entirely: it
SHALL NOT reconcile, SHALL NOT clear the join marker, SHALL NOT write the ledger, and
SHALL NOT create upload jobs; the cycle SHALL complete cleanly and the next cycle SHALL retry.

This SHALL hold on **every upload tier and at every trigger**, not only where the OS is the
invoker. The tiers differ in who invokes a cycle — the OS on iOS ≥26.1, the app on iOS 18–26.0 —
and not in what an unreadable membership means. A tier SHALL NOT reach this decision through a
two-state read that cannot express "unreadable"; the three-state read is the only permitted path
(capability `upload-lifecycle`, which owns where the decision is made).

Conflating the two is what makes an ordinary locked-device wake perform a *false leave*: the
marker is cleared, and the next readable cycle sees a marker mismatch and pays for a full re-join
reconciliation (a device listing, an atomic ledger clear-and-seed to bare rows, and a walk that
must re-read every seeded asset's resources) — repeatedly, without the marker ever settling.

#### Scenario: An unreadable config does not clear the join marker

- **WHEN** an upload cycle reads the config and the read fails because protected data is
  unavailable (the file read fails permission-class before first unlock)
- **THEN** the cycle is skipped, the reconciliation is not invoked, the persisted `joinedEventId`
  marker is left intact, the ledger is not written, and the cycle completes cleanly

#### Scenario: A definitely-absent config still drives the leave path

- **WHEN** an upload cycle reads the config and the file is missing by the not-found error class
- **THEN** the reconciliation runs for the no-config case and clears the `joinedEventId` marker,
  exactly as a leave requires — with no other store consulted

#### Scenario: An unrecognized read error stays unreadable

- **WHEN** the file read fails with an error outside the not-found whitelist
- **THEN** the read reports unreadable and the cycle skips — the classifier's `else` arm never
  admits an unknown error into the absence class, because it is now the only thing standing between
  a misclassified error and an unintended leave

#### Scenario: A joined device stays settled across locked wakes

- **WHEN** a joined device runs cycles repeatedly while locked and its config is unreadable
- **THEN** its join marker still matches its configured event on the next readable cycle, so no
  re-join reconciliation or ledger re-seed is performed

#### Scenario: The app-driven tier skips rather than leaves

- **WHEN** the app-driven tier (iOS 18–26.0) runs a cycle from any trigger — foreground, background task,
  silent push, or session events — and the config read fails because protected data is unavailable
- **THEN** the cycle is skipped, the `joinedEventId` marker is left intact, and the membership survives —
  the same outcome the OS-invoked tier produces
