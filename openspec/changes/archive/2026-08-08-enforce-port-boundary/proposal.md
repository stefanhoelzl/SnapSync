## Why

The law "Ports are the I/O boundary named for the need" is complete and correct, and it has never
had a gate. A 122-file audit of `:domain` found it violated in two ways that no existing check can
see: the composition hands the core five platform touches as **function-typed fields** instead of
ports (including `now = { NSDate()… }`, while a `Clock` port exists and is bypassed), and four sites
in the platform-free zones **name Apple constants directly**. Every zone gate today inspects
imports; both violation classes are invisible to imports.

This is not preparation for a second platform. Each site violates a requirement that is in force
today, and the fixes are justified without reference to any platform that does not yet exist.

## What Changes

**Gap 1 — the composition hands the core platform touches as lambdas.**
`AppPorts` carries 48 fields: 25 port-typed, 20 function-typed, 3 plain values. The function-typed
fields split cleanly: most carry *coordination within the core* (`pumpForeground`, `reloadConfig`,
`refreshAttestation`, …), which is how `compose/` hands `flow/` its own machinery back without
`flow/` naming a port. Five do not — they reach out of the process:

- `now: () -> Long` → `NSDate()` inline in the composition root, while `ports/Time.kt`'s `Clock`
  and `:adapter:generic:app`'s `SystemClock` already exist. **Reach for the existing port.**
- `downloadStagingRoot: () -> String` → resolves the App Group container inline. **Moves onto
  `StagedBytes`**, the port that already owns staging-file lifetimes.
- `presentPhotoPicker: () -> Unit` → presents `PHPicker`. **Moves onto `PhotoAccessRequester`**,
  which already presents system UI via `openSettings()`.
- `notifyLeave: suspend (eventId) -> Unit` → HTTP via `HttpLeaveNotifier`, an adapter that
  implements no port. **New need-named port** `ports/LeaveNotifier` — which resurrects a name a
  2026-07-17 change deleted as ceremony, so a deletion-ledger row is overturned (design D10).
- `share: (String) -> Unit` → presents `UIActivityViewController`. **New need-named port**
  `ports/SharePresenter`.

**Gap 2 — platform-free zones name platform constants.**

- `model/ConfigFile.kt`'s `isConfigFileAbsence(domain, code)` — an `NSError` domain/code table.
- `model/UniversalLinkActivity.kt`'s `BROWSING_WEB_ACTIVITY_TYPE = "NSUserActivityTypeBrowsingWeb"`.
- `model/UploadKeys.kt`'s `resourceRole(Long)` and `model/RawAsset.kt`'s `RawResource.type: Long`
  and `.contentTypeUti` — `PHAssetResourceType`'s raw ABI and an Apple UTI.
- `Resource.contentType` carries a UTI across the `CandidateSource` port, while the resolved MIME
  the same adapter already produces rides unused in `metadata`.

Each moves into the iOS adapter that already holds the values. None of them cross a port today, so
nothing platform-shaped stops or starts crossing one — except `Resource.contentType`, which starts
carrying the MIME.

**Two gates, both pinned inventories, exact in both directions** (the mechanism
`KotlinShellGuardTest` already uses; neither needs type resolution or an AST):

- the **seam gate** pins the function-typed field inventory of `AppPorts`/`UploadPorts` (15 and 6
  fields, each with its reason), so adding one forces a stated reason it is not a port;
