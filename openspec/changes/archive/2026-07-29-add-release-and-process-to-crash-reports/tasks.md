## 1. Reporting adapter

- [x] 1.1 In `adapter/ios/ext-safe/.../logging/SentryCrashReporting.kt`, set `options.release` inside
      `Sentry.init` from the process bundle's `CFBundleShortVersionString`, assigned only when
      present and non-blank (reuse the existing `bundleValue()` helper, which already applies
      `takeIf { it.isNotBlank() }`).
- [x] 1.2 After `Sentry.init`, set the `process` tag on the global scope
      (`Sentry.configureScope { it.setTag("process", …) }`) to `NSBundle.mainBundle.bundleIdentifier`.
      Keep it inside `start()`, after the `processStarted` guard, so it runs exactly once per process
      and only in a DSN-carrying build.
- [x] 1.3 Do **not** set `options.dist`. Add a comment at that spot recording why the omission is
      deliberate (options `dist` overwrites at send time where `release` only fills; the omission is
      what keeps the build number crash-time accurate for `dsyms-<build>` lookup) and pointing at
      this change's `design.md` D1.
- [x] 1.4 Extend the class KDoc to cover the three new facts: the release is set explicitly because
      the KMP layer clobbers the native SDK's bundle-derived default; the process tag is
      bundle-derived because the adapter is constructed at three sites across two processes; `dist`
      is deliberately unset.

## 2. Verify it compiles on the iOS source set

- [x] 2.1 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for `iosMain`.
      **BUILD SUCCESSFUL.**
- [x] 2.2 `./gradlew build` — the canonical check (the architecture guards run here; confirm
      `RuntimeIdentityTest` and the extension-safety gate stay green, since this edits an
      extension-linked adapter). **BUILD SUCCESSFUL**, 326 tasks, guards green.

## 3. Spec of record

- [x] 3.1 Apply the `crash-reporting` delta to `openspec/specs/crash-reporting/spec.md`: the MODIFIED
      requirement (its crash scenario no longer names a release format) plus the three ADDED
      requirements (release, process tag, and the never-override rule for the build number).
- [x] 3.2 Add this change to the capability's `Decision record:` line so the rationale is reachable
      from the spec.
- [x] 3.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` — **60 passed, 0 failed.**
- [x] 3.4 At archive time, confirm the archive directory's date prefix matches the citation written
      into `openspec/specs/crash-reporting/spec.md`
      (`changes/archive/2026-07-29-add-release-and-process-to-crash-reports`). If archiving happens on
      a different day, correct the citation — a dated path that points nowhere is worse than none.
      **Archived 2026-07-29 — citation matches.**

## 4. Triage tooling

- [x] 4.1 In `.claude/skills/bugsink/SKILL.md`, document `data.release` and `data.tags.process` in the
      event-detail field list, and print both (plus dist and environment) from the drill-in script so
      they are surfaced, not merely documented. **Drill-in only** — measured: the issue objects the
      list view reads carry no release field, only `is_resolved_by_next_release`, so there is nothing
      to add to step 1.
- [x] 4.2 Add a gotcha: an event with no `release` — or one shaped `app.snapsync@<v>+<build>`, the
      SDK's own fallback, which Bugsink renders truncated as `app.snapsync` — comes from a build
      predating this change and is **not** a regression. The drill-in script flags this inline.
- [x] 4.3 Correct the skill's closing note that "`data.environment` is `production`": a
      deliberately DSN-injected dev build reports `development` (this is the documented on-device
      verification path in `Config.xcconfig`). Also added a gotcha recording that `dist` is
      crash-time *because* the app never sets the option — so the omission is not "fixed" later.

## 5. Post-merge verification

Deliberately post-merge. An early DSN-injected ssh-mac build was evaluated and declined: the DSN is
not obtainable from the repo (GitHub secrets are write-only, `.proton.yaml` carries only the Bugsink
token), a dev build would report floor values (`release=0.1, dist=1`) rather than CI's, it reaches
the app process only, and it cannot exercise 5.2 at all since a dev build has no `dsyms-<build>`
artifact. Rationale and the "force an event without a crash" recipe: `design.md`, Risks.

- [ ] 5.1 After the next `main` build reaches TestFlight and any event arrives, run `/bugsink` and
      confirm on a fresh event: `data.release` is the build's marketing version, `data.tags.process`
      names the reporting bundle id, and `data.dist` is still the build number.
- [ ] 5.2 Confirm the `dsyms-<dist>` lookup still resolves for that event's build — the `dist`
      omission is the load-bearing part and this is the only place it is observable.
