# Design

## Context

See proposal.md — Why. The state of the tree this design starts from (`origin/main` `69f3543`):

- **Protection classes.** Every Keychain item is written `kSecAttrAccessibleAfterFirstUnlock`
  (`IosSecureStore`); the databases and files are `NSFileProtectionCompleteUntilFirstUserAuthentication`
  (`IosDatabases`, `IosFiles`), and the App-Group `UserDefaults` suite inherits its container's class
  (`IosPreferences`). Both classes open at the first unlock after a restart and stay open while the screen is locked
  afterwards. `DataProtectionEntitlementTest` keeps the default class from being raised.
- **What is measured, and what is not.**
  - Locked after a first unlock: `isProtectedDataAvailable` reads `false` — `ProcessInfo@IOS_DEVICE_APP.LOCKED.rec`,
    SE2, iOS 26.6.2, 2026-10-08. Nothing else was recorded in that state.
  - Before the first unlock: **never measured for any class.** The July SE2 had no passcode, so the state was
    unreachable (`changes/archive/2026-07-16-fix-upload-config-gate/evidence.md`); an app running in that window
    cannot write its own log; the Keychain's `INACCESSIBLE` clauses run on the simulator's test executable and the
    mocks, never on a device (`SecureStore@IOS_DEVICE_APP.rec`: `NotRunHere`). What each class does there is Apple's
    documented semantics, adopted knowingly by `changes/archive/2026-07-14-fix-locked-device-keychain-access`.
  - `UserDefaults` before the first unlock: unknown. It reports no failure at all (`IosPreferences` declares it never
    fails a write); the likely answer is a missing value, which the app reads as "absent".
- **Nothing reads `ProcessInfo.protectedDataAvailable`.** Its last two readers — log lines on every entry and on a
  background session event — went with `7f7fd9ef0`. Only the port, its adapters, its contract, a mock and a lever
  remain.
- **The channel's levers today:** `databases.lock(on)` seals the databases alone (Keychain and protected data stay
  available — a phone no device can be in); `databases.fail(database, table, kind, times?)`;
  `keychain.forget(EVENT_KEY)`, implemented by the rig; `process-info.protectedData(available)`. The Keychain mock's
  `unavailable` and the preferences mock's `foreign` / `refusesWrites` exist only at construction, and no `port()`
  passes the preferences ones.
- **The mobile-data choice is decided twice.** `MobileDataSetting.allowed` is seeded from `read() != OFF` and
  `transferNetwork()` asks `read() == ON`. For a value that is neither `on` nor `off`, or unreadable, the menu says
  on and the transfers hold to Wi-Fi.
- **A refused key write on join half-joins.** `JoinEvent.join` enrols the device at the event first and keeps the
  event key afterwards; a refused keep answers `EnrollFailed` with the enrolment already made.

## Goals / Non-Goals

**Goals:**

- Every protected-store state a member can meet is reachable through a typed lever, each lever held to a contract
  clause, and the seven unclaimed scenarios plus the three new ones each have a behaviour test that waits on events.
- A locked phone in the channel is one device fact, never per-store switches that disagree.

**Non-Goals:**

- Measuring the before-first-unlock state on a device. It is documented as unmeasured, not re-taken here.
- Android's Keystore-sealed store and `isUserUnlocked` (phase ⑧), and the older-install service's state (phase ⑤).
- Making Preferences part of the lock.

## Decisions

### D1 — `device.lock()` plays the phone before its first unlock since restart, and nothing else

A new `device` service (`DeviceLevers`) with `lock()` and `unlock()`. `lock()` seals, at once, every protected store
that is mocked on this host: the Keychain (every read Unavailable, every write and delete Failed — the `INACCESSIBLE`
clauses), the databases (an open fails — `UNOPENABLE_*`), and the files (`UNAVAILABLE_AREA_IS_NEITHER_FOUND_NOR_ABSENT`).
The service needs all three mocked (`KEYCHAIN_MOCKED`, `DATABASES_MOCKED`, `FILES_MOCKED`): a lever's needs all
hold or it is refused, and a phone sealed in some stores and not others is the incoherent phone this replaces.
`unlock()` is the first unlock. The lever's `Acted` is the event a test waits on; no new world event.

A running phone cannot become one never unlocked since it restarted, so a test plays the restart: `device.lock()`,
then the host's cold background relaunch (no scene). The mocks also fail a statement on a database a process already
holds while locked — a state no phone is in, kept so a test that locks mid-process stays honest about what it reads.

- **No `sinceRestart` argument.** Locked after a first unlock, every store stays readable, and the one fact that
  changes (`protectedDataAvailable`) has no reader (D2). An argument whose `false` does nothing would let a test claim a
  locked phone while proving nothing. "Uploads while locked" is claimed by a background-upload test whose comment says
  why the phone is not sealed in that state.
- **Preferences is not sealed.** What `UserDefaults` answers before the first unlock is unmeasured, and the plausible
  answer — absent — would flip the mobile-data choice to its default. A guess encoded in a lever becomes a contract
  nobody measured.
