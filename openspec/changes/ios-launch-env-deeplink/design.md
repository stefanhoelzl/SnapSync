## Context

An agent can drive the connected device headless over the codehydra usbmux bridge using
`pymobiledevice3 --userspace` (Python ≥3.14): no root, in-process tunnel, DDI auto-mounted.
Verified on device — app **launch** (`dvt launch app.snapsync`) and **screenshot**
(`dvt screenshot`) both work and return the real status screen.

The remaining gap is **event subscription**. Config is provisioned by a `snapsync://config?v=3&d=…`
deeplink (capability `deeplink-config`), today delivered only by the Camera scanning a QR and
forwarded via SwiftUI `.onOpenURL` → `SnapSyncRoot.onOpenUrl(raw)`. There is no headless path: iOS
17+ exposes no openURL service over USB (`simctl openurl` is simulator-only), and opening a custom
scheme via WebDriverAgent on a real device is flaky (Siri hijack / native-context URL-set broken) and
needs a signed WDA we don't have. So a human tap is required to join an event — the last blocker to a
fully-headless per-build test loop.

## Goals / Non-Goals

**Goals:**
- A headless, reliable way for a developer/agent launch to subscribe the app to a config event over
  USB, reusing the existing decode + re-provision path verbatim.
- Inert in production with no new build variant or compile-time flag.
- Zero Swift change, zero CI change, zero new tests.

**Non-Goals:**
- Taps / UI gestures (would need a signed WebDriverAgent) — out of scope.
- Forcing `processJobs()` (the background-upload extension) on demand — OS-owned, unfixable by any
  tool.
- Any change to the `snapsync://` URL contract, decoder, seams, or Keychain store.
- A one-shot/persisted "first launch only" semantic, or any on-device persistence.

## Decisions

### D1 — Transport: a `SNAPSYNC_DEEPLINK` process-environment variable carrying the full `snapsync://` URL
The app reads `NSProcessInfo.processInfo.environment["SNAPSYNC_DEEPLINK"]` and forwards the value
verbatim to `onOpenUrl`, reusing the entire tested decode/validate/re-provision path.

*Alternatives:* **process argv** — rejected on availability: `pymobiledevice3 developer dvt launch`
accepts only a single positional (the bundle id) and rejects extra tokens ("Got unexpected extra
argument(s)"), so argv is unreachable through the proven `--userspace` launcher. On the merits it was
also no simpler and introduced the iOS `NSArgumentDomain` footgun (UIKit interprets `-key value` argv
as `NSUserDefaults` overrides). A named env key is self-describing and avoids that. Carrying just the
bare `eventId` was rejected to avoid a second construction path alongside the deeplink decoder.

### D2 — Read once per process via `by lazy`; re-fires on each fresh cold launch
The read is wrapped in a `by lazy` (Kotlin's thread-safe exactly-once-per-process primitive),
realized from a `LaunchedEffect(Unit)` in `MainViewController`. A new process re-realizes the `lazy`,
so a subsequent cold launch with the variable set re-provisions — the intended per-build trigger.

*Alternatives:* a true **once-ever persisted** flag (`NSUserDefaults`/`@AppStorage`) — rejected: it
would fire once and then no-op forever, and since `apps install` is an *update* that preserves the
container, it would break the per-build loop. Mutating the env after read (`posix.unsetenv`) —
rejected: `NSProcessInfo.environment` returns a snapshot whose reflection of a runtime `unsetenv` is
undocumented/caching-dependent, strictly weaker than the in-process guard, and pulls in a posix
cinterop. A hand-rolled boolean — superseded by `by lazy`, which is the idiomatic built-in.

### D3 — Self-guarding; no compile-time guard
A process environment variable is only injectable via a developer launch (Developer Mode + DDI +
`dvt launch --env`). SpringBoard and TestFlight launches carry a clean environment, so the trigger is
inert in production by construction. This keeps one binary everywhere (dev IPA == TestFlight binary)
and needs no BuildKonfig flag or CI dispatch input.

### D4 — Spec home is `ios-app-shell`, not `deeplink-config`
What changes is a *trigger/forwarding source*, which `ios-app-shell` already owns (the
`.onOpenURL → onOpenUrl` requirement). The contract, decoder, seams, and store in `deeplink-config`
are reused untouched. Captured as a new dedicated requirement (not a scenario bolted onto the
production-shell requirement) so the self-guarding property and once-per-process semantics are stated
explicitly.

### D5 — Verification gate: log line + screenshot (not end-to-end upload)
`SnapSyncRoot.resetForReprovision()` logs `"re-provisioned: ledger + discovery cursor reset,
extension re-registered"` to `Documents/debug.log`, pulled via
`pymobiledevice3 apps pull app.snapsync Documents/debug.log`. The merge gate is ① that log line +
② a screenshot showing the ledger reset. End-to-end object-landing-in-MinIO is a separate concern
(the existing `real-s3-upload` loop) that tests the upload pipeline and depends on OS trigger
latency — deliberately excluded from this change's gate.

## Risks / Trade-offs

- **`LaunchedEffect`/VC could be recreated within a process** → the `by lazy` guard makes a second
  realization a no-op, so the ledger is never re-cleared mid-session.
- **Re-provision clears the ledger on every cold launch with the var set** → intended (deterministic
  per-build re-trigger), and harmless under the kept drain-all test setup; documented as the semantic,
  not a bug.
- **`NSProcessInfo.environment` caching across Darwin versions** → avoided entirely: we never mutate
  the env, we guard in-process.
- **Future production vector for env injection** → none known on iOS; if Apple ever exposed
  user-settable launch env, revisit D3 and add a compile-time guard.

## Migration Plan

Additive and dev-only; no data migration, no rollback concern. The single binary behaves identically
in production (no `SNAPSYNC_DEEPLINK` present → no-op). Reverting is deleting the function + the
`LaunchedEffect` line.

## Open Questions

None — resolved during exploration.
