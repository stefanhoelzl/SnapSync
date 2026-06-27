## Why

On device, the background-upload extension kept uploading every photo to a **previously-joined
event** even after the user joined a new one — the new event's storage stayed empty while the app's
UI showed it as "synced". This presented as "the app does not sync after joining an event."

Root cause: `UploadExtensionRoot` held its `KeychainConfigStore` as a process-lifetime `by lazy`
singleton and read `config.value`, which `KeychainConfigStore` seeds from the Keychain **only at
construction**. The extension process outlives a single `process()` invocation, and an event joined
by the **app** (a separate process) writes the Keychain without notifying the extension's in-memory
`StateFlow`. So a long-lived extension uploaded to the event it read at startup — despite the spec's
own requirement claiming the `eventId` is "read synchronously from the shared Keychain" (silent on
whether that read is per-cycle or once-per-process). Verified on device: forcing a fresh extension
moved uploads from the stale event to the newly-joined one.

## What Changes

- The extension **re-reads the Keychain payload at the start of every `process()` cycle** instead of
  caching a value from process construction, so a new join redirects subsequent uploads to the new
  event. `KeychainConfigStore` gains a `reload()` that re-reads the Keychain into its `StateFlow`;
  `UploadExtensionRoot.process()` calls it before reading the config.
- The `ios-background-upload` spec's config-assembly requirement is tightened to mandate the
  per-cycle fresh read (not process-lifetime caching) and gains a scenario for the redirect-on-rejoin.

## Capabilities

### Modified Capabilities
- `ios-background-upload`: the "Extension assembles config from the Keychain payload" requirement now
  mandates a **fresh Keychain read each `process()` cycle** (no process-lifetime caching), with a new
  scenario asserting a newly-joined event redirects uploads on the next cycle.

## Impact

- **`:capability:config`** (`KeychainConfigStore`, `iosMain`): new `reload()` — re-reads the Keychain
  into the `config` `StateFlow`. (Untestable on Linux; `iosMain` adapter.)
- **`:app:ios:photokit-extension`** (`UploadExtensionRoot.process()`): calls `configSource.reload()`
  before reading the config each cycle.
- **Out of scope**: the OS-owned scheduling latency between a join and the extension's next
  `process()` invocation (unforceable); the two-pass `reconcileThenEnable` double-trigger on
  provision (a separate log-noise cleanup, not behavior).
