## Why

On a build that reports nowhere — every development, sideload and simulator build — the hidden bug-report
gesture does nothing at all, so the one path that gathers both processes' logs and the sync state into a single
report cannot be used, or even seen, on the builds the developer tests with. The report is just as useful kept
on the phone: it can be pulled off over a cable like the device log, without shipping a reporting build.

## What Changes

- The double-tap on the app's name opens the report sheet on **every** build, not only on distributed ones.
- On a build with a reporting destination nothing changes: the sheet says the report goes to the developer's
  error-tracking service, and Send sends it.
- On a build with no reporting destination the sheet says the report is **saved on this device**, its button
  reads **Save**, and saving keeps the report on the phone, replacing the previous one. Nothing leaves the phone.
- A development build still reports nothing anywhere: no crash report, no bug report.
- The control channel's bug-report command stops refusing on such builds and saves the report as the sheet does.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `privacy-security`: the bug-report requirement — the gesture is offered on every build; a build that cannot
  report keeps the report on the device instead of doing nothing.

## Impact

- `:domain:services` crash reporting: a report on a build with no destination is written to the app's private
  files instead of being sent.
- `:domain:model` / presentation / `:ui:screens`: the sheet's wording and button depend on where the report
  goes; the command is always wired.
- `:test:rig`: the `sendDiagnostics` refusal (409) is retired.
- No backend, storage-zone or App Store change. Distributed builds behave exactly as before.
