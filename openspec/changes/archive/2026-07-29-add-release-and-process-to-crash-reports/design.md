## Context

`crash-reporting` shipped with a requirement that is false. Its scenario asserts a reported crash
carries "the release (`app.snapsync@<marketing>+<build>`)"; the only stored Bugsink event
(`SNAPSYNC-1`, project 1) carries:

```
data.release     = null
data.dist        = "519"
data.environment = "production"
data.tags        = {}
timestamp        = 2026-07-21T08:58:45Z
ingested_at      = 2026-07-24T12:20:17Z     ← three days after the crash
```

Two gaps and one trap follow from that, and all three turn on SDK internals rather than on anything
in this repo. The measurements below are the forcing proofs for every decision here; each cites
sentry-kmp 0.27.0 and sentry-cocoa 8.58.2 (`gradle/libs.versions.toml` pins both). **Expiry trigger:
any upgrade of either SDK** — the whole change exists to work around one unconditional assignment,
and a fixed upstream would make part of it redundant (harmlessly, but the claims should be re-read).

**Why `release` is null.** sentry-cocoa computes a default `releaseName` from the bundle
(`SentryOptions.m:178`, `<CFBundleIdentifier>@<CFBundleShortVersionString>+<CFBundleVersion>`).
sentry-kmp then overwrites it unconditionally:

```kotlin
// SentryOptionsExtensions.apple.kt:26-31
cocoaOptions.dist = kmpOptions.dist
kmpOptions.environment?.let { cocoaOptions.environment = it }   // ← conditional
cocoaOptions.releaseName = kmpOptions.release                   // ← NOT conditional
```

`environment` is guarded, `release` is not, so our unset value wins and the default is lost.

**Why `dist` survived anyway.** `SentryClient.m:734` has an independent, options-unreachable fallback
to `[NSBundle mainBundle].infoDictionary[@"CFBundleVersion"]`. The build number is present today by
that separate path, not by anything we configured.

**Why the delivery lag matters.** A crash is cached and delivered on a later launch — three days
later in the sample. Any field resolved at *send* time can therefore describe a different build than
the one that crashed. `SentryClient.m:733-750` resolves the two fields asymmetrically:

```objc
if (nil != infoDict && nil == event.dist) { event.dist = infoDict[@"CFBundleVersion"]; }

NSString *releaseName = self.options.releaseName;
if (nil == event.releaseName && nil != releaseName) {   // GUARDED — fills only
    event.releaseName = releaseName;
}

NSString *dist = self.options.dist;
if (nil != dist) { event.dist = dist; }                 // UNGUARDED — always overwrites
```

## Goals / Non-Goals

**Goals:**

- Make `crash-reporting`'s release requirement true, and state it in a form that cannot go stale
  again.
- Let a triage session tell the app process from the background-upload extension without inferring
  it from a stacktrace.
- Record the `dist` non-decision as a requirement, so the asymmetry above is not "fixed" into a
  defect by a later contributor.

**Non-Goals:**

- **Changing the app's version scheme.** `X.Y.<build>` as a general marketing version was designed
  and rejected here — see Decisions.
- Changing the `dsyms-<build>` contract, the scrubbing rules, `SentryLogWriter`'s severity mapping,
  breadcrumb volume or configuration, or any composition root.
- Pre-merge verification. Dev builds bake no DSN by design (absence is the off-switch), so this path
  is dark everywhere except a distribution build.

## Decisions

### D1 — Set `options.release`; leave `options.dist` to the SDK

The two fields look symmetric and are not. Setting `release` is safe and setting `dist` is harmful,
for reasons that are invisible at the call site:

| | recorded at crash time? | overwritten at send time? | result |
|---|---|---|---|
| `options.release` set | yes — `SentryCrashIntegration.m:241` writes it into the SentryCrash `userInfo`, which is stored *in the crash report* | no — `SentryClient.m:739` is guarded | crash-time ✓ |
| `options.dist` set | yes — `:242`, same mechanism | **yes** — `SentryClient.m:747` is unguarded | **send-time ✗** |
| `options.dist` unset | n/a | no | converter falls back to the report's own `app_build` (`SentryCrashReportConverter.m:158`) → crash-time ✓ |

So *not* setting `dist` is what makes it crash-time accurate, and setting it would attribute a crash
to whichever build was installed when the cached report finally went out. Because `/bugsink`
resolves dSYMs as `dsyms-<data.dist>`, that failure mode is precisely the "symbolicate against a
different build's dSYMs → subtly-wrong frames" outcome the skill warns about, with nothing anywhere
signalling the mismatch. **Alternative considered and rejected:** pinning `dist` explicitly for
belt-and-braces determinism. It is the one change in this area that can only make things worse.

