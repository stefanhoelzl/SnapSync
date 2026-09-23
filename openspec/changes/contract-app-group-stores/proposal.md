## Why

The config file is the only record of whether this device has joined an event. A missing file IS a leave,
and `isConfigFileAbsence` alone decides "missing". Yet no test runs the real `FileBackedConfigStore`. Its
pieces are only tested separately: the classifier against named constants, and the read algorithm against
synthetic `ConfigFileRead` values. The same holds for the other stores in the App-Group container:
- **No honest fake:** config. `:test:world`'s double is an inline object that reports a *Keychain* status
  for a file.
- **Fakes never compared with their real adapter:** the device manifest, staged bytes, the device log
  and the album map.

Phase 3 (`changes/archive/2026-09-22-establish-port-contracts`) built the mechanism that closes this gap;
this change puts the App-Group-backed ports through it.

## What Changes

- **Five new port contracts in `:test:contracts`**, each a hand-written clause list with its state
  vocabulary:
  - `ConfigStoreContract`: one subject bundling `ConfigSource` + `ConfigStore` + `ConfigReader`, which one
    adapter implements and one fake will implement.
  - `DeviceManifestStoreContract`
  - `StagedBytesContract`
  - `DeviceLogSourceContract`
  - `AlbumMapStoreContract`: covers `AlbumMapStore` only. `AlbumManager`, in the same file, is PhotoKit and
    belongs to the PhotoKit phase.
- **Fake bindings** in `:adapter:generic:fake` `commonTest`, on `JVM` and `IOS_SIM_KEXE`.
- **Live bindings on `IOS_SIM_KEXE`**, placed beside each adapter:
  - `:adapter:ios:ext-safe` `iosTest`: config, manifest, device log and album map.
  - `:adapter:ios:app-only` `iosTest`: staged bytes. That module's tests gain a dependency on
    `:test:contracts`.
  - Readable states use an injected temporary directory. The unavailable-container state uses the **real**
    container lookup, which answers nil in the unentitled test binary.
- **A new honest fake, `InMemoryConfigStore`**, in `:adapter:generic:fake`. It implements the three config
  ports, with its state (the persisted config and a readable flag) given at construction. `:test:world`
  moves onto it; its `membershipUnreadable` lever becomes a world-side wrapper over the fake's readable
  cell, and stops reporting the Keychain status `-25308`.
- **Two adapters take their container location as a defaulted constructor parameter**, as
  `IosDeviceManifestStore` already does:
  - `FileBackedConfigStore`: a nullable path.
  - `IosStagedBytes`: a lazy provider, so the container is still resolved at first download and never at
    composition.

  Both shells omit the parameter, so production behaviour is unchanged.
- **Behaviour change: `FileBackedConfigStore.clear()` fails when the App-Group container cannot be
  resolved.** Today it returns quietly, and the in-memory value goes `null` while any file stays. That
  contradicts the adapter's own "the leave retries visibly rather than half-completing". `save` already
  fails in the same state. No user reaches it: a missing container means a build without the App-Group
  entitlement.
- **Fake correction: `InMemoryDeviceLogSource` answers `null` for an empty log**, as the real adapter does.
  Today it answers `""`, which a contract clause catches.
- **No new `Host` and no device recording.** Every state these contracts condition on is reachable in
  `IOS_SIM_KEXE`. The one state the leave decision was built against — the device locked since boot, its
  protected files unreadable — is reachable by no host: the rig cannot drive a process before first unlock,
  and the simulator enforces no file protection. So it stays a documented belief in `isConfigFileAbsence`'s
  KDoc, as `port-contracts` requires of such beliefs. It does not become a clause.
- Stale documentation fixed alongside:
  - CLAUDE.md's module map names an `IosDiscoveryStore` and a fake "discovery store", neither of which
    exists.
  - `harness-world-model` names a discovery store.
  - `leave-event` still cites the config store's "Keychain copy first" clear ordering, which the Stage-2
    change deleted.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `port-contracts`:
  - The host matrix gains the measured App-Group container column.
  - "Every clause runs against a real implementation on some host" states when a real adapter over an
    injected directory counts as a real implementation, and what it leaves uncovered.
- `event-link`: "iOS file-backed config store" states that the container location is an input with the
  shared container as default, and that `clear` fails, rather than succeeding silently, when the container
  cannot be resolved.
- `leave-event`: "Leave is best-effort with no rollback" drops the dead "Keychain copy first, file second"
  clear ordering. A failed-clear-leaves-the-user-joined guarantee now rests on `clear` failing whenever it
  cannot delete, the unresolvable container included.
- `harness-world-model`: "Real-stack composition helpers" names the world's config ports as the
  `:adapter:generic:fake` `InMemoryConfigStore` under a world lever, and drops the non-existent discovery
  store.

## Impact

- **Code:**
  - `:test:contracts` commonMain: 5 contracts plus their state enums.
  - `:adapter:generic:fake`: new `InMemoryConfigStore` and factory, the `InMemoryDeviceLogSource` fix, and
    5 fake bindings.
  - `:adapter:ios:ext-safe`: the `FileBackedConfigStore` parameter and `clear` change, plus 4 live bindings
    in `iosTest`.
  - `:adapter:ios:app-only`: the `IosStagedBytes` parameter, a test dependency on `:test:contracts`, and 1
    live binding.
  - `:test:world`: the config double is replaced by the fake plus a lever.
- **Gates:**
  - `ContractCoverageTest` must see every new clause reached by a `Live` binding on `IOS_SIM_KEXE`.
  - `InMemoryConfigStore` stays `internal`, behind port-typed factories, which is how fake honesty is
    enforced now.
  - `RuntimeIdentityTest`'s pinned literals do not move.
- **CI:** the new live bindings run in the existing `iosSimulatorArm64Test` job. No new job and no new
  recording file.
- **Not touched:** the `Host` enum, the rig, and every production composition root, whose default arguments
  are unchanged.
- **Label:** `internal`. The one behaviour change is reachable only by an unentitled build.
