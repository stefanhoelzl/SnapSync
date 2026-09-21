## MODIFIED Requirements

### Requirement: No autonomous library reads under a limited grant

While permission is `LIMITED`, no autonomous trigger SHALL read the photo library: the foreground
upload pump kick, the upload half of the silent-push fan-out, and the status refresh's gallery walk
SHALL all skip their `PHAsset`-fetching work. Everything that does not touch `PHAsset` SHALL keep
running on those same triggers — config reload, HTTP reconcile, download planning and imports,
ledger-count polling, attestation refresh.

**The justification is corrected, the behaviour is not.** This requirement was recorded as *the*
load-bearing alert rule — the claim being that autonomous fetches queue the alert while in-flow reads
are clean. On-device measurement (SE2, iOS 26.5.2 — `PROBE-FINDINGS.md` in this change's decision
record) contradicts both halves:

- ~15 autonomous library walks, four launches one second apart, against an **unchanged** library
  produced **zero** alerts; and
- one camera capture followed by the **sanctioned** change-observer read produced an alert, queued,
  surfacing on the bare home screen after the app was killed.

**What the alert is.** It is iOS's automatic *"Select More Photos… / Keep Current Selection"* prompt: a
nudge to the member to add photos to a partial selection. It is **not** a guard on reads, because under
`.limited` the app can only ever read the selection. The app suppresses it with
`PHPhotoLibraryPreventAutomaticLimitedAccessAlert` and offers its own route instead (see *The app owns the
limited-library picker*).

**What is settled.** Two probes, on iOS 26.5.2 (2026-08-06) and on iOS 26.6.2
(`changes/archive/2026-09-21-album-gathers-retroactively`, both on the SE2 with the key in the bundle), agree
that neither **reads of an unchanged library** nor **the app's own creations** raise the prompt. The
app's own creations are imports, which join the selection at creation, and album creation and adds over
selected assets. That holds however many reads follow.

**What is not settled, and SHALL NOT be designed on.** The probes disagree on a change **outside** the
selection:

- **iOS 26.5 and 26.5.2** leaked the prompt **despite** the key in two probes. In July, prompts stormed
  during the first-grant picker, and queued after a camera photo plus re-fetches, surviving the app's
  death. In August, one camera capture then the app's reads surfaced exactly one queued prompt.
- **iOS 26.6.2:** the same camera stimulus, with 11 reads across 4 launch-and-kill cycles, surfaced
  **none**, which is what the key promises.

That fits the key working as documented from 26.6 on, but it is one device and one probe on the newer
release. No requirement, design or justification SHALL assume either outcome. In particular, nothing
SHALL assert that a limited member pays one prompt per photo taken, and nothing SHALL be justified as
suppressing one.

It follows that **read volume does not change the alert count**, so this discipline SHALL NOT be
justified as alert suppression. It is retained on its own merits, which are real: under a partial grant
the selection *is* the scope, so reading it rather than walking the library is the correct source, and it
removes per-foreground `PHAsset` round-trips that buy nothing. Reads under `LIMITED` therefore continue
to happen at exactly two moments and no others:

- **one baseline read on a cold foreground launch** (opening the app is a user action), establishing
  the status total and catching any backlog (selection changes made while the app was dead); and
- **on a selection-change emission** (next requirement).

Expiry trigger: re-measure on the next iOS major, or if Apple documents the automatic alert's trigger.
Caveats on the evidence: one device. The leak was seen on iOS 26.5 and 26.5.2 (two probes), and not
on iOS 26.6.2 (one probe, n = 1 out-of-scope change).

#### Scenario: Foreground entry under limited does not walk the library
- **WHEN** the app enters the foreground with permission `LIMITED` (not a cold launch)
- **THEN** no `PHAsset` fetch occurs; the reconcile, ledger-count poll, and attestation refresh still run

#### Scenario: A silent push under limited wakes only the download arm
- **WHEN** a silent push arrives while permission is `LIMITED`
- **THEN** the download receiver runs; the upload receiver performs no library read

#### Scenario: The cold-launch baseline catches offline selection changes
- **WHEN** the selection was widened while the app was not running, and the app is then cold-launched
  to the foreground
- **THEN** the single baseline read discovers the new photos and they are enqueued

#### Scenario: Reads against an unchanged library are alert-free
- **WHEN** the app fetches repeatedly under a partial grant and the library has gained nothing outside
  the selection since its last read
- **THEN** no limited-access alert is queued, however many times it reads

#### Scenario: The app's own creations raise no prompt
- **WHEN** under a partial grant the app imports a photo, or creates an album and adds selected photos to
  it, and then reads the library any number of times
- **THEN** no limited-access prompt is presented, in the running app or after it is killed

### Requirement: The limited selection is a facts-only candidate source for the admitted set

Under `LIMITED`, the user's hand-picked selection SHALL be presented to the admission (capability
`photo-selection-policy`) as one **candidate source** — pre-filled with the current selection — so the
same single admission runs over it exactly as over a full-library walk under `GRANTED`. The admission and
the `EventPhotoSet` abstraction SHALL be permission-oblivious: the mode difference is one source impl, not
a branch in the policy or its consumers. No consumer SHALL select between a walking and a snapshot path
itself; the permission-aware source SHALL make that choice once (capability `permission-gate`'s grant
read). No native fetch narrowing applies under `LIMITED` (there is no walk to narrow); the authoritative
in-memory admission filters the captured selection.

