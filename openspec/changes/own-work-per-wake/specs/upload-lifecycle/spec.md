## MODIFIED Requirements

### Requirement: Triggers are delivered to the mechanism and declined explicitly

App-side upload triggers SHALL be delivered to the **app-driven engine unconditionally**, whatever the
registration state and the photo permission — foreground entry, a silent push, a background-task heartbeat, and
a photo selection change. The caller SHALL NOT decide whether the engine is interested: the engine's upload work
decides at its entry gate ("The upload cycle owns its entry decision"), withholding when the app may not create.
The OS-driven mechanism receives no app-side trigger — the OS schedules it — so it states no declines.

In the app process a trigger reaches the engine through the process's **tail runner** (capabilities
`ios-app-shell`, "Each OS wake does its own work, then hands the rest to one opportunistic tail", and
`ios-url-session-upload`, "The tail runner reimplements the OS scheduler"; decision record
`changes/own-work-per-wake`, design D1): each wake does its own work, and the tail then runs the remaining work in
a fixed order. The tail's **top-up** (②) and its **discovery walk → manifest publish** (③) are the upload
mechanism's work, and each SHALL pass through that same entry gate — Skip, Not joined and Withheld SHALL decline
them exactly as they declined a whole cycle, and the declines SHALL stay explicit. Where a wake's own work is
itself upload work — a selection change's snapshot-fed discovery → manifest — it too SHALL pass through the gate.
The heartbeat and foreground entry run no upload unit as their own work: they reach the engine only through the
tail, and under a partial grant the tail runs no ③. The tail's other unit (① importing staged downloads) is the
download arm's and is not gated here. Moving work into the tail SHALL NOT move the decision out of the mechanism:
no wake and no tail unit SHALL pre-decide whether the upload engine is interested.

Deciding at the caller is an **invoker-gate**, and this capability has already ruled on that shape ("The arm's
direction gate lives at the choke point, never at the invoker"): the enumeration of invokers is invalidated
silently by a new tier or a new trigger.

**An upload completion always records, and requests work only when the app may create.** A transfer's
completion SHALL be recorded through the guarded write whatever the app's admission. Whether that completion
then requests the tail's top-up SHALL be decided by the tested tail runner from the app's current admission — a
request only on **Admit** — and never by the shell that receives the callback. A completion requests the top-up
alone, never a walk (design D2). Late completions after a revoke or a leave (`-999`s arriving when their
transfers are cancelled or finish) otherwise keep driving work that can do nothing (field observation,
2026-09-16). This is the same admission the entry gate reads, applied before the top-up is requested rather than
instead of the gate. Decision record: `changes/both-uploaders-active` (D7).

Each trigger SHALL be a `suspend` function that returns when its work is done and SHALL NOT accept an OS
completion handler. The handler is held by the entry point that received it: a push's or a background-session
relaunch's handler only for that wake's own work, a `BGProcessingTask`'s until its tail ends or its expiration
handler fires; "time is up" is learned only from Apple's signals, never from a deadline of the app's own
(capability `ios-app-shell`, "Time is up is learned only from the operating system"). A declining unit still returns, so the handler is still released.

**Cold background wakes run real work.** A trigger reaching a process whose host was never assembled — a
`BGProcessingTask` or a silent push that launched it in the background — SHALL drive the app engine's upload
work like any other. (It previously reached an idle stand-in held until a UI-launch transition moved it, so the
heartbeat's re-submission never ran and the chain ended at the first cold wake: a customer-visible stall of
background uploads, fixed by this requirement.)

#### Scenario: A cold heartbeat wake re-submits the heartbeat
- **WHEN** a `BGProcessingTask` launches the app in the background, with an upload-inclusive membership and a
  usable photo grant
- **THEN** the task's tail runs the app engine's upload work and the next `BGProcessingTask` is scheduled

#### Scenario: A trigger while the extension is registered runs the app's cycle
- **WHEN** a foreground trigger reaches the app engine under `GRANTED` while the extension is registered
- **THEN** the app's upload work is admitted and creates jobs only for `DISCOVERED` rows, leaving every row the
  extension requested untouched

#### Scenario: A background wake with no usable access still completes
- **WHEN** the OS delivers a background trigger while photo access is `NOT_DETERMINED` or `DENIED`
- **THEN** no upload work is performed, no permission dialog is raised, and the OS completion handler is still
  released

#### Scenario: A late completion after a revoke records and drives nothing
- **WHEN** an app transfer completes after photo access was revoked, or after a leave
- **THEN** its outcome is recorded through the guarded write, and no top-up is requested for it

#### Scenario: Every tail upload unit declines at the entry gate
- **WHEN** the tail reaches its top-up or its discovery walk while the app's admission withholds (or the
  membership is unreadable, or absent)
- **THEN** that unit declines at the upload cycle's entry gate with the same outcome a whole cycle would have
  had, and the tail's import unit is unaffected
