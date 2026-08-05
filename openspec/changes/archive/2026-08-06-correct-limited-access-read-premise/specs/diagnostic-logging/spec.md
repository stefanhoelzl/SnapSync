## MODIFIED Requirements

### Requirement: Diagnostic dump contents and byte budget

A diagnostic dump SHALL be delivered as a **single event** whose message is the operator's
description behind a **fixed marker prefix**, and SHALL carry exactly five structured sections:

> **Reviewer note:** the only change in this requirement is inside the **state** bullet — the sentence
> excluding the partial-selection size is re-justified on this requirement's own
> "reads no data the app does not already read" principle, and the superseded alert-storm claim is
> removed. One scenario is added for it. Everything else is restated verbatim because `MODIFIED`
> blocks carry the whole requirement.

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
