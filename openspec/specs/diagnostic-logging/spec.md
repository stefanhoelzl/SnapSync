# diagnostic logging Specification

## Purpose

The device diagnostic-log contract for the headless iOS app and upload extension. The app and
extension are separate processes with separate sandboxes, and only a process's own `Documents/` is
pullable over USB (the shared App Group container is not), so each writes its own verbatim,
un-redacted `Documents/debug.log`. This capability defines that log's guarantees: verbatim (no
redaction), size-bounded (10 MB roll), and self-explaining — every platform invocation and app entry
point logs enter/exit with parameters, result, and duration; every line carries a `[<entryPoint>]`
ambient prefix tracing it to what triggered it; every HTTP request logs one line; and full-library
enumeration is accountable via a per-cycle summary. Cross-cutting infra lives in `:domain`'s `model/`
zone (the `Logger.invocation` helper, driving the injected `ports/LogScope` seam — migration step 8
C1 resolved the step-5 interim seat this way, not as compose/ decorators) and in
`:adapter:ios:ext-safe` (the consolidated device-log writers plus the process-global ambient
context they read, `LogContext`/`IosLogScope`).
## Requirements
### Requirement: Per-process un-redacted device log

Each process (the app and the upload extension) SHALL write its diagnostic log verbatim to its own
`Documents/debug.log`, applying no masking, redaction, or truncation to logged values. The two files
SHALL remain separate, per-process files, each pullable via `pymobiledevice3 apps pull <bundle>
Documents/debug.log`.

#### Scenario: Identifiers logged verbatim
- **WHEN** the app logs a line containing an event id, asset id, upload key, or URL
- **THEN** the value appears in `debug.log` in full, un-masked form

#### Scenario: No redaction layer
- **WHEN** any component logs through Kermit
- **THEN** the written line contains the original message text with no `***`, `<private>`, or truncation applied by the app

### Requirement: Size-bounded log with rotation

Each `debug.log` SHALL be bounded to at most 10 MB by rolling: when the file exceeds 10 MB it SHALL
be renamed to `debug.log.1` (replacing any existing `debug.log.1`) and a fresh `debug.log` started,
retaining exactly one previous file.

#### Scenario: Roll at threshold
- **WHEN** a write would grow `debug.log` beyond 10 MB
- **THEN** the current file is moved to `debug.log.1` and subsequent lines are written to a new `debug.log`

#### Scenario: One previous file retained
- **WHEN** a second roll occurs
- **THEN** the earlier `debug.log.1` is replaced by the just-rolled file and no `debug.log.2` is created

### Requirement: Atomic-append line writes

Each log line SHALL be written as a single atomic append so that concurrent writes within a process
never interleave within a line.

#### Scenario: Lines never torn
- **WHEN** two threads in the same process log simultaneously
- **THEN** each written line is complete and intact (interleaving may occur only between whole lines)

### Requirement: Uniform platform-invocation logging

Every platform invocation, app entry point, and background trigger SHALL be logged with a uniform
enter/exit convention recording the entry-point name, its parameters, its result, and its elapsed
duration. This SHALL cover the upload-platform methods, the extension `process()` cycle, the
background-pump triggers, the schedulers, the app entry points, the download controller, and the
app-driven upload controller.

#### Scenario: Enter and exit are logged
- **WHEN** an instrumented entry point runs to completion
- **THEN** an enter line records the entry-point name and parameters, and an exit line records the result and the elapsed duration in milliseconds

#### Scenario: Failure is logged with duration
- **WHEN** an instrumented entry point throws
- **THEN** an exit line records the error and the elapsed duration

### Requirement: Ambient entry-point context prefix

Every log line SHALL carry a `[<entryPoint>]` prefix naming the outermost entry point that triggered
the work, so downstream engine, HTTP, and download lines trace back to their trigger. The prefix
SHALL NOT include a process token (the file identifies the process). The ambient mechanism SHALL sit
behind `:domain`'s `ports/LogScope` seam: platform-free code drives the injected `LogScope`
(defaulting to `LogScope.NoOp` off-device), and the process-global ambient context the device-log
writers read (`LogContext`, driven via `IosLogScope`) SHALL live in `:adapter:ios:ext-safe` beside
those writers — `:domain` holds no global mutable state for it (spec `module-architecture`, "State
and authority"; migration step 8 C1).

#### Scenario: Downstream line inherits the trigger
- **WHEN** a silent push triggers `onSilentPush`, which drives a download reconcile
- **THEN** the reconcile's log lines are prefixed `[onSilentPush]`

#### Scenario: Outermost entry point wins
- **WHEN** an entry point that is already within an active entry-point context invokes a nested instrumented seam
- **THEN** the nested seam's lines keep the outer entry point's prefix rather than overwriting it

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

### Requirement: HTTP request logging

Every HTTP request issued through the shared Ktor client SHALL be logged as a single line recording
the method, URL, response status, elapsed duration, request size, and response size. The line SHALL
be emitted through Kermit so it carries the ambient entry-point prefix.

#### Scenario: One line per request
- **WHEN** any of the Ktor call sites (device-manifest PUT, notify POST, token PUT, union GET, device-files GET, event-create POST, event-metadata GET) completes
- **THEN** exactly one line is logged with method, URL, status, duration, request size, and response size

#### Scenario: Failed request is logged
- **WHEN** an HTTP request fails or times out
- **THEN** a line is logged recording the method, URL, and the failure outcome

### Requirement: SyncEngine enumeration summary

Each upload discover cycle SHALL log one summary line accounting for the enumeration as `seen`,
`new`, and `already-uploaded` counts, without emitting a per-asset line for assets that are already
uploaded.

#### Scenario: Per-cycle summary
- **WHEN** a discover cycle enumerates the library and the engine decides each resource
- **THEN** one summary line reports the number seen, the number newly minted for upload, and the number already uploaded

#### Scenario: Skips stay silent
- **WHEN** the engine returns `AlreadyUploaded` for a resource during enumeration
- **THEN** no per-asset line is written for that resource (only the cycle summary reflects it)

