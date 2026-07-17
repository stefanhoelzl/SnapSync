# Proposal: move-features-upload-membership-status-trust

## Why

Migration step 5 of the `module-architecture` plan (`test/architecture/migration/PLAN.md`,
"features I"). The `:domain` core has `model/` and `ports/` (step 3a) and every platform impl
lives in an adapter module (step 4); the business rules themselves still live in the legacy
`:capability:*` / `:domain:status` modules, where nothing mechanical holds the feature laws
("features are mutually blind"; "rules in features, order in flows"). Moving them into
`domain/src/*/kotlin/app/snapsync/feature/<name>/` arms the feature-blindness and flow-no-ports
gates created pending in step 0 — from this diff on, a cross-feature reference is a red build,
not a review hope.

## What Changes

Pure `commonMain` moves — behavior-preserving, bodies byte-identical, only `package`/`import`
lines change (plus the `ledgerBackend`→`ledgerStore` name sweep in the four `app/ios` root files
this diff touches anyway, per step 3b's advisory):

- **`feature/upload`** (package `app.snapsync.feature.upload`): `UploadCycle`, `UploadArm` +
  `UploadProducer`, `BackgroundUploadPump`, the cycle gate (`UploadConfig`/`CycleGate`/
  `cycleGate`/`JoinedMembership`), `clearRequestedOffMain` (re-homed from
  `:capability:membership` — the step's named human-eyes item; dossier in design.md D4), and the
  re-join reconciliation (`ExtensionReconciler`, single-writer placement per the plan). The
  ledger trio separates per 3a's D3: `SyncEngine` + `LedgerWriter` become feature code here, and
  `LedgerStore` takes its `ports/` seat.
- **`feature/membership`** (package `app.snapsync.feature.membership`): `JoinEvent` +
  `JoinOutcome`, `DeviceEnroller` + `ManifestDeviceEnroller`, `LeaveEvent`.
- **`feature/status`** (package `app.snapsync.feature.status`): the ledger→`SyncStatus`
  projections — `SyncStatusSource`, `LedgerBackedSyncStatusSource`, `LedgerCountsSource` (+
  `LedgerCounts`, `ReadingLedgerCountsSource`, `MutableLedgerCountsSource`),
  `OwnDeviceGalleryStatusSource`. `DownloadStatusSource` stays in `:domain:status`: it is the
  download feature's read-model (implemented by `:capability:download`'s
  `StoreDownloadStatusSource`), so seating it in `feature/status` would force a cross-feature
  reference when the download feature moves at step 6.
- **`feature/trust`** (package `app.snapsync.feature.trust`): `DeviceAttestation` (+
  `tokenExpirySeconds`) — the attestation policy and the reader path (`token()`/`onRejected()`).
- **`model/` rider (forced move; design.md D2)**: `Logger.invocation` + `LogContext` move from
  `:domain:logging` into `app.snapsync.model` — feature files call `log.invocation`, the
  feature-blindness gate forbids legacy references, and `:domain` has zero project dependencies,
  so the helper must live inside `:domain`.
- **`StatusEngineBoundaryTest` retired** (`test/architecture/src/test/kotlin/app/snapsync/architecture/StatusEngineBoundaryTest.kt`
  deleted): superseded by the armed feature-blindness gate; its non-vacuity twin would turn
  `build` red the moment `domain/status` empties.
- **Adapter interim edges**: `:adapter:ios:ext-safe` and `:adapter:ios:app-only` drop their
  `:domain:logging` dependency (the seam moved into `:domain` in this diff); the
  `:capability:album` and `:domain:gallery` interim edges stay, re-documented as dying at step 6.
- Emptied modules keep their skeletons (deletions are step 6): `:capability:join` and
  `:capability:membership` become sourceless; `:capability:upload` retains `UploadPushReceiver`
  (an OS-callback receive seam — flow material, step 8); `:capability:attest` retains
  `InMemoryAttestStore` (an honest double, bound for `:adapter:fake` at step 10);
  `:domain:status` retains `DownloadStatusSource` (step 6); `:domain:logging` becomes sourceless.

## Capabilities

### Modified (spec deltas)

- **`architecture-guards`**: REMOVED "The status module never names an engine type" — the armed
  feature-blindness gate holds the same invariant strictly stronger for the moved sources (a
  `feature/status` file referencing `app.snapsync.engine`, any legacy package, or a sibling
  feature fails; text-matched, so fully-qualified references are caught; non-vacuity twin
  included), and the Konsist guard's own non-vacuity twin would go red on the emptied module.
- **`sync-status`**: MODIFIED "Module placement plugs the engine leak" and "LedgerCountsSource
  seam" — the placement claims move from the `:domain:status` module boundary to the
  `feature/status` zone and its gate.
- **`upload-lifecycle`**: MODIFIED "Upload producer seam has no destructive verb", "Lifecycle
  orchestration is tier-neutral and tested", "The arm's direction gate lives at the choke point,
  never at the invoker" — `:capability:upload` placement language becomes the `feature/upload`
  zone.

### Touched without a delta (accounting, per the archive gate)

- `event-rejoin-reconciliation`, `join-event`, `leave-event`, `device-attestation`,
  `push-registration`: behavior-preserving moves; these specs name no module placement for the
  moved types (`join-event` line "HttpEventDirectory in `:capability:join`" has been stale since
  step 4 seated it in `:adapter:generic`; step-4 precedent leaves it to the owning
  reconciliation).
- `diagnostic-logging`: `Logger.invocation`/`LogContext` move is behavior-preserving; only the
  Purpose prose names `:domain:logging`, which is not delta-addressable — reconciled at step 6
  when the module dies.
- `gallery-status`, `sync-status-screen`, `photo-selection-policy`: prose mentions of
  `:domain:status`/`:capability:upload` unaffected in substance (dep-scope and clock-free claims
  still hold); reconciled by their owning steps.
- `harness-world-model`, `full-stack-harness`, `desktop-test-harness`, `event-creation-ui`,
  `sync-status-screen`: import-line updates only.
- `ios-photokit-upload`, `ios-url-session-upload`, `ios-app-shell`: composition roots update
  imports and the `ledgerBackend`→`ledgerStore` rename; no contract change.

## Impact

- `./gradlew build`, `compileIosMainKotlinMetadata`, `:test:architecture:test` green;
  feature-blindness + flow-no-ports gates arm (PENDING → active); diagrams regenerated; beacon
  unchanged (no module create/delete — measured before/after in tasks.md).
- Every merge-facing behavior byte-identical: no runtime identity string moves (step 0's
  `RuntimeIdentityTest` holds), no threading change (`clearRequestedOffMain` keeps its
  `Dispatchers.Default` hop — design.md D4).