**The source SHALL also answer whether an admitted set can be produced at all**, and a consumer SHALL NOT
keep a grant check of its own for that question (capability `gallery-status`, "Library resource enumeration
seam"). Splitting the two questions — the source choosing where candidates come from, each consumer
deciding whether a grant permits an answer — left the consumers restating the grant distinction the source
already owns, which is the restatement that lets two paths drift apart.

**A selection snapshot that has not been captured is not an empty selection.** Until the cold-launch
baseline or the first observer emission has been consumed, the source holds no selection and SHALL report
that the admitted set cannot be determined — never an empty candidate set. The two have opposite
consequences: an empty selection is a counted zero and settles the status screen as "everything shared",
which is true for a receive-only member and false for a member whose selection has simply not arrived yet.
Because the status projection publishes only a ready state (capability `sync-status`), a settled frame
cannot be retracted, so the honest count that follows reads as the screen going backwards — the reported
defect `SNAPSYNC-14` / `SNAPSYNC-16`, in the form it survived under a partial grant.

A read that cannot be answered SHALL be recorded at a severity that does not reach crash reporting: a
member who withheld access, or a snapshot that has not yet arrived, is not a defect (capability
`diagnostic-logging`).

The sanctioned-read discipline SHALL be scoped to **library fetches**, not to every PhotoKit call, and
SHALL live entirely in how the source is **constructed and fed**:

- A library **fetch/query** (`PHAsset.fetchAssets…`) SHALL NOT be issued autonomously. The selection is
  captured only at the cold-launch baseline and at photo-selection-change observer emissions, and the
  source is **fed** that snapshot — never pulled. Under a partial grant the selection *is* the scope, so
  the captured snapshot is the correct source, and an autonomous fetch is a round-trip that buys nothing.
  (This was once justified as a "storm" of alerts from off-flow fetches. The 2026-08-06 probe retired
  that: an unchanged library raised none. See *No autonomous library reads under a limited grant*.)
- A per-asset **resource read** (`assetResourcesForAsset`) of an already-selected asset MAY be issued
  off-flow. Measured on device (SE2, iOS 26.5.2, `.limited`, alert suppression on): six off-flow bursts
  over already-held baseline refs produced zero alerts, during the bursts and on the bare home screen
  after a `SIGKILL`.

The snapshot SHALL nonetheless continue to be read **eagerly, with resources**, at those sanctioned
points. The spike licenses a lazy per-asset read where the asset reference is still held; it does not
license one across the snapshot cell, because reaching those assets again later would mean either holding
platform references for an unbounded period — resting on an invariant no type expresses — or re-fetching
by local identifier, an autonomous library fetch the first bullet forbids. The eager read is what keeps
every library **fetch** in-flow, and a limited selection is hand-picked and small, so the deferral would
save almost nothing. The lazy path belongs to the *walking* sources, where the reference never
leaves the call.

Consequently a candidate under `LIMITED` carries facts derived from the snapshot it was built from, and
its resources are already held rather than fetched on demand. That is not a special case in the admission
— a candidate source is free to have its resources in hand — and no rule reads resources to decide
(capability `photo-selection-policy`).

Reading the selection **eagerly** is also what bounds the un-answerable window: it lasts from the grant
turning partial until the first sanctioned read completes, and is closed by that read rather than by any
consumer waiting or retrying.

#### Scenario: The admitted set under LIMITED is the filtered selection

- **WHEN** permission is `LIMITED` and any consumer resolves the admitted set
- **THEN** the permission-aware source yields the snapshot's candidates and the one admission filters them
  exactly as it would a walk, with no autonomous library fetch and no branch in the consumer

#### Scenario: An un-captured snapshot is not an empty selection

- **WHEN** permission is `LIMITED`, the member has photos selected, and the status total is refreshed
  before the baseline read or any observer emission has been consumed
- **THEN** the source reports that the admitted set cannot be determined, the total stays un-counted, and
  the screen does not settle to "In sync"

#### Scenario: A snapshot that never arrives never becomes a zero

- **WHEN** permission is `LIMITED` and no selection snapshot is ever emitted for the lifetime of the
  process
- **THEN** the total remains un-counted for that lifetime, rather than resting on a counted zero that
  would read as "everything shared"

#### Scenario: An empty selection is still a counted zero

- **WHEN** permission is `LIMITED`, a snapshot has been captured, and it contains no photo the policy
  admits
- **THEN** the total is a counted `0` and the screen settles — the receive-only resting state is
  unaffected

#### Scenario: A consumer keeps no grant check of its own

- **WHEN** the status total or the join preview resolves its answer under any grant
- **THEN** it reads the source's result alone, and consults no photo-access grant to decide whether an
  answer was available

#### Scenario: The snapshot's resources are already in hand

- **WHEN** a consumer under `LIMITED` needs an admitted asset's resources
- **THEN** they are already held from the sanctioned read — nothing is fetched again, and in particular no
  fetch by local identifier is issued outside the sanctioned points

#### Scenario: No autonomous fetch is issued under LIMITED

- **WHEN** any consumer resolves the admitted set under `LIMITED`
- **THEN** no library fetch/query is issued outside the cold-launch baseline and the observer emissions —
  the selection always arrives as a fed snapshot

#### Scenario: The snapshot is read at the sanctioned points only

- **WHEN** the selection is captured
- **THEN** it is read at the cold-launch baseline or an observer emission, eagerly and with resources —
  never by a fetch issued at any other moment

#### Scenario: No consumer branches on the grant to pick a source

- **WHEN** the status total or the join preview resolves its answer
- **THEN** it calls one candidate source and never distinguishes `GRANTED` from `LIMITED` itself; only the
  source makes that choice
