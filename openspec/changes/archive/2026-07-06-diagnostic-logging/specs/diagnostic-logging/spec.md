## ADDED Requirements

### Requirement: Per-process un-redacted device log

Each process (the app and the upload extension) SHALL write its diagnostic log verbatim to its own `Documents/debug.log`, applying no masking, redaction, or truncation to logged values. The two files SHALL remain separate, per-process files, each pullable via `pymobiledevice3 apps pull <bundle> Documents/debug.log`.

#### Scenario: Identifiers logged verbatim
- **WHEN** the app logs a line containing an event id, asset id, upload key, or URL
- **THEN** the value appears in `debug.log` in full, un-masked form

#### Scenario: No redaction layer
- **WHEN** any component logs through Kermit
- **THEN** the written line contains the original message text with no `***`, `<private>`, or truncation applied by the app

### Requirement: Size-bounded log with rotation

Each `debug.log` SHALL be bounded to at most 10 MB by rolling: when the file exceeds 10 MB it SHALL be renamed to `debug.log.1` (replacing any existing `debug.log.1`) and a fresh `debug.log` started, retaining exactly one previous file.

#### Scenario: Roll at threshold
- **WHEN** a write would grow `debug.log` beyond 10 MB
- **THEN** the current file is moved to `debug.log.1` and subsequent lines are written to a new `debug.log`

#### Scenario: One previous file retained
- **WHEN** a second roll occurs
- **THEN** the earlier `debug.log.1` is replaced by the just-rolled file and no `debug.log.2` is created

### Requirement: Atomic-append line writes

Each log line SHALL be written as a single atomic append so that concurrent writes within a process never interleave within a line.

#### Scenario: Lines never torn
- **WHEN** two threads in the same process log simultaneously
- **THEN** each written line is complete and intact (interleaving may occur only between whole lines)

### Requirement: Uniform platform-invocation logging

Every platform invocation, app entry point, and background trigger SHALL be logged with a uniform enter/exit convention recording the entry-point name, its parameters, its result, and its elapsed duration. This SHALL cover the upload-platform methods, the extension `process()` cycle, the background-pump triggers, the schedulers, the app entry points, the download controller, and the app-driven upload controller.

#### Scenario: Enter and exit are logged
- **WHEN** an instrumented entry point runs to completion
- **THEN** an enter line records the entry-point name and parameters, and an exit line records the result and the elapsed duration in milliseconds

#### Scenario: Failure is logged with duration
- **WHEN** an instrumented entry point throws
- **THEN** an exit line records the error and the elapsed duration

### Requirement: Ambient entry-point context prefix

Every log line SHALL carry a `[<entryPoint>]` prefix naming the outermost entry point that triggered the work, so downstream engine, HTTP, and download lines trace back to their trigger. The prefix SHALL NOT include a process token (the file identifies the process).

#### Scenario: Downstream line inherits the trigger
- **WHEN** a silent push triggers `onSilentPush`, which drives a download reconcile
- **THEN** the reconcile's log lines are prefixed `[onSilentPush]`

#### Scenario: Outermost entry point wins
- **WHEN** an entry point that is already within an active entry-point context invokes a nested instrumented seam
- **THEN** the nested seam's lines keep the outer entry point's prefix rather than overwriting it

### Requirement: Process lifecycle banners

Each process SHALL write a boot banner on start naming the process and the build version, and SHALL write a teardown line where a clean shutdown path exists.

#### Scenario: Boot banner on start
- **WHEN** the app or extension process starts and installs logging
- **THEN** a banner line naming the process and build version is written before other log lines of that run

### Requirement: HTTP request logging

Every HTTP request issued through the shared Ktor client SHALL be logged as a single line recording the method, URL, response status, elapsed duration, request size, and response size. The line SHALL be emitted through Kermit so it carries the ambient entry-point prefix.

#### Scenario: One line per request
- **WHEN** any of the Ktor call sites (device-manifest PUT, notify POST, token PUT, union GET, device-files GET, event-create POST, event-metadata GET) completes
- **THEN** exactly one line is logged with method, URL, status, duration, request size, and response size

#### Scenario: Failed request is logged
- **WHEN** an HTTP request fails or times out
- **THEN** a line is logged recording the method, URL, and the failure outcome

### Requirement: SyncEngine enumeration summary

Each upload discover cycle SHALL log one summary line accounting for the enumeration as `seen`, `new`, and `already-uploaded` counts, without emitting a per-asset line for assets that are already uploaded.

#### Scenario: Per-cycle summary
- **WHEN** a discover cycle enumerates the library and the engine decides each resource
- **THEN** one summary line reports the number seen, the number newly minted for upload, and the number already uploaded

#### Scenario: Skips stay silent
- **WHEN** the engine returns `AlreadyUploaded` for a resource during enumeration
- **THEN** no per-asset line is written for that resource (only the cycle summary reflects it)
