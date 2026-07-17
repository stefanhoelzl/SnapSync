# Design — split mixed files

## Context

Migration step 2 (PLAN.md): the beacon's mixed-files measurement counted 6 files mixing a port
interface with a Ktor/SQLDelight impl; four more files on the plan's list bundle declarations
whose step-3a destinations differ (ports vs model vs impl). Splitting now makes 3a's moves
whole-file `git mv`s.

## Goals / Non-Goals

- Goal: every listed file split within its own module and package; mixed count → 0.
- Non-goals: package renames, module moves, signature/visibility changes, spec deltas.

## Decisions

- **D1 — naming convention**: the port interface + seam vocabulary keep the original file (file
  identity stays stable for references and history); technology impls move to `<Tech><Name>.kt`
  (`Http*`/`Ktor*`/`SqlDelight*`); fakes to `InMemory<Name>.kt`; declarations whose step-3a zone
  differs from their file-mates get a file named after the declaration (`PushTokenSource.kt`,
  `DeviceManifestMapping.kt`). Matches the repo's existing pairs (`SqlDelightLedgerBackend`,
  `InMemoryDownloadStore`, `HttpLeaveNotifier`).
- **D2 — DTOs ride with their sole consumer**: private wire DTOs stay beside the impl that uses
  them (`PushRegistration.kt` keeps `ApnsPushToken` + config DTOs; HTTP DTOs move with their
  `Http*` classes). They are wire vocabulary, not model.
- **D3 — the NUL marker is byte-pinned**: `DeviceManifestProducer.kt`'s skip-marker literal
  embeds a raw NUL (`"$eventId\0$json"`, the NUL written as a literal control char, not the escape; git sees the file as
  binary). The split preserved it byte-exact (verified via `od` and a pure-deletion diff) — the
  literal is a persisted on-device marker format, so flattening it would be a silent
  device-facing behavior change.
