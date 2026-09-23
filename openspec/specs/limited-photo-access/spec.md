# limited photo access Specification

## Purpose

How the app behaves under a **partial** photo-library grant (iOS `.limited` → `PermissionStatus.LIMITED`):
the user's hand-picked selection **is** the membership's own-photo scope. That reframe is what makes a
partial grant a first-class working state rather than a failure mode — "is everything shared?" is
answerable again, because the selection defines "everything", and "In sync" over the chosen set is true.
For a guest at a stranger's event, picking exactly what to share is the *more* natural grant.

Three measured platform facts shape every requirement here (SE2; the probe records live with the decision
records). First, the app never raises iOS's automatic limited-access prompt itself. That prompt nudges the
member to widen the selection; it does not guard reads. The app suppresses it
(`PHPhotoLibraryPreventAutomaticLimitedAccessAlert`, in the app **and** the extension bundle — the extension
evaluates the prompt at launch, measured) and owns the picker instead. Reads of an unchanged
library, and the app's own creations, raise none. Whether a photo taken **outside** the selection gets
past the key has differed by release (it leaked on iOS 26.5.x and held on 26.6.2), so nothing is designed
on it. The read discipline below is kept for a different reason: under a partial grant the selection *is*
the scope, so reading it rather than walking the library is simply the correct source. Second, a
partially-granted process **cannot change its upload-job registration at all** —
`setUploadJobExtensionEnabled` is refused in both directions with `PHPhotosErrorAccessUserDenied` (3311) —
so the ≥26.1 PhotoKit background-upload extension is never registered from `.limited`. A registration made
under a full grant survives a downgrade and the OS still invokes it (measured 2026-09-21); its extension
withholds there — it records and acknowledges, and creates nothing — and its in-flight jobs settle once access
returns (measured 2026-09-22). Uploads under `.limited` are the app's uploader's, which creates under every usable
grant (`changes/archive/2026-09-22-both-uploaders-active`). Third, asset and album **creation** are
unrestricted under `.limited`; hence downloads and the event album need no special handling at all, and
receive-only is a valid resting state.

