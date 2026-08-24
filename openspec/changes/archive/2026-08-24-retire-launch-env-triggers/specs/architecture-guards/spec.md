## REMOVED Requirements

### Requirement: The launch-trigger index agrees with production source

**Reason**: The index and the triggers it indexed are both gone. The requirement held a duplicate
loud-when-stale — the `ios-device` skill's operator table against the `SNAPSYNC_*` literals in production
Kotlin — and with production Kotlin declaring none, there is nothing on one side to hold the other to. Its
non-vacuity floor (`>= 5` literals) is the exact negation of the invariant this change establishes, so it
cannot be retuned; it has to be replaced.

The stated gap it recorded — that `SNAPSYNC_SEED_PHOTOS`, `SNAPSYNC_SEED_POLICY`, `SNAPSYNC_WIPE_GALLERY`
and `SNAPSYNC_POLICY_PROBE` shipped in production Kotlin and appeared in no spec — closes with them: those
four are now channel commands, and the two that hold behavior are documented in the `rig-channel` runbook.

**Migration**: Replaced by "Production Kotlin declares no launch triggers" below, which asserts absence
rather than agreement. Operator documentation for the channel's commands lives in the `rig-channel` skill
and is not held to source by name, because the channel's own coverage guard already derives its
`/os` and `/user` populations from source and the `/device` set is not derivable from any population.

## ADDED Requirements

### Requirement: Production Kotlin declares no launch triggers

A test-only JVM guard SHALL assert that production Kotlin source declares **no** `"SNAPSYNC_*"` string
literal at all.

Dev/test control of a device is the control channel's surface (`:test:rig`), contained at compile time and
absent from every production build. A `SNAPSYNC_*` literal in production Kotlin is therefore a regression to
a surface this repo removed deliberately: a remote-control affordance present in every shipped binary, inert
only because a SpringBoard launch supplies no process environment — which is a property of how the app is
*started*, not of what it *contains*.

The guard SHALL be an **exact inventory** whose permitted set is empty, and the failure SHALL name every
literal found together with its file. It SHALL NOT be expressed as a maximum count: a count invites being
raised, and the previous guard's floor is what this requirement replaces.

Two readers are deliberately **out of scope**, both for the same reason — the file reading them does not
exist in a production build, so their inertness is a property of the module graph rather than a runtime
check:

- `SNAPSYNC_RIG_PORT`, read in the source `:test:rig` contributes into the shell under its build property;
- the forge target's state selector, read in the forge module's own source.

The scan SHALL therefore cover the production main source sets under `domain/`, `app/`, `adapter/` and
`ui/`, excluding test sources, `build/`, and the build-property-gated trees.

The guard SHALL fail loudly rather than vacuously: an empty *result* over a non-empty *scan* is the passing
condition, and a scan that resolves zero Kotlin files SHALL fail rather than pass while inspecting nothing.

#### Scenario: A launch trigger is re-added to production Kotlin

- **WHEN** a production main source set gains a `"SNAPSYNC_*"` literal
- **THEN** the guard fails, naming the literal and its file, so the trigger must be argued rather than
  landing unnoticed

#### Scenario: A gated tree may read one

- **WHEN** the source `:test:rig` contributes into the shell reads `SNAPSYNC_RIG_PORT`, or the forge module
  reads its state selector
- **THEN** the guard passes, because neither file is on a production build's compile path

#### Scenario: The guard is not vacuous

- **WHEN** the scanned roots are absent, renamed, or resolve to zero Kotlin files
- **THEN** the guard fails rather than passing while inspecting nothing

## MODIFIED Requirements

### Requirement: The platform-identifier gate
The build SHALL fail when an Apple identifier appears in the **code** of `:domain`'s `model/`,
`ports/` or `feature/` zones. Comments and KDoc are **exempt**, and that exemption is what gives the
gate its signal: measured when the gate was introduced, scanning those zones including comments
flagged 48 files while scanning with comments stripped flagged 5 — and all 5 were genuine. Four
have since been paid off (below), leaving a baseline of 1. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. `UploadTier`'s members
  (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload tiers the pure resolver
  `resolveComposition` returns, not platform APIs the core calls, and a second tier is a new member
  rather than a new coupling.
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
