## MODIFIED Requirements

### Requirement: Process lifecycle banners

Each process SHALL write a boot banner on start naming the process and the build version, and SHALL
write a teardown line where a clean shutdown path exists.

Each process SHALL additionally write, at boot, the **baked upload base** — the compile-time backend
host that build targets. It names the one fact that makes an otherwise-invisible failure legible: a
build pointed at a different backend without the `SNAPSYNC_RESET_STATE` reset (capability
`ios-app-shell`) still holds a ledger claiming that library is uploaded, so it enumerates and uploads
**nothing**, with no error, no failed request, and no other log line. Read beside the cycle's existing
`enumeration: … seen, … new, … already-uploaded` summary, a changed host next to an unchanged ledger
identifies the cause from the log alone.

The value SHALL be read from the **same** source the process's HTTP clients use, so the banner cannot
disagree with the host actually being called — a banner that could lie about the destination would be
worse than none.

This is **diagnostics only**: it SHALL NOT alter behaviour, SHALL NOT introduce persisted state, and
SHALL NOT add I/O beyond the bundle read the process already performs. In particular the boot path
SHALL NOT read the ledger to report counts — that would add a launch-time database touch on a
possibly-locked device (and, in the app process, force the deferred graph assembly) for information
the per-cycle enumeration summary already carries.

#### Scenario: Boot banner on start
- **WHEN** the app or extension process starts and installs logging
- **THEN** a banner line naming the process and build version is written before other log lines of that run

#### Scenario: Boot banner names the backend this build targets
- **WHEN** the app or extension process starts and installs logging
- **THEN** a boot line names the baked upload base, so a run that uploads nothing can be attributed to
  a changed backend rather than guessed at

#### Scenario: The boot diagnostic reads no ledger
- **WHEN** either process starts on a locked device
- **THEN** the boot lines are written without opening the upload ledger, so the diagnostic cannot fail
  or stall on protected data being unavailable