Decision record: `changes/archive/2026-07-20-accept-limited-photo-access` (`PROBE-FINDINGS.md` +
`LIMITED-ACCESS-DESIGN.md`) established this capability;
`changes/archive/2026-08-06-correct-limited-access-read-premise`
(`PROBE-FINDINGS.md`, SE2 / iOS 26.5.2) **superseded its fact 5**, the "every autonomous fetch storms"
claim. `changes/archive/2026-09-21-correct-limited-access-alert-rule` **corrects the alert rule again**.
August's "one prompt per photo taken" residual rested on the suppression key leaking on iOS 26.5.x, and
the key held on iOS 26.6.2, so the out-of-scope case is now recorded per release rather than as a rule.
Evidence: one device; re-measure at the next iOS major.
`changes/archive/2026-08-25-collapse-upload-tier-seam` (D11, D11b; SE2 / iOS 26.6) **corrects fact 2** —
the earlier reading, that registration *succeeds and lies* under `.limited`, is contradicted by
measurement: both directions are refused, and the enable was reached only through a development
override (since replaced by the control channel's per-uploader switch). Evidence: one device, one OS point release; re-measure at the iOS 27 GM
re-assessment.
`changes/archive/2026-09-22-selection-is-the-walk` made the selection the walk. A **read** selection
snapshot is authoritative, so de-selecting a photo withdraws it from the event (this reverses "deselection
is not withdrawal"). A selection **not yet read** is its own scope (`Unread`), and the app's upload cycle is
withheld on it. Verified on an SE2 (iOS 26.6, 2026-09-22).
Decision record for its seam, failure, state and concurrency rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.

## Requirements
### Requirement: A limited grant is a working membership whose scope is the selection

The user's hand-picked selection SHALL define the membership's own-photo scope under a partial
photo-library grant (`PermissionStatus.LIMITED`): the photos the member selected — and only those — are
the candidates the selection policy (capability `photo-selection-policy`) filters for upload, and the
status total counts. The cutoff, origin exclusions, and echo suppression SHALL apply to selected
photos unchanged. The screen's "In sync" SHALL mean the selected-and-in-scope set is synced — which is
the whole truth under a limited grant, since the selection *is* the scope.

**Receive-only under limited is a valid resting state**: a member MAY hold a limited grant, receive the
event's photos (imports and the event album work unrestricted under `.limited` — measured, decision
record fact 4), and never select anything to upload. Nothing SHALL treat that state as an error or
prompt the member out of it.

#### Scenario: Selected photos upload through the ordinary policy
- **WHEN** a member with a `LIMITED` grant has selected photos of which some are post-cutoff captures
  and some are pre-cutoff
- **THEN** the post-cutoff selected photos upload and the pre-cutoff ones are excluded, exactly as the
  policy would treat them under a full grant

#### Scenario: In sync over the selection is the resting state
- **WHEN** every selected in-scope photo is uploaded and downloads are settled
- **THEN** the status line reads "In sync"

#### Scenario: Receive-only limited membership works without any selection
- **WHEN** a member with a `LIMITED` grant and an empty (or never-widened) selection is joined with a
  direction that includes download
- **THEN** foreign photos import into their library (and the event album, if opted in), no upload work
  is created, and no state prompts them to select photos

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

### Requirement: Selection changes reach the domain through a change-source port

A `ports/` seam (`PhotoSelectionChangeSource`, named for the need) SHALL deliver photo-library
change notifications to the domain as a cold-safe stream the composition subscribes to. Its iOS
adapter SHALL live in `:adapter:ios:app-only` (the observer and picker are app-process surface;
extension linkage is structurally excluded) and SHALL register a `PHPhotoLibraryChangeObserver`
**only while permission is `LIMITED`**, unregistering otherwise — under a full grant the autonomous
walks already cover every change, and the observer would add redundant reads.

The port SHALL be fakeable: `:test:world` provides an operator-drivable fake so the world harness and
`:test:integration` can emit selection changes on demand.

#### Scenario: A selection change while limited triggers exactly one read
- **WHEN** permission is `LIMITED` and the user changes the selection (in-app picker, Settings edit, or
  iCloud sync)
- **THEN** the change source emits, and exactly one library read follows (the next requirement's
  consumption), with no other read triggered by the same change

#### Scenario: The observer is not registered under a full grant
- **WHEN** permission is `GRANTED`
- **THEN** no photo-library change observer is registered by this seam, and library changes reach the
  app through the existing autonomous refresh paths only

### Requirement: Change consumption reloads the pushed result and dedups via the ledger

The consumer of a selection-change emission SHALL source discovery from the change's own pushed fetch
result (`changeDetailsForFetchResult(held).fetchResultAfterChanges` — a handed-to-you result object,
never a fresh scope-query fetch), enumerate it, and rely on the **ledger** to drop already-known
assets — the same dedup the ordinary walk uses. Itemized `insertedObjects` MAY be used as a fast-path
when the change reports `hasIncrementalChanges`, but the reload-and-dedup path SHALL be the one the
design relies on: bulk library changes arrive non-incremental (measured — a 5-asset batch reported no
itemized inserts even against a sorted baseline).

No debounce and no self-caused-change filter SHALL be required for correctness: an app-side import
appears in the reload and dies at the ledger/echo-suppression check (one cheap pass — measured
harmless; asset creation itself never triggers the limited-access alert).

#### Scenario: A bulk selection change is fully discovered
- **WHEN** the user adds several photos to the selection at once and the change arrives with no
  itemized inserts
- **THEN** the consumer enumerates the pushed after-result, the ledger drops the already-known assets,
  and every newly-selected in-scope photo is enqueued

#### Scenario: The app's own import does not re-upload
- **WHEN** a foreign photo is imported (which auto-joins the limited selection and fires the observer)
- **THEN** the consumption pass drops it via echo suppression/ledger dedup and no upload job is created

### Requirement: One discovery serves both the status total and the enqueue

Under `LIMITED`, the own-device status total `N` SHALL be derived from the same selection snapshot that
enqueues upload work — the baseline read or a selection-change consumption — rather than from a separate
autonomous gallery walk. This preserves the policy identity (`photo-selection-policy`: the total and the
upload walk resolve the same admitted set) with **one** library read per event instead of two. Under
`GRANTED` the existing separate gallery refresh is unchanged.

The total SHALL obtain that snapshot through the permission-aware candidate source rather than by being
handed a resource list through a second, snapshot-specific entry point. A consumer with two entry points —
one for walking, one for a pushed snapshot — restates the mode difference the source already owns, and it is
that restatement, not the reading itself, that lets the two paths drift apart.

#### Scenario: A selection change updates N and the queue together

- **WHEN** permission is `LIMITED` and a selection change adds two in-scope photos
- **THEN** one read both raises `N` by two and enqueues the two uploads — no second library read occurs

#### Scenario: The total has one entry point regardless of grant

- **WHEN** the status total is refreshed under `GRANTED` and under `LIMITED`
- **THEN** the same single refresh entry point serves both, differing only in which source backs it

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

### Requirement: The app owns the limited-library picker

The app SHALL suppress iOS's automatic limited-access alert
(`PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true`) in **both** bundles' Info.plist — the app target's
**and** the background-upload extension's — **and**
SHALL offer its own route to widen the selection: the status screen's "Choose more photos" affordance
(capability `sync-status-screen`) drives `presentLimitedLibraryPicker` (PhotosUI category on
`PHPhotoLibrary`; app-only adapter). Both halves are mandatory and measured: without suppression the
autonomous-era alert storm was app-killing; suppression without an in-app picker route strands a
limited user with no way to widen their selection except Settings.

The resulting selection change is observed through the change-source port like any other — the picker
completion is not a separate signal path.

**The extension needs the key as well, because the Photos framework reads it from the running process's own
bundle.** Measured on the SE2, iOS 26.6, 2026-09-21: under a partial grant with an extension registration
surviving from a full grant, the OS launches the extension, and its `PhotoLibraryServicesCore` evaluates the
automatic prompt **at launch**, before any of the app's code runs — while the app process, which carried the
key, never evaluated it in the same window. One such launch put the prompt on the member's screen. No read
discipline in the extension's code can prevent a decision taken before that code starts; only the key can
(decision record: `changes/archive/2026-09-21-retire-the-upload-arm`).

#### Scenario: The picker is reachable from the status screen under limited
- **WHEN** permission is `LIMITED` and the member taps "Choose more photos"
- **THEN** the system limited-library picker presents, and photos added there upload via the ordinary
  selection-change path

#### Scenario: The automatic alert is suppressed
- **WHEN** the app reads the photo library under a limited grant in its steady state
- **THEN** iOS's automatic "Select More Photos / Keep Current Selection" alert is not presented by the
  app's reads (the Info.plist key is present in the built bundle)

#### Scenario: An extension launch under a limited grant raises no alert
- **WHEN** the OS launches the background-upload extension while the app holds a limited grant (a
  registration surviving from a full grant)
- **THEN** the automatic alert is not presented, because the key is present in the extension bundle's own
  Info.plist

### Requirement: Upload under limited uses the app-driven mechanism on every OS version

A `LIMITED` membership's upload jobs SHALL be created by the app-driven `URLSession` mechanism (capability
`ios-url-session-upload`) regardless of OS version. The app's cycle creates there because it creates under
**every usable grant** — `GRANTED` or `LIMITED` (capability `upload-lifecycle`) — not because anything picks
it for a partial grant: its entry gate admits the cycle, scoped to the selection snapshot. On iOS ≥26.1 the
extension cannot take that role, by measurement: its registration cannot be changed while the app holds
`.limited` — neither created nor removed (capability `ios-photokit-upload`) — so it is registrable only under
`GRANTED`, and nothing registers it under a partial grant. Decision record: `changes/both-uploaders-active`.

