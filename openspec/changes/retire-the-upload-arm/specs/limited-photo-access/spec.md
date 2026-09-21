## MODIFIED Requirements

### Requirement: Upload under limited uses the app-driven mechanism on every OS version

A `LIMITED` membership's uploads SHALL run on the app-driven `URLSession` mechanism (capability
`ios-url-session-upload`) regardless of OS version. On iOS ≥26.1 this is forced by measurement: the
OS does not invoke the PhotoKit background-upload extension while the app holds `.limited`, and its
registration cannot be changed there (capability `ios-photokit-upload` records both constraints). Resolution
therefore yields the app-driven kind under `LIMITED` (capability `upload-lifecycle`), and the app engine's
entry gate admits its cycle there, scoped to the selection snapshot.

The extension SHALL **withhold** under `LIMITED` at its own entry gate, reading the grant in its own process.
A registration made under a full grant survives a downgrade (the deregistration is refused), and an extension
cycle there would inherit a whole-library scope — it has no selection snapshot — so relying on the OS never
invoking it would rest the partial grant's scope on an unmeasured OS behaviour. The extension SHALL decide on
the permission, never on its selection scope, whose default is untrue under a partial grant.

#### Scenario: A limited member on iOS ≥26.1 uploads via the app-driven tier
- **WHEN** a member on iOS ≥26.1 holds a `LIMITED` grant with upload-inclusive direction and selects an
  in-scope photo
- **THEN** the upload completes through the app-driven `URLSession` mechanism without any PhotoKit
  extension invocation

#### Scenario: An extension invoked under a limited grant withholds
- **WHEN** a registration survives a downgrade to `LIMITED` and the OS invokes the extension
- **THEN** the extension's cycle withholds: it walks nothing, creates no job, and publishes no manifest

### Requirement: An upgrade to full access is an offered route and an ordinary transition

The app SHALL offer a limited member an in-app route to switch the grant to Full Access: the status
screen's "Allow full access" affordance (capability `sync-status-screen`) deep-links to the app's
system Settings page, where the switch itself happens — iOS exposes no API that re-raises the
full-access dialog while the app holds `.limited`, so Settings is the only mechanism (expiry
trigger: an iOS release adding a re-prompt API). The route SHALL NOT issue a PhotoKit authorization
request (which is a no-op under a determined status) and SHALL NOT interpose any in-app consent
surface.

The app SHALL treat the resulting `LIMITED→GRANTED` change as an ordinary scope change, the mirror
of the existing downgrade requirement: the OS terminates the app when the grant changes in Settings,
and the next cold launch composes the ordinary `GRANTED` state — the baseline covers the whole
post-cutoff library under the selection policy, the selection-change observer is not registered, the
launch reconcile brings up the mechanism resolution yields for `GRANTED` (capability `upload-lifecycle`),
and the ledger guarantees photos uploaded under the limited selection are not re-uploaded.

#### Scenario: The upgrade resumes as an ordinary full grant
- **WHEN** a member who uploaded photos under a `LIMITED` selection switches to Full Access in
  Settings and relaunches the app
- **THEN** the app composes the ordinary `GRANTED` state — no selection-change observer, the
  `GRANTED`-tier mechanism — and only newly-in-scope post-cutoff photos upload; nothing re-uploads

#### Scenario: The route raises no permission dialog
- **WHEN** the member takes the in-app route to Full Access
- **THEN** the app opens its system Settings page and issues no `PHPhotoLibrary` authorization
  request

### Requirement: The read discipline is enforced at the mechanism, not at the trigger fan-out

