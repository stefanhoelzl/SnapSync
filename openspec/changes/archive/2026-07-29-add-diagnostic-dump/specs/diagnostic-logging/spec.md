## MODIFIED Requirements

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

Because the App Group container is not USB-pullable, the extension's log SHALL be reachable over USB
via the export trigger (`SNAPSYNC_EXPORT_LOGS`, capability `ios-app-shell`), which copies it into the
app's `Documents/`.

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

## ADDED Requirements

### Requirement: Operator-initiated diagnostic dump

The app SHALL provide a **hidden** operator affordance that sends the recent device log to the
reporting channel, so a device that cannot be reached over USB — a tester's phone, or the operator's
own away from a cable — can still yield its log. The affordance SHALL be triggered by a **double-tap
on the app-name label** rendered by the shared screen layout, which is present in every UI state, so
a stuck join gate or a denied-permission screen is dumpable too.

The affordance SHALL present **no** discoverable control: no button, no menu entry, no settings
surface, and no accessibility semantics that expose the label as interactive. Adding click semantics
to the label would make it read as a control and defeat the requirement.

Firing the gesture SHALL open a **confirmation dialog** that names what will be sent, and SHALL send
only if the operator confirms. Cancelling SHALL send nothing. The dialog is the consent moment and
the only disclosure of the payload.

The app SHALL show **no** post-send feedback. Delivery cannot be honestly confirmed at the moment of
the tap (the reporting SDK may cache and retransmit on a later launch), so a claim of "sent" would be
unverifiable.

The affordance SHALL be **absent entirely** on a build whose reporting channel is not configured: no
gesture is wired and no dialog can open, so a build that could send nothing never suggests it can.

There SHALL be no rate limit: each confirmed gesture sends one dump.

#### Scenario: A confirmed gesture sends one dump
- **WHEN** the operator double-taps the app-name label on a build with a configured reporting channel and confirms the dialog
- **THEN** exactly one diagnostic dump is sent

#### Scenario: Cancelling sends nothing
- **WHEN** the dialog is opened and dismissed with Cancel
- **THEN** no dump is sent and no state changes

#### Scenario: The affordance exists in every state
- **WHEN** the app is showing the create screen, any join-gate phase, the joined status, or the reconfigure surface
- **THEN** the same gesture on the app-name label opens the same dialog

#### Scenario: An unconfigured build offers nothing
- **WHEN** a build with no reporting configuration is running and the label is double-tapped
- **THEN** no dialog opens and nothing is sent

#### Scenario: The label is not an exposed control
- **WHEN** the status screen's accessibility tree and rendered controls are inspected
- **THEN** the app-name label exposes no click action and no control affordance

### Requirement: Diagnostic dump contents and byte budget

A diagnostic dump SHALL be delivered as a **single event** carrying a fixed message, so every dump
groups as an occurrence of one issue rather than creating a new one, and SHALL carry exactly four
structured sections:

- **state** — the facts a log tail may not contain: app marketing version and build number, OS
  version and device model, resolved upload tier, photo-permission status, whether a membership is
  held and its configuration, the baked upload base, and the reporting environment. It SHALL NOT
  include the size of a partial photo-access selection: obtaining it would be an autonomous
  `PHAsset` read under `LIMITED`, which queues the system's limited-access alert into an
  app-killing storm surviving process death (capability `limited-photo-access`). A diagnostic must
  not be able to break the device it diagnoses;
- **ledger** — five counts already read by shipped code: pending and completed **photos** from the
  ledger aggregates, and the download store's imported, total-asset, and in-flight counts. The
  section SHALL label its units, because the ledger counts photos while the log speaks of resource
  rows and the two legitimately disagree;
- **app log** and **extension log** — the tails of the two logs.

The two log tails SHALL share a **fixed byte budget**, each taking at most half and either taking the
other's unused share, so a device whose extension has barely run still yields a full-budget app tail.
Each tail SHALL be cut at a line boundary and taken from the **current** log file only, never its
rolled `.1` sibling.

The budget SHALL be chosen to keep an assembled dump below the reporting channel's maximum event
size with headroom for the reporting SDK's own contributions. An over-budget dump is **rejected at
ingest and silently lost** — the sender observes success — so the budget is a hard bound and not a
target to be maximised.

The dump SHALL read no data the app does not already read, SHALL perform no write, and SHALL add no
port surface for diagnostics alone.

#### Scenario: A dump carries all four sections
- **WHEN** a dump is assembled on a joined device after both processes have logged
- **THEN** it carries the state, ledger, app-log and extension-log sections in one event

#### Scenario: The budget is never exceeded
- **WHEN** both logs are far larger than the budget
- **THEN** the assembled dump's log content is at or below the budget, and each tail ends at a line boundary

#### Scenario: Unused budget is borrowed
- **WHEN** the extension's log is much smaller than half the budget
- **THEN** the app-log tail takes the remaining share rather than being capped at half

#### Scenario: Dumps group as one issue
- **WHEN** two dumps are sent from the same or different devices
- **THEN** both arrive as occurrences of a single issue rather than as two distinct issues

#### Scenario: A dump reads only, and never writes
- **WHEN** a dump is assembled
- **THEN** no ledger, download-store, or configuration write occurs

### Requirement: Diagnostic dumps are delivered verbatim

A diagnostic dump SHALL be transmitted **without identifier redaction**: event ids, asset ids, device
ids, and upload keys appear in full. The dump is deliberate, confirmed, and largely worthless without
its identifiers — a log in which every id reads alike cannot answer which photo, which event, or
which device.

This exemption SHALL be **narrow**: it covers only the operator-initiated dump. Automatic events and
breadcrumbs remain scrubbed (capability `crash-reporting`), because they are transmitted without
anyone's knowledge or consent.

The exemption SHALL be stated and pinned, not left to rest on the current shape of the scrubbing
implementation. It holds today only because the scrub covers message text, exception values, and
breadcrumbs but not structured context sections — an incidental property that a later, well-meaning
widening of the scrub would silently destroy, emptying every future dump with no failing test and no
visible error.

#### Scenario: A dump keeps its identifiers
- **WHEN** a dump whose log lines contain event ids, asset ids and upload keys is transmitted
- **THEN** the received payload contains those values in full

#### Scenario: Automatic reports stay scrubbed
- **WHEN** an error event or breadcrumb is captured automatically on the same build
- **THEN** its identifiers are redacted exactly as before this change

#### Scenario: Widening the scrub is caught
- **WHEN** the scrubbing function is changed to also cover structured context sections
- **THEN** a test fails, naming the dump exemption, rather than dumps silently arriving empty
