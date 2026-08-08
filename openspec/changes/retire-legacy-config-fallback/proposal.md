## Why

The config-storage migration (step 11a, `74d2b848`) made an App-Group file the storage of record for
the membership and left a **read-only legacy-Keychain fallback** behind it: a definitively-missing
file consults the pre-11a Keychain item, resurrects a found membership into the file, and only
file-missing **and** item-absent reads as "this device left the event". That was deliberate and
temporary. The migration branch reached `main` — and therefore the whole installed base — as **one
merge**, so at update time every joined production device was a pre-11a device whose config file had
never existed; deleting the fallback in the same merge would have read every joined device as left,
a silent fleet-wide logout. `event-rejoin-reconciliation` records that as **Stage 1** and names its
own successor: *"a follow-up change SHALL delete the read-only fallback (`KeychainConfigReader`) and
retire the config pair's runtime-identity pin … That change SHALL carry its own delta to this
requirement, collapsing the staging."*

This is that change. It fires the Stage-2 gate on a **distribution argument** rather than on
telemetry (`design.md` D1 records the evidence and verifies its git facts): the fallback shipped in
`74d2b848` on 2026-07-18, both it and the migration finale `94f0bfe5` are ancestors of the `v0.1`
tag, and `v0.1` (2026-07-21) is SnapSync's **first App Store release**. No public user can therefore
be pre-11a.

Stage 1 is not free to keep. While the fallback lives, a **reinstall resurrects a membership** the
user deleted the app to be rid of; a *switch* leaves a stale legacy item behind, so the resurrected
membership can be the **previous** event rather than the current one; every leave has to delete a
Keychain item it otherwise never touches; and `:test:architecture` pins a runtime-identity seat that
production code exists only to service.

## What Changes

- **`KeychainConfigReader` is deleted** — the whole legacy seat: the read, the accessibility-class
  repair, and the leave-path `deleteLegacyItem()`. No production code addresses the
  `app.snapsync.config`/`eventconfig` Keychain item any more.
- **`ConfigFileRead.Missing` becomes definitive.** `configReadViaFile` loses its `fallback`,
  `migrate` and `repair` parameters and collapses to the file's own three outcomes: content decodes
  → `Joined`; missing → `None`, consulting nothing; anything else → `Unavailable`.
- **`ports/ConfigPorts.configReadFrom(read: KeychainRead, …)` is deleted.** It mapped a raw Keychain
  read onto a `ConfigRead` and had exactly one production caller — `KeychainConfigReader`. With that
  gone it is dead code, and its `commonTest` file (`ConfigReadTest`) goes with it; every branch it
  covered has a live counterpart in `ConfigFileReadTest`.
- **The leave path stops deleting the legacy item.** `FileBackedConfigStore.clear()` becomes
  file-only. The Keychain-first ordering existed *solely* so the fallback could not resurrect a
  completed leave; with no fallback there is nothing to resurrect.
