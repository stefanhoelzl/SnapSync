# Design — delete-dead-weight

## Context

Migration step 1 executes the deletion ledger established by `establish-target-architecture` (D8's
burn-down) minus the two deferred items. The moves themselves are settled by the plan; the decisions
below are the ones implementation surfaced.

## Goals / Non-Goals

**Goals:** delete every step-1 ledger item behavior-preservingly; keep the step-0 runtime-identity
pins byte-identical; leave the specs true about every deleted type.

**Non-Goals:** no package renames, no file splits (step 2), no Arrow unification (step 9), no
uploader dedup (steps 7/10), no new abstractions.

## Decisions

### D1 — Device id is a `() -> String`, not an interface; the class rides `:domain:keychain`
The plan's call, confirmed cheapest at every use site: `DeviceAttestation` and `JoinEvent` take a
supplier, tests inject `{ DEVICE }`, and the roots pass `KeychainDeviceIdentity()::deviceId` /
`{ deviceId }`. The class moves (not dissolves) because its read/mint/migrate contract and the
pinned Keychain pair are already tested and guarded there. The attestation root keeps constructing
its **own** `KeychainDeviceIdentity` instance (bound as a method reference) rather than borrowing
the root's `deviceId` lazy — preserving the pre-change instance topology and resolution timing
exactly.

### D2 — The merged name refresh adopts the details client's stricter semantics
`HttpEventMetadataSource.name()` accepted any 200 carrying a `name`; `HttpEventDetailsSource`
yields `Found` only when **both** `name` and a canonical `startsAt` are present. The name refresh
therefore now ignores a 200 lacking a parseable `startsAt`. Accepted: the backend guarantees
`startsAt` on every 200 (it synthesizes one for legacy markers), so the divergence is unreachable
in production, and one client with one semantics is the point of the merge. Rejected: keeping a
looser parse path alive for the refresh — that is exactly the duplicate this step deletes.

### D3 — The beacon must not scan itself
Four deletion-ledger patterns (`interface LedgerReader`, `class LoggingPushReceiver`,
`interface EventMetadataSource`, `interface LeaveNotifier`) self-matched the beacon's own quoted
pattern strings, so those items could never reach zero and the beacon could never go green. The
deletion-ledger scan now excludes `test/architecture/migration/` — the same self-exclusion
`targetModules` already applies. Discovered because `interface LedgerReader` matched **only** the
beacon: the real declaration was `open class LedgerReader`, so that item was self-match-only from
birth.

### D4 — Spec deltas beyond the plan's four
The plan names `event-link`, `device-identity`, `join-event`, `event-creation-ui`. The archive's
dead-type gate (openspec/config.yaml) also finds `LedgerReader` named in `sync-ledger`,
`ios-photokit-upload`, `ios-app-shell` and `LeaveNotifier` in `leave-event` and `event-link`'s
switch requirement. Those requirements are restated minimally — same invariants, concrete names —
rather than left lying about deleted types. (`ios-app-shell`'s requirement also named a
`LedgerWatcher` that has not existed for some time; the restatement pins what the code does: the
app constructs no `LedgerWriter`.)

## Risks / Trade-offs

- [Name refresh stricter than before (D2)] → unreachable divergence per the backend contract;
  visible in `debug.log` as an unchanged name if it ever fires.
- [Reader-typed compile-time narrowing gone (D7 of the target: text gates over ceremony)] → the
  single-writer invariant was never enforced by the narrowing (any holder could construct a
  writer); it rests on composition, as before, and on the target's zone gates going forward.

## Migration Plan

Single change on branch `arch`; gates: `./gradlew build`, `compileIosMainKotlinMetadata`,
`architectureDiagrams`, beacon before/after. Rollback is `git revert` of the one commit set.

## Open Questions

None.
