## Context

Phase 3 (`changes/archive/2026-09-22-establish-port-contracts`) built the port-contract mechanism:
- hand-written clause lists in `:test:contracts`;
- bindings that enter a state at construction and declare their reach as a literal;
- explicit outcomes;
- a static gate (`ContractCoverageTest`) that fails any clause no real implementation reaches.

It carried `LedgerStore`, `DownloadStore` and `SecureStore` through it. This change takes the ports stored in
the shared App-Group container through the same loop.

The ports, verified against the tree:

| port | real adapter | module | fake today |
|---|---|---|---|
| `ConfigSource` + `ConfigStore` + `ConfigReader` | `FileBackedConfigStore` (file `eventconfig.json`) | `:adapter:ios:ext-safe` | none — `World.kt` holds an inline object |
| `DeviceManifestStore` | `IosDeviceManifestStore` (directory `device-manifest/`) | `:adapter:ios:ext-safe` | `InMemoryDeviceManifestStore` |
| `StagedBytes` | `IosStagedBytes` (`download-staging/`) | `:adapter:ios:app-only` | `InMemoryStagedBytes` |
| `DeviceLogSource` | `IosDeviceLogSource` (extension log in the App Group; app log in `Documents/`) | `:adapter:ios:ext-safe` | `InMemoryDeviceLogSource` |
| `AlbumMapStore` | `IosAlbumMapStore` (App-Group `NSUserDefaults` suite, plus a one-time Keychain migration) | `:adapter:ios:ext-safe` | `InMemoryAlbumMapStore` |

The phase handoff also listed a "discovery cursor store". It does not exist: `UploadDiscovery` states
there is no persisted cursor, and the CLAUDE.md line naming `IosDiscoveryStore` is stale.

Measured facts this design stands on:
- A Kotlin/Native `test.kexe` has no App-Group entitlement, so the container lookup answers `nil`.
  Recorded in `IosDeviceManifestStoreTest` and in `TempDirectory.kt`.
- An ad-hoc-signed simulator app does have a container (`scripts/sim-sign`, measured 2026-08-09).
- Phase 3 already counts a real adapter over an **injected directory** as `Live` on `IOS_SIM_KEXE`:
  `AppGroupFileSecureStoreContractTest` does this, and the gate accepts it.

## Goals / Non-Goals

**Goals:**
- Every App-Group-backed port has one contract, and each contract runs against its honest fake and its
  real adapter.
- The config-absence decision is covered end to end through the real adapter, for every state a CI host
  can enter.
- `:test:world`'s config double becomes an honest fake held to the same contract.

**Non-Goals:**
- **A new `Host`.** Decision D2 explains why.
- **A device recording, and the OS seams one would need.** Decision D3 explains why.
- **Coverage of the locked-since-boot state.** No host can enter it (D3).
- **`AlbumManager`, and the PhotoKit ports generally.** These belong to the PhotoKit phase.
- **`BackgroundTransfer`'s use of the App Group** in `IosUrlSessionUploadPlatform`. That is transport,
  not storage.
- **The album map's legacy-Keychain migration.** It is adapter-internal: its decision is
  `AlbumMapMigration`, already covered in `commonTest`, and a clause reaching it would need a stub Keychain,
  which is not a real implementation.

## Decisions

### D1. Five contracts; config is one contract over the three ports together

The config ports get **one** contract whose subject bundles all three. The bundle is a small holder type
declared beside the contract in `:test:contracts`. The reason: the obligations that matter cross the
ports.
- A `save` must be visible to both `read()` and `config`.
- A `clear` must turn `read()` into `None` **and** `config` into `null`.
- An unreadable file must be `Unavailable` to the reader while `config` shows `null`.

Split into three contracts, none of these could be stated. Each port still has exactly one contract, as
`port-contracts` requires, because no port appears in two contracts.

*Alternative:* three contracts, one per port. Rejected: every cross-port clause would need an observation
handle, and `port-contracts` reserves that shape for ports that declare no reads of their own.

`reload()` is not a port member (it is on `FileBackedConfigStore` only), so no clause calls it. Its merge
rule is the pure `configAfterReload`, already covered on both targets.

### D2. No fourth `Host`

