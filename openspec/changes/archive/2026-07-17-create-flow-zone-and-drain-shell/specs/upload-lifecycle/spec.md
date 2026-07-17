# upload-lifecycle — delta for create-flow-zone-and-drain-shell

## MODIFIED Requirements

### Requirement: Exactly one producer per process

The app SHALL construct **exactly one** `UploadProducer` for the process, selected **once per
process** by the pure sealed composition resolver (`model/`'s `resolveComposition` over the parsed
launch directives and OS facts — the OS-version tier gate of `ios-url-session-upload`, "Per-version
tier selection", is one of its inputs; spec `module-architecture`, "One shared composition") and
consumed at the shell's **single** mode switch — no entry point re-derives the tier. The
non-selected tier's producer SHALL NOT be constructed, so its mechanism cannot run. This SHALL hold
under the development tier-force flag as well: forcing the app-driven tier on a device that supports
the OS-driven tier SHALL NOT register the PhotoKit upload extension.

This makes the two tiers' mutual exclusion structural rather than a runtime guard, and preserves the
`sync-ledger` single-record-writer invariant (two live producers would mean two `LedgerWriter`s over
one App-Group ledger).

#### Scenario: Only the selected tier's producer exists

- **WHEN** the composition root assembles the upload arm
- **THEN** exactly one `UploadProducer` is constructed, and the other tier's mechanism is never invoked

#### Scenario: Forcing the app-driven tier does not enable the extension

- **WHEN** the app-driven tier is forced on a device whose OS supports the OS-driven tier
- **THEN** the PhotoKit upload extension is not registered, and only the app-driven producer is live
