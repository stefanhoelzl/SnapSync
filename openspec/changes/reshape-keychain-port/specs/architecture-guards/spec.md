# architecture-guards — delta for reshape-keychain-port

> **Ordering:** the *platform-identifier gate* requirement below is introduced by
> `enforce-port-boundary` and amended by `retire-legacy-config-fallback`, both implemented and
> committed but **not yet synced**. Sync those two first, in that order, then this one; the
> `MODIFIED` here is built on the `retire-legacy-config-fallback` text. Out of order it fails loudly
> ("MODIFIED failed … not found") rather than silently.

## MODIFIED Requirements

### Requirement: The platform-identifier gate
The build SHALL fail when an Apple identifier appears in the **code** of `:domain`'s `model/`,
`ports/` or `feature/` zones. Comments and KDoc are **exempt**, and that exemption is what gives the
gate its signal: measured when the gate was introduced, scanning those zones including comments
flagged 48 files while scanning with comments stripped flagged 5 — and all 5 were genuine. Three
have since been paid off (below), leaving a baseline of 2. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. `CompositionMode`'s tier
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload tiers the pure resolver
  selects, not platform APIs the core calls, and a second tier is a new member rather than a new
  coupling.
- **deferred** — a real violation of the port law, left standing deliberately, which SHALL carry an
  expiry trigger. Today there is exactly one: `ports/OsReceipt.kt`'s
  `ReceiptDeadlines.URL_SESSION_EVENTS`, a naming slip whose two sibling deadlines are already
  neutral; it expires with the iOS 18–26.0 app-driven tier.

Both `Keychain`-token entries in that list have now been discharged, by two different routes, and
recording which is the point of the split:

- `ports/ConfigPorts.kt` was discharged **incidentally** — the Stage-2 change deleted
  `configReadFrom`, the file's only `KeychainRead`-typed function, with the legacy fallback it
  served (capability `event-rejoin-reconciliation`), well before the family's reshape.
- `ports/Keychain.kt` and `feature/album/AlbumMapMigration.kt` were discharged **by the expiry
  trigger they were filed under**: the port was renamed for its need (`SecureStore`), its `OSStatus`
  and accessibility-class vocabulary moved into the iOS adapter, and the feature took the neutral
  read type.

A deferred pin may therefore be discharged by whatever removes the code; the expiry trigger is a
floor, not a schedule.

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
