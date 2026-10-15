# Tasks

## 1. Remove `protectedDataAvailable` (design D2, its own commit)

- [x] 1.1 Delete the port member from `ProcessInfo`, its implementations in `IosProcessInfo` and `AndroidProcessInfo`
  (and the `@NeverAnswers` entry), the recording proxy's member, `InMemoryProcessInfo`'s cell, `ProcessInfoMock`'s
  field and its `MockState` encoding; verify `./gradlew compileIosMainKotlinMetadata` and the `:adapter:generic:mock`
  JVM tests pass (`MockStateTest` updated)
- [x] 1.2 Delete `ProcessInfoContract`'s two availability clauses and `UNLOCKED`/`LOCKED` states, every binding's
  `reaches` of them, and `test/contracts/recordings/ProcessInfo@IOS_DEVICE_APP.LOCKED.rec`; verify
  `ContractCoverageTest`, `ClauseCoversTest`, `AdapterCoverageTest` and `ProxyCompletenessTest` pass
- [x] 1.3 Delete `ProcessInfoLevers.protectedData` and `ProcessInfoView.protectedDataAvailable` and the operator's
  implementation; update `docs/testing.md`, `docs/architecture.md`, CLAUDE.md's module map and the `rig-channel` skill
  where they name it; verify `LeverFacesTest` passes and `grep -rn protectedData` finds no live reference outside the
  archive
- [x] 1.4 Run `./gradlew build` and `./gradlew architectureDiagrams`, commit the diagrams if they moved; verify the
  build is green

## 2. The device lock (design D1)

- [x] 2.1 Make the Keychain mock's unavailability operator state (plumbing on the mock), and give the databases and
  files mocks a sealed state each — an open fails as `UNOPENABLE_*` does, an area answers as `UNAVAILABLE` does;
  verify the mock contract bindings enter `SecureStoreState.INACCESSIBLE`, the databases' unopenable states and
  `FilesState.UNAVAILABLE` through that plumbing and pass
- [x] 2.2 Add `DeviceLevers` (`device`: `lock()`, `unlock()`) in `:test:levers`, needing the three systems mocked, registered on both hosts and classified in `RigVocabulary`; KDoc states it plays the
  phone before its first unlock and that the per-class behaviour is Apple's documented semantics, unmeasured on a
  device; verify `LeverFacesTest` and `:test:control`'s JVM host tests pass and `rig.advertise()` lists `device`
- [x] 2.3 Delete `DatabasesLevers.lock` and `DatabaseSwitches.locked`; move `CycleEntryGateIntegrationTest` to
  `device.lock()` / `unlock()`; verify that test passes unchanged in what it asserts
- [x] 2.4 Document `device` in `docs/testing.md` ("The device's stores") and the `rig-channel` skill, replacing
  `databases.lock`; verify `RunbookSkillsTest` passes

## 3. The store faults (design D3)

- [x] 3.1 Gather the preference keys into a `PreferenceKeys` object beside `SecureSlots` (values unchanged), and make
  the preferences mock's foreign / refused-write state operator plumbing armed per key and count; verify
  `MobileDataSettingTest`, `PushRegistrationRecord`'s tests and the `Preferences` mock contract binding (entering
  `FOREIGN` / `UNWRITABLE` through the plumbing) pass
- [x] 3.2 Add `KeychainLevers.fail(item, kind, times?)` with `KeychainFault` (`READ`, `WRITE`) and `KeychainItem.DEVICE_ID`,
  implemented by the rig over the Keychain mock's per-slot fault plumbing, each failure a `WorldEvent.KeychainFaulted`;
  verify a `:test:control` JVM host test arms one and observes its event
- [x] 3.3 Add `PreferencesLevers` (`preferences.fail(item, kind, times?)`, `PreferenceItem` = `MOBILE_DATA`,
  `PUSH_REGISTRATION`, `PreferenceFault` = `READ`, `WRITE`), rig-implemented, needing `PREFERENCES_MOCKED`, each failure
  a `WorldEvent.PreferencesFaulted`; verify a `:test:control` JVM host test arms one and observes its event, and the
  `rig` CLI derives `preferences fail`