The rule that no autonomous library read occurs under a partial grant SHALL be enforced by the upload
**mechanism** that would perform the read — at its cycle's entry gate and in its discovery — not by the
trigger fan-out that wakes it. A trigger SHALL be
delivered to the resolved mechanism unconditionally (`upload-lifecycle`, "Triggers are delivered to the
mechanism and declined explicitly"), and the mechanism SHALL decide whether responding would read the
library.

Placing the gate at the fan-out makes it an **invoker-gate**, and its soundness then depends on the
fan-out's enumeration of who might read — an enumeration invalidated silently by a new mechanism or a new
trigger. This is the same failure shape `upload-lifecycle` records for the direction gate ("The arm's
direction gate lives at the choke point, never at the invoker"), and the same remedy applies.

The mechanism is also the only component that **knows the answer**: whether a cycle walks the library or
consumes the in-memory selection snapshot (`SelectionScopedDiscovery`, which wraps the cycle's
`UploadDiscovery`) is a property of the mechanism, and it differs between mechanisms on the same OS and the
same grant.

Relocating this gate SHALL preserve the behaviour it currently produces. It SHALL NOT be widened as a
side effect of the move — if the relocated gate would admit a trigger the fan-out currently refuses, that
widening is a separate decision requiring its own evidence.

A selection-scoped discovery SHALL NOT report a full enumeration, so it is never authoritative for
deletion and drives no ledger deletion (capability `sync-ledger`, "Deletion is a presence diff over an
authoritative walk"). A snapshot is the member's selection, not the library: a photo absent from it may
simply be de-selected, and an uploaded, later-deselected photo SHALL keep its `COMPLETED` row, because
deselection is not withdrawal and an upload is a publish. This is also what makes the mechanism's own empty
answer safe: where the count must distinguish an un-captured snapshot from an empty one, discovery need
not, **because its empty answer is retryable and the count's is not**. An un-captured snapshot costs the
upload arm one idle cycle, which the next observer emission re-runs; a snapshot treated as authoritative
would instead delete the rows of every photo it did not carry.

The reason this is stated as a requirement rather than left to the implementation is that the flag is easy
to set wrongly for a snapshot that looks complete: a selection the member put every photo into is still not
the library, and nothing about its contents says so.

#### Scenario: A trigger that would walk the library is declined under a partial grant

- **WHEN** a background trigger reaches an upload mechanism whose response would enumerate the photo
  library, and photo access is `LIMITED`
- **THEN** the mechanism performs no library read, and the decision is made in the mechanism rather than
  by the component that delivered the trigger

#### Scenario: A selection-scoped mechanism is not blocked by a gate meant for walks

- **WHEN** a trigger reaches a mechanism whose discovery consumes the selection snapshot rather than
  walking, under a `LIMITED` grant
- **THEN** whether it responds is decided by that mechanism's own reading of the discipline, not by a
  blanket refusal at the fan-out

#### Scenario: A scoped discovery deletes nothing

- **WHEN** a selection-scoped discovery runs, and the member has de-selected a photo whose `COMPLETED` row
  is in the event's window
- **THEN** it reports no full enumeration, and no ledger row is deleted as absent from the walk, so the
  de-selected photo stays listed

#### Scenario: An un-captured snapshot costs an idle cycle, not lost photos

- **WHEN** an upload cycle runs under a partial grant before any selection snapshot has been captured
- **THEN** it enqueues nothing, no row is deleted as absent from the walk, and the next observer emission
  re-runs discovery over the real selection

### Requirement: A limited grant resolves the app-driven mechanism by resolution, not by a branch

Under a partial grant the app-driven mechanism SHALL be the one **resolution** yields on every OS
version (`upload-lifecycle`, "The upload mechanism is resolved, never selected").

No deregistration SHALL be attempted under a partial grant. The platform refuses it with
`PHPhotosErrorAccessUserDenied` (`ios-photokit-upload`, "The registration cannot be changed under a partial
grant"), so an attempt could only ever report a refusal; the transitions' compared reconcile changes nothing
under any grant other than `GRANTED` (`upload-lifecycle`). A record that already exists survives, and that is
safe: the extension withholds at its own gate under a partial grant ("Upload under limited uses the app-driven
mechanism on every OS version"), so a surviving record produces no second ledger writer, and a return to a full
grant is a permission change whose compared reconcile re-registers through the disable → enable ritual if the
record is gone.

Where the app-driven kind is resolved under a **full** grant — which only a development mechanism override
produces — the deregistration succeeds and is load-bearing (`upload-lifecycle`, "A mechanism override is a
runtime input a shipped build cannot carry").

#### Scenario: A downgrade to a limited grant attempts no registration change
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` on an OS carrying the OS-driven mechanism
- **THEN** resolution yields the app-driven kind, the app engine is armed, and no registration write is
  attempted

#### Scenario: A surviving registration does not block the pump
- **WHEN** a registration survives the downgrade
- **THEN** the app-driven mechanism pumps regardless, the extension withholds if invoked, and exactly one
  process writes ledger records
