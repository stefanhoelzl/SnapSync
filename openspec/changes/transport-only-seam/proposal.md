## Why

The upload backend seam `BackgroundTransfer` is three things at once: the transfer lifecycle, a photo-library
reader, and — through the adapters that implement it — a ledger reader and writer. Every implementation
forwards `discoverResources` and `resourcesFor` to the same shared `IosDiscovery` in one line, and each holds
the whole `LedgerStore` to use two of its operations plus, on the app-driven tier, a third that carries a
recovery decision (which `REQUESTED` rows are stranded) inside an iOS-only adapter no host test reaches. The
tierless-upload programme will run both backends as transports around one core (phases M8a/M8b); a seam that
is only the lifecycle makes that cleaner, and this change is phase M7 of it.

Verifying the seam against the tree also found a defect on the OS-driven tier: `retryJob` looks the system
job up again by `destination.URL.lastPathComponent == key`, a v1-route rule. Under the v2 byte route that
segment is the resource's role, so the lookup matches nothing and the OS's single free retry is never
applied — every first failure waits for its retry to be spent and is then re-created by the cycle.

## What Changes

The change lands as **one PR with two commits**, (a) then (b), each independently buildable.

**(a) Photo-library reads leave the transport seam** — behaviour-preserving:

- A new port **`UploadDiscovery`** (`:domain` `ports/`) carries `discover(sinceToken, policy): Discovery` and
  `resourcesFor(keys)`. `IosDiscovery` implements it and each root binds it **once**; the three transports'
  forwarding methods are deleted, and `Discovery` moves beside the new port.
- **`SelectionScopedTransfer` becomes `SelectionScopedDiscovery`**, wrapping `UploadDiscovery` instead of the
  transport. Its read discipline is unchanged.
- `IosDiscovery.buildRequest` moves out to a top-level upload-request builder in the same module, so no
  transport depends on the discovery class.
- The enter/exit log lines (`platform.discoverResources`, `platform.resourcesFor`) keep their text, emitted by
  `IosDiscovery` under the logger the root passes.

**(b) Transports hold no ledger store; the stranded decision moves to the cycle; PhotoKit retries work** —
changes behaviour only in the retry fix:

- A new narrow port **`TransferRecord`** declares `markTerminal(key, outcome)` and `entryForDestination(path)`;
  **`LedgerStore` extends it**. The three transports and the world's fake queue receive a `TransferRecord`,
  never a `LedgerStore`. No operation is added to either interface.
- **`BackgroundTransfer.liveKeys(): Set<String>?`** reports the ledger keys of the transfers the transport
  still holds, or `null` for a durable OS queue that cannot enumerate them (OS-driven tier, simulator
  substitute).
- **The stranded reconciliation moves into `UploadCycle`**, at the same position (immediately after
  `drainTerminals`): `requestedKeys − liveKeys` → guarded `markTerminal(FAILED)`, with the same two log lines.
  `strandedKeys` and its tests move from `:adapter:ios:app-only` into `feature/upload`.
- **The PhotoKit `retryJob` finds its system job by the same route the drain resolves rows by** — the
  recorded destination path, with the v1 last-segment fallback — so the free retry is applied.
- The target-bound factory becomes `uploadJobQueue(log, record: TransferRecord)`.
- A `:test:architecture` gate keeps `LedgerStore` out of transport adapters.

No wire format, ledger schema, UI or backend change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-url-session-upload`: the port implementation drops discovery and gains `liveKeys`; the stranded
  reconciliation is decided by the cycle over the adapter's reported live set; key resolution is the shared
  `UploadDiscovery`; the delegate records through `TransferRecord`; module placement no longer names the walk.
- `ios-photokit-upload`: a retry job is found by its recorded destination path; the PhotoKit adapter records
  through `TransferRecord`; discovery is bound by the root, not delegated by the job-queue bindings; the
  extension target's adapter inventory is restated.
- `sync-ledger`: the reader/writer split names `TransferRecord` as the one narrow surface a platform callback
  records through; the guarded terminal write is declared there.
- `limited-photo-access`: the read-discipline requirement names `SelectionScopedDiscovery`.
- `harness-world-model`: the world's fake job queue records through `TransferRecord` and no longer serves
  discovery; a fake `UploadDiscovery` carries the token-delta feed, key resolution and their observability.
- `architecture-guards`: a gate that no transport adapter references `LedgerStore`.

Not modified, checked:

- `module-architecture` — a new need-named port and a core-to-adapter read are ordinary under "Ports are the
  I/O boundary named for the need". No callback into the core is introduced, so "Commands cross one door" is
  untouched; that is a design constraint of this change, not an amendment.
- `upload-lifecycle` — its stranded clause says a mechanism "reconciles stranded in-flight rows precisely from
  its own enumeration", which stays true: the enumeration is the transport's and the reconciliation runs in
  that mechanism's cycle.
- `gallery-status` — `CandidateSource` is unchanged; only its KDoc's pointer to where the cursor lives moves.
- `sync-engine`, `event-album`, `device-manifest` — untouched.

## Impact

- `:domain` — `ports/` (new `UploadDiscovery`, new `TransferRecord`, `BackgroundTransfer` loses two members
  and gains `liveKeys`, `LedgerStore` extends `TransferRecord`, KDocs of `CandidateSource` and
  `DiscoveryStore`); `feature/upload` (`UploadCycle`, `SelectionScopedDiscovery`, the moved `strandedKeys`);
  `compose/` (`UploadPorts` gains the discovery port).
- `:adapter:ios:ext-safe` — `IosDiscovery` implements the port; the request builder moves out;
  `IosPhotoKitUploadPlatform`, the `uploadJobQueue` expect/actuals, and the simulator substitute.
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform`, `UrlSessionOutcome` (stranded rule leaves).
- `:adapter:generic:app`, `:adapter:generic:fake` — none beyond `LedgerStore` now extending `TransferRecord`.
- `:app:ios`, `:app:ios:extension` — each root binds `IosDiscovery` once and passes `TransferRecord`.
- `:test:world` — `FakeBackgroundTransfer` splits; a fake `UploadDiscovery`; `World` exposes both.
- `:test:integration` — tests reading discovery observability address the discovery fake.
- `:test:architecture` — the new transport-ledger gate.
- `architecture/ports.md` regenerates.
- Changelog label: `bug` (the retry fix is the one behaviour a user's device changes; the rest is internal).
- Programme: supersedes the handoff's core-owned terminal sink (D14) — a sink the core implements would be an
  adapter calling a feature directly, which "Commands cross one door" forbids, and no flow command can be
  called synchronously. M2 inherits `liveKeys()` and moves only where it is asked; M8a/M8b record through
  `TransferRecord`.
