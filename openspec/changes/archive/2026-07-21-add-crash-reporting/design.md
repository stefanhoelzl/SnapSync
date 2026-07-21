# Design: add-crash-reporting

## Context

Errors in SnapSync are deliberately reduced into state, not thrown to the UI, and diagnostics land in a
per-process on-device `debug.log` readable only over USB. That serves the dev loop; it serves App Store
users not at all — a crash or a silently failing upload on a stranger's phone is invisible to the
operator. The operator runs a Bugsink instance (Sentry-protocol-compatible, hosted by Bugsink — an EU
company) at `steho.bugsink.com`, DSN project 1.

Constraints that shaped everything below:

- **Module laws**: `:domain` is platform-free; third-party deps are withheld by module; shells are
  zero-conditional wiring; ports are named for the need. The app and the upload extension are separate
  processes, each with its own composition root.
- **Privacy posture**: the privacy policy claims no analytics/tracking and lists exactly two processors.
  An eventId **is** the upload capability; the device id is the GDPR-request correlator. Neither may
  reach Bugsink.
- **Public repo**: nothing secret can be committed; the `APS_ENVIRONMENT`/`ASC_REVIEW_CONTACT_*`
  patterns (xcconfig injection at archive time / GH secrets) already exist for exactly this.
- **Bugsink limitations**: errors only — no tracing, performance, replay, profiling; and **no dSYM
  symbolication** (open tracker bugsink/bugsink#20 since Jan 2025), so native crash frames arrive as
  raw addresses.
- **ssh-mac re-sign loop**: signs exactly the appex then the app, assuming no nested dylibs.

## Goals / Non-Goals

**Goals:**

- Crashes, handled errors, and log breadcrumbs from **production builds** of both iOS processes reach
  Bugsink, attributed to release + environment, with useful device context.
- No identity and no capability leaves the device: all UUID-shaped tokens in outgoing text are redacted.
- Dev/sideload/simulator builds send nothing, with no separate flag to maintain.
- Crash reports remain *eventually* symbolicatable: dSYMs for every `main` build are retained.
- The privacy policy honestly discloses the channel before it ships.

**Non-Goals:**

- No tracing/performance/session-replay/profiling (Bugsink cannot ingest them).
- No in-app opt-out toggle: after scrubbing, reports carry no identity — legitimate-interest basis
  (Art. 6(1)(f)), same framing as the existing App Attest bullet. Apple's OS-level share-with-developers
  setting still governs native crash collection.
- No reporting from the JVM desktop harnesses (test equipment, not a product).
- No new `:domain` port: capture rides the existing Kermit `LogWriter` seam.
- No server-side symbolication workaround (no self-hosted symbolicator).

## Decisions

### D1 — Sentry Kotlin Multiplatform SDK, initialized from Kotlin

`io.sentry:sentry-kotlin-multiplatform` (wraps sentry-cocoa on iOS). Alternatives:

- *sentry-cocoa in Swift*: best native fidelity, but the Swift shell is a pinned transcriber
  (`SwiftShellGuardTest`) and Kotlin exceptions would surface as opaque `NSException` traces.
- *CrashKiOS + sentry-cocoa*: two moving parts, still touches the pinned Swift shell.

The KMP SDK needs no Swift changes, gives Kotlin exceptions sensible traces, and initializes from the
Kotlin roots both processes already own. Risk: it is a 0.x SDK — verify it compiles under Kotlin 2.4.0
as the **first** implementation task; fallback is sentry-cocoa in Swift with a pinned guard exception.

### D2 — Capture seam: Kermit severity mapping, no new port

One `SentryLogWriter` implementing Kermit's `LogWriter`: `Error`/`Assert` → Sentry event (throwable
attached when present); `Verbose`–`Warn` → breadcrumb. Rationale: the architecture reduces errors into
state, so nothing throws past a boundary — but every such reduction already logs at error severity.
The Logger seam is the one place both processes' errors already flow through; an explicit
`ErrorReporter` port would touch `:domain`, every feature, and both roots, and under-report anything
not hand-instrumented.

### D3 — A `CrashReporting` port, started by the shared composition; Sentry adapter in `ext-safe`

