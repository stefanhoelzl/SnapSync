## Context

`enforce-port-boundary` armed a gate for the port law's second violation class and then pinned a
violation of it as deferred debt, with reasons (D6): the `Keychain` port family is named for Apple
technology and carries an `OSStatus` and an accessibility class into `:domain`, but reshaping it
touches `KeychainDeviceIdentity`, and that class writes a value **once, ever**. There is no
migration, no rewrite and no support path: a wrong access group, service or account addresses a
*different real item*, every read still succeeds, and the device silently acquires a second identity
— which orphans its `/files/devices/<deviceId>/` partition and makes every photo it already uploaded
read back as another member's, one duplicate per photo in its owner's own library. That shipped on
2026-07-20 and ran for nine hours with nothing anywhere logging it.

D6 named the condition for lifting the deferral: *"should land after those three values are pinned
explicitly. They were implicit when the 2026-07-20 incident happened; pinning them is the durable fix
independent of any rename."* Commit `2b5eb54f` did that — `KeychainDeviceIdentityTest` asserts the
device-id item's service, account and shared group and the legacy view's unscoped address;
`IosKeychainTest` asserts the raw attribute keys and that every written item carries
`kSecAttrAccessibleAfterFirstUnlock`; `KeychainAttestStoreTest` covers the token/keyId seats. The
reshape is now checkable rather than merely careful, which is the whole reason it is happening now
and not in July.

The port family as it stands: `interface Keychain` (`read`/`write`/`migrateAccessibility`/`delete`),
`KeychainRead {Found(value, accessibility: String?) | Absent | Unavailable(status: Int)}`,
`KeychainUnavailable(status: Int)`, `KeychainResolution`, `DeviceIdentityAbsent`, and three pure
functions — `resolveOrMint`, `readExisting`, `needsMigration` — that carry the mint-once-then-read
policy. Consumers: `IosKeychain` (the only implementation), `KeychainDeviceIdentity`,
`KeychainAttestStore`, `IosAlbumMapStore`, `feature/album/AlbumMapMigration`, `compose/UploadCore`,
and `ports/AttestSeams` in prose.

## Goals / Non-Goals

**Goals:**

