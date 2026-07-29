## 1. Port rename (mechanical, lands first so nothing rebases on the old name)

- [x] 1.1 Rename `ports/CrashReporting` to `ports/DiagnosticsReporter`, keeping `start()`'s contract
      (build-configured, idempotent, no-op when unconfigured) verbatim in its KDoc
- [x] 1.2 Rename `:adapter:generic:fake`'s `InMemoryCrashReporting` to match, and update `:test:world`
      and every call site in `compose/`'s `snapSyncApp` / `uploadCore`
- [x] 1.3 `./gradlew build` green on the rename alone (`FakeHonestyTest` still passes)

## 2. Log placement

- [x] 2.1 Give `FileLogWriter` a destination (directory + file name) instead of resolving
      `Documents/` itself; keep one class, one behaviour
- [x] 2.2 Point `:app:ios`'s `SnapSyncRoot` at `Documents/debug.log` (unchanged behaviour) and
      `:app:ios:extension`'s `UploadExtensionRoot` at `ext-debug.log` in the App Group
- [x] 2.3 Fall back to the extension's own `Documents/debug.log` when the App Group container is
      unavailable, and name the fallback in the boot banner
- [x] 2.4 Delete a stale `Documents/debug.log` once on extension launch
- [x] 2.5 Confirm the roll bound still applies to both files under their own names (`.1` sibling)

## 3. Reading a log tail

- [x] 3.1 Add a `ports/` seam for a bounded, line-aligned tail read of a named process log
- [x] 3.2 Implement it in `:adapter:ios:ext-safe` beside the writers — seek from the end, never read
      the whole file; current file only, never the `.1` sibling
- [x] 3.3 Add the fake implementation in `:adapter:generic:fake` (port contract + initial-state
      constructor only, per `FakeHonestyTest`)

## 4. Dump assembly (`:domain`, pure and tested)

- [x] 4.1 Add the dump value type in `model/` with its four sections and the byte-budget constant,
      commented against `MAX_EVENT_SIZE` = 1 MiB and the measured ~1% JSON overhead
- [x] 4.2 Add the diagnostics assembly in `feature/`: state section, ledger section (the five
      existing counts, units labelled), and the two tails with greedy budget splitting
- [x] 4.3 `commonTest`: budget never exceeded, tails cut on line boundaries, slack borrowed when one
      log is short, state section contents, ledger units labelled
- [x] 4.4 Verify the assembly performs no write and adds no port method to `LedgerStore` / `DownloadStore`

## 5. Delivery

- [x] 5.1 Add the send operation to `DiagnosticsReporter` (no-op when unconfigured)
- [x] 5.2 Seat it in the Sentry adapter: `captureMessage` with the fixed dump message and the four
      sections as contexts
- [x] 5.3 `commonTest` pinning the scrub exemption — `scrubbedEvent` must not reach context sections,
      failing with a message that names the dump exemption if someone widens it

## 6. Command and UI

- [x] 6.1 Add nullable `sendDiagnostics` to `model/UserCommands`; build the live instance in
      `compose/`, leaving it null when the reporter is unconfigured
- [x] 6.2 Add the double-tap to the app-name label in `ScreenLayout` via `pointerInput` /
      `detectTapGestures(onDoubleTap = …)` — no `combinedClickable`, no click semantics, no ripple
- [x] 6.3 Wire the confirm dialog as local screen state (following `confirmingLeave`), with copy
      naming what is sent; no post-send feedback
- [x] 6.4 `:ui:screens` jvmTest (headless): double-tap opens the dialog, Cancel sends nothing, Send
      fires the command exactly once, a null command opens no dialog
- [x] 6.5 Confirm no accessibility click action is exposed on the label

## 7. USB export trigger

- [x] 7.1 Add `SNAPSYNC_EXPORT_LOGS` to `model/LaunchDirectives` (presence-triggered) with its
      commonTest parse coverage
- [x] 7.2 Perform the boot-time copy of `ext-debug.log` (+ `.1`) into the app's `Documents/`,
      independent of the membership-trigger ordering and applied on forge launches too
- [x] 7.3 Confirm `RuntimeIdentityTest` sees the new literal exactly once

## 8. Integration coverage

- [x] 8.1 `:test:integration`: fire the command over the world and assert one dump reaches the fake
      reporter, in budget, carrying both logs and the five counts
- [x] 8.2 Assert a dump's identifiers arrive verbatim while an error captured in the same run is
      still redacted

## 9. Docs and verification

- [x] 9.1 Update CLAUDE.md: the extension log pull becomes the `SNAPSYNC_EXPORT_LOGS` route; add the
      dump gesture and the Bugsink measurements (attachments dropped, 1 MiB cap, 413 boundary)
- [x] 9.2 Teach the `/bugsink` skill to detect a dump and write `app_log` / `ext_log` to files rather
      than printing them
- [x] 9.3 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green; run
      `./gradlew architectureDiagrams` and commit if anything moved
- [ ] 9.4 Device-verify: build a dev IPA over ssh-mac with `SENTRY_DSN` injected, sideload, fire the
      gesture, and confirm one in-budget dump lands in Bugsink with both log halves and verbatim ids
- [x] 9.5 Resolve the synthetic probe issue `SNAPSYNC-2` in the Bugsink UI