### D2 — `release` = the bundle's `CFBundleShortVersionString`, verbatim

Read from the running process's own bundle, assigned only when non-blank (the same
`takeIf { it.isNotBlank() }` shape `bundleValue()` already uses for the DSN). A blank assignment
would be worse than the current null: it would create an empty release record and, for `dist`, defeat
cocoa's own fallback.

This lands on the textbook Sentry mapping — **release = the version line, dist = the build** — and
gets there by doing nothing clever. Because `ios.yml` only advances `MARKETING_VERSION` when a `vX.Y`
tag appears, releases map one-to-one onto shipped App Store versions, so Bugsink's
`is_resolved_by_next_release` reads as "fixed in the next version I ship" rather than "fixed in build
612".

**Alternatives considered:**

- *Compose the string ourselves* (e.g. `"$short.$build"`, or cocoa's own
  `<id>@<version>+<build>`). Rejected: it invents an identifier that exists nowhere else, and
  duplicates a format `ios-testflight-delivery` owns.
- *Per-process release using each bundle's own id.* Rejected: the extension's bundle id would split
  every build into two release lines. Process identity belongs in a tag (D3), not in the release.

### D3 — Process identity is a scope tag derived from the bundle, not a constructor parameter

`SentryCrashReporting()` is constructed at **three** sites — `SnapSyncRoot.kt:303`,
`UrlSessionUploadController.kt:184`, `UploadExtensionRoot.kt:183` — but there are only **two**
processes (the first two are both the app). `start()` is idempotent process-wide, so whichever
composes first wins. A hand-passed `Process.App` literal that disagreed between the two app-process
sites would be invisible: no error, no log line, just a wrong tag on half the builds.

Deriving the value from `NSBundle.mainBundle.bundleIdentifier` removes the possibility. An extension's
main bundle *is* its `.appex`, so the app reports `app.snapsync` and the extension
`app.snapsync.BackgroundUpload` with no wiring, no shell change, and nothing to keep in sync. The tag
is set on the global scope inside `start()`; sentry-cocoa persists scope tags into fatal events
(`SentryCrashScopeObserver`), so it survives a crash.

The value is the raw bundle id rather than a mapped `app`/`extension` — the tag then claims nothing
beyond what it read, and there is no mapping to test.

### D4 — No `beforeSend` normalisation, and no new pure function

`SentryCrashReportConverter.m:145` reads `userContext["release"]` *before* falling back to
constructing `<id>@<version>+<build>` at `:151`. Since `SentryCrashIntegration` has already written
`options.releaseName` into that `userInfo`, a crash report from a build carrying this change reports
**our** string, and the `%@@%@+%@` construction never runs. Both event sources — SentryCrash reports
and our Kermit `Error` lines — therefore converge on one release string with no work.

An earlier draft normalised the two shapes inside the existing `beforeSend`, backed by a pure
`stripToVersion()` in `:domain` `model/` with commonTest coverage. That is dead code against this
mechanism: it would only ever act on reports written by builds predating this change. Those are a
bounded, self-extinguishing tail, and it is arguably *correct* that they look different — a release
row shaped `app.snapsync@0.2+519` means "crash from a build that predates release metadata". With
nothing composed and nothing mapped, no logic remains for a `model/` function to hold; the adapter is
two verbatim bundle reads, and a commonTest over a function returning its argument would be theatre.

### D5 — The version scheme is left alone

