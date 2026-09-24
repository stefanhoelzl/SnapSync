## RENAMED Requirements

- FROM: `### Requirement: Deadline expiry is logged`
- TO: `### Requirement: Operating-system expiry is logged

The app SHALL log every time the operating system signals that a wake's time is up — a `BGTask`'s
`expirationHandler`, or the expiration handler of a background task begun through the background-time port
(capability `ios-app-shell`, "Time is up is learned only from the operating system") — in **two lines**, because
the expiry is answered at once while the unit in flight runs on (capability `ios-app-shell`, "Expiry stops work
cooperatively at the next boundary"), so the stop and the tail's end are two different moments:

1. **At the stop**, written by the expiration handler's path before it returns: **which signal** fired and the
   wake it ended (the push, the transfer channel, the background task's identifier, or the foreground entry),
   and **what was running** — the tail's unit in flight and what becomes of it (a walk is abandoned; any other
   unit completes, and nothing further starts) — or that no tail was running.
2. **When the stopped tail ends**: whether the unit that was running **completed or was abandoned**, which units
   the stop kept from running (a pass joiners had requested included), and **what was left** for a later wake —
   at least the count of staged downloads not yet imported. This line may be written only when the process next
   runs, since the process can be suspended before the unit in flight reaches its end.

Where the expiry releases an OS completion handler whose own work had not finished, the handler-carrying type
SHALL log that release too, naming the entry point and the signal — the only evidence that a wake's own work did
not fit inside the time the operating system gave it.

The lines SHALL report the operating system's signal, never a deadline of ours; there is none (capability
`ios-app-shell`). A stop that fires silently is indistinguishable from work that completed, so the mechanism that
ends a wake cleanly would be invisible in exactly the dumps that exist to explain it — and these lines are also
how the change that removed the app's own deadlines is measured in the field, since its benefit could not be
reproduced on the test device.

Decision record: `changes/own-work-per-wake` (D3, D4; the measurement risk).

#### Scenario: An expiry is attributable

- **WHEN** the operating system signals expiry for a wake while its tail runs
- **THEN** the log records, at the stop, the signal, the wake it belongs to and the unit that was running; and,
  when the tail has stopped, whether that unit completed, what did not run, and how many staged downloads were
  left unimported

#### Scenario: An expiry during the walk says so

- **WHEN** the operating system signals expiry while the tail's discovery walk is in flight
- **THEN** both lines name the walk as abandoned, so a dump distinguishes "no new photos" from "the walk never
  finished"

#### Scenario: An expiry before the own work finished is visible

- **WHEN** the operating system's expiry releases a silent push's or a transfer wake's handler before its own
  work has finished
- **THEN** a line names the entry point and the signal, stating that its own work had not finished

#### Scenario: No deadline line exists

- **WHEN** a wake's own work or tail takes longer than any former receipt deadline
- **THEN** no line reports a deadline of ours, because no such deadline releases anything

### Requirement: An import that never returns is attributable

Each per-asset photo-library import SHALL be traced with the uniform enter/exit invocation logging, naming
the asset and reporting the duration on exit — so an import that entered and never exited is visible in a
pulled log and in a diagnostic dump, and is distinguishable from one that was never attempted.

This is the primary route by which a never-reporting import becomes visible. Nothing bounds such an import in
time (capability `photo-download`), and no deadline of ours exists to fire on it; the operating-system expiry
line names the unit in flight only if the operating system's signal arrives while it runs, and a process
suspended or killed without one leaves only this entry line.

#### Scenario: A stuck import is identifiable from the log

- **WHEN** an import is entered and its completion never arrives
- **THEN** the log carries that import's entry line naming the asset, with no matching exit line

#### Scenario: An ordinary import reports its duration

- **WHEN** an import completes normally
- **THEN** the log carries matching entry and exit lines for it, the exit carrying the elapsed duration


