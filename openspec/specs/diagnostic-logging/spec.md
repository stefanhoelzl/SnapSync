# diagnostic logging Specification

## Purpose

The device diagnostic-log contract for the headless iOS app and upload extension. The app and
extension are separate processes with separate sandboxes, so each writes its own verbatim,
un-redacted log — but to **different** places, because the two are reached differently. The **app**
writes `Documents/debug.log` inside its own container, still pullable directly over USB. The
**extension** writes `ext-debug.log` into the **shared App Group container**, so the app process can
read it — one process cannot read another bundle's `Documents/`, and that read is what an
operator-initiated diagnostic dump needs; and because an App Group container is *not* USB-pullable,
the `DeviceLogSource` port exposes its tail to the dev/test control channel when a cable is what you
have. This capability defines those logs' guarantees: verbatim
(no redaction), size-bounded (10 MB roll), and self-explaining — every platform invocation and app entry
point logs enter/exit with parameters, result, and duration; every line carries a `[<entryPoint>]`
ambient prefix tracing it to what triggered it; every HTTP request logs one line; and full-library
enumeration is accountable via a per-cycle summary. It also defines the **operator-initiated
diagnostic dump**: a hidden double-tap on the app-name label, confirmed by a dialog that names what
will be sent, delivering one byte-budgeted event to the reporting channel (capability
`crash-reporting`) verbatim — the route to a log on a device no cable will reach. Cross-cutting infra lives in `:domain`'s `model/`
zone (the `Logger.invocation` helper, driving the injected `ports/LogScope` seam — migration step 8
C1 resolved the step-5 interim seat this way, not as compose/ decorators) and in
`:adapter:ios:ext-safe` (the consolidated device-log writers plus the process-global ambient
context they read, `LogContext`/`IosLogScope`).

