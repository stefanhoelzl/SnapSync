## MODIFIED Requirements

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
  include the size of a partial photo-access selection: obtaining it would be an autonomous
  `PHAsset` read under `LIMITED`, which queues the system's limited-access alert into an
  app-killing storm surviving process death (capability `limited-photo-access`). A diagnostic must
  not be able to break the device it diagnoses;
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
