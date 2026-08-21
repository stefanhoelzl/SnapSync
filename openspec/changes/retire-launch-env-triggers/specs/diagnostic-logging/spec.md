## MODIFIED Requirements

### Requirement: Per-process un-redacted device log

Each process (the app and the upload extension) SHALL write its diagnostic log verbatim to its own
file, applying no masking, redaction, or truncation to logged values. The two logs SHALL remain
separate, per-process files.

The **app** SHALL write `Documents/debug.log` inside its own container, pullable via
`pymobiledevice3 apps pull app.snapsync Documents/debug.log`.

The **upload extension** SHALL write `ext-debug.log` into the **shared App Group container**, so the
app process can read it and carry it in a diagnostic dump. This is the one read the previous
placement made impossible: the two processes have separate sandboxes, and an app cannot read another
bundle's `Documents/`. The app's own log SHALL NOT move — a process can always read its own
`Documents/`, so relocating it would buy no capability while breaking every existing pull command.

Because the App Group container is not USB-pullable, the extension's log SHALL be reachable through the
`DeviceLogSource` port, which the dev/test control channel exposes over HTTP. That read is a pass-through:
the port bounds the read in bytes and cuts at a line boundary, and the caller states the bound. It reads the
**current** file only — a rolled `.1` sibling is **not** reachable this way, which is a deliberate reduction
against the previous copy-the-whole-file route and is stated here rather than discovered: by the time anyone
reads a log, a roll file is stale, and including it would halve the live tail.

If the App Group container is unavailable, the extension SHALL fall back to its own
`Documents/debug.log` and SHALL record the fallback in its boot banner — a writer that silently
resolved to nothing would produce no log at all, which is indistinguishable from a process that never
ran.

The extension SHALL delete a stale `Documents/debug.log` left by an earlier build, once, so a pull
against that path fails honestly rather than returning frozen content that reads as current.

#### Scenario: Identifiers logged verbatim
- **WHEN** the app logs a line containing an event id, asset id, upload key, or URL
- **THEN** the value appears in its log in full, un-masked form

#### Scenario: No redaction layer
- **WHEN** any component logs through Kermit
- **THEN** the written line contains the original message text with no `***`, `<private>`, or truncation applied by the app

#### Scenario: The app can read the extension's log
- **WHEN** the app process assembles a diagnostic dump after the extension has run
- **THEN** it reads the extension's `ext-debug.log` from the shared App Group container without any
  cross-process request, and the extension need not be running

#### Scenario: An operator reads the extension's log without a relaunch
- **WHEN** an operator reads the extension's log through the control channel on a build carrying it
- **THEN** the current `ext-debug.log`'s tail is returned within the requested byte bound, with no copy
  step, no relaunch, and no `apps pull`

#### Scenario: A rolled sibling is not returned
- **WHEN** the extension's log has rolled and an operator reads it through the port
- **THEN** only the current file's tail is returned, and the `.1` sibling is not included

#### Scenario: The app's own pull path is unchanged
- **WHEN** an operator runs `pymobiledevice3 apps pull app.snapsync Documents/debug.log`
- **THEN** the app's current log is returned, exactly as before this change

#### Scenario: A stale extension log does not masquerade as current
- **WHEN** a device that ran an earlier build launches the extension of a build carrying this change
- **THEN** the extension's old `Documents/debug.log` is removed, so a pull against that path returns
  no file rather than months-old content
