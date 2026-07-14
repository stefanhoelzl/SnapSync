## Context

The app writes three Keychain items — the device id (`:capability:device-id`), the event config
(`:capability:config`), and the event-album map (`:capability:album`) — and **none** of them sets
`kSecAttrAccessible`. The iOS default is `kSecAttrAccessibleWhenUnlocked`, so all three are
unreadable while the device is locked. Both composition roots resolve all three from background
contexts, and *background* here means *idle*, which usually means *locked*:

- the app process, woken by a `BGProcessingTask` (the download import-tail backstop), a silent push,
  or a background `URLSession` completion;
- the PhotoKit upload extension, whose `process()` cadence is OS-owned and scheduled at idle.

Crash report `SnapSync-2026-07-13-233840.ips` (build 297 = `40a6ee2`, iPhone XS / iOS 18.7.9,
`"isLocked": 1`) was symbolicated against a rebuild of that commit. The faulting stack is
unambiguous: `DarwinMainDispatcher` → `DispatchedTask.run` → the `startUploadsOnGrant` permission
collector → the lazy chain `uploadArm` → `uploadProducer` → `urlSessionUpload` → `deviceId` →
`KeychainDeviceIdentity.writeValue` → `check(status == errSecSuccess)` → `handleCoroutineException`
→ `processUnhandledException` → `abort()`. The `download.backstop` `BGTask` running on another thread
was the *trigger* that woke the process, not the crash site.

Constraints that shape the design:

- **`:app:ios` is wiring-only and untested** (project hard rule), so no decision logic may be parked
  in either composition root — it must live in a `domain`/`capability` module under test.
- **Every unit test must run on `iosSimulatorArm64` too**, so logic belongs in `commonMain`/
  `commonTest` wherever it can be made platform-free.
- **The simulator has no lock state.** No test — `commonTest`, `iosTest`, or integration — can
  exercise a locked background wake. That is a hard limit on what verification can prove, and it
  shapes the verification decision below rather than being a footnote to it.
- The Kotlin/Native platform libraries (`platform.Security`) are **ambient** in every `iosMain`
  source set. Unlike the Material 3 containment rule — which is enforced by the Gradle dependency
  graph, so a violation is a *compile error* — Keychain containment cannot be compiler-enforced.

## Goals / Non-Goals

**Goals:**

- Every Keychain item the app writes is readable by background work on a locked device (after first
  unlock).
- A Keychain read error can never be mistaken for "no value stored" — neither for the device id
  (which would mint a new identity) nor for the config (which would look like a leave).
- Existing devices heal without changing their device id, their storage partition, or their ledger.
- The bug class becomes structurally unwritable, not merely fixed in the three places it exists today.
- A real locked background wake is *observable* in production, given it cannot be tested.

**Non-Goals:**

- Making uncaught coroutine exceptions non-fatal. An exception in any `scope.launch` on
  `Dispatchers.Main` terminates a Kotlin/Native process; that is a separate change with its own
  contract. This change makes the throw *unreachable*; that one makes a residual throw *survivable*.
- Detecting or repairing a device id cloned onto a second physical device by a backup restore
  (see Decision 1 and Risks).
- Adopting a linter as a general practice. Konsist enters as the mechanism for one architecture
  guard, not as a code-style regime.
- Building a repeatable locked-device test harness (see Decision 10).

## Decisions

### 1. `kSecAttrAccessibleAfterFirstUnlock` — deliberately backup-restorable

**Decision:** `AfterFirstUnlock`, **not** `AfterFirstUnlockThisDeviceOnly`.

The obvious instinct is `ThisDeviceOnly`: it keeps the item out of backups and iCloud Keychain, so a
restored phone cannot clone the device id and collide on the `/files/devices/<deviceId>/` partition.
That instinct is wrong here, because **the app container rides the encrypted backup too** — including
the SQL ledger and the discovery cursor. A `ThisDeviceOnly` id would give a restored phone:

- a **fresh** device id (new, empty byte partition, new manifest key), and
- a **restored** ledger whose rows all say `COMPLETED`.

