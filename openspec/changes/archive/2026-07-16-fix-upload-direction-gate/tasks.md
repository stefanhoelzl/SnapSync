## 1. Prove the bug (gate: if these pass, the analysis is wrong — stop)

> **Correction, made during apply.** 1.1/1.2 originally specified `:capability:upload` `commonTest` cases.
> Those cannot be red today: `UploadCycle` has no direction input at all — that *is* the bug — so the test
> would not fail, it would not compile. The provable-today test is the **integration** one (originally parked
> at 10.2), which asserts the world outcome the member actually cares about. Moved here; the `:capability:upload`
> unit tests are fix-side regressions and now live at 3.7.

- [x] 1.1 Add `download_only_uploads_nothing_when_the_cycle_actually_runs` to `:test:integration`: a
      `DownloadOnly` membership, an own asset, and the cycle **driven** — assert no job created, no bytes
      landed, no manifest listing. **RED**, first assertion:
      `AssertionError: download-only must create no upload job` (`FullStackIntegrationTest.kt:331`).
- [x] 1.2 The union-leak half (no `onDiscovery` / no manifest listing) folded into the same test rather than a
      separate one — same membership, same drive, three assertions.
- [x] 1.3 Gate check: the bug is confirmed. The analysis stands; proceed.
- [x] 1.4 **New finding.** `download_only_imports_foreign_but_uploads_nothing_and_masks_the_upload_arrow`
      (`FullStackIntegrationTest.kt:287`) asserts `"download-only uploads nothing"` **without ever calling
      `w.runUploadCycle()`** — its comment, *"the producer never runs"*, is D3's assumption used as the test's
      method rather than its finding. It is vacuous and has been green since it was written, directly above the
      leak. Make it drive the cycle, or fold it into 1.1 and delete the vacuous half. Do this **after** the gate
      lands (it is red until then).

## 2. `Contribution` — the type

- [x] 2.1 Add `sealed interface Contribution { data object None; data class Since(val cutoff: String) }` to
      `:domain:gallery` (package `app.snapsync.gallery`), with a KDoc stating why it has no default in either
      polarity (permissive uploads everything; fail-closed silently shares nothing).
- [x] 2.2 Add `commonTest` coverage that `None` carries no cutoff and the two states are distinct
      constructors.

## 3. The gate at the choke point

- [x] 3.1 Add `CycleResult.SKIPPED`. Fix the resulting non-exhaustive `when` compile errors — each is a
      deliberate decision point, not a mechanical edit.
- [x] 3.2 Replace `UploadCycle`'s `photoCutoff: suspend () -> String` with a required
      `contribution: Contribution` (no default). Update the KDoc to carry the rationale from the existing
      `photoCutoff`/`reconcile` docs.
- [x] 3.3 Return `SKIPPED` at the top of `run()` on `Contribution.None` — before the reconcile, the walk, job
      creation, the manifest write, and the notify. Do not advance the discovery cursor.
- [x] 3.4 Thread `Since(cutoff)` where the cutoff is read today: `UrlSessionUploadController`,
      `UploadExtensionRoot`, `:test:world`. Bind `None` when the membership's direction excludes upload.
- [x] 3.5 Update the ~11 `photoCutoff = { … }` test call sites to pass `Contribution`.
- [x] 3.6 Confirm 1.1 now goes **GREEN**.
- [x] 3.7 Add the fix-side `commonTest` regressions in `:capability:upload` (originally 1.1/1.2, which could
      not exist before `Contribution` did): `Contribution.None` creates no job, fires no `onDiscovery`, returns
      `SKIPPED`, and does not advance the discovery cursor. These run on JVM **and** `iosSimulatorArm64`; the
      integration proof at 1.1 is the world-outcome half.

## 4. No background work for a non-contributor

- [x] 4.1 `BackgroundUploadPump`: never schedule on `SKIPPED`, at every trigger — including `onBackgroundTask`,
      whose re-arm is otherwise unconditional.
- [x] 4.2 Add `commonTest` coverage against the existing fake `BackgroundScheduler`: `SKIPPED` from each
      trigger schedules nothing.

## 5. `N` moves downstream of the gate

- [x] 5.1 `OwnDeviceGalleryStatusSource`: take `Contribution` in place of the cutoff; return `0` for `None`
      **without enumerating**. Assert the no-walk property, not just the count.
- [x] 5.2 Update the composition roots and `:test:world` to pass `Contribution` to the refresh.
- [x] 5.3 Add `commonTest`: a `None` membership totals `0` while the library holds admitted-looking photos.

## 6. Remove the arrow masks

> **Correction, made during apply.** 6.1 originally said to confirm `N = 0` in the harness *before* removing
> the masks. That observation cannot distinguish what it must: with the mask in place, a download-only
> membership hides the upload arrow whether `N` is `0` or `4000`, so "reads In sync" is true in both worlds
> and proves nothing. The visual check is only meaningful **after** 6.2. Reordered; the logic check moves to
> an integration assertion (6.1a), which drives the same `:test:world` status source the harness does.

