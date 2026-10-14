# Proposal

## Why

An iPhone tells the app, once a day, how its earlier runs ended and how long it froze; the app turns a run the system
ended abnormally, or a freeze past a second, into a failure report for the operator, carrying the app's own last
memory readings. That outcome is promised nowhere — `privacy-security`'s only freeze scenario is Android's — and no
integration test reaches it: the JVM host composes no source of these reports, and the crash reporter's double
records only diagnostic dumps, so nothing a test can observe says a report arrived or what it carried.

## What Changes

- `privacy-security` gains two scenarios under "Automatic failure reports are minimal and anonymous": an iPhone that
  says the system ended the app (out of memory, overusing the processor, crashed, or frozen) yields a report naming
  why, with the app's last memory use and without the event's key; an iPhone account with nothing that counts yields
  no report. The development-build scenario is unchanged.
- The JVM host composes a process-metrics mock instead of no provider, with a typed operator service that delivers one
  of three reports — quiet, ended abnormally, froze — built from real reports with their identifying values
  randomized: the SE2's recorded daily report and Bugsink's SNAPSYNC-61, the one crossing a phone has sent.
- The crash reporter's double records each automatic event it is handed, as it records dumps, with the latest context
  of each name riding it; the world stream tells each one, and the crash reporter's typed service reads them back.
- The double is licensed for that by the `CrashReporter` contract's on-the-wire clauses it can honestly model — a
  reversal of the contract's "the real SDK only" stance for those clauses, argued in design.md.
- Coverage floors rise: `:domain:entrypoints` to 100, `:domain:model` and `:domain:services` to what is measured after.
- No change to what the app does or sends. Any defect the tests reveal is fixed here.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `privacy-security`: "Automatic failure reports are minimal and anonymous" gains the iPhone process-report scenarios.

## Impact

- `:adapter:generic:mock` — a process-metrics mock and its fixtures; `InMemoryCrashReporter` records captured events
  with contexts; its contract binding reaches the on-the-wire state for the clauses it models.
- `:test:contracts` — `CrashReporterContract`'s KDoc on `ON_THE_WIRE`, and possibly clause ids/covers a double runs.
- `:test:levers` — `ProcessMetricsLevers` (`process-metrics`), a new `LeverNeed`, `CrashReporterLevers.received()`,
  `WorldEvent.EventReceived`, R8 keep rules.
- `:app:jvm` — composes the mock; `:test:rig` — the JVM host serves the service and re-words its refusal of the
  string verb `device/process-metrics`; the iOS host's string verb stays (3f/3g).
- `:test:integration` — the behaviour tests; `:test:architecture` — `LeverFacesTest`'s face list.
- Root `build.gradle.kts` coverage floors; `docs/testing.md` where it lists the faces and world events.
