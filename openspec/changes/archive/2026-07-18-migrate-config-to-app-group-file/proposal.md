# Proposal: migrate-config-to-app-group-file

## Why

Migration step 11a of the `module-architecture` plan (`test/architecture/migration/PLAN.md`,
"behavior: config → App-Group file") — the campaign's **first deliberate behavior change**. The
persisted `EventConfig` is the only cross-process runtime state still living in the Keychain: a
Keychain item **survives app uninstall**, which is exactly right for the device id (identity must
never fork) and exactly wrong for a membership — a guest who deletes the app has, by every
reasonable reading, left the event, yet today a reinstall silently resurrects the membership and
resumes uploading to it. The decided end state is **reinstall = left the event**; its storage
consequence is that the config must live where the OS scopes data to the install: the App-Group
container, beside the ledger.

Moving it is a one-way door with two traps the design must close (PLAN 11a, rebuilt after
adversarial review):

- **The false-leave window.** An absent config is a *definitive* not-joined, which the upload
  cycle's leave side acts on (clears the `joinedEventId` marker). The OS can schedule the upload
  extension before the user ever opens the updated app — so any migration that runs "at app
  startup" leaves a window where every joined device's first extension cycle reads "no file" and
  performs a false leave. The migration must live **inside the store adapter**, running in
  whichever process reads first.
- **The revert window.** Every merge ships to TestFlight and fix-forward is the only realistic
  revert. A revert build reads only the Keychain — so the Keychain entry must keep being written
  through (**copy, don't move**) for the whole soak window; its deletion is a separate later
  change (step 13b or after).

## What Changes

- **`FileBackedConfigStore`** (`:adapter:ios:ext-safe`, iosMain) becomes the iOS
  `ConfigSource`/`ConfigStore`/`ConfigReader`: the config persists as a **versioned envelope**
  (`{"v":1,"payload":<EventConfig>}`) in **`eventconfig.json`** in the App-Group container root —
  written **atomically** (temp + rename) under
  `NSFileProtectionCompleteUntilFirstUserAuthentication` (locked-readable after first unlock, like
  the ledger). It **composes** `KeychainConfigStore`: every `save`/`clear` writes through to the
  legacy Keychain item, and a read whose file is **definitively missing** (not-found error class
  only) falls back to the Keychain copy and migrates a found config into the file.
- **Unreadable-vs-absent re-grounds on file errors** (settle-list ⑥): absent = the not-found
  error class **only** (Cocoa 260/4, POSIX `ENOENT`); *any* other error — notably the
  permission-class failure of a protected read before first unlock — is unreadable
  (`ConfigRead.Unavailable`), never a leave. An **unknown envelope version** is likewise
  unreadable, never absent: a revert build opening a future build's file must defer, not leave.
- **The intelligence is pure and `commonTest`-covered**: the envelope codec + absence classifier
  in `:domain` `model/` (`ConfigFile.kt`), the full read algorithm (`configReadViaFile`) in
  `ports/` beside `configReadFrom`. The iosMain adapter is file IO + `NSError` mapping only.
- **Reinstall semantics are staged, honestly**: while the write-through lasts, a reinstall (App
  Group wiped, Keychain surviving) still resurrects the membership — indistinguishable from an
  update-in-place by design, since the fallback is what closes the false-leave window. "Reinstall
  = left" becomes true when the Keychain copy is deleted (13b+), and the spec deltas record that
  staging explicitly.
- **Wiring**: all three roots (`SnapSyncRoot`, `UrlSessionUploadController`,
  `UploadExtensionRoot`) construct `FileBackedConfigStore` where they constructed
  `KeychainConfigStore`; the `ConfigSource` StateFlow seeding and the unlock-hook `reload()`
  semantics carry over unchanged.
- **Guard**: `eventconfig.json` joins `RuntimeIdentityTest`'s pinned inventory (spec delta to
  `architecture-guards`; deliberate-red proven).

## Impact

- Affected specs: `event-link` (the config-store contract of record — the Keychain-backed store
  requirement is replaced by the file-backed one; unreadable-vs-absent re-grounded),
  `event-rejoin-reconciliation` (the staged reinstall semantics), `upload-lifecycle` (the entry
  gate's absent/unreadable inputs now stand on the file), `device-identity` (annotation: the id
  *stays* Keychain, deliberately — opposite reinstall contracts), `architecture-guards` (new pin),
  `ios-app-shell` (composition-root and protected-data wording that named the Keychain store),
  `ios-photokit-upload` (the extension's config-assembly requirement re-grounded on the shared
  store), `leave-event` (the no-transaction wording that named the Keychain), `event-invite-qr`
  (Purpose-only edit — "the `eventId` in the Keychain" → the persisted config).
- Affected code: `:domain` `model/` + `ports/` (new pure code + tests), `:adapter:ios:ext-safe`
  (new adapter), `:app:ios` ×2 + app-only controller (wiring type swap), `:test:architecture`
  (pin). No port interface changes; `:test:world`'s fakes are untouched (the three-state lever is
  port-shaped; file semantics live below the port).
- Device verification: Session C (PLAN) — update-in-place migration, reinstall behavior,
  revert-build simulation over the written-through Keychain.