- the **platform-identifier gate** scans `model/`, `ports/` and `feature/` for Apple identifiers
  with **comments stripped**, over a **five-site pinned baseline** — not zero — split into
  `accepted` (`CompositionMode`'s `PHOTOKIT`/`URL_SESSION` tier names) and `deferred` debt with
  expiry triggers (the `Keychain` token in `ports/Keychain.kt`, `ports/ConfigPorts.kt` and
  `feature/album/AlbumMapMigration.kt` — the family D6 defers — plus
  `ports/OsReceipt.kt`'s `URL_SESSION_EVENTS`, a naming slip whose siblings are already neutral).
  The `Keychain` token is deliberately kept in the scan so D6's reshape cannot land without
  deleting those pins (design D2).

Each gate's documentation states what it cannot see — the identifier gate is blind to an ABI decoder
written in bare integers, and the seam gate says nothing about what the OS hands the shell.

**Deferred, and named here rather than split off:** the `ports/Keychain` type family is the same
violation (a port named for Apple technology, its `OSStatus` and accessibility-class vocabulary
reaching `ConfigPorts`, `AttestSeams` and `feature/album/AlbumMapMigration`). It is held back because
it touches `KeychainDeviceIdentity`, whose stored value is written once and never rewritten, and
because the simulator coverage that would make the reshape verifiable does not exist yet.

## Capabilities

### New Capabilities
<!-- none: this change adds no capability, it enforces an existing requirement -->

### Modified Capabilities

- `module-architecture`: the "Ports are the I/O boundary named for the need" requirement gains
  scenarios for both violation classes — a composition-supplied function type that reaches out of
  the process, and a platform constant named inside a platform-free zone. The "Dispatcher lanes are
  fixed by the composition" requirement additionally records the cost it accepts (correctness became
  non-local) and names its expiry trigger, and gains a scenario for a hop justified by the rule that
  requirement itself withdrew — two adapter comments cite it today, one naming this spec as its
  authority. That amendment rides here because it edits the same spec file: two changes each
  carrying a `MODIFIED` copy of one requirement means the second to land silently reverts the first.
- `architecture-guards`: two new gate requirements (the seam gate and the platform-identifier gate),
  each with its stated blind spot, following "Gates fail closed on novelty"; and the deletion
  ledger's enumerated items lose `the LeaveNotifier interface ceremony`, which this change brings
  back as a port (design D10). The requirement's own contract permits that — resurrection is
  forbidden *silently*, and the row is deleted in the same commit with the argument stated.
- `leave-event`: the backend notify is a **port**, not "an injected notify lambda backed by
  `HttpLeaveNotifier`" as the requirement currently reads. The behaviour is unchanged in every
  respect the capability cares about (fire-and-forget after local teardown, `Result` not a throw,
  both the explicit leave and the switch path) — what changes is which layer names the adapter, and
  that `deviceId` no longer crosses the seam.
- `ios-app-shell`: the live composition root supplies `share` as the `SharePresenter` port rather
  than as a shell-supplied lambda (`presentShareSheet`, a top-level function that no longer exists),
  and the picker likewise reaches `PhotoAccessRequester`. The requirement enumerates the shell's
  platform effect lambdas, so it names one that is gone.
- `gallery-status`: states which value `Resource.contentType` carries. The spec currently requires
  the MIME be resolved iOS-side and carried as a raw fact, but is silent on which field holds it —
  which is how a UTI came to occupy `contentType`.

## Impact

- **`:domain`** — `compose/SnapSyncApp.kt` and `compose/UploadCore.kt` (five fields become port
  types); `model/ConfigFile.kt`, `model/UniversalLinkActivity.kt`, `model/UploadKeys.kt`,
  `model/RawAsset.kt`, `model/RawAssetMapping.kt` (constants and translations leave); `ports/`
  gains two ports and three members.
- **`:adapter:ios:ext-safe` / `:adapter:ios:app-only`** — receive the moved translations;
  `IosShareSheet` and `PresentLimitedLibraryPicker` become port implementations;
  `PhotoKitCandidateSource` reports `role` and the MIME instead of raw PhotoKit values.
- **`:adapter:generic:app`** — `HttpLeaveNotifier` gains a port supertype.
- **`:app:ios`** — `SnapSyncRoot` stops constructing five inline lambdas and wires ports instead.
- **`:test:architecture`** — two new gates.
- **Tests move with their subjects.** The assertions that pin Apple constants currently run in
  `commonTest`, where they cannot fail: `assertEquals("NSUserActivityTypeBrowsingWeb",
  BROWSING_WEB_ACTIVITY_TYPE)` compares a constant to a copy of itself. In `iosTest` the same
  assertions can compare against the real symbol, so coverage gets stronger, not weaker.
- **No user-visible behavior changes.** The stored object's `Content-Type` header changes from a UTI
  to a MIME; nothing reads it (download URLs are presigned, and the import path branches on the
  device manifest's value, which is already the MIME).