Decision record: `changes/archive/2026-07-29-add-diagnostic-dump` (the operator-initiated dump, the
extension log's move to the App Group, and the measured Bugsink limits behind the byte budget); the
required written description, the sheet that collects it, grouping by description (which **reverses**
that record's constant-message decision), the tag-carried redaction exemption, and the full-height
sheet the keyboard forced: `changes/archive/2026-07-31-add-bug-report-description`.
## Requirements

### Requirement: Per-process un-redacted device log

Each process (the app and the upload extension) SHALL write its diagnostic log verbatim to its own
file, applying no masking, redaction, or truncation to logged values. The two logs SHALL remain
separate, per-process files.

The **app** SHALL write `Documents/debug.log` inside its own container, pullable via
`pymobiledevice3 apps pull app.snapsync Documents/debug.log`.

The **upload extension** SHALL write `ext-debug.log` into the **shared App Group container**, so the
app process can read it and carry it in a diagnostic dump. This is the one read the previous
placement made impossible: the two processes have separate sandboxes, and an app cannot read another
bundle's `Documents/`. The app's own log SHALL NOT move — a process can always read its own
`Documents/`, so relocating it would buy no capability while breaking every existing pull command.

Because the App Group container is not USB-pullable, the extension's log SHALL be reachable through the
`DeviceLogSource` port, which the dev/test control channel exposes over HTTP. That read is a pass-through:
the port bounds the read in bytes and cuts at a line boundary, and the caller states the bound. It reads the
**current** file only — a rolled `.1` sibling is **not** reachable this way, which is a deliberate reduction
against the previous copy-the-whole-file route and is stated here rather than discovered: by the time anyone
reads a log, a roll file is stale, and including it would halve the live tail.

If the App Group container is unavailable, the extension SHALL fall back to its own
`Documents/debug.log` and SHALL record the fallback in its boot banner — a writer that silently
resolved to nothing would produce no log at all, which is indistinguishable from a process that never
ran.

The extension SHALL delete a stale `Documents/debug.log` left by an earlier build, once, so a pull
against that path fails honestly rather than returning frozen content that reads as current.

#### Scenario: Identifiers logged verbatim
- **WHEN** the app logs a line containing an event id, asset id, upload key, or URL
- **THEN** the value appears in its log in full, un-masked form

#### Scenario: No redaction layer
- **WHEN** any component logs through Kermit
- **THEN** the written line contains the original message text with no `***`, `<private>`, or truncation applied by the app

#### Scenario: The app can read the extension's log
- **WHEN** the app process assembles a diagnostic dump after the extension has run
- **THEN** it reads the extension's `ext-debug.log` from the shared App Group container without any
  cross-process request, and the extension need not be running

#### Scenario: An operator reads the extension's log without a relaunch
- **WHEN** an operator reads the extension's log through the control channel on a build carrying it
- **THEN** the current `ext-debug.log`'s tail is returned within the requested byte bound, with no copy
  step, no relaunch, and no `apps pull`

#### Scenario: A rolled sibling is not returned
- **WHEN** the extension's log has rolled and an operator reads it through the port
- **THEN** only the current file's tail is returned, and the `.1` sibling is not included

#### Scenario: The app's own pull path is unchanged
- **WHEN** an operator runs `pymobiledevice3 apps pull app.snapsync Documents/debug.log`
- **THEN** the app's current log is returned, exactly as before this change

#### Scenario: A stale extension log does not masquerade as current
- **WHEN** a device that ran an earlier build launches the extension of a build carrying this change
- **THEN** the extension's old `Documents/debug.log` is removed, so a pull against that path returns
  no file rather than months-old content

### Requirement: Size-bounded log with rotation

Each process's log file SHALL be bounded to at most 10 MB by rolling: when the file exceeds 10 MB it
SHALL be renamed by appending `.1` to its name (replacing any existing `.1` file) and a fresh log
started, retaining exactly one previous file. This applies to whichever name and container the
process writes to — `Documents/debug.log` for the app, `ext-debug.log` in the App Group for the
extension.

#### Scenario: Roll at threshold
- **WHEN** a write would grow a process's log beyond 10 MB
- **THEN** the current file is moved to its `.1` sibling and subsequent lines are written to a new, empty log

#### Scenario: One previous file retained
- **WHEN** a second roll occurs
- **THEN** the earlier `.1` file is replaced by the just-rolled file and no `.2` is created

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

The **enter line SHALL precede any decision** the entry point makes, and SHALL record the raw
inputs the platform supplied — including the fields a filter is about to test. An entry point that
declines to act SHALL still name its outcome on exit (spec `module-architecture`, "Absence is never
silent"). Recording only successful paths is what made a reported defect undiagnosable: an event
link that never reached the join gate was indistinguishable from one iOS never delivered, because
the filter that discarded it wrote nothing.

An entry point is a declaration the **platform** calls, and the set is identified by these rules rather
than by a maintained list:

1. every member of a composition-root object invoked from outside that root's own file — which
   covers both Swift→Kotlin doors (the app delegate and scene delegate, and the Compose entry the
   Swift view calls);
2. every overridden member of a class conforming to a platform callback protocol;
3. every observer body registered with a platform notification or change-observer centre.

A declaration reached only from our own Kotlin is **not** an entry point; what distinguishes one is
that the platform is on the other side of the call. Read-model members that presentation polls are
therefore excluded, while the platform's request for the root view is not.

**This obligation is maintained by review, not by a build gate.** The guard that derived the entry-point
set and asserted each was marked and logged has been retired (capability `architecture-guards`): it
enforced diagnosability rather than behaviour, and an unlogged entry point ships correct behaviour. The
consequence is stated rather than left implicit — a new entry point that decides and returns without
logging will not fail any build, and a defect of the shape described above will again be undiagnosable
from a device log.

**User taps SHALL be instrumented as entry points too**, decorated where the command bundle is
built (spec `module-architecture`, "Commands cross one door": instances are decorated only in
`compose/`), so that every line in the device log traces to a named trigger.

#### Scenario: An entry point declines to act
- **WHEN** a platform entry point receives a delivery and a filter discards it
- **THEN** the log carries both the enter line with the raw platform inputs and an exit line naming the
  outcome, so "discarded" is distinguishable from "never delivered"

#### Scenario: A new Swift-to-Kotlin door is added
- **WHEN** a new delegate method forwards to a new composition-root member
- **THEN** that member is instrumented with the enter/exit convention as part of the change, and its
  absence is caught in review rather than by a build failure

### Requirement: Ambient entry-point context prefix

Every log line SHALL carry a `[<entryPoint>]` prefix naming the outermost entry point that triggered
the work, so downstream engine, HTTP, and download lines trace back to their trigger. The prefix
SHALL NOT include a process token (the file identifies the process). The ambient mechanism SHALL sit
behind `:domain`'s `ports/LogScope` seam: platform-free code drives the injected `LogScope`
(defaulting to `LogScope.NoOp` off-device), and the process-global ambient context the device-log
writers read (`LogContext`, driven via `IosLogScope`) SHALL live in `:adapter:ios:ext-safe` beside
those writers — `:domain` holds no global mutable state for it (spec `module-architecture`, "State
and authority"; migration step 8 C1).

User-tap entry points SHALL use a distinct context namespace from platform callbacks, so a reader
can tell which side initiated the work without consulting the source.

The mechanism's accepted inaccuracy SHALL be stated rather than inherited: the ambient context is a
process-global with outermost-wins semantics, justified on the grounds that the platform delivers
its entry points serially per process. **User taps are not serial with background work**, so a tap
arriving inside an in-flight platform invocation inherits that invocation's prefix. The mislabeling
therefore falls on the tap, never on the platform work whose trail matters most, and this is
accepted for a dev-only diagnostic log.

#### Scenario: Downstream line inherits the trigger
- **WHEN** a silent push triggers `onSilentPush`, which drives a download reconcile
- **THEN** the reconcile's log lines are prefixed `[onSilentPush]`

#### Scenario: Outermost entry point wins
- **WHEN** an entry point that is already within an active entry-point context invokes a nested instrumented seam
- **THEN** the nested seam's lines keep the outer entry point's prefix rather than overwriting it

#### Scenario: A tap during background work is mislabeled, not the reverse
- **WHEN** a user tap is instrumented while a platform invocation already holds the ambient context
- **THEN** the tap's lines carry the platform invocation's prefix, and the platform invocation's own
  lines remain correctly attributed

### Requirement: Process lifecycle banners

Each process SHALL write a boot banner on start naming the process and the build version, and SHALL
write a teardown line where a clean shutdown path exists.

The **build version** in that banner is the version the build DECLARES — and on a dev, local or
sideload build that is the `MARKETING_VERSION` floor verbatim, because such a build has no release tag
to compute a version from. A reader SHALL NOT infer recency from it: a dev build of today's `main`
reports a version BELOW every build that has been released, and the two numbers are answering different
questions. The declared version is also what the backend's version gate reads (capability
`min-app-version`), so the banner is the one place a `426` refusal can be attributed from the log alone
— which is why it names the declared version rather than something more flattering.

Each process SHALL additionally write, at boot, the **baked upload base** — the compile-time backend
host that build targets. It names the one fact that makes an otherwise-invisible failure legible: a
build pointed at a different backend that has not had its durable sync state voided (capability
`device-state-reset`) still holds a ledger claiming that library is uploaded, so it enumerates and
uploads **nothing**, with no error, no failed request, and no other log line. Read beside the cycle's
existing `enumeration: … seen, … new, … already-uploaded` summary, a changed host next to an unchanged
ledger identifies the cause from the log alone.

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

#### Scenario: A dev build's banner version trails released builds
- **WHEN** a dev, local or sideload build writes its boot banner
- **THEN** the version it names is the `MARKETING_VERSION` floor, which is at or below every released
  version, and is NOT evidence that the build is old

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

The line SHALL be emitted whether or not the cycle went on to create a job for every resource it
accounted for, and a cycle that stopped creating jobs early SHALL say so in that line, reporting how
much of the enumeration it left un-enqueued. A cycle that stops early is the one whose accounting is
needed most: it is the state in which a backlog is accumulating, and without the line a device log
shows the candidates going in and a handful of jobs coming out with nothing stating the difference.

#### Scenario: Per-cycle summary
- **WHEN** a discover cycle enumerates the library and the engine decides each resource
- **THEN** one summary line reports the number seen, the number newly minted for upload, and the number already uploaded

#### Scenario: A cycle that stopped creating jobs still accounts for its enumeration
- **WHEN** a discover cycle stops creating jobs because the platform's job limit was reached
- **THEN** the summary line is still written, and it states that creation stopped early and how many
  admitted resources were left un-enqueued

#### Scenario: Skips stay silent
- **WHEN** the engine returns `AlreadyUploaded` for a resource during enumeration
- **THEN** no per-asset line is written for that resource (only the cycle summary reflects it)

### Requirement: Operator-initiated diagnostic dump

The app SHALL provide a **hidden** operator affordance that sends the recent device log to the
reporting channel **together with a written account of the problem**, so a device that cannot be
reached over USB — a tester's phone, or the operator's own away from a cable — can still yield both
its log and what the person was doing when it went wrong. The affordance SHALL be triggered by a
**double-tap on the app-name label** rendered by the shared screen layout, which is present in every
UI state, so a stuck join gate or a denied-permission screen is dumpable too.

The affordance SHALL present **no** discoverable control: no button, no menu entry, no settings
surface, and no accessibility semantics that expose the label as interactive. Adding click semantics
to the label would make it read as a control and defeat the requirement.

Firing the gesture SHALL open a **bug-report sheet** that names what will be sent and collects a
**required** free-text description of the problem. The sheet is the consent moment and the only
disclosure of the payload; there SHALL be no second confirmation step. Sending SHALL be refused while
the description, once trimmed of surrounding whitespace, is empty — so an empty or whitespace-only
description can never be submitted, and no validation error need ever be shown. The value sent SHALL
be the trimmed description. Dismissing the sheet — by its cancel action, by the scrim, or by a
dismissal gesture — SHALL send nothing.

The description SHALL be entered as multi-line text bounded to **200 characters**, enforced by the
input component refusing input beyond the bound. The bound is chosen because the description titles
the report in the reporting channel and must stay scannable in a list of issues.

The app SHALL show **no** post-send feedback. Delivery cannot be honestly confirmed at the moment of
the tap (the reporting SDK may cache and retransmit on a later launch), so a claim of "sent" would be
unverifiable.

The affordance SHALL be **absent entirely** on a build whose reporting channel is not configured: no
gesture is wired and no sheet can open, so a build that could send nothing never suggests it can.

There SHALL be no rate limit: each sent report sends one dump.

#### Scenario: A described report sends one dump
- **WHEN** the operator double-taps the app-name label on a build with a configured reporting channel, types a description, and sends
- **THEN** exactly one diagnostic dump is sent, carrying that description

#### Scenario: An empty description cannot be sent
- **WHEN** the sheet is open and the description is empty or contains only whitespace
- **THEN** the send action does not fire and no dump is sent

#### Scenario: The description is trimmed
- **WHEN** the operator sends a description with leading or trailing whitespace
- **THEN** the transmitted description carries neither

#### Scenario: The description is bounded
- **WHEN** the description already holds 200 characters and more input arrives
- **THEN** the description does not grow beyond 200 characters

#### Scenario: Dismissing sends nothing
- **WHEN** the sheet is opened and dismissed by its cancel action, the scrim, or a dismissal gesture
- **THEN** no dump is sent and no state changes

#### Scenario: The affordance exists in every state
- **WHEN** the app is showing the create screen, any join-gate phase, the joined status, or the reconfigure surface
- **THEN** the same gesture on the app-name label opens the same sheet

#### Scenario: An unconfigured build offers nothing
- **WHEN** a build with no reporting configuration is running and the label is double-tapped
- **THEN** no sheet opens and nothing is sent

#### Scenario: The label is not an exposed control
- **WHEN** the status screen's accessibility tree and rendered controls are inspected
- **THEN** the app-name label exposes no click action and no control affordance

### Requirement: Diagnostic dump contents and byte budget

A diagnostic dump SHALL be delivered as a **single event** whose message is the operator's
description behind a **fixed marker prefix**, and SHALL carry exactly five structured sections:

- **note** — the operator's description, verbatim. It is carried as its own section and not folded
  into `state`, which holds machine facts;
- **state** — the facts a log tail may not contain: app marketing version and build number, OS
  version and device model, resolved upload tier, photo-permission status, whether a membership is
  held and its configuration, the baked upload base, the reporting environment, and **the surface the
  report was written from**. That last one is supplied by the UI as an opaque label, because the
  surfaces worth naming — the reconfigure surface, a pending switch, which join phase is showing —
  are screen-local by design: they touch no port, so they appear in no log line and no ledger row, and
  this field is the only route by which they reach a report. It SHALL NOT
  include the size of a partial photo-access selection: no shipped read makes that count available to
  this feature, so reporting it would mean adding a seam for diagnostics alone — which the
  reads-nothing-new rule below forbids;
- **ledger** — five counts already read by shipped code: pending and completed **photos** from the
  ledger aggregates, and the download store's imported, total-asset, and in-flight counts. The
  section SHALL label its units, because the ledger counts photos while the log speaks of resource
  rows and the two legitimately disagree;
- **app log** and **extension log** — the tails of the two logs.

Because the reporting channel titles and groups such an event by its message, reports SHALL group
**by description**: two reports describing the same problem in the same words arrive as occurrences
of one issue, while two describing different problems arrive as distinct issues. The marker prefix
SHALL keep every report identifiable as an operator report rather than a captured error.

The two log tails SHALL share a **fixed byte budget**, each taking at most half and either taking the
other's unused share, so a device whose extension has barely run still yields a full-budget app tail.
Each tail SHALL be cut at a line boundary and taken from the **current** log file only, never its
rolled `.1` sibling.

The budget SHALL be chosen to keep an assembled dump below the reporting channel's maximum event
size with headroom for the reporting SDK's own contributions. An over-budget dump is **rejected at
ingest and silently lost** — the sender observes success — so the budget is a hard bound and not a
target to be maximised. The budget bounds the **log** content only; the description does not count
against it and does not reduce either tail, its bound being three orders of magnitude below the
budget's headroom.

The dump SHALL read no data the app does not already read, SHALL perform no write, and SHALL add no
port surface for diagnostics alone.

#### Scenario: A dump carries all five sections
- **WHEN** a dump is assembled on a joined device after both processes have logged
- **THEN** it carries the note, state, ledger, app-log and extension-log sections in one event

#### Scenario: The state section names the surface the report came from
- **WHEN** a report is sent from a screen-local surface such as the reconfigure screen
- **THEN** the state section names that surface, which no other section of the report carries

#### Scenario: The state section omits the partial-selection size
- **WHEN** a dump is assembled while the app holds a partial photo grant
- **THEN** the state section reports the permission status but not how many photos are selected,
  because no shipped read makes that count available to this feature

#### Scenario: The description titles the report
- **WHEN** a report is sent with a description
- **THEN** the event's message carries that description behind the fixed marker prefix

#### Scenario: Reports group by description
- **WHEN** two reports carrying different descriptions are sent
- **THEN** they arrive as two distinct issues rather than as occurrences of one

#### Scenario: The budget is never exceeded
- **WHEN** both logs are far larger than the budget
- **THEN** the assembled dump's log content is at or below the budget, and each tail ends at a line boundary

#### Scenario: The description does not consume the log budget
- **WHEN** a report carries a description at its maximum length
- **THEN** both log tails are the same size they would have been with an empty description

#### Scenario: Unused budget is borrowed
- **WHEN** the extension's log is much smaller than half the budget
- **THEN** the app-log tail takes the remaining share rather than being capped at half

#### Scenario: A dump reads only, and never writes
- **WHEN** a dump is assembled
- **THEN** no ledger, download-store, or configuration write occurs

### Requirement: Diagnostic dumps are delivered verbatim

A diagnostic dump SHALL be transmitted **without identifier redaction**: event ids, asset ids, device
ids, and upload keys appear in full, **in every part of the payload including the operator's
description and the message built from it**. The dump is deliberate, confirmed, and largely worthless
without its identifiers — a log in which every id reads alike cannot answer which photo, which event,
or which device, and a description reading "stuck on event ‹uuid›" has lost the one fact it carried.

This exemption SHALL be **narrow**: it covers only the operator-initiated dump. Automatic events and
breadcrumbs remain scrubbed (capability `crash-reporting`), because they are transmitted without
anyone's knowledge or consent.

The exemption SHALL be carried **explicitly by the event**, as a marker the sender sets and the
scrubbing step consults, rather than resting on where the payload happens to sit. It previously held
only because the scrub covered message text but not structured context sections — an incidental
property that a later, well-meaning widening of the scrub would silently destroy. An explicit marker
retires that hazard: a widened scrub still skips an event that declares itself exempt.

The marker SHALL be named for the property it claims rather than for the one feature that claims it,
and both halves of the wiring SHALL be pinned by tests — that the sender **sets** it, and that the
scrubbing step **consults** it — because either half missing degrades every future dump silently,
with no failing request and no visible error.

#### Scenario: A dump keeps its identifiers
- **WHEN** a dump whose log lines contain event ids, asset ids and upload keys is transmitted
- **THEN** the received payload contains those values in full

#### Scenario: A described identifier survives in the message
- **WHEN** the operator's description contains a UUID-shaped identifier
- **THEN** it appears in full in the transmitted message, not as a redaction marker

#### Scenario: Automatic reports stay scrubbed
- **WHEN** an error event or breadcrumb is captured automatically on the same build
- **THEN** its identifiers are redacted exactly as before this change

#### Scenario: Losing the exemption marker is caught
- **WHEN** the sender stops setting the exemption marker, or the scrubbing step stops consulting it
- **THEN** a test fails, naming the dump exemption, rather than dumps silently arriving redacted

### Requirement: Log timestamps carry millisecond resolution

Every device-log line SHALL carry a timestamp with at least millisecond resolution. At whole-second
resolution the ordering of lines emitted within the same second is unrecoverable, and that ordering is
exactly what separates "the library call was slow" from "the process was frozen after it returned" —
a distinction that had to be reconstructed by deduction from durations rather than read.

#### Scenario: Two lines in the same second are ordered

- **WHEN** two log lines are emitted within the same second, on the same or different threads
- **THEN** their timestamps distinguish which was written first

### Requirement: Deadline expiry is logged

Every bounded wait that reaches its deadline SHALL be logged, naming what expired and what was still
outstanding. That wait is an OS completion handler released on its deadline (capability `ios-app-shell`).
A bound that fires silently
is indistinguishable from work that completed, so the mechanism that protects the app would be invisible
in exactly the dumps that exist to explain it.

#### Scenario: A released-on-deadline handler is attributable

- **WHEN** an OS completion handler is released because its deadline expired rather than because its
  work finished
- **THEN** the log records the expiry and the entry point it belongs to

### Requirement: Photo-library change blocks and completions are traced

The photo-library asset-creation change block and its completion callback SHALL each be logged — block
entry, and completion with its success or error. An import whose completion never arrives currently
leaves no trace of whether the change block ran or whether the transaction committed, which is precisely
the state that decides whether an abandoned import becomes a duplicate.

#### Scenario: A never-completing import leaves evidence

- **WHEN** an import's completion callback never arrives before the process ends
- **THEN** the log shows whether the change block ran, so the transaction's fate is diagnosable

#### Scenario: A failed commit is attributable

- **WHEN** an asset-creation commit reports failure
- **THEN** the completion's error is logged, not only the resulting import outcome
### Requirement: An import that never returns is attributable

Each per-asset photo-library import SHALL be traced with the uniform enter/exit invocation logging, naming
the asset and reporting the duration on exit — so an import that entered and never exited is visible in a
pulled log and in a diagnostic dump, and is distinguishable from one that was never attempted.

This is the only route by which a never-reporting import becomes visible. Nothing bounds such an import in
time (capability `photo-download`), so no expiry line will ever name it, and the OS-handler receipt's own
expiry line reports that *something* outran the wake without saying what.

#### Scenario: A stuck import is identifiable from the log

- **WHEN** an import is entered and its completion never arrives
- **THEN** the log carries that import's entry line naming the asset, with no matching exit line

#### Scenario: An ordinary import reports its duration

- **WHEN** an import completes normally
- **THEN** the log carries matching entry and exit lines for it, the exit carrying the elapsed duration