Making `MARKETING_VERSION` a three-part `X.Y.<run_number>` was designed in full and rejected. It
would have delivered one identifier everywhere plus semver-valid ordering in Bugsink (two-part `0.2`
fails python-`semver`'s strict `Version.parse`, so Bugsink date-orders our releases). It would have
cost `ios.yml`'s version computation, `appstore_release.py`'s `^\d+\.\d+$` guard, the `vX.Y` tag
scheme, `Config.xcconfig`, two further specs, and CLAUDE.md.

The decisive cost was operational, not clerical. `appstore_release.py:150` records that App Store
Connect allows only **one editable version at a time**, and the release tag is written last, on
success only. Under two-part versions a failed promote can be retried with a *different* build,
because `find-or-create` resolves both builds to the same `X.Y` record and reuses it. Under
`X.Y.<build>` the retry resolves to a new version string, tries to create a second record, and is
refused while the first is still editable — reintroducing by hand exactly the "delete-and-re-push
dance" that `ios-appstore-release` names as its reason for being dispatch-triggered.

Date-ordered releases are correct for a single, strictly-increasing build stream, and Bugsink's
`sort_epoch` makes a later scheme change safe. The scheme can be revisited on its own merits; because
this change's spec wording names no format (D6), that revisit needs no `crash-reporting` delta.

### D6 — The spec names no version format

`crash-reporting` will say the event carries **the marketing version the build carries**, not
`X.Y` or `app.snapsync@<marketing>+<build>`. The version format is `ios-testflight-delivery`'s
contract; restating it in a second spec is exactly how the current false claim came to exist and to
survive unnoticed. One capability, one contract.

## Risks / Trade-offs

- **A later contributor "fixes" the missing `dist`.** → The strongest mitigation is a spec
  requirement stating that `dist` is deliberately not set, plus the mechanism recorded in D1. The
  code comment alone is not enough: the omission reads as an oversight from the call site.
- **Verification cannot happen before merge, and may not happen soon after.** Dev builds bake no DSN,
  and the project currently holds zero real crashes (one watchdog termination). → Accepted. The
  failure mode is benign — a wrong or absent release is a triage inconvenience, never a user-facing
  or data-integrity fault, and the mechanism is grounded in SDK source rather than inference.

  The DSN-injected ssh-mac build (`Config.xcconfig` keeps `SENTRY_ENVIRONMENT=development` for
  exactly this) was **evaluated and declined** — it is a weaker proof than it first appears, and the
  reasons are worth recording rather than rediscovering:
  - **The DSN is not obtainable from the repo.** `SENTRY_DSN` exists only as a GitHub Actions
    secret, and GitHub secrets are write-only; `.proton.yaml` maps `BUGSINK_TOKEN` and no DSN.
    Deriving it from Bugsink's own project key over the API is untested. It has to be supplied by
    hand.
  - **A dev build carries floor values, not CI's.** `MARKETING_VERSION` is the `Config.xcconfig`
    floor and `CURRENT_PROJECT_VERSION` the `project.pbxproj` default, so the event would read
    `release=0.1, dist=1` — enough to show the fields are populated, not enough to show CI's values
    flow through.
  - **It cannot reach the load-bearing half at all.** A dev build has no `dsyms-<build>` CI
    artifact, so the dSYM-resolution check — the entire reason the `dist` omission matters — is
    unreachable outside a `main` build.
  - **It proves the app process only.** The extension's `process()` is OS-scheduled and cannot be
    forced.

  Forcing an event needs no crash: `UploadCycle`'s reconcile failure logs at `Error`, so a joined
  device pointed at an unreachable backend produces one on its next cycle. That remains the route if
  someone does want an early signal — noting it must be *join-healthy-then-break*, since a
  `SNAPSYNC_RESET_STATE` device is unjoined and runs no cycle, and `CreateEvent` is not among the
  nine `Error` call sites.
- **The legacy release tail is ugly in the Bugsink UI.** Pre-change builds that crash after this
  ships report `app.snapsync@0.2+519`, which is not valid semver, so `get_short_version()` truncates
  it to `app.snapsync`. → Accepted and bounded: it extinguishes as the installed base updates, and it
  unambiguously marks a pre-metadata build.
- **`release` adds little on its own while versions are two-part** — `0.2` is near-constant across
  builds, and `dist` already carries the discriminator. → Accepted. The value is that it makes a
  false requirement true, and it turns on Bugsink's release features, which are entirely dark today
  because every event lands in the empty release.
- **Scope tags are set after `Sentry.init`.** An event captured between init and the
  `configureScope` call would lack the tag. → Both happen synchronously inside `start()`, which both
  shared compositions run as their first act, so the window contains no capture site.

## Migration Plan

None required. No durable state, no payload field removed, no installed-base contract touched. A
build without a baked DSN is unaffected (no SDK starts). Rollback is reverting the two assignments;
events then return to `release = null` with `dist` unchanged throughout.

## Open Questions

None outstanding. The two questions this change opened and closed during design — whether
`beforeSend` runs for fatal events (it does: `SentryClient.m:859` has no `isFatalEvent` guard, and
`captureCrashEvent` routes through `prepareEvent` with `isFatalEvent:YES`), and whether crash reports
carry crash-time version data (they do: `SentryCrashReportConverter.m:145-159`) — are recorded above
rather than deferred to runtime verification.