The extension SHALL **withhold** under `LIMITED` at its own entry gate, reading the grant in its own process:
it acknowledges the jobs the OS presents and records their outcomes, and creates none. A surviving
registration's in-flight jobs were measured to survive a round trip through `.limited` and settle once access
returns (SE2, iOS 26.6, 2026-09-22; capability `ios-photokit-upload`, "The registration cannot be changed under a
partial grant").
A registration made under a full grant survives a downgrade (the deregistration is refused), and **the OS
does invoke it there** — measured on the SE2, iOS 26.6, 2026-09-21: with a surviving record and a partial
grant, `process()` ran four seconds after a new photo joined the selection. An extension cycle there would
inherit a whole-library scope — it has no selection snapshot — so the gate is load-bearing, not
defense-in-depth. The extension SHALL decide on
the permission, never on its selection scope, whose default is untrue under a partial grant.

#### Scenario: A limited member on iOS ≥26.1 uploads via the app-driven tier
- **WHEN** a member on iOS ≥26.1 holds a `LIMITED` grant with upload-inclusive direction and selects an
  in-scope photo
- **THEN** the upload completes through the app-driven `URLSession` mechanism, and no PhotoKit extension
  job is created for it

#### Scenario: An extension invoked under a limited grant withholds
- **WHEN** a registration survives a downgrade to `LIMITED` and the OS invokes the extension
- **THEN** the extension's cycle withholds: it acknowledges and records the jobs presented, walks nothing,
  creates no job, and publishes no manifest

### Requirement: A downgrade to limited narrows the visible set without breaking sync

The app SHALL treat a grant change from `GRANTED` to `LIMITED` as an ordinary scope change. On that
transition, previously-imported foreign assets are no longer visible to the app (iOS auto-adds
app-created assets to the selection only at creation time — measured), and the own-photo scope narrows
to the selection. From the app's point of view the selection now **is** the gallery. The first upload
cycle after the selection has been read is an authoritative walk (see "The read discipline is enforced
at the mechanism, not at the trigger fan-out"). It removes the in-window rows of every photo outside the
selection, whatever their upload state, so those photos leave the device manifest in the cycle that
publishes it. Photos inside the selection keep their rows and are not re-uploaded. The status total
re-derives from the new scope. Nothing SHALL treat the narrowed visibility as an error.

A photo whose upload was in flight at the downgrade and that lies outside the selection may still finish
uploading: its bytes land, and are listed in no manifest (capability `sync-ledger`, "Deletion is a
presence diff over an authoritative walk"). A photo inside the selection whose upload finished while
the extension was withheld settles at the next foreground from the backend's per-device listing
(capability `upload-state-reconciliation`), not at the OS's next acknowledgement.

Decision record: `changes/selection-is-the-walk` (D1, D2, D4). This reverses the earlier rule that
already-uploaded photos stay in the event across a downgrade because "upload is a publish".

#### Scenario: Downgrading withdraws the photos outside the selection
- **WHEN** a member who uploaded photos under a full grant switches to limited with a selection that
  excludes some of them, and the selection has been read
- **THEN** the next cycle removes the excluded photos' rows and the manifest it publishes no longer lists
  them, the selected photos keep their rows, no re-upload occurs, and the status reflects the new
  selection-defined total

#### Scenario: An upload that landed during the downgrade settles at foreground
- **WHEN** a selected photo's upload was queued under a full grant, its bytes landed after the grant
  narrowed to limited, and the withheld extension never acknowledged it
- **THEN** its row becomes `COMPLETED` at the next foreground, and the status stops reporting it as
  outstanding

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
launch reconcile registers the extension where it is registrable and the OS reads no record (iOS ≥26.1,
through the disable→enable ritual — capability `upload-lifecycle`), the app's cycle keeps creating as it
does under every usable grant, and the ledger guarantees photos uploaded under the limited selection are not
re-uploaded.

#### Scenario: The upgrade resumes as an ordinary full grant
- **WHEN** a member who uploaded photos under a `LIMITED` selection switches to Full Access in
  Settings and relaunches the app
- **THEN** the app composes the ordinary `GRANTED` state — no selection-change observer, the extension
  registered on iOS ≥26.1 if the OS reads no record, the app still creating — and only newly-in-scope
  post-cutoff photos upload; nothing re-uploads

#### Scenario: The route raises no permission dialog
- **WHEN** the member takes the in-app route to Full Access
- **THEN** the app opens its system Settings page and issues no `PHPhotoLibrary` authorization
  request

### Requirement: The read discipline is enforced at the mechanism, not at the trigger fan-out

The rule that no autonomous library read occurs under a partial grant SHALL be enforced by the upload
**mechanism** that would perform the read — at its cycle's entry gate and in its discovery — not by the
trigger fan-out that wakes it. A trigger SHALL be
delivered to the mechanism unconditionally (`upload-lifecycle`, "Triggers are delivered to the
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

A selection snapshot that has been **read** SHALL be reported as a full enumeration: it is authoritative for
deletion exactly as a full-library walk is under a full grant (capability `sync-ledger`, "Deletion is a
presence diff over an authoritative walk"). Under a partial grant the selection is the gallery, from the
app's point of view. What the snapshot holds may be shared, subject to the selection policy. What it no
longer holds is removed from the ledger, and so from the device manifest, whatever its upload state:
**de-selecting is deleting.** Re-selecting a removed photo records it as new work and re-uploads the same
object idempotently. That duplicate is accepted.

A snapshot that has **not been read yet** SHALL be a distinct scope (`Unread`), and SHALL NOT be treated as
an empty selection anywhere on the upload path. Between a grant turning partial (or a cold launch under
one) and the first selection read, the app holds no selection. An authoritative empty snapshot would
delete the rows of every photo, and an empty answer to a key resolution means "gone" to the enqueue, which
also deletes. So the app's upload cycle SHALL be **Withheld** while the scope is `Unread` (capability
`upload-lifecycle`, "The upload cycle owns its entry decision"). It settles narrowly, and reads, creates,
deletes and publishes nothing. The observer's first emission then triggers the cycle that runs. The
selection-scoped discovery SHALL refuse to answer for an `Unread` scope, failing the call rather than
returning an empty result, so that no caller that reaches it anyway can mistake "not read" for "nothing".

The status total already draws the same distinction, for its own reason: an unread snapshot must not settle
the screen (see "One discovery serves both the status total and the enqueue"). Both SHALL read the one
snapshot cell, so they cannot disagree about whether the selection has been read.

Decision record: `changes/selection-is-the-walk` (D1). It reverses the earlier requirement that a
selection-scoped discovery never report a full enumeration because "deselection is not withdrawal and an
upload is a publish". Its one real hazard, the un-read snapshot, is kept closed by the `Unread` scope
rather than by making every snapshot non-authoritative.

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

#### Scenario: De-selecting a photo withdraws it from the event

- **WHEN** a read selection snapshot no longer carries a photo whose `COMPLETED` row is in the event's
  window
- **THEN** the discovery reports a full enumeration, the photo's rows are deleted, and the manifest that
  cycle publishes no longer lists it

#### Scenario: De-selecting a photo mid-upload withdraws it too

- **WHEN** a read selection snapshot no longer carries a photo whose row is `REQUESTED`
- **THEN** the row is deleted and the photo is not listed. The transfer may still complete, and its
  terminal write applies to no row

#### Scenario: Re-selecting a withdrawn photo shares it again

- **WHEN** a photo whose rows a de-selection removed is selected again
- **THEN** the next cycle records it as new work, re-uploads it to the same destination, and lists it again

#### Scenario: An un-read snapshot withholds the cycle and deletes nothing

- **WHEN** the app's upload cycle runs under a partial grant before any selection snapshot has been read,
  while the ledger holds admitted `DISCOVERED` and `COMPLETED` rows
- **THEN** the cycle is withheld: no row is deleted, no job is created, nothing is published, and the
  observer's first emission starts the cycle that runs over the real selection

#### Scenario: An un-read scope never answers empty

- **WHEN** the selection-scoped discovery is asked to discover or to resolve keys while the scope is
  `Unread`
- **THEN** the call fails, and no empty result is returned

### Requirement: Selection snapshots are emitted in change order

The selection-change source SHALL emit its snapshots from one serial lane, in the order of the changes
they reflect, so the last snapshot a consumer holds always reflects the latest change. It SHALL emit
nothing after observation has ended, and in particular SHALL NOT emit a snapshot built under a limited grant
once the grant has become full. A change that arrives before the baseline read completes SHALL be applied
after the baseline, not dropped.

#### Scenario: Two changes in quick succession

- **WHEN** the member picks more photos twice, quickly, and the second snapshot's enumeration would finish
  first on a parallel dispatcher
- **THEN** the second change's snapshot is still the last one emitted

#### Scenario: The grant becomes full while the baseline is being read

- **WHEN** the grant changes from limited to full while the baseline read is in flight
- **THEN** no snapshot is emitted from that baseline

#### Scenario: A change during the baseline read

- **WHEN** a selection change arrives before the baseline read has completed
- **THEN** a snapshot reflecting that change is emitted after the baseline one