- [x] 6.1a Assert in `:test:integration` (real status stack over `:test:world`): a download-only membership
      reports `N = 0` and the joined screen reads `InSync`, with the masks **already removed**. This is the
      gate; it is the same source `:app:desktop:run` renders, minus the pixels.
- [x] 6.2 Remove both direction branches from `syncHealth`; drop its `direction` parameter and the now-unused
      plumbing.
- [x] 6.1b **After 6.2**: drive a download-only join in `:app:desktop:run` and confirm the screen still reads
      "In sync" with no upload arrow — now genuinely load-bearing, because nothing is masking it. If the arrow
      spins, `N` is wrong and the smoke detector is working as designed. Operator-run (needs a display).
      **Confirmed by the operator** against the real status source, with the masks already removed — so the
      "In sync" observed is reachable only via `N = 0`, not via a force-hidden arrow.
- [x] 6.3 Update `:domain:presentation` tests: the download-only and upload-only cases now settle via a zero
      total, not a mask.
- [x] 6.4 Add a test asserting the upload arrow **shows** when a non-contributing membership reports upload
      work — the smoke detector the mask used to silence.

## 7. Posture-explicit bindings

- [x] 7.1 Remove `?: true` from the download binding at `SnapSyncRoot.kt:344`; make the read three-valued so
      no membership means no arm.
- [x] 7.2 Remove `DownloadController`'s `downloadEnabled: () -> Boolean = { true }` default; make it required
      and three-valued. Update call sites and tests.
- [x] 7.3 Add `commonTest`: an absent membership performs no reconcile (rather than defaulting to enabled).

## 8. Silent push drives the upload arm

- [x] 8.1 Add `pump.onSilentPush()` — drains, and re-arms (`alwaysScheduleNext = true`), subject to the
      `SKIPPED` rule from 4.1.
- [x] 8.2 Add `UploadPushReceiver` in `:capability:upload`, mirroring `DownloadPushReceiver`: active-event
      guard, then the pump. `commonTest` for the guard, including the locally-left-event case.
- [x] 8.3 Compose a fan-out receiver in `SnapSyncRoot` so one push drives both arms. Keep `completion()`
      prompt — never held for the cycle.
- [x] 8.4 `commonTest`: a push to a download-only membership passes the active-event guard, drives the pump,
      and still creates no job and schedules nothing.

## 9. Foreground re-arms

- [x] 9.1 `pump.onForeground()`: `alwaysScheduleNext = true`.
- [x] 9.2 `commonTest`: foreground on a contributing membership schedules a task with no prior `onStart`
      (the force-quit recovery); on a non-contributing one it schedules nothing.

## 10. Verify

- [x] 10.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green.
- [x] 10.2 The integration case over `:test:world` — moved to 1.1 and written first, as the proof rather than
      the confirmation.
- [x] 10.3 On the SE2, force the app-driven tier (`SNAPSYNC_FORCE_URLSESSION_UPLOAD=1`) with a
      `direction=download` deeplink and `SNAPSYNC_SEED_POLICY`. Deregister the extension first (the
      download-only join on the PhotoKit tier, per `app/ios/CLAUDE.md`) or it uploads behind the tier's back.
      Confirm `debug.log` shows the cycle declining and the bunny zone gains nothing.
      **VERIFIED on the SE2** (operator joined download-only via the UI; agent forced the tier). The real
      `SnapSyncRoot.onForeground()` path — the one that leaked — reached the cycle and it declined:
      `url-session runCycle: config ok — invoking UploadCycle` → `cycle skipped — this membership contributes
      nothing (direction excludes upload)` → `← url-session.runCycle = SKIPPED (2ms)`. Zero upload jobs;
      `gallery: … → N=0 (no enumeration)`; no `scheduler.scheduleNext` after the SKIPPED (no heartbeat for a
      non-contributor); 0 `photokit.` calls (tier isolation held). Control, same device 20 min earlier under
      the prior `Both` membership: `enumerated 6 resource(s) → N=3 … in 113ms` — three admissible photos were
      present and the walk found them. The screen read "In sync" with no arrows — reachable only via `N=0`,
      since the masks are gone.
- [x] 10.4 Confirm the ≥26.1 tier is untouched: a normal join still uploads.
      **Partially, and honestly:** no byte upload was observed on device (the discovery cursor was settled, so
      nothing new existed to upload) and `process()` timing is OS-owned, so the extension path cannot be forced.
      What IS proven on hardware is the concern behind this task — that the binding is neither inverted nor
      stuck: the SAME `Contribution.of` call took the `Since` branch under `Both` (`enumerated 6 resource(s) →
      N=3 … in 113ms`, full cycles `COMPLETED`) and the `None` branch under `DownloadOnly` (`→ N=0 (no
      enumeration)`, `SKIPPED (2ms)`), minutes apart on one device. An actual byte upload on the new build
      remains covered by JVM + `iosSimulatorArm64` + `:test:integration` only.

## 11. Record

- [x] 11.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.
- [x] 11.2 Confirm `design.md` supersedes D3 by name and that `upload-lifecycle`'s new requirement is the
      rule, not the symptom.
- [x] 11.3 Update `app/ios/CLAUDE.md` if the tier-force runbook changed shape.