Init crosses a real port (revised during apply, at the operator's direction, from an adapter-only
function): `ports/CrashReporting { start() }`, a **required** member of both `AppPorts` and
`UploadPorts` (same no-default posture as the reconciler, and for the same reason — a tier that
forgot it would fail invisibly). `snapSyncApp` (in `AppCore`'s init) and `uploadCore` (first line)
start it, so both tiers and both processes report by the One-shared-composition law, and the world
harness passes an honest `InMemoryCrashReporting` fake (`:adapter:generic:fake`) whose cell makes the
start observable to integration tests. The contract is **idempotent**: the app process composes
`snapSyncApp` AND (on the 18–26.0 tier) `uploadCore`, and the roots construct adapter instances
independently, so `SentryCrashReporting` dedupes on a process-level flag — a second start must not
re-init the SDK or register a duplicate writer (which would double every event).

Placement by linkage is unchanged: the SDK dep, `SentryLogWriter`, and `SentryCrashReporting` live in
`:adapter:ios:ext-safe` (both processes link it; no UIKit references, so the extension-safety gate
stays green). The pure UUID-redaction function lives in `:domain` `model/` beside the logging infra,
giving it commonTest coverage on JVM + simulator. Capture itself still does NOT cross the port
(D2's Kermit seam); the port owns lifecycle only.

### D4 — Scrub all UUIDs; keep the SDK's per-install id

`beforeSend`/`beforeBreadcrumb` hooks run every outgoing message/breadcrumb through one dumb rule:
any UUID-shaped token becomes `‹uuid›`. This catches eventIds, device ids, membership ids — including
identifiers added to future log lines nobody audits. Targeted scrubbing (only known eventIds) was
rejected: it requires the scrubber to know which UUIDs are which, and fails open on new log lines.

**Deliberate exception**: sentry-cocoa's auto-generated random per-install `user.id` stays. It powers
"users affected" counts (1 device crashing 50× vs 50 devices crashing once), is generated by the SDK,
and is linked to neither the backend device id nor any identity. The spec records this so a future
reader doesn't "fix" it. `sendDefaultPii` stays off.

### D5 — Production-only by DSN absence

The DSN is a GitHub secret (`SENTRY_DSN`) injected into **Release** archives through the same
`ios-archive` composite seam that bakes `APS_ENVIRONMENT`, flowing into both targets' Info.plists;
the adapter reads it from the bundle. Dev-sideload/simulator builds get no DSN → the SDK never starts.
Rejected: committing the DSN (public repo; though a DSN ships in every IPA and is not a secret by
Sentry's definition, keeping it out of git costs one secret) and a separate enable flag (a second
axis that can disagree with the first). Events carry `environment=production` and the standard
release string `app.snapsync@<marketing>+<build>`.

### D6 — Accept unsymbolicated native crashes; retain dSYMs

Bugsink cannot symbolicate dSYMs. Handled errors and breadcrumbs are message-strings — fully readable
regardless; native crash reports still group and count. Mitigation: `main` builds publish the archive's
dSYMs as a workflow artifact keyed by build number, so any specific crash can be symbolicated offline
with `atos`, and if Bugsink ships dSYM support the wiring is already done. Rejected: switching to
sentry.io (abandons the EU/self-hostable posture, adds a US processor) and skipping crash capture
(an address-only report that counts occurrences beats no trace at all).

### D7 — sentry-cocoa statically linked via SPM, both targets

The ssh-mac re-sign script signs appex-then-app and assumes no nested dylibs; a dynamic
Sentry.framework would silently break it. Static linkage keeps the archive shape unchanged. The
sentry-cocoa version must be the exact one the KMP SDK pins.

### D8 — Privacy policy: keep the claim, add the carve-out

The no-analytics/no-tracking sentence stays (still true — crash reporting is neither). Added: a
"crash and error reports" bullet under *What we process* (Art. 6(1)(f); identifiers scrubbed; random
per-install id disclosed in the spirit of the existing device-ID bullet), Bugsink in the processors
list, and a bumped last-updated date. The disclosure is a requirement of `crash-reporting`, not
`marketing-site` (whose requirements only demand the policy's existence and anchors).

## Risks / Trade-offs

- [KMP SDK 0.x vs Kotlin 2.4.0 incompatibility] → verify as task 1.x before any other work; fallback
  to sentry-cocoa-in-Swift is designed (D1) but re-opens the shell-guard pins.
- [Native crash frames unreadable in Bugsink] → accepted (D6); dSYM artifact + offline `atos`;
  re-check bugsink/bugsink#20 periodically.
- [Workflow-artifact retention caps at 90 days — a crash from an older App Store build loses its
  dSYMs] → accepted for now (releases ship well inside that window); if a version lives longer,
  park its dSYMs durably at promote time (e.g. attach to the `vX.Y` release/tag in
  `ios-appstore-promote.yml`, which runs within the artifact's lifetime) — a follow-up change.
- [Extension process constraints: short OS-scheduled lifetime could drop events] → sentry-cocoa
  persists envelopes to disk and retries on next launch; each process has its own container, so no
  cache contention with the app.
- [Breadcrumb scrubbing misses a non-UUID identifier (e.g. a photo filename)] → filenames are
  random-ish and needed for debugging; accepted. The scrub rule is pure and commonTest-pinned, so
  widening it later is one function change.
- [SPM resolution adds a network dependency to CI archive and ssh-mac builds] → both already resolve
  packages online; pin the sentry-cocoa version to keep builds reproducible.
- [A future dev build accidentally receives the DSN] → the injection lives only in the Release
  archive seam; the dev-IPA path and ssh-mac never read the secret (same guarantee as
  `APS_ENVIRONMENT=production` never reaching dev builds today).

## Open Questions

- ~~Exact sentry-cocoa version pinned by KMP SDK~~ — resolved: sentry-kmp **0.27.0** (built with
  Kotlin 2.1.21; compiles under 2.4.0) pins **sentry-cocoa 8.58.2**; both live in
  `gradle/libs.versions.toml` so the SPM pin and test-link provisioning reference one value.
- Whether the extension should also install the crash handler or only the log writer (crash handlers
  in short-lived extension processes are of limited value; decide during implementation by observing
  extension behavior — default: full init in both, it is one code path).