The engine would therefore upload nothing while the new device's manifest sat empty — the photos would
remain reachable only under the *old* device's partition. Keeping the id restorable keeps
**id ↔ ledger ↔ partition** mutually consistent, which is the invariant that actually matters.

**Alternatives considered:** `ThisDeviceOnly` (rejected: desynchronizes the id from the restored
ledger, as above); a different class per item (rejected: the config and the id must agree about what
a restore means, or a restored phone comes back joined under an identity that no longer owns its
uploads).

**Accepted cost:** restoring a backup to a new phone *while the old phone is still active on the same
event* yields two live devices sharing one device id. Filenames are derived from per-library PhotoKit
asset ids, so byte collisions are unlikely; the device manifest becomes last-writer-wins and a leave
from either device removes the shared membership. Degraded, not corrupting, and rare. Detecting it is
a possible future change, explicitly out of scope here.

### 2. Migrate in place, preserving the value

**Decision:** on a successful read, if the item's accessibility class is not the target, `SecItemUpdate`
it — **same value, same id**. Read data and attributes in one query (`kSecReturnData` +
`kSecReturnAttributes`), and update only when the class actually differs, so the steady state costs
nothing.

The tempting cheap option — "ship the new attribute, new installs get it right" — **never heals any
existing device.** The Keychain **survives app uninstall** (that is the spec'd reinstall-stability of
`device-identity`), and the device id is written exactly **once**, at mint. So an already-shipped
device keeps a `WhenUnlocked` item *forever*: not a reinstall, not an update, nothing rewrites it. The
crashing iPhone XS would stay broken permanently.

**Alternatives considered:** new-installs-only (rejected, above); delete-and-remint (rejected: it
changes the device id for every existing user, orphaning their partition and ledger and re-uploading
their library — the exact catastrophe Decision 3 exists to prevent).

### 3. Three-state read; never mint on error

**Decision:** the read returns `Found(value)` / `Absent` / `Unavailable(status)`. Only
`errSecItemNotFound` is `Absent`, and **only `Absent` mints**.

Today `readValue()` maps every non-success status to `null`, which conflates "there is no item" with
"I could not look". That conflation is what turns a locked read into a mint attempt. It is also a
standing data-integrity hazard *independent of the lock*: any read failure whose subsequent write
happened to succeed would silently give the device a **new identity**. The crash is currently the only
thing preventing that outcome — which is a poor safety mechanism.

The decision logic (`read → mint → write`, and the migrate branch) is pure and lives in `commonMain`,
so it is tested on JVM **and** the simulator; only the `SecItem` calls are in `iosMain`.

### 4. `Unavailable` is not `None` — an unreadable config is not a leave

**Decision:** `ConfigSource` distinguishes *joined* / *not joined* / *unreadable*. On `Unavailable`,
`UploadExtensionRoot.process()` **skips the cycle entirely** — no reconcile, no marker clear, no
upload, a clean `COMPLETED`. Only a definite `None` reaches `reconciler.reconcile(null)`.

This is the silent half of the bug. `process()` currently treats a `null` config as "not joined yet
… or a leave" and calls `reconciler.reconcile(null)`, whose documented job in that case is to **clear
the `joinedEventId` marker**. A locked read therefore performs a *false leave* on every OS-scheduled
invocation; the next unlocked cycle sees a marker mismatch and runs the full re-join path — device
LIST, atomic ledger clear-and-seed, and a **discovery-cursor clear that forces a complete PhotoKit
re-enumeration**. Dedup survives (the seed answers `AlreadyUploaded`), so nothing re-uploads, but the
marker never settles and every cycle pays for a full re-walk — the same unbounded walk that caused the
`0x8BADF00D` watchdog kill of build 286.

Note this fix stands **on its own**: even with Decision 1 in place, an unreadable config must never be
allowed to mean "left the event". The attribute makes it improbable; the three-state read makes it
impossible.

### 5. Typed exception, not a seam change

**Decision:** the Keychain adapter throws a typed `KeychainUnavailable(status)`. The
`DeviceIdentity.deviceId(): String` seam contract — synchronous, non-null — is **unchanged**.