- **Home.** `device` rather than `process-info` (which keeps only the footprint after D2) or `host` (which would need
  the HOST need, for an act that does not restart the process). A locked phone is a fact about the device.
- **`databases.lock` is deleted**, not kept as the lock's database part. Its "a statement on a database already open
  fails" describes a state SnapSync cannot be in: nothing can have opened a database before the first unlock. Its one
  user, `CycleEntryGateIntegrationTest`, moves to `device.lock()`.

*Alternatives:* `lock(sinceRestart)` as briefed — rejected above; per-store lock switches — the incoherent phone this
replaces; sealing only whichever stores are mocked — a partly sealed phone, and a need the dispatch cannot express.

### D2 — `ProcessInfo.protectedDataAvailable` is deleted

The port member, `IosProcessInfo`'s and `AndroidProcessInfo`'s implementations (and the latter's `@NeverAnswers`), the
contract's `AN_UNLOCKED_DEVICE_READS_AVAILABLE` / `A_LOCKED_DEVICE_READS_UNAVAILABLE` clauses and their states, the
`LOCKED` recording, the recording proxy's member, the mock's cell and state encoding, the `process-info.protectedData`
lever and `ProcessInfoView.protectedDataAvailable`. `ProcessInfo` keeps `memoryFootprint`. One commit of its own.

*Alternative:* restore the log field. Rejected: the one window it would matter for — before the first unlock — is the
one in which the app cannot write its log, and the after-unlock value predicts nothing about the stores.

### D3 — The store faults follow `databases.fail`, addressed by named items

`keychain.fail(item: KeychainItem, kind: KeychainFault, times?)` and `preferences.fail(item: PreferenceItem, kind:
PreferenceFault, times?)` — `times` omitted is one, each kind `READ` (the read answers Unavailable) or `WRITE` (a write
or removal answers Failed), each failure told as a world event (`KeychainFaulted`, `PreferencesFaulted`, beside
`DatabaseFaulted`).

- **Named items, mapped by the rig.** `databases.fail` and `backend.fail` can refuse an address the system does not
  hold (a table, an `operationId`), so a renamed target fails the test loudly. A Keychain slot or a preference key
  cannot be checked that way — a preference key is created lazily — so a raw key would let a renamed key silently
  disarm a test. The rig, which sees the services, maps each item to the app's own address, and the compiler holds
  the mapping. `KeychainItem` gains `DEVICE_ID`; `PreferenceItem` is `MOBILE_DATA` and `PUSH_REGISTRATION`. The
  preference keys move into one `PreferenceKeys` object beside `SecureSlots` (visibility only; the values are
  unchanged, so no stored state moves).
- **Contract backing.** The mocks' fault state becomes operator state (plumbing, off the face), and the mock bindings
  enter `SecureStoreState.INACCESSIBLE`, `PreferencesState.FOREIGN` and `PreferencesState.UNWRITABLE` through it —
  the same states the real adapters enter (the simulator's test executable and Android for the Keychain's; Android for
  the preferences').
- **Separate from the lock.** The lock is the sustained state; `fail` is a refusal the lock does not explain.

### D4 — The mobile-data choice is decided in one place

One function turns the stored answer into allowed / not allowed: nothing stored or `on` is allowed, anything else —
`off`, an unknown value, an unreadable one — is not. `allowed` is seeded from it and `transferNetwork()` maps it; the
three-valued reading survives only in `describe()`, for the diagnostic dump. This makes the decision record's rule
(`changes/archive/2026-10-07-mobile-data-per-device` D1: "a wrong hold only delays a photo, a wrong send spends the
member's data") hold for the menu too, where an unreadable value used to show the default.

### D5 — A join keeps the event key before it enrols

`JoinEvent.join` moves the key keep (or, for a plain event, the forget) ahead of the enrolment. A refused keep then
answers `EnrollFailed` with nothing done at the event, so the requirement's "never half-joined" holds, and the event's
member count never includes a device that failed to join. A key kept with no membership is inert: `presenceFor(null)`
is `NotNeeded`, and the next join, leave or reset overwrites or deletes it. The comment's rule — "a config naming a
key id must never exist without its key" — still holds: the config is written after both.

*Alternative:* leave the order and accept the stray enrolment. Rejected: it contradicts the requirement, and another
member's screen would count the device.

## Risks / Trade-offs

- [The lock encodes Apple's documented semantics, not a measurement] → each mock's KDoc and `docs/testing.md` say so;
  re-measuring needs a passcode-protected phone and an observer outside the app, which is the user's call.
- [`device.lock()` is refused on a device host that keeps any of the three stores real] → the JVM host mocks all
  three, and a device host's partial mix is refused with its reason rather than playing a mixed phone.
- [Phase ⑤ edits the same preferences mock] → separate fields; a rebase, not a redesign.
- [D5 changes join ordering] → the existing join tests (a failed enrolment leaves no key kept that matters; Retry
  joins) run unchanged, and the new scenario pins the refused-keep path.

## Migration Plan

No stored state changes: the preference keys keep their values, and the join's order only changes what happens on
a refused write. Rollback is a revert.