- [x] 3.4 Document `keychain.fail` and `preferences` in `docs/testing.md` and the `rig-channel` skill; verify
  `./gradlew build` is green

## 4. One mobile-data decision (design D4, spec `mobile-data`)

- [x] 4.1 Make one function decide allowed / not allowed from the stored answer, derive `allowed` and
  `transferNetwork()` from it, and keep the three-valued reading only for `describe()`; verify `MobileDataSettingTest`
  covers absent, `on`, `off`, an unknown value and an unreadable one, each for both the menu and the transfer rule
- [x] 4.2 Add a behaviour test claiming "A choice the phone cannot read": `preferences.fail(MOBILE_DATA, READ)`
  before launch, then wait for the screen showing mobile data off and a transfer held to an unrestricted network
- [x] 4.3 Add a behaviour test claiming "A change that cannot be saved": `preferences.fail(MOBILE_DATA, WRITE)`, flip
  the switch, wait for the screen's could-not-be-saved notice and the switch back at the choice in effect

## 5. A join keeps the key before it enrols (design D5, spec `join-event`)

- [x] 5.1 Move the key keep / forget in `JoinEvent.join` ahead of the enrolment and update its comment; verify the
  existing join feature and integration tests pass
- [x] 5.2 Add a behaviour test claiming "A join whose key the phone cannot keep": `keychain.fail(EVENT_KEY, WRITE)`,
  tap Join on an encrypted event, wait for the failed phase with Retry, observe at the backend that the device is not
  enrolled, then Retry and wait for the joined screen

## 6. The locked-phone and lost-key scenarios (specs `delivery`, `app-experience`, `sync-status`, `invite-link`)

- [x] 6.1 "A wake before the first unlock does not make the phone a new member" (new) and "A locked-device wake keeps
  the membership": join, `device.lock()`, deliver a background wake, `unlock()`, foreground; wait on the backend's
  events and the screen — still joined, no new device enrolled, the held photo uploaded after the unlock
- [x] 6.2 "Uploads while locked": a background upload test with the phone locked after its first unlock, whose
  comment states that no store is sealed in that state (design D1)
- [x] 6.3 "A locked phone is not mistaken for a lost key": an encrypted event, `device.lock()`, a background wake,
  `unlock()`, foreground; wait for the joined screen and assert it never asked for the invite
- [x] 6.4 "A restored phone asks for the invite", "Reopening the invite restores a lost key" and "An incomplete
  invite does not restore a lost key": claim them by scenario on behaviour tests over `keychain.forget(EVENT_KEY)`,
  splitting `EncryptedEventIntegrationTest`'s lost-key test where one test would claim several outcomes
- [x] 6.5 The device identity under a refused write: a behaviour test where `keychain.fail(DEVICE_ID, READ)` is armed
  for a background wake and the backend records no new device; fix any failure it shows

## 6b. What the behaviours found (design D6, D7)

- [x] 6.6 The download controller reads this device's id when it reads the union, never at composition; verify the
  locked-phone behaviours compose a background launch with the Keychain sealed
- [x] 6.7 `OnForeground` re-reads the membership before it assembles the status host; verify "A wake before the first
  unlock does not make the phone a new member" holds `neverScreen<CreateEvent>` after the unlock

## 7. Integration checks

- [x] 7.1 `./gradlew :test:architecture:verifiesReport` lists none of the ten scenarios as unclaimed
- [x] 7.2 Raise (or delete) the integration exception of every module these tests move in the root build file's
  `coverage { }`, per its rule; verify `./gradlew build` is green, with `deno` on PATH
- [x] 7.3 `./gradlew compileIosMainKotlinMetadata` and `./gradlew architectureDiagrams` (committed if moved) pass
- [x] 7.4 `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` and the change's own validation pass