`deviceId()` is consumed by upload, download, join, push and membership. Threading a three-state result
through all five (and their tests) is a large diff in service of a state that Decision 1 renders nearly
unreachable. Instead the two composition roots — the only places that can meaningfully react — catch
it: the extension skips the cycle (symmetric with Decision 4), and the app defers (Decision 6).

**Alternative considered:** making the seam return `Found/Absent/Unavailable` (rejected: ripples through
five capabilities to model a state that is handled identically at both roots).

### 6. Defer-and-resume in the app; skip-the-cycle in the extension

**Decision:** the app process reads `UIApplication.isProtectedDataAvailable` and, when protected data
is unavailable, **defers** the background work and resumes it on
`UIApplicationProtectedDataDidBecomeAvailable` — which fires the moment the user unlocks. The extension
has no `UIApplication` (unavailable to app extensions) and keeps the typed-exception / skip-cycle path.

iOS exposes this invariant as a first-class signal; the app should ask rather than guess and fail.
Skipping and waiting for the OS's next wake could strand staged downloads or pending uploads for hours;
resuming at unlock is immediate and costs one notification observer.

### 7. `:domain:keychain` — a cross-cutting leaf

**Decision:** one new `:domain:keychain` module owns the *only* `SecItem*` code in the repo, mirroring
the `:domain:logging` precedent (a cross-cutting infrastructure leaf, not a product capability).

**Alternatives considered:** `:capability:keychain` (rejected: it maps to no user-facing behavior, and
`domain/` is where this codebase files cross-cutting infrastructure); parking the helper in
`:capability:device-id` and having config depend on it (rejected: invents a semantic dependency edge
from config to device identity that does not exist); fixing the three copies in place (rejected: it
triplicates the decision logic and leaves three places for the next drift — and the fix would be
untested in two of them).

### 8. The album map leaves the Keychain

**Decision:** move `IosAlbumMapStore` to the App-Group `NSUserDefaults` suite (mirroring
`IosDiscoveryStore`), and migrate the legacy Keychain item once — copy it in, then `SecItemDelete` it.

App-Group container data defaults to `NSFileProtectionCompleteUntilFirstUserAuthentication`, i.e.
background-readable after first unlock **by construction** — the same protection class the SQL ledger
already depends on. The `event-album` spec requires only "a shared store, readable and writable by both
the app and the extension, that survives leave"; it never pins the Keychain. And the map is a
self-healing **cache**: `AlbumCoordinator.ensureAlbum` resolves the stored id, checks it still exists,
and otherwise re-creates by name. The only property the Keychain adds is uninstall-survival, which
nothing requires. So the third Keychain user is removed rather than fixed — and no `event-album`
requirement changes.

The one-shot migration is preferred over letting the cache self-heal because between the update and the
next foreground launch the extension would skip album adds, and photos imported in that window would
land in the camera roll only — permanently, since the import is one-shot. It also avoids leaving a dead
Keychain item that outlives uninstall forever.

### 9. Two guards, because the invariant has two opt-in surfaces

The real invariant is *"state read by background work must survive a locked device."* Walking every
piece of such state shows iOS gets the file-backed cases right by default — the ledger, the download
store, the discovery cursor, and (after Decision 8) the album map all inherit
`CompleteUntilFirstUserAuthentication`. There are exactly **two** places a developer can opt out:

| surface | mechanism | polarity | guard |
| --- | --- | --- | --- |
| Keychain items | `kSecAttrAccessible` | must **be** set | Konsist: no `SecItem*` outside `:domain:keychain`, **plus** that module's own test asserting every query it builds carries `AfterFirstUnlock` |
| App-Group files | `default-data-protection` entitlement | must **not** be `NSFileProtectionComplete` | a plist assertion over both `.entitlements` files |

Neither Keychain guard suffices alone: containment proves all Keychain code is in one module; the
module's test proves that module always sets the attribute. Together they prove *every Keychain item in
the app is background-readable*.

