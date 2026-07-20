# limited-photo-access — delta

## ADDED Requirements

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

This is the load-bearing alert rule, from measurement (decision record fact 5): autonomous fetches
across repeated foregrounds queue iOS's automatic limited-access alert into an app-killing storm that
survives process death, while in-flow reads (cold-launch, observer-callback — including a coalesced
burst) are clean. Reads under `LIMITED` therefore happen at exactly two moments and no others:

- **one baseline read on a cold foreground launch** (opening the app is a user action), establishing
  the status total and catching any backlog (selection changes made while the app was dead); and
- **on a selection-change emission** (next requirement).

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

Under `LIMITED`, the own-device status total `N` SHALL be derived from the same discovery pass that
enqueues upload work — the baseline read or a selection-change consumption — rather than from a
separate autonomous gallery walk. This preserves the policy identity (`photo-selection-policy`: the
total and the upload walk apply the same three-way subtraction) with **one** library read per event
instead of two. Under `GRANTED` the existing separate gallery refresh is unchanged.

#### Scenario: A selection change updates N and the queue together
- **WHEN** permission is `LIMITED` and a selection change adds two in-scope photos
- **THEN** one read both raises `N` by two and enqueues the two uploads — no second library read occurs

### Requirement: The app owns the limited-library picker

The app SHALL suppress iOS's automatic limited-access alert
(`PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` in the app target's Info.plist) **and**
SHALL offer its own route to widen the selection: the status screen's "Choose more photos" affordance
(capability `sync-status-screen`) drives `presentLimitedLibraryPicker` (PhotosUI category on
`PHPhotoLibrary`; app-only adapter). Both halves are mandatory and measured: without suppression the
autonomous-era alert storm was app-killing; suppression without an in-app picker route strands a
limited user with no way to widen their selection except Settings.

The resulting selection change is observed through the change-source port like any other — the picker
completion is not a separate signal path.

#### Scenario: The picker is reachable from the status screen under limited
- **WHEN** permission is `LIMITED` and the member taps "Choose more photos"
- **THEN** the system limited-library picker presents, and photos added there upload via the ordinary
  selection-change path

#### Scenario: The automatic alert is suppressed
- **WHEN** the app reads the photo library under a limited grant in its steady state
- **THEN** iOS's automatic "Select More Photos / Keep Current Selection" alert is not presented by the
  app's reads (the Info.plist key is present in the built bundle)

### Requirement: Upload under limited uses the app-driven mechanism on every OS version

A `LIMITED` membership's uploads SHALL run on the app-driven `URLSession` mechanism (capability
`ios-url-session-upload`) regardless of OS version. On iOS ≥26.1 this is forced by measurement: the
OS never invokes the PhotoKit background-upload extension while the app holds `.limited` (capability
`ios-photokit-upload` records the constraint), so the arm starts the app-driven producer under
`LIMITED` and the PhotoKit producer under `GRANTED` (capability `upload-lifecycle` owns the
exactly-one-started invariant).

#### Scenario: A limited member on iOS ≥26.1 uploads via the app-driven tier
- **WHEN** a member on iOS ≥26.1 holds a `LIMITED` grant with upload-inclusive direction and selects an
  in-scope photo
- **THEN** the upload completes through the app-driven `URLSession` mechanism without any PhotoKit
  extension invocation

### Requirement: A downgrade to limited narrows the visible set without breaking sync

The app SHALL treat a grant change from `GRANTED` to `LIMITED` as an ordinary scope change. On that
transition, previously-imported foreign assets are no longer visible to the app (iOS auto-adds
app-created assets to the selection only at creation time — measured), and the own-photo scope narrows
to the selection: already-uploaded photos remain in the event (upload is a publish), the ledger keeps
its history, and the status total re-derives from the new scope. Nothing SHALL re-upload, and nothing
SHALL treat the narrowed visibility as an error.

#### Scenario: Downgrading does not disturb uploaded work
- **WHEN** a member who uploaded photos under a full grant switches to limited with a selection that
  excludes some of them
- **THEN** the event retains every uploaded photo, no re-upload occurs, and the status reflects the new
  selection-defined total
