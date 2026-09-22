## MODIFIED Requirements

### Requirement: The upload cycle owns its entry decision

The upload cycle SHALL read the membership itself and decide what the invocation does, before any library
walk, upload job, device manifest, or notify. The decision SHALL have exactly four outcomes:

- **Skip** — a required input could not be read (protected data unavailable, or — since migration
  step 11a — config-file content this build cannot positively interpret; capability `event-link`,
  *An unreadable config is not an absent config*). Unreadable content includes a foreign envelope
  version and an undecodable current-version payload. The cycle SHALL touch nothing: no ledger write, no
  jobs. It SHALL complete cleanly; the next cycle retries.
- **Not joined** — there is definitively no usable membership (no config file by the not-found
  error class and — while the read-only fallback lasts — no legacy Keychain item, or a legacy
  item that does not decode (the legacy-item rule, Keychain-side only), or no baked
  host). The cycle SHALL create no upload job and SHALL write, clear, or reset no ledger row. Clearing
  the upload ledger belongs to the leave itself (capability `leave-event`), an explicit app action; the
  cycle does not detect or repair a membership change, and there is no leave-side step for it to run.
- **Withheld** — joined, but this process may not create: the extension under any grant other than
  `GRANTED`; the app under any grant other than `GRANTED` or `LIMITED`, under `LIMITED` while the selection
  has not been read yet (capability `limited-photo-access`), or while the control channel has switched its
  creation off. The cycle SHALL settle narrowly ("Settling with the platform is owed regardless
  of the cycle's other outcomes") and SHALL create no job, walk nothing, and publish nothing.
- **Run** — joined, configured, and admitted. The cycle SHALL proceed to its contribution gate and phases.

There is no "not resolved" outcome: no process declines because the other uploader may run ("Both uploaders
may run; an overlap is a duplicate, never a loss"). The app's former not-resolved case — no usable access —
is **Withheld**, whose narrow settle is safe in the app too: it creates nothing, and there is no stranded pass
left to run.

The two admission outcomes SHALL be decided **before** the membership's selection policy is built. Building
the policy reads the denylisted-album structure, and a `PHAssetCollection` fetch under `NOT_DETERMINED`
presents iOS's permission dialog (measured: simulator, iOS 26.4, `tccd` logs `AUTHREQ_PROMPTING`); a
background wake must never raise it.

**Admission is per process and asymmetric.** The app process SHALL admit exactly under a usable grant —
`GRANTED` or `LIMITED` — unless the control channel has switched its creation off: under `LIMITED` it runs
scoped to the selection snapshot, and under `GRANTED` it runs alongside a registered extension. Under
`LIMITED` it SHALL admit only once the selection has been read, and SHALL withhold while the selection
scope is `Unread`. An unread selection is not an empty one: a cycle run over it would delete the rows of
every photo (decision record `changes/selection-is-the-walk`, D1). The admission SHALL read the same
snapshot cell the selection scope is derived from, so the two cannot disagree. It SHALL NOT
consult the registration state. The extension SHALL admit exactly under `GRANTED`, read from its own process;
it SHALL NOT infer admission from its selection scope, whose default (`Unrestricted`) is untrue under a
partial grant. Decision record: `changes/both-uploaders-active` (D3).

A composition root SHALL NOT make this decision. A root SHALL supply only the platform reads the decision
consumes — the membership read, the device-identity probe, the build-time host, and the process's admission answer —
and the shared, tested decision function SHALL combine them. This is the same containment the `SelectionPolicy` already
has, and for the same reason: an upload tier's root is wiring-only and
untested by project rule,
so a decision placed there reaches whichever tiers its author happened to enumerate.

The **translation** of those reads into the decision's inputs SHALL itself exist exactly once, in the
shared composition (`uploadCore`, `:domain` `compose/`) — not once per root. It SHALL be **port-pure**:
one fresh three-state `ConfigReader.read()` per cycle, the identity probe, the host read, and the
admission answer, and nothing else. In particular it SHALL NOT refresh any adapter-held read-model state (such as the
UI-facing `ConfigSource` `StateFlow`) as a side effect of gating a cycle: repairing a `StateFlow`
seeded while protected data was unavailable is the app process's trigger flows' concern — every
OS-callback flow re-reads the membership before acting (migration step 12; see `ios-app-shell`,
*Background triggers re-read the membership and fail cleanly before first unlock*) — not the entry
gate's. (Decision record: `changes/archive/establish-shared-composition` D1 — the previously-shipped
per-root translations diverged on exactly this side effect, with the gate outcome provably identical.)

The decision SHALL be reachable per cycle, not resolved once at construction: a tier whose process
outlives a cycle SHALL re-read the membership on each run so a join, leave, or switch takes effect without
a relaunch.

An unresolvable device identity SHALL produce **Skip**, never **Not joined**. Resolving the identity can
fail exactly as the membership read can — the identity is a Keychain item and the membership a
protected App-Group file, and both
are unreadable in the same locked-device windows — and every outcome needs it. "I could not look" is
not "no identity" (capability `device-identity`, which never reports absence: an absent item mints).

#### Scenario: An unreadable membership skips without touching state
- **WHEN** the cycle's membership read reports unreadable
- **THEN** the cycle completes cleanly, having created no upload job and written no ledger row

#### Scenario: An unresolvable device identity skips, and does not read as a leave
- **WHEN** the device identity cannot be resolved because protected data is unavailable
- **THEN** the cycle skips, no ledger row is written, and the identity is not re-minted

#### Scenario: A definitely-absent membership creates and clears nothing
- **WHEN** the cycle's membership read reports definitively no usable membership
- **THEN** no upload job is created and no ledger row is written, cleared, or reset — the cycle runs no
  leave-side step

#### Scenario: The decision holds on every tier
- **WHEN** any tier runs a cycle from any trigger with an unreadable membership
- **THEN** the outcome is Skip, regardless of which tier or trigger invoked it

#### Scenario: A long-lived tier re-reads the membership each cycle
- **WHEN** a tier whose process survives across cycles runs a cycle after the membership changed
- **THEN** the cycle acts on the current membership, without a relaunch

#### Scenario: The entry-gate translation is one implementation
- **WHEN** any tier (or the world harness) assembles an upload cycle
- **THEN** its entry gate is the shared `uploadCore` translation over that tier's ports — a fresh
  three-state read per cycle with no adapter read-model refresh — so no tier can carry gate semantics
  another tier lacks

#### Scenario: The app without usable access withholds
- **WHEN** the app engine's cycle runs while photo access is `DENIED` or `NOT_DETERMINED`, or while the control
  channel has switched the app's creation off
- **THEN** the outcome is Withheld: the cycle settles narrowly, creates no job, walks nothing, and publishes no
  manifest

#### Scenario: An undetermined grant never builds the policy
- **WHEN** a cycle runs in either process while photo access is `NOT_DETERMINED`
- **THEN** the gate declines before the selection policy is built, so no album structure is read and no
  permission dialog is presented

#### Scenario: A limited grant admits the app and withholds the extension
- **WHEN** photo access is `LIMITED` on an OS carrying the OS-driven mechanism
- **THEN** the app engine's cycle runs scoped to the selection snapshot once the selection has been read,
  and an extension cycle withholds

#### Scenario: An unread selection withholds the app
- **WHEN** photo access is `LIMITED` and the app's cycle runs before the first selection read
- **THEN** the outcome is Withheld: the cycle settles narrowly, reads nothing, creates no job, deletes no row,
  and publishes no manifest

#### Scenario: A full grant admits both processes
- **WHEN** photo access is `GRANTED` on an OS carrying the OS-driven mechanism
- **THEN** both the app engine's cycle and an extension cycle are admitted, whether or not the extension is
  registered

## ADDED Requirements

### Requirement: A presented job whose row is gone is answered and nothing more

A presented job whose key has **no ledger row** SHALL be answered to the platform and SHALL leave no trace,
whether it is a first failure for retry, a terminal outcome, or a retry-spent failure:

- the transport SHALL acknowledge it, as it must every presented job, and SHALL write nothing for it;
- it SHALL NOT be retried, re-created, or reported to the engine. No settle or retry path SHALL record a row
  for a key the ledger does not hold.

A row goes missing by design: an authoritative walk deletes a departed asset's rows whatever their state,
including a `REQUESTED` row whose job is still in flight (capability `sync-ledger`, "Deletion is a presence
diff over an authoritative walk"). The photo was deleted from the library or, under a partial grant,
de-selected. A late outcome for it is expected. Before this rule, a late failure reached the engine,
whose failure record is an upsert guarded only against a *settled* row. That recreated the row as a bare
`DISCOVERED` row (its `assetId` parsed from the key, no capture date). The row was never admitted, so it was
never uploaded or listed. It was never in any walk's window either, so it was never deleted, and it counted
as pending forever. A first-failure retry went further: it re-uploaded the photo the walk had just removed.

The rule SHALL hold on every settle and retry path of the cycle — the narrow settle of a withheld cycle, the
re-creation of retry-spent failures, and the first-failure retry loop — **before** the engine is consulted.
The PhotoKit transport SHALL enforce it on its side as well: it SHALL emit a presented job to the cycle only
when the job's row exists, and SHALL acknowledge a job whose row is gone in place (capability
`ios-photokit-upload`, "Completion and retry adjudication"). So a job the cycle declines is never one left
un-acknowledged (error 50008).

Decision record: `changes/selection-is-the-walk` (D3).

#### Scenario: A late failure for a deleted row records nothing
- **WHEN** a withheld cycle's narrow settle is presented a failure whose key has no row
- **THEN** the job is acknowledged, no row is recorded, and the ledger's pending count is unchanged

#### Scenario: A retry-spent failure for a deleted row is not re-created
- **WHEN** a running cycle is handed a retry-spent failure whose key has no row
- **THEN** no engine event is raised, no job is created, and no row is recorded

#### Scenario: A first failure for a deleted row is not retried
- **WHEN** the platform presents a first failure for retry whose key has no row
- **THEN** the job is acknowledged, not retried, and no `REQUESTED` row is recorded

#### Scenario: A late failure for a present row is still retried
- **WHEN** a presented failure's key has a `REQUESTED` row
- **THEN** it is adjudicated as before: the row returns to `DISCOVERED`, and the retry or re-creation is made