The entitlement guard is not hypothetical safety theatre. Ticking Xcode's "Data Protection" capability
sets `default-data-protection = NSFileProtectionComplete`, which makes **every** file in both containers
unreadable while locked — the ledger, the download store, the cursor, the album map. It would kill the
entire background tier, silently, on locked devices only, and it would be done by someone who believed
they were improving security. Ten lines of test is a cheap answer.

**Why Konsist and not a linter.** The rule we need is "no `SecItem*` outside one module", and the code
lives in `iosMain`:

- detekt **1.23.8** (latest stable) is built against the Kotlin **2.0.21** compiler; this repo is Kotlin
  **2.4.0**.
- detekt **2.0.0-alpha.5** *is* built against Kotlin 2.4.0 — but it is an alpha.
- Decisively: `ForbiddenImport` inspects **import statements only**, and
  `platform.Security.SecItemAdd(query, null)` is legal Kotlin with no import. The rule that would catch
  it, `ForbiddenMethodCall`, requires **type resolution**, which detekt does not have for Kotlin/Native
  source sets — in 1.x or 2.x. So on the exact source set where every `SecItem` call lives, every linter
  degrades to import-checking.

Konsist parses Kotlin **source** (PSI), so it can inspect `iosMain` — code with no JVM bytecode that
cannot even be compiled on Linux — from an ordinary JVM test, and its `text` escape hatch closes the
fully-qualified-call hole. It lands as a test in a new test-only `:test:architecture` module, which fits
the existing `:test:world` / `:test:integration` taxonomy and runs under `./gradlew build`.

**Alternatives considered:** a Gradle grep task wired into `check` (rejected in favour of an
architecture-test module, though it is strictly simpler and fails *closed* — see Risks); detekt as the
guard (rejected: cannot see the FQN call); no guard (rejected: this bug shipped precisely because
nothing mechanically prevented it).

### 10. Verification: diagnostics, not a lab

**Decision:** cover everything testable in `commonTest`/`iosTest`, and make the untestable part
**observable in production** instead of building a device rig.

**Corrected during implementation — the real Keychain is not reachable from any test.** This design
originally claimed an `iosTest` could round-trip a real item, assert its accessibility class, and
migrate a synthesised legacy one. It cannot. A Kotlin/Native test binary is **not an app bundle**: it
carries no keychain-access-group entitlement, so `securityd` refuses it Keychain access outright and
every call returns `errSecNotAvailable` (**-25291**). The first `IosKeychainTest` failed 9 of 19 on the
simulator, for this reason and no other. Only a real app bundle on a device can exercise the happy path.

So the coverage actually available is:

- **`commonTest`** (JVM **and** `iosSimulatorArm64`): the three-state read, the mint rules, the migrate
  branch, the config `Unavailable`-is-not-`None` mapping, the `CycleGate`, the album-map migration, and
  the protected-data defer/resume gate — all over fakes.
- **`iosTest`, against the real `SecItem*` API**: the refusal path — which, usefully, *is the bug*. An
  inaccessible Keychain is exactly what a locked device presents, and the crash was the adapter reading
  that condition as "no value stored". So the real-API test proves: a refusal reports `Unavailable`,
  never `Absent`, and mints nothing, writes nothing, changes no identity. Plus the one structural fact
  needing no `securityd`: `IosKeychain.writtenAttributes()` — the single source both `write` and
  `migrateAccessibility` build their dictionaries from — carries `AfterFirstUnlock`. That is the half of
  Decision 9's argument that containment alone cannot supply.
