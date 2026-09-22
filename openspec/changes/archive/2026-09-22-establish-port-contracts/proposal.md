## Why

What SnapSync believes about the external systems it stands on is mostly unverified prose, and it goes
stale with nothing failing: the universal-link matrix in `iOSApp.swift` carried an unexplained
contradiction for weeks; a KDoc's `-25291` was nearly "corrected" to `-34018` — both true, for different
hosts; the limited-access "alert storm" stood for a year before re-measurement showed it false. The
doubles the world harness and every integration test stand on are gated for their *surface*
(`FakeHonestyTest`) but nothing gates their *behaviour*, and several ports' obligations — `SecureStore`'s
three-state read, `resolveOrMint`'s "normative" ordering — live in doc comments no check reads. This is
change 3 of the testing-concept sequence: establish the mechanism that closes all three gaps, proven
end-to-end on one small external system, the Keychain behind `SecureStore`.

## What Changes

- **Port contracts.** A contract is a hand-written list of clause values per port. Each clause names the
  system state it needs ("under `Inaccessible`, `read()` is `Unavailable`"). The same list runs against
  every implementation of the port — the honest fake and each real adapter — through **bindings**, one
  per (implementation, host). A binding creates a fresh implementation already in the requested state, or
  answers `Unreachable(reason)`. Outcomes are `Passed` / `Failed` / `NotRunHere(reason)` /
  `Diverged` / `NotWithin(T)`; nothing is ever silently skipped. **The contract code is the
  specification of a port's clauses.**
- **Probing is running the contract against a real implementation.** There is no generator. A clause run
  against the real thing cannot state a falsehood and keep passing; a mismatch is a failure a human
  resolves by fixing the clause or the code.
- **Every clause has a real host.** A clause that no real implementation reaches on any host is not
  admitted — it is a fact about the platform (docs), or a test of our own logic (an ordinary fake-backed
  test). A new gate derives this from the bindings and recordings, which also closes the escape hatch of
  declaring a failing clause's state unreachable.
- **Record / replay at the OS boundary** for hosts CI cannot reach. On demand, over the rig channel, the
  contract runs in-app on an entitled device and records every `SecItem*` call `IosKeychain` makes and
  iOS's answer. Every CI build replays the recording under the **current** adapter, and the hand-written
  clauses judge. A comment edit replays green; a behaviour change asks iOS something unrecorded and reads
  `Diverged` (re-record); a recorded answer that violates a clause reads `Failed` (a real finding). The
  recording is input, never expectation.
- **Hosts are a closed enum** of platform × process kind × entitlements — `JVM`, `IOS_SIM_KEXE`,
  `IOS_DEVICE_APP` in this change. OS version, device and toolchain are provenance, not identity.
- **The `SecureStore` contract**, bound by a new honest `InMemorySecureStore`, `IosKeychain` live on the
  simulator test binary (`Inaccessible`), `AppGroupFileSecureStore` on the simulator (`Empty`/`Holding`),
  and `IosKeychain` on the entitled device (recorded, replayed in CI). `IosKeychain`'s four direct
  `SecItem*` calls move behind an internal seam so they can be recorded and replayed.
- **`LedgerStoreContract` and `DownloadStoreContract` are converted** to clause values and move from
  `:test:world` to the new `:test:contracts`, so the repository has one contract shape and one runner.
- **New module `:test:contracts`** (jvm + iosSimulatorArm64 + iosArm64), linked into the app only under
  `-Psnapsync.rig=true`; `:adapter:ios:ext-safe` gains a rig-gated source set holding the device binding.
- **Deliberate departure from the commissioning brief.** The brief asked for "a generator: a probe run
  emits a contract with a provenance header". This change generates **no contract content**. Contracts are
  hand-written; what is recorded is the external system's answers, and every CI build re-judges them
  against the current clauses. The brief's provenance header survives on the recording, not on the
  contract.

## Capabilities

### New Capabilities
- `port-contracts`: the contract mechanism — clauses conditioned on states, bindings per
  (implementation, host), the host enum and the known host matrix, outcomes, the real-host rule,
  record/replay at the OS boundary, the recording format, and the in-app device run.

### Modified Capabilities
- `testing-architecture`: shared contracts are hosted in `:test:contracts`, not `:test:world`; the
  test-only module list gains `:test:contracts`.
- `harness-world-model`: `:test:world` no longer hosts the storage-seam contracts.
- `sync-ledger`: `LedgerStoreContract` is hosted in `:test:contracts`, and the fake's binding runs from
  `:adapter:generic:fake`'s tests.
- `module-architecture`: `:test:contracts` joins the contained group, withholding `kotlin-test` from
  every other main source set; the containment law admits a property-gated source set inside a
  withholding module.
- `architecture-guards`: the contract-coverage gate.
- `coverage-bounds`: `:test:contracts` joins the not-instrumented modules (test equipment).

## Impact

- **New:** `:test:contracts`; `InMemorySecureStore` (`:adapter:generic:fake`); contract bindings in
  `:adapter:generic:fake`, `:adapter:generic:app` and `:adapter:ios:ext-safe` test source sets; the rig-gated
  source set in `:adapter:ios:ext-safe`; a `/contract/<name>` rig verb (`:test:rig`, wired from
  `:app:ios`'s rig hook); the committed recording `SecureStore@IOS_DEVICE_APP.rec`.
- **Changed:** `IosKeychain` calls the Keychain through an internal seam (behaviour-preserving);
  `AppGroupFileSecureStore.write` now refuses with `SecureStoreUnavailable` instead of a bare
  `IllegalStateException` — the first defect the `SecureStore` contract caught, on its first run;
  `:test:world` loses the two contracts and its `commonMain` `kotlin-test` dependency; the fake bindings of
  the storage contracts move from `:test:world` to `:adapter:generic:fake`.
- **Build/CI:** the replay runs in the existing `iosSimulatorArm64Test` job; a device session (lease +
  rig build) is needed to record, not to verify.
- **Docs:** CLAUDE.md's module list; the `rig-channel` skill (the new verb).
- **Out of scope, recorded as follow-ups:** binding `NoSuchStore` (its `write` throws
  `IllegalStateException`, so it is not substitutable today); contracts for `AttestStore` and
  `IosAlbumMapStore`; retiring the test-local `StubSecureStore` / `FakeSecureStore`; OS-scheduled
  systems (expressible via `NotWithin(T)`, not implemented).
