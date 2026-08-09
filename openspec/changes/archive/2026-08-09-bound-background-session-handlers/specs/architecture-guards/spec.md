## ADDED Requirements

### Requirement: OS completion handlers are held in one type

Holding an OS-supplied completion handler SHALL be confined to the single `:domain` `ports/` type that
bounds the hold and releases every outstanding handler (`BackgroundEventsReceipts`, capability
`ios-app-shell`). No other production source SHALL declare a **mutable** property whose type is a
nullary `Unit`-returning function — `var x: (() -> Unit)?`, `var x: () -> Unit`, or the `lateinit`
form — whether by import or by fully-qualified reference.

The rule **confines rather than forbids**, because storing the handler is the platform's own documented
recipe (*"You should then store that completion handler before creating a background configuration
object"*). What is unsafe is not the storing but the shape a bare field forces: a single slot has no
deadline, and a second handover silently overwrites the first, which costs the app its future background
wakes. Naming one home makes both properties provable in one tested place, in the same shape Keychain
access is confined to one module. Any exempt declaration SHALL state, at the exemption, why it is
exempt.

The guard SHALL fail when it reads no files, so a moved directory or a regex that stops matching fails
loudly instead of passing empty. A guard of this kind has already failed silently once: a prior version
matched on field **names** containing `ompletion`/`nComplete`, which passed a field of the exact
forbidden type — `IosUrlSessionUploadPlatform.onBackgroundEventsFinished` — in a directory it was
scanning. Matching the **type** is what makes the rule mean anything, and it is why that adapter's
callback slot becomes a constructor `val`: an allowlist for a field that is not an OS handler at all
would invite the next one.

The rule SHALL cover **both languages of the shell**. Apple's recipe stores the handler on the
`UIApplicationDelegate`, so a Swift property holding a `(() -> Void)?` is the likeliest reintroduction, and
a Kotlin-only rule would never see it.

**The rule's residue SHALL be stated where the rule is written**, not implied away. It catches *storing*,
not *releasing early*: an entry point that invokes its raw handler inline stores nothing and passes. It
does not match non-nullary or non-`Unit` handler shapes, a handler held inside a collection, or one behind
a type alias. It reads raw source text, so it also matches the shape inside a comment — prose must
describe such a declaration rather than quote it. Where a missed case appears, the rule SHALL be widened
rather than an exception added.

#### Scenario: A stored handler outside the owning type fails the build

- **WHEN** any production source other than the owning `ports/` type declares
  `var handler: (() -> Unit)? = null` or an equivalent mutable nullary-`Unit` function property
- **THEN** the guard fails the build

#### Scenario: A non-null or lateinit store is caught too

- **WHEN** the declaration avoids nullability — `lateinit var handler: () -> Unit` — to hold the same
  value
- **THEN** the guard still fails the build

#### Scenario: The owning type is exempt, with its reason recorded

- **WHEN** the owning `ports/` type holds handlers itself
- **THEN** the guard passes, and its allowlist entry states why that type is the one permitted holder

#### Scenario: A constructor parameter is not a stored handler

- **WHEN** a type takes its release action as a `val` constructor parameter, as the OS receipt does
- **THEN** the guard passes, because an immutable parameter can be neither overwritten nor left unbounded

#### Scenario: A handler stored in the Swift shell is caught

- **WHEN** a Swift shell property holds the completion handler, as Apple's own sample does
- **THEN** the guard fails the build, in that language

#### Scenario: The guard fails when it scans nothing

- **WHEN** the scanned roots match no files
- **THEN** the guard fails rather than reporting no violations

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

- **accepted** — a judgement the owner stands behind, with no expiry. `CompositionMode`'s tier
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload tiers the pure resolver
  selects, not platform APIs the core calls, and a second tier is a new member rather than a new
  coupling.
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

### Requirement: The OS-receipt expiry line is pinned
The diagnostic line emitted when an OS-handler receipt is released on its deadline SHALL be pinned by a
guard, in the same manner as other cross-boundary literals.

The line is emitted on deadline-expiry paths and on no others, which makes its presence the only
authoritative answer to whether the app released a handler because its work finished or because the bound
fired. Any consumer reading it therefore treats **absence** as "the work finished" — so rewording the line
turns every consumer green while hiding exactly the class of defect it was watching for. The failure is
silent and in the dangerous direction.

**The pin SHALL cover the SET of emitters, derived from the source and compared in both directions**, not
one named file. More than one receipt type may bound a hold — a receipt that bounds work in flight, and one
that bounds a wait for a signal that may never arrive — and each reports a genuine expiry. Pinning a single
file leaves every other emitter rewordable with the guard still green, which is the same silent failure one
level down. Each declared emitter SHALL state which expiry it reports, and SHALL emit the line exactly once.

#### Scenario: The expiry line is reworded
- **WHEN** the text of any declared emitter's deadline-expiry log line changes
- **THEN** the pin guard fails, naming the consumers that read it as ground truth

#### Scenario: An undeclared emitter appears
- **WHEN** production source emits the pinned line from a file the inventory does not name
- **THEN** the guard fails until that emitter is declared with the expiry it reports, because an emitter
  nobody pinned can be reworded without any guard noticing

#### Scenario: The expiry line is emitted on a non-expiry path
- **WHEN** a code path that is not a deadline expiry emits the same line
- **THEN** that is a defect: absence of the line must remain equivalent to "the handler was released
  because the work completed"