- **Not testable at any level**: the happy path on a real Keychain, and the end-to-end locked background
  wake (the simulator has no lock state; a `BGTask`'s timing is OS-owned and cannot be forced).

For that last part we do not build a lab; we make it *report itself*. Every background entry point
(`runDownloadBackstop`, `onSilentPush`, `handleBackgroundUrlSession`) logs protected-data availability,
and the extension's `process()` — which cannot query it, having no `UIApplication` — logs the Keychain
status codes it got, to `debug.log`, the project's canonical un-redacted channel. The next real
background wake on any device answers the question with one `pymobiledevice3 apps pull`, and the
diagnostic keeps paying out for the *next* background bug.

**Alternative considered:** a repeatable locked-wake harness (lock via
`notification post com.apple.springboard.lockdevice`, wake via silent push, pull `debug.log`). Deferred:
it requires a build + sideload per iteration, none of the chain is proven end-to-end, and it could never
gate CI (no device). If the diagnostics prove insufficient, it becomes its own operator-infrastructure
change, like `ssh-mac.yml`.

## Risks / Trade-offs

- **Konsist can fail open.** It embeds `kotlin-compiler-embeddable` **2.0.21** while the repo is on
  Kotlin **2.4.0** — the same version lag that disqualifies detekt 1.23.8, and intellectual honesty
  requires naming it. If a future Kotlin feature ever fails to parse, the file may drop silently out of
  scope and the guard stops guarding. → **Mitigation:** the architecture test also asserts the scanned
  scope is non-empty and covers the expected number of `iosMain` files, so a parser regression fails the
  build loudly instead of passing vacuously.
- **A restored backup can clone the device id onto a second live phone** (Decision 1). → Accepted:
  degraded (last-writer-wins manifest, shared partition), not corrupting, and rare. Detection is a
  possible future change.
- **`AfterFirstUnlock` is still unreadable before the first unlock after a reboot.** → Mitigated by
  design, not eliminated: the app defers and resumes at unlock (Decision 6), the extension skips the
  cycle (Decisions 4, 5), and nothing mints or clears a marker in that window (Decision 3).
- **Migration requires one unlocked read.** A device that is never foregrounded again never heals. →
  Accepted: an app update is installed by a user who then opens the app; and the app's own foreground
  path resolves the device id.
- **The end-to-end fix is unproven until it runs on a real locked device.** → This is the honest residual
  risk of Decision 10, mitigated by diagnostics rather than removed. The mechanism is fully understood
  and symbolicated, and the remedy is a documented Apple attribute — but "there is a *second* reason the
  background wake fails" is exactly the hypothesis no test here can refute.
- **The happy path has no test, anywhere.** `securityd` refuses a Kotlin/Native test binary
  (`errSecNotAvailable`, -25291), so no test can confirm that a real item is *actually stored* under
  `AfterFirstUnlock`, nor that a real legacy item *actually* migrates. → **Mitigation:**
  `writtenAttributes()` is a single source of truth consumed by both write paths and asserted in
  `iosTest`, so the class the adapter applies cannot drift; the rest rests on the on-device diagnostics
  (Decision 10) and on Apple's documented semantics. This is the residual risk of this change, and it is
  larger than it looked when the verification decision was taken.
- **Konsist is a 0.x dependency** in the build. → Test-only, in a test-only module; a breaking change
  costs a test refactor, never a shipped defect.

## Migration Plan

1. `:domain:keychain` lands with the attribute, the three-state read, and the in-place migration. The
   device id and config heal on their **first unlocked read** after the update — no user action, same
   id, same partition, same ledger.
2. The album map performs its one-shot copy Keychain → App-Group `NSUserDefaults` on first read, then
   deletes the legacy Keychain item. The copy is idempotent and safe to run in either process.
3. Ship. Pull `debug.log` after the next real background wake and confirm protected data was available
   and the Keychain status was `errSecSuccess` on a locked device (`isLocked` visible in any crash
   report; absence of new `SIGABRT` reports is the other signal).
4. **Rollback:** reverting restores the old attribute for *new* writes but does not un-migrate existing
   items — a migrated item stays `AfterFirstUnlock`, which is strictly more available and harmless to
   the old code. There is no destructive step to roll back.

## Open Questions

- Should `:test:architecture` also take the *"no Material 3 type may appear in an `App*` signature"*
  rule? It is a stated hard rule that nothing currently enforces (the dependency graph bans the *import*
  outside `:domain:ui:components`, but nothing stops an M3 type in an `App*` signature *inside* it), and
  it is nearly free once the module exists. Deliberately left out of scope here so a crash fix does not
  quietly become an architecture-testing initiative.
- Both `.entitlements` files carry stale comments ("the same **S3 credentials** the app stores", "the
  **future** background upload extension"). Cosmetic, unrelated to this change; worth a separate sweep.
