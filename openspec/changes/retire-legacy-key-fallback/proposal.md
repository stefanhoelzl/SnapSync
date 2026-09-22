## Why

The PhotoKit adapter still carries the **v1 last-segment fallback**: a returned upload job whose destination
path matches no recorded `destinationPath` has its ledger key read from the last segment of a v1 destination
(`/files/devices/<deviceId>/<key>`). It can only serve a job created by a build from before
`30489610` (2026-08-28, "the device speaks v2"). Since then no build creates that shape, and the
`selection-is-the-walk` design record left open "whether the v1 last-segment fallback still has any job to
serve — not measured". It cannot be measured: no current build can create an old-shape job. So the decision
rests on reasoning, and the reasoning says the population it serves is at most tiny, is shrinking, and ends
around 2026-09-27. The operator has chosen to delete it now rather than wait out that window, and to accept
the residual risk it leaves (design, D1).

## What Changes

- **Remove** the v1 fallback from job resolution in `:adapter:ios:ext-safe`: `legacyKeyOf`, the
  `FetchedJob.Emit.legacyKey` field, the `legacyKey`/`legacyRowExists` inputs to `jobRowOf`, and the fallback
  lookup in `IosPhotoKitUploadPlatform.rowFor`. A job is found by its recorded destination path, and only by
  that.
- **Narrow** the recognised byte-route shapes to the v2 shape `/files/devices/<deviceId>/<assetId>/<role>`. A
  job with a v1 destination is then **unmappable**: acknowledged, and counted and reported at `Error` — not
  pruned quietly at `Info` (design, D2).
- The `.retry` lookup (`retryJobMatching`) resolves through the same single route.
- Drop the fallback tests from `PhotoKitJobMappingTest`. Add one that pins a v1 destination as unmappable.
- Correct the spec text that still describes last-segment recovery. That includes one sentence in
  "Engine-gated real upload-job creation" that has been wrong since v2 shipped.
- **Narrow** the transport ledger surface: `get(key)` moves from `TransferRecord` back to `LedgerStore`. The
  v1 fallback was its only transport caller, so a transport keeps the guarded `markTerminal` and the
  destination read (design, D5).
- No ledger migration. `destinationPath` stays nullable (rows from before `7.sqm` still read and write), but no
  job-recovery route reads a row that lacks it any more.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `ios-photokit-upload`: "Completion and retry adjudication" loses the v1 fallback, its "previous build still
  resolves" scenario, and the fallback leg of the `.retry` lookup. It gains a scenario that a v1-shaped job is
  unmappable. "Engine-gated real upload-job creation" stops claiming that ack-path recovery reads the last path
  segment.
- `sync-ledger`: "The ledger records the destination a job was sent to" no longer says that a row without a
  destination path is recovered by a tier-specific fallback. Such a row stays usable, but a job cannot be
  resolved to it. "Reader and writer capability split": `TransferRecord` carries `markTerminal` and one read
  (`entryForDestination`); `get(key)` is `LedgerStore`'s.

## Impact

- Code: `adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/ios/upload/PhotoKitJobMapping.kt`,
  `IosPhotoKitUploadPlatform.kt`, and `adapter/ios/ext-safe/src/iosTest/.../PhotoKitJobMappingTest.kt`;
  `domain/ports/.../TransferRecord.kt` and `LedgerStore.kt` (the `get` move); the message text in
  `:test:architecture`'s `TransportLedgerGateTest`. Every `TransferRecord` implementation is a `LedgerStore`, so
  no implementation changes.
  Comment-only: the `7.sqm` header stays as the historical record of that migration (a committed migration is
  not rewritten).
- No backend, API, ledger-schema, trigger (`11.sqm`) or UI change. `StoredUploadSettle` is unchanged.
- Risk: a device still holding a failed v1-shaped job can be left with a `REQUESTED` row that nothing
  re-requests. That surfaces as an `Error` event rather than silently (design, Risks).
