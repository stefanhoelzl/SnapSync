## ADDED Requirements

### Requirement: Every outgoing event is bounded below the ingest's maximum event size

Every event either process transmits SHALL stay below the reporting channel's maximum event size, judged on
the **decoded** body and not on the compressed wire size. The bound SHALL hold **by construction**: every part
of an event that this app composes SHALL carry its own cap, and the caps plus a stated allowance for the
SDK's own contributions SHALL sum below the maximum.

The bound is a hard one because a refused event is not dropped. The reporting SDK deletes a cached event only
when the channel accepts it, and always sends the oldest first. So a refused event stays queued, is re-sent on
every trigger, and holds back every later report from its process, across launches, until enough newer
events evict it. Nothing on the device shows this, so a process that has stopped reporting looks the same as
one that has nothing to report.

The caps SHALL be:
- **breadcrumbs:** the count SHALL be set explicitly rather than left to the SDK default. Each breadcrumb's
  message and string data values SHALL together be at most a fixed byte cap, message first. This applies to
  breadcrumbs from the logging seam and the SDK's automatic breadcrumbs alike;
- **automatic events:** the message and each exception value SHALL be at most a fixed byte cap;
- **the operator-initiated dump:** bounded by its own sections' budgets (capability `diagnostic-logging`),
  which the sum SHALL account for.

Caps SHALL be counted in UTF-8 bytes and SHALL cut on a code-point boundary. A cut text SHALL end in a fixed
marker naming how many bytes were dropped, so a reader never takes a cut line for a whole one. The caps apply
to the reporting channel only: the device logs keep every line in full.

#### Scenario: An over-long log line becomes a capped breadcrumb
- **WHEN** a line longer than the breadcrumb cap is logged below `Error` severity
- **THEN** the breadcrumb the channel carries is at most the cap and ends in the truncation marker, while the
  device log holds the full line

#### Scenario: A runaway exception message is capped
- **WHEN** an `Error` line is logged with a message or throwable text longer than the event cap
- **THEN** the transmitted event's message and exception value are each at most the cap and end in the
  truncation marker

#### Scenario: A short text is untouched
- **WHEN** a breadcrumb or event text is within its cap
- **THEN** it is transmitted byte-for-byte, with no marker

#### Scenario: The worst-case dump still arrives
- **WHEN** the process has logged a full set of breadcrumbs, each over the cap, and then sends a dump whose
  log tails fill their whole budget
- **THEN** the dump reaches an ingest that refuses anything over the maximum event size, followed by a later
  event, so nothing is left stuck in the queue
