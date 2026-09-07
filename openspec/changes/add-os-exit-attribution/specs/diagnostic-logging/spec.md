## ADDED Requirements

### Requirement: Every process-metric report writes one device-log line

Each report the platform delivers about this process (capability `crash-reporting`) SHALL write
**exactly one line** to the app's device log, whatever the report contains — including a report whose
counters are all normal. The device log is the channel that exists on every build, before and
independently of any reporting configuration, so it is the one place an attribution can always be
found.

The line SHALL carry the report's fields, and the **report is the unit**: one line per report, never
one line per counter. A report that crosses the reporting threshold SHALL additionally be logged at a
severity that reaches the reporting channel, so the severity states the meaning of the occurrence
rather than its destination — including on builds where that channel is inert.

Call-stack data SHALL NOT be written to the device log. Measured: one delivery of twelve queued
diagnostic payloads wrote 15.1 MB, rolled the 10 MB log, and destroyed the history preceding it — so
logging it verbatim destroys the very channel it was meant to feed, and takes an operator dump's log
tail with it.

The line SHALL be written **before the delivering call returns**, rather than handed to another lane.
Delivery is one-shot: once the platform hands a report to a subscriber it is not redelivered, and a
process woken briefly in the background may be killed before deferred work runs.

#### Scenario: A quiet report is still recorded

- **WHEN** a report is delivered whose exit counters are all normal
- **THEN** one line is written naming the report, so a reader can tell "nothing was wrong" from
  "nothing arrived"

#### Scenario: A report on a build with no reporting configuration

- **WHEN** a report crosses the threshold on a build carrying no reporting configuration
- **THEN** the line is written at the same severity as it would be elsewhere, and nothing is
  transmitted

#### Scenario: A report is never logged as a call stack

- **WHEN** a report is delivered that includes call-stack data
- **THEN** that data is not read and not written, and the log's existing history survives the delivery

#### Scenario: A background wake ends immediately after delivery

- **WHEN** a report is delivered to a process woken in the background that is killed moments later
- **THEN** the line has already been written, because it was written before the delivering call
  returned