An entitled simulator app (`IOS_SIM_APP`) would add exactly one thing over `IOS_SIM_KEXE` with an injected
directory: the container lookup returning a real path. It would cost:
- a new value in the closed enum, with its argument in `port-contracts`;
- a CI job that builds the app with the rig, ad-hoc signs it, installs it and drives the contract verb;
- teaching the coverage gate that this host runs in CI and is not recorded.

Nothing a clause asserts depends on that path, so it is not worth that cost. If a later phase needs
cross-process visibility between the app and the extension (for example, "the extension reads what the app
wrote"), that is the argument for the host. It is not a clause over one port instance.

### D3. No device recording

A recording pays off when the device reaches a state CI cannot. For these ports, the only such state is
**protected data unavailable** — a background wake before first unlock. It is the state the leave decision
was built against. No host reaches it:
- the rig can drive only a running, unlocked app;
- the simulator enforces no file protection.

Every other state is reachable in `IOS_SIM_KEXE`. A recording would therefore mean an OS seam in five
adapters, with no clause to show for it.

`port-contracts` routes a belief no host can exercise to "the adapter's documentation with its evidence".
The claim that reading a protected file fails permission-class (257 / `EPERM`) and never not-found stays in
`isConfigFileAbsence`'s KDoc, grounded on Apple's data-protection contract. This change states that plainly
rather than implying the contract covers it.

### D4. Injected directories are real implementations; the lookup is covered by not injecting it

Live bindings reach the readable states through a fresh temporary directory per clause, as
`AppGroupFileSecureStoreContractTest` does. Everything the adapter does runs for real — the NSFileManager
and NSData calls, error mapping, atomic writes, the envelope decode — except the one expression that
resolves the container.

That expression is covered in the other direction. The **unavailable** state is entered by constructing the
adapter with its **default** container argument. In the kexe that runs the real
`containerURLForSecurityApplicationGroupIdentifier` and gets `nil`. So each adapter's nil branch is
exercised against the platform's actual answer, not a hand-passed `null`.

This requires two behaviour-preserving production changes, both copying `IosDeviceManifestStore`'s
precedent:
- `FileBackedConfigStore` gains `containerPath: String? = <lookup>`.
- `IosStagedBytes` gains `container: () -> String? = { <lookup> }`. It is a provider rather than a value
  because `stagingRoot` must stay lazy: a locked background launch must not be forced into the lookup at
  composition.

Shells pass nothing. `RuntimeIdentityTest`'s pinned literals do not move.

The `port-contracts` delta writes this rule down, because the handoff left it open and the next phase
(PhotoKit) will meet the same question.

### D5. The state vocabularies

State names describe what is behind the port, never how a binding produced it.

| contract | states |
|---|---|
| config | `INACCESSIBLE` (nothing can be read or written), `ABSENT`, `JOINED`, `FOREIGN` (another envelope version), `UNUSABLE` (current version, payload does not decode), `FILE_UNREADABLE` (a file is present, but reading it fails with an error that is not not-found) |
| device manifest | `UNAVAILABLE`, `EMPTY`, `HOLDING` |
| staged bytes | `UNAVAILABLE`, `EMPTY`, `STAGED` (seed files at paths derived from the clause id under `stagingRoot()`) |
| device log | `NO_LOG`, `EMPTY_LOG`, `HOLDING` (seeded multi-line text for both processes, distinct per process, longer than the clauses' budgets), `ROLLED_ONLY` (only a `.1` sibling exists) |
| album map | `EMPTY`, `HOLDING`, `CORRUPT` (the stored value does not decode) |

How each binding reaches them:

| binding | reaches |
|---|---|
| **config, fake** | `INACCESSIBLE` (readable flag false), `ABSENT`, `JOINED`. The fake stores an `EventConfig`, not text, so it answers `Unreachable` for `FOREIGN`, `UNUSABLE` and `FILE_UNREADABLE`. |
| **config, live** | all six. `FILE_UNREADABLE` is a mode-000 file at the record's path. Measured in the kexe (2026-09-23, CI `ios-test`, uid 501): its read fails `NSCocoaErrorDomain` 257 (`NSFileReadNoPermissionError`, underlying `EACCES`), the same Cocoa code Apple documents for a protected read. A directory at the path fails 256 (underlying `EISDIR`), and a missing file fails 260 (underlying `ENOENT`). |
| **manifest, staged bytes, album map — fakes** | answer `Unreachable` for `UNAVAILABLE` and `CORRUPT`, which have no in-memory meaning. The real adapters reach every state. |
| **device log** | the fake reaches `NO_LOG`, `EMPTY_LOG` and `HOLDING`, and answers `Unreachable` for `ROLLED_ONLY`, since it has no roll concept. |

### D6. The clauses that carry the absence decision

The config contract's clauses, grouped by what they protect. The code is authoritative once written.

- **Missing is the only road to not-joined.**
  - `ABSENT`: `read()` is `None` and `config` is `null`.
  - `INACCESSIBLE`, `FOREIGN`, `UNUSABLE` and `FILE_UNREADABLE`: `read()` is `Unavailable` and **never**
    `None`.

  The `FILE_UNREADABLE` clause is the first to run `isConfigFileAbsence`'s else-branch with an `NSError` a
  real filesystem raised.
- **The leave round trip.**
  - `JOINED`: `clear()` makes `read()` `None` and `config` `null`.
  - `ABSENT`: `clear()` is a no-op.
- **Writes.**
  - `ABSENT`: `save` makes `read()` `Joined` with that config, and `config` holds it.
  - `JOINED`: `save` replaces the seed; saving an equal config leaves the value equal.
  - `JOINED`: a fresh instance is seeded from the persisted config at construction.
- **An unreachable store refuses rather than half-completing.**
  - `INACCESSIBLE`: `save` fails.
  - `INACCESSIBLE`: `clear` fails. This clause is the reason for the behaviour change in D7.

`Unavailable.status` values are not asserted. They are diagnostics, like `SecureStoreRead.Unavailable`'s
detail.

### D7. `clear()` fails when the container cannot be resolved

Today `deleteFile()` returns silently on a `nil` path, and `clear()` then sets `config` to `null`. A leave
on such a build reports success, shows the setup gate, and leaves any file on disk to resurrect the
membership at the next launch. That is the half-completed state the adapter's own KDoc says the throw
exists to prevent, and `save` already refuses in the same state.

Now `clear()` raises when the path cannot be resolved, and `config` keeps its value. A missing **file** is
still success: the not-found branch is unchanged.

`LeaveEvent` already treats a failed `clear()` as "still joined, retry" (capability `leave-event`), so no
caller changes. Only an unentitled build can reach this. The `leave-event` delta also removes that spec's
dead "Keychain copy first" wording, which is what currently claims the guarantee.

### D8. `InMemoryConfigStore` and the world

The fake follows the gallery fakes' shape: its constructor takes
- `persisted: MutableStateFlow<EventConfig?>` — the "file" — and
- `readable: MutableStateFlow<Boolean>`.

`config` is the persisted cell. `read()` answers `Unavailable` while unreadable, and otherwise `Joined` or
`None`. `save` and `clear` fail while unreadable, as the real store does.

The `Unavailable` status is `NSFileReadNoPermissionError` (257), the permission class Apple documents for a
protected read. It is chosen so a world log reads like a device log, and it is not asserted anywhere.

`:test:world` keeps its cell and passes it in. Operator actions that write the cell directly (provision, the
leave helper) are unchanged. `membershipUnreadable` becomes a property over the `readable` cell: levers live
in `:test:world`, never in the fake.

Fake honesty is enforced by the compiler, not by a text gate: the fake class is `internal`, and its public
surface is factories in `Factories.kt` that return port types. So the one double is exposed as three
factories — `inMemoryConfigSource`, `inMemoryConfigStore` and `inMemoryConfigReader` — each a view over the
caller's two cells. (The handoff and the old CLAUDE.md named a `FakeHonestyTest`; it no longer exists.)

### D9. Binding placement

| binding | source set |
|---|---|
| fakes (5) | `:adapter:generic:fake` `commonTest` — `JVM` and `IOS_SIM_KEXE` via `currentHost` |
| config, manifest, device log, album map | `:adapter:ios:ext-safe` `iosTest` |
| staged bytes | `:adapter:ios:app-only` `iosTest`, which gains `implementation(project(":test:contracts"))` — allowed, since `testing-architecture` says `:test:contracts` is "consumed by the bindings' test source sets" |

No new module, and no change to `module-architecture`.

Each album-map clause gets its own `NSUserDefaults` suite, named from the clause id, and its persistent
domain is removed on dispose. The default legacy Keychain store is passed unchanged. In the kexe it answers
unavailable, which drives the adapter's retry branch to an empty map. That is honest for `EMPTY`, and is
what the real adapter does on a host with no Keychain.

## Risks / Trade-offs

- **[`FILE_UNREADABLE` might have been unreachable in the kexe]** → measured first, and it is reachable: a
  mode-000 file fails 257 over `EACCES` (see D5). The stand-in shares the Cocoa code with the locked read,
  not the underlying POSIX error (`EPERM` on a device), and the classifier reads only the outer code.
- **[`NSUserDefaults` suites might not persist in the unentitled kexe]** → measured first, and they do: a
  named suite round-trips, and `removePersistentDomainForName` empties it (2026-09-23).
- **[World tests that save while the membership is unreadable]** → the fake now refuses those saves where
  the old inline object accepted them. `CycleEntryGateIntegrationTest` is the only lever user, and it does not
  save while unreadable. Any other failure is a test that relied on an impossible state, and is fixed there.
- **[The locked-since-boot state stays uncovered]** → stated in D3 and in the proposal. The contract makes
  every other road to "not joined" executable, which is the larger part of the risk surface. It does not
  replace the Apple-grounded belief.
- **[A contract clause could surface further adapter defects]** → the intended outcome. Each one is fixed in
  this change if it preserves behaviour; otherwise it is raised with the user before it becomes a delta.

  One did surface. `HOLDING_WITHIN_BUDGET_IS_WHOLE` failed against `IosDeviceLogSource` (CI run 35837231495):
  the reader dropped everything before the first newline even after reading the file from its first byte, so
  every log shorter than its budget lost its opening line. `IosDeviceLogSourceTest` pinned that drop under the
  title "a log shorter than the budget comes back complete". With the user's agreement, the reader now drops a
  partial first line only when its read began mid-file, and the pinning test asserts the whole log. This needs
  no spec delta: `diagnostic-logging` asks only for a cut at a line boundary, and a whole file satisfies that.

## Migration Plan

Additive, apart from the world's config double and the one `clear()` change.

Order:
1. Measure `FILE_UNREADABLE` and the suite round trip.
2. Constructor parameters (green).
3. One commit per contract: contract, fake binding, live binding (each green).
4. The `clear()` change with its clause.
5. `InMemoryConfigStore`, and the world moved onto it.
6. Docs and diagrams.

Rollback is a revert.

## Open Questions

- None blocking. Whether an `IOS_SIM_APP` host is worth adding is left to the first phase that needs
  cross-process clauses.

## Delta completeness (archive gate)

| module touched | owning capability | delta, or why none |
|---|---|---|
| `:test:contracts` | `port-contracts` | delta: the injected-location rule and the App-Group host column. The five contracts themselves are clause code, which `port-contracts` makes the specification; no spec restates them. |
| `:adapter:generic:fake` | `harness-world-model` (the doubles the world stands on) | delta: the world's config ports are `InMemoryConfigStore`. Behaviour elsewhere: the manifest fake gained constructor state, and the empty-log answer moved from `""` to `null` to match the device, which the port's own KDoc already required. |
| `:adapter:ios:ext-safe` | `event-link` (config file) · `diagnostic-logging` (log reader) | `event-link` delta: a container input, and `clear` failing without a container. `diagnostic-logging`: none needed. It requires a tail "cut at a line boundary" from the current file, and the fix only stops discarding a first line that was never cut, which satisfies that more exactly. The manifest and album-map changes are test-only. |
| `:adapter:ios:app-only` | `download-store` | none: `IosStagedBytes` gains a defaulted container provider, still resolved lazily, and the shell passes nothing. |
| `:test:world` | `harness-world-model` | delta, as above. |
| `leave-event` (spec only) | `leave-event` | delta: the outdated "Keychain copy first" ordering is replaced by the failing-`clear` guarantee. |
| `architecture/` | `architecture-diagrams` | none: regenerated output. |
| `CLAUDE.md` | none (docs) | none: the stale module-map lines are corrected. |

Dead-type gate: the two hits (`InMemoryDeviceManifestStore`, `IosStagedBytes`) are changed declaration lines, not removals. `AppGroupProbeTest` was never on `main`.
