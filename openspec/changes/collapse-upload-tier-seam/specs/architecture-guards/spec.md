## MODIFIED Requirements

### Requirement: The upload producers are never both started

A `:test:architecture` guard SHALL pin the invariants of `upload-lifecycle` that the compiler cannot,
at the two places the risk lives once exclusion is structural again:

- **The resolver's cells.** The guard SHALL drive the pure mechanism resolution over **every**
  combination of OS facts, permission, and override, asserting that no combination yields a mechanism the
  OS cannot run. This is the sharper risk: a wrong cell yields the OS-driven kind below iOS 26.1, where
  its registration selector does not exist, and the process aborts.
- **The orchestrator's transitions.** The guard SHALL drive the orchestrator over fake producers through
  every transition row of the lifecycle table — provision under each permission, the `GRANTED` ↔
  `LIMITED` flips in both directions, grant-with-no-membership, and leave — asserting after each step
  that its held producer is the one resolution yielded, that every change of kind observed
  stop-before-start, and that no transition leaves a producer started.

The guard SHALL NOT be retired on the grounds that "both started" became a compile error again.
Exclusion moving back to the compiler removed one failure mode and introduced two others: an unrunnable
resolved kind, and sequence bugs in an orchestrator that now holds mutable state where it previously held
none. The guard follows the risk rather than the original wording.

#### Scenario: No transition sequence leaves the wrong producer held or started
- **WHEN** the guard drives the orchestrator through every lifecycle transition row, in sequence and in
  permission-flip combinations
- **THEN** the held producer always matches the resolved kind, no transition leaves a producer started
  that should not be, and the build fails if any sequence violates this

#### Scenario: A resolver cell that cannot run on its OS fails the build
- **WHEN** any combination of OS facts, permission and override resolves to a mechanism whose platform
  API does not exist on that OS
- **THEN** the guard fails the build


#### Scenario: A switch that starts before stopping fails the build
- **WHEN** an orchestrator change makes a resolution change start the incoming producer before the
  outgoing producer's stop completes
- **THEN** the guard fails the build

### Requirement: The platform-identifier gate
The build SHALL fail when an Apple identifier appears in the **code** of `:domain`'s `model/`,
`ports/` or `feature/` zones. Comments and KDoc are **exempt**, and that exemption is what gives the
gate its signal: measured when the gate was introduced, scanning those zones including comments
flagged 48 files while scanning with comments stripped flagged 5 — and all 5 were genuine. Four
have since been paid off (below), leaving a baseline of 1. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. the upload **mechanism kind**'s
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload mechanisms the pure resolver
  yields, not platform APIs the core calls, and a third mechanism is a new member rather than a new
  coupling. (These members were `UploadTier`'s until mechanism resolution absorbed `resolveComposition`;
  the pin follows them to the kind — the judgement is unchanged, only the type carrying it.)
- **deferred** — a real violation of the port law, left standing deliberately, which SHALL carry an
  expiry trigger. Today there are **none**. The list being empty is a state to hold, not a gap to
  fill: a deferred pin is a receipt with an expiry, and it stops being one once the expiry is
  fiction.

The discharged entries went by three different routes, and recording which is the point of the split:

- `ports/ConfigPorts.kt` was discharged **incidentally** — the Stage-2 change deleted
  `configReadFrom`, the file's only `KeychainRead`-typed function, with the legacy fallback it
  served (capability `event-rejoin-reconciliation`), well before the family's reshape.
- `ports/Keychain.kt` and `feature/album/AlbumMapMigration.kt` were discharged **by the expiry
  trigger they were filed under**: the port was renamed for its need (`SecureStore`), its `OSStatus`
  and accessibility-class vocabulary moved into the iOS adapter, and the feature took the neutral
  read type.

- `ports/OsReceipt.kt`'s `ReceiptDeadlines.URL_SESSION_EVENTS` was discharged because its expiry
  trigger was **invalidated rather than reached**. It was filed to expire "with the iOS 18–26.0
  app-driven tier"; giving the download session the same handler budget put the constant in service of
  a session that exists on every iOS version, so the debt would have outlived the tier it was charged
  against. It was renamed for its need (`BACKGROUND_EVENTS`) instead of re-filed under a weaker expiry.

A deferred pin may therefore be discharged by whatever removes the code; the expiry trigger is a
floor, not a schedule. A pin whose expiry has become false SHALL be repaid or re-argued, never
silently re-filed — an expiry that cannot arrive makes the pin permanent while still reading as debt.

The scanned vocabulary SHALL keep the `Keychain` token even though no pin now names it. Its original
purpose is served — because the pin list is exact in both directions, retaining the token is what
made the reshape unable to land without deleting those pins, and what made the `ConfigPorts`
discharge visible the moment the code went. Its remaining purpose is ordinary: a port or feature that
reintroduces the token SHALL fail the gate rather than arrive unpinned.

**What it does not cover, stated so a green run is not over-read:** the gate is lexical. A decoder
over another system's values written in bare integers — a `when` over `0L`, `1L`, `2L` that is in
fact a `UIApplicationState` table — is indistinguishable from arithmetic and SHALL NOT be assumed
caught. The gate's hits are therefore not ranked by risk: it fires on named constants, which are the
safer kind, and is silent on unnamespaced integer tables, which are the kind that can return a wrong
answer to a second platform rather than a safe default. It is likewise blind to a platform encoding
carried in a neutral type — an `Int` that is really an `OSStatus`, or a `String` that is really an
accessibility class — which is how `ports/Keychain.kt`'s pin understated what that file actually
owed.

#### Scenario: An Apple constant is introduced into a platform-free zone
- **WHEN** an `NS*`, `PH*`, `kSec*`, `UI*`, `AV*` identifier or an Apple product name appears
  outside a comment in `model/`, `ports/` or `feature/`
- **THEN** the gate fails, naming the file and the token

#### Scenario: A documented binding note is written
- **WHEN** a KDoc records how a neutral type is bound on iOS (for example, that an opaque payload is
  a `PHAssetResource` there, or that a legacy item physically lived in the Keychain)
- **THEN** the gate does not fire, because comments are exempt by design

#### Scenario: A pinned exception is removed from the code
- **WHEN** a pinned Apple identifier is deleted or moved into an adapter
- **THEN** the gate fails until its pin is removed, so the pin list cannot describe absent code

#### Scenario: A deferred pin's code is deleted before its expiry trigger fires
- **WHEN** unrelated work removes the code a deferred pin describes — as the Stage-2 fallback
  deletion removed `ports/ConfigPorts.kt`'s `KeychainRead` use ahead of the port family's reshape
- **THEN** the gate fails on the stale pin, and the pin is deleted with that work rather than
  waiting for the trigger it was filed under

#### Scenario: A deferred pin's expiry trigger fires
- **WHEN** the reshape a deferred pin named as its expiry lands, and the token leaves the code
- **THEN** the gate fails on every pin that reshape cleared, and each is deleted in the same commit,
  so the receipt and the debt end together

#### Scenario: A retired token is reintroduced
- **WHEN** a platform token that no pin names any more reappears in the code of a scanned zone
- **THEN** the gate fails, because the vocabulary is not narrowed when a pin is discharged

#### Scenario: Deferred debt is filed as accepted
- **WHEN** a pin is added for an identifier the owner intends to remove later
- **THEN** it belongs in the deferred list with an expiry trigger, not in the accepted list, so the
  pin inventory never reads as if the law had no outstanding violations