- **The config pair's runtime-identity pin is retired** — both the `(app.snapsync.config,
  eventconfig)` entry in the pinned Keychain-pairs inventory and its entry in the
  unscoped-Keychain-seat inventory. Neither can hold: the pairs pin asserts the literal appears
  **exactly once** in production Kotlin, and after this change it appears **nowhere**.
- **BREAKING (user-visible, by design): reinstall now means the device left the event.** Deleting and
  reinstalling the app wipes the App Group, so the first cycle reads definitively-not-joined, runs
  the leave-side reconciliation, uploads nothing, and rejoining requires re-scanning the invite. This
  is the decided end state, not a regression (decision record:
  `changes/archive/2026-07-18-migrate-config-to-app-group-file` D5).
- **A safety net disappears, and this change is where that gets written down.** Today a *wrong*
  `Missing` is caught: the fallback finds the legacy item, answers `Joined`, and the device stays
  joined. Afterwards a wrong `Missing` is an **uncaught logout**, which makes
  `isConfigFileAbsence` — the `NSError` domain/code classifier in
  `:adapter:ios:ext-safe` — **solely load-bearing** for the leave decision. Its logic is
  deliberately **unchanged**; what changes is that the spec and the code now say what rests on it.

## Capabilities

### New Capabilities

<!-- none: this change deletes a staged migration remnant and collapses the staging it was recorded under -->

### Modified Capabilities

- `event-rejoin-reconciliation`: the staging requirement collapses. *Reinstall semantics stay staged
  until a post-ship change deletes the read fallback* is **renamed** to *Reinstall means the device
  left the event* and rewritten to the end state — Stage 1 / Stage 2 become history rather than
  contract, the Stage-1 scenarios (the pre-11a update path, the Stage-1 resurrection) are replaced by
  the end-state ones, and the requirement additionally records that `isConfigFileAbsence` is now the
  sole thing standing between a misclassified read error and an unintended leave.
- `event-link`: two requirements. *iOS file-backed config store* loses the read fallback, the
  Keychain-first `clear` ordering, and the accepted Stage-1 divergence (a switch's stale legacy item
  resurrecting the previous membership) — all three die together. *An unreadable config is not an
  absent config* loses the fallback clause from its definition of **definitely absent**: it becomes
  the not-found error class on the file and nothing else.
- `architecture-guards`: two requirements. *Runtime identity is pinned* loses the
  `(app.snapsync.config, eventconfig)` pair from the pinned inventory **and** from the unscoped-seat
  inventory, per the requirement's own rule that adding, removing, or re-valuing a pin is a spec
  delta. *The platform-identifier gate* loses `ports/ConfigPorts.kt` from its **deferred** pin list:
  deleting `configReadFrom` removed that file's only `KeychainRead`-typed function, so the `Keychain`
  token is gone from its code and the exact-in-both-directions pin went stale — the gate said so by
  failing. A deferred pin discharged early by unrelated deletion is worth recording, since the
  requirement files it under the Keychain family's reshape, which has not happened.
  ⚠️ **Ordering:** that second requirement is introduced by the `enforce-port-boundary` change,
  implemented and committed but **not yet synced**. Sync it before this change, or the `MODIFIED`
  fails loudly ("MODIFIED failed … not found").

## Impact

- **`:domain`** — `ports/ConfigPorts.kt`: `configReadFrom` deleted; `configReadViaFile` loses three
  parameters; the `ConfigFileRead`/`ConfigRead` docs stop describing a fallback (and stop pointing
  `isConfigFileAbsence` at `model/`, which it left in `ce1f75c3`).
- **`:adapter:ios:ext-safe`** — `KeychainConfigReader.kt` deleted; `FileBackedConfigStore` loses its
  constructor dependency and its `clear()` Keychain step; `ConfigFileAbsence.kt` gains the
  now-solely-load-bearing note (documentation only — **no logic change**).
- **`:domain` tests** — `ConfigReadTest` deleted; `ConfigFileReadTest` loses its five fallback cases
  and keeps the file-side ones, with `Missing → None` restated as definitive.
- **`:test:architecture`** — `RuntimeIdentityTest` drops the config pair from `keychainPairs` and
  from `unscopedKeychainSeats`; `PlatformIdentifierTest` drops `ports/ConfigPorts.kt` from its
  `deferred` pins (the gate failed on the stale pin, which is what it is for);
  `ConstructorBlockingTest`'s grandfathering note stops citing a Keychain fallback that no longer
  exists.
- **Docs** — root `CLAUDE.md` and `app/ios/CLAUDE.md` describe the fallback as live; both are
  corrected.
- **No backend, protocol, or storage-format change.** The envelope, the file path, the App Group,
  and every wire contract are untouched.
- **Left behind on purpose:** the orphaned legacy Keychain item on already-migrated devices. Purging
  it would require keeping the seat, the pin, and a Keychain write alive to delete something nothing
  reads (`design.md` D3).