- Name the port for the need, so the name survives a second platform — the law's own test.
- Get the two platform encodings (`OSStatus`, accessibility class) out of `:domain` and into the
  adapter that already holds them, per `enforce-port-boundary` D3 ("translations move outward, to the
  adapter that owns their inputs").
- Keep the three-state read, which is the reason the seam exists at all.
- Discharge the two deferred `PlatformIdentifierTest` pins this clears, in the same commit, because
  the pin list is exact in both directions.

**Non-Goals:**

- **Any change to what is stored or where.** The access group, service, account, accessibility class,
  query shape and value bytes are out of scope in the strongest sense: this change is only allowed to
  be correct if they are byte-identical afterwards (D1).
- Re-litigating the mint-once-then-read *policy*. `resolveOrMint`'s ordering, the adoption branch and
  the read-only role are unchanged; only their types and the dropped parameter move.
- `ports/OsReceipt.kt`'s `URL_SESSION_EVENTS` pin, whose expiry is the iOS 18–26.0 tier, not this.
- Renaming the adapter classes. `IosKeychain`, `KeychainDeviceIdentity`, `KeychainAttestStore` are
  **correctly** named: the law says adapters are named for the technology that satisfies the need.
  `RuntimeIdentityTest` also classifies seats by matching `IosKeychain(` construction sites, so the
  adapter's name is load-bearing for a guard.

## Decisions

### D1 — Value preservation is a construction, not a review promise

The reshape touches the interface's **name** and its **error and protection types**. It touches no
argument of any `SecItem*` query. Mechanically:

- `IosKeychain.baseQuery()` — the `(class, service, account, accessGroup)` dictionary — is not edited.
- `IosKeychain.writtenAttributes()` — the single source both `write` and the migration build from —
  is not edited; it still returns `kSecAttrAccessible → ACCESSIBLE_AFTER_FIRST_UNLOCK`.
- `ACCESSIBLE_AFTER_FIRST_UNLOCK`, `SHARED_KEYCHAIN_ACCESS_GROUP` and `deviceIdItem`'s
  `service`/`account` literals are not edited.
- The comparison that decides migration is preserved exactly. It was `needsMigration(current,
  required) = current != required` with `required = ACCESSIBLE_AFTER_FIRST_UNLOCK`; it becomes an
  adapter-side classification against the *same* constant, followed by `protection !=
  BACKGROUND_READABLE`. The truth table is identical for all three inputs (matching class, other
  class, unreported).

The oracle is the test suite `2b5eb54f` added: `KeychainDeviceIdentityTest`'s address assertions,
`IosKeychainTest`'s attribute-key and written-attribute assertions, and the
`SHARED_KEYCHAIN_ACCESS_GROUP` equality. **None of those assertions is edited by this change** — if
one had to be, that would be the signal a stored value had moved, and the correct response is to stop
rather than to update the expectation. `RuntimeIdentityTest` and the entitlements cross-check remain
green untouched for the same reason.

### D2 — The name is `SecureStore`

The need, stated without naming a platform: *one addressed place to keep one small value, so that it
is confidential at rest, outlives the app install, and is readable while the device is locked.* All
four consumers want exactly that — the device id, the attest token, the attest `keyId`, and (for one
more migration) the legacy album map.

`SecureStore` passes the law's test: it stays correct if a second platform ships, where the same need
is met by Android Keystore-wrapped storage rather than by `SecItem*`. It is also an established
cross-platform name for precisely this shape, which is a small but real argument — a reader arriving
from another ecosystem recognises it, and the name is therefore unlikely to drift back toward the
technology.

**Alternatives considered.** *`SecretStore`* — accurate for the token, wrong for the device id and
the album map, neither of which is a secret; the shared property is protection and durability, not
secrecy of the contents. *`ProtectedValue` / `DurableSecret`* — describe the item, not the seam, and
neither reads naturally at a call site (`tokenItem: SecureStore` does). *Keeping `Keychain` and
merely deleting the `OSStatus`* — leaves the violation the change exists to fix; the name is the
part the law is actually about.

An instance addresses **one** value, not a keyspace, and the KDoc says so; that is the same shape
`ConfigStore` already has.

### D3 — `Unavailable` carries an opaque `detail: String`, not a code

`status: Int` is an `OSStatus`. Replacing it with a neutral *code* (an `Int` "error number", or a
sealed set of causes) was rejected in both directions:

- A neutral `Int` is the same leak with a euphemism: the only values it could carry are Apple's, and
  the first consumer to branch on `-25308` re-imports the platform silently.
- A sealed cause enum (`LOCKED` / `OTHER`) claims a classification the adapter cannot honestly make.
  `errSecInteractionNotAllowed` is the common case, not the only one, and the code's own comment
  already says every non-`errSecItemNotFound` status means one thing: *"I could not look."*

So the port carries a **diagnostic string the adapter formats** (`"OSStatus -25308"`), and nothing in
the core branches on it — it is interpolated into one log line and one exception message. Making it a
`String` rather than a number is the enforcement: a string is not switchable, so the "never
classify" rule is structural rather than a comment. Losing the ability to branch costs nothing,
because no caller ever did: the three-state `SecureStoreRead` — not the status — is what every
decision reads.

This is deliberately **not** the shape `ConfigFileRead.Failed(status, detail)` uses. That seam keeps
a code because it carries two synthetic sentinels (`CONFIG_FILE_FOREIGN_STATUS`,
`CONFIG_FILE_UNUSABLE_STATUS`) that the core itself mints and the device log distinguishes. This one
mints nothing.

### D4 — The accessibility class becomes a three-member `StoredProtection`, and the required class stops crossing the port

Today the core receives the raw class string and is handed the *required* class as a parameter, then
compares them. Both halves are the adapter's business: which class satisfies "readable by a
background wake" is a fact about the platform, and the string itself is `kSecAttrAccessible`'s value
in disguise.

`SecureStoreRead.Found` therefore carries:

```
enum class StoredProtection { BACKGROUND_READABLE, RESTRICTED, UNREPORTED }
```

and `resolveOrMint` / `readExisting` / `needsMigration` lose their `requiredAccessibility: String`
parameter. The core's remaining question — *"must this item be upgraded in place?"* — stays in
`:domain`, pure and covered on both targets, because it is a rule about the resolution order (upgrade
**before** returning, never delete-and-re-add, never mint) rather than a translation.

**Three members, not a `Boolean?`.** `RESTRICTED` ("stored under some class that is not the required
one") and `UNREPORTED` ("the platform did not say") absorb into the same action — upgrade in place,
best-effort, value-preserving — but they are different facts, and "Absence is never silent" asks that
a collapse be deliberate rather than incidental. Keeping them apart costs one enum member and keeps
the log line honest about which happened.

**What `RESTRICTED` deliberately does not say** is *which* class. That detail was previously carried
into the core purely to be printed (`KeychainResolution.Found(accessibility, migrated)` → the device
log). To avoid losing a diagnostic while narrowing the type, `IosKeychain.read()` logs the observed
class itself when it is not the required one — once per legacy item per process, and never for a
healthy one. The information stays on the device where it is read; only the *type* narrows.

### D5 — `AlbumMapMigration` takes the neutral read type, unchanged

`albumMapSource(stored: String?, legacy: KeychainRead)` becomes
`albumMapSource(stored: String?, legacy: SecureStoreRead)`, and that is the whole edit. It can take
the neutral type because it never needed the platform one: it is a pure rule over a **three-state
read** (`Found` → migrate, `Unavailable` → retry, anything else → nothing anywhere), and the three
states are exactly what the neutral type preserves. Its `Retry` arm exists for the same reason the
port's `Unavailable` does — an unreadable legacy item must not be deleted and must not be read as an
empty map.

**Alternative considered:** give the feature its own `LegacyAlbumMapRead` type so `feature/` does not
name a port read at all. Rejected as a type for nothing: `feature/` may reference `ports/` (the zone
order is `model/ ← ports/ ← feature/`), the adapter would have to translate one three-state type into
an identical three-state type, and the translation is the exact shape D3 of `enforce-port-boundary`
argued against inventing.

The KDoc keeps saying "Keychain" where it describes *where the legacy map physically was* — that is a
binding note, which the identifier gate exempts by design and which a second implementer needs.

### D6 — `needsMigration`'s justification is re-grounded on the store's contract

The current KDoc argues: *"the Keychain **survives app uninstall** … so a device provisioned by an
older build would keep its locked-unreadable item forever."* That is an iOS-specific premise doing
load-bearing work in a platform-free zone. The neutral form is the port's own contract — **this store
outlives the install by definition** (it is why the device id lives here at all, capability
`device-identity`) — plus the fact that the value is written exactly once, at mint. Together those
still force the conclusion: nothing in the device's remaining lifetime would ever rewrite the item,
so the upgrade must happen on the read path or not at all. Same argument, grounded on the seam
instead of on Apple.

### D7 — The two deferred pins are deleted here, and the gate's own argument is closed out

`PlatformIdentifierTest`'s `deferred` map loses `ports/Keychain.kt` and
`feature/album/AlbumMapMigration.kt`; `ports/OsReceipt.kt` remains. The gate is exact in both
directions, so this is not optional — leaving either pin fails the build on "pinned but absent from
the code", which is precisely the mechanism `enforce-port-boundary` D2 installed to make the debt
inseparable from the work that removes it. It worked twice: once by accident (`ports/ConfigPorts.kt`,
discharged by the Stage-2 fallback deletion) and once as designed (here).

The `architecture-guards` requirement carries D2's argument for **keeping** the `Keychain` token in
the scanned vocabulary. The token stays in the scan — it must, or a reintroduction would be invisible
— but the argument's tense changes: it was a claim about a reshape that had not happened, and it
becomes a record of one that has. The requirement's `deferred` list drops to a single entry.

## Risks / Trade-offs

- **A rename touching the device-identity path could re-address the stored item** → the only defence
  that matters is that the query is not edited at all (D1), checked by an unmodified test suite that
  reads the address back from the query the adapter would issue. The change is verified on a real
  simulator, not inferred, because Linux cannot compile the Apple target that contains every one of
  those assertions.
- **`RESTRICTED` is less precise than the raw class string** → mitigated by logging the observed
  class adapter-side (D4), where it is read; the narrowing is in the type, not in the diagnostics.
- **`detail: String` cannot be programmatically classified** → intended (D3). No caller classified it
  before, and a string makes that structural. If a future caller genuinely needs a cause, the honest
  move is a named cause on the port, not re-exposing another platform's numbering.
- **Three stacked, unsynced `architecture-guards` deltas** (`enforce-port-boundary` →
  `retire-legacy-config-fallback` → this) → each restates the whole requirement built from its
  predecessor, and the ordering is stated at the top of the delta file, matching the convention the
  Stage-2 change already established. Syncing out of order fails loudly ("MODIFIED failed … not
  found") rather than silently.
- **The `module-architecture` citation is a one-line edit inside a very long requirement** → the
  delta restates the requirement whole, diffed against the current main spec, so nothing outside the
  intended line is dropped.

## Migration Plan

None. Nothing here writes durable state, changes an item address, or alters a wire format. Rollback
is a revert; a device that ran a build with this change and a device that did not are byte-identical
in every store they touch.

Ordering within the change is forced in one place only: the pins must be deleted in the same commit
as the code they describe, or the build is red in both directions.

## Open Questions

- **Should `ConfigRead.Unavailable(status: Int)` follow?** It carries the same shape for the config
  seam — an `NSError` code plus two core-minted sentinels. It is not touched here because the
  sentinels make it a genuinely different case (D3), but the two seams now disagree about how an
  unreadable store reports itself, and a future reader will notice.
- **Does `StoredProtection` belong on the port at all, or should the adapter heal silently?** The
  alternative is a self-healing `read()` that upgrades in place and never reports the class, removing
  the concept from `:domain` entirely. Rejected here because it makes a read perform a write behind
  the port and moves a normative ordering (`upgrade before return`, tested on both targets) into
  macOS-only code — but it is the shape to revisit if a second platform has no equivalent concept.
