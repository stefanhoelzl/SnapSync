# Tasks

## 1. The OpenSpec change

- [x] 1.1 Verify `npx --yes @fission-ai/openspec@1.13.2 validate android-sharing --strict` and `… validate --specs --strict` pass (the change ships in the phase's one PR, D9)

## 2. The Android gallery, photo access and system UI

- [x] 2.1 Add the default-gallery scope (`DCIM/` prefix, case-insensitive, any volume) as a pure function in `:adapter:android`, and the three new titles (`Screenshots`, `Screen recordings`, `ScreenRecorder`) to `SELECTION_CALIBRATION`'s denylist; verify a device test (the project runs no Android host tests) covers `DCIM` subfolders, case, `Pictures/*` and an empty path, and `SelectionCalibrationTest` that the new titles are denylisted
- [x] 2.2 Implement `AndroidGalleryReader` over MediaStore (images + video, every external volume, trashed/pending excluded, scoped per D1) with the facts and resources of D2; split `GalleryReaderContract`'s album writes into their own state and add the folder-album and outside-the-default-gallery clauses (D3); verify the `GalleryReader` contract passes on `ANDROID_EMU` and the mock binding still passes
- [x] 2.3 Map `AssetId` to MediaStore's `_ID` by identity (D2); verify the contract's `RESOURCES_BY_ID_CARRY_EACH_ORIGINAL` clause (upload key `<assetId>-<role>.<ext>`) passes on `ANDROID_EMU`
- [x] 2.4 Implement the permission adapter of D4 (status table, the "asked" record in `Preferences`, request via the activity-result bridge, `widenSelection` re-request, `ACCESS_MEDIA_LOCATION` alongside) and the `PhotoAccessStatusSource`, and verify the `PhotoAccess` contract on `ANDROID_EMU` for `GRANTED`/`DENIED`/`NOT_DETERMINED` (and `LIMITED` via UiAutomator, or `NotRunHere` with its reason)
- [x] 2.5 Declare the permissions in `app/android`'s manifest (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION`, `READ_EXTERNAL_STORAGE` `maxSdkVersion=32`) and verify the merged manifest lists exactly these
- [x] 2.6 Implement `AndroidGallery` (the change observer of D4, the change token, the phase-4 methods answering refusals per D3) and verify the `Gallery` contract on `ANDROID_EMU`, phase-4 clauses reporting `NotRunHere` with the reason
- [x] 2.7 Try to measure on the emulator whether `setRequireOriginal` keeps the location; verify the outcome is recorded in design.md (Risks) — measured: the emulator cannot produce a located photo owned by another app, so the check moves to phase 6's closed test
- [x] 2.8 Implement `AndroidSystemUi` (share chooser, `ACTION_VIEW`, app details settings) and `AndroidExtensionRegistry` (`Unsupported` / `UNSUPPORTED`); verify `SharePresenter` and `LinkOpener` contracts on `ANDROID_EMU`, and the registry's answer pinned beside it
- [x] 2.9 Reword the policy law — `CLAUDE.md`, `SelectionPolicy.kt`'s "Where it lives", the `Gallery.kt` port KDoc, `docs/architecture.md` — to "the platform defines the default gallery; the policy is the one decision over it", and verify by grep that no text still claims platform narrowing cannot change the admitted set
- [x] 2.10 Add the library, extension-registry, system-UI (and, if the composition requires it, process-info) systems to `ANDROID_REAL_ADAPTERS` and any coherence rule they need, and verify the rig launches on the emulator with them real and `/device/adapters/current` lists them
- [x] 2.11 Verify the group: `./gradlew build` (emulator stopped), `./gradlew compileIosMainKotlinMetadata`, the `android-emulator` CI job green, `./gradlew architectureDiagrams` committed

## 3. The library-change wake rule

- [x] 3.1 Split `Heartbeat`'s arming (D5): the heartbeat keeps its work-left rule; the library-change wake is armed at join, at start while joined, and after every tail while the membership contributes and the event is open, and cancelled at leave, receive-only and close; verify with feature tests in `:test:feature` (armed after a tail with nothing left; not armed for receive-only; cancelled at leave) and that iOS's `Unsupported` answer leaves the iOS flows' tests unchanged

## 4. The WorkManager uploader

- [x] 4.1 Add `androidx.work:work-runtime` (and `work-testing`) to `gradle/libs.versions.toml` and `:adapter:android`, and verify `./gradlew :adapter:android:assemble` resolves it
- [x] 4.2 Implement `AndroidWake` (D6: one unique work per `WakeId`, delay + network for the heartbeat, content-URI triggers + max delay for the library change, `APPEND_OR_REPLACE`, `onStopped` as the expiry) with the worker as an entry port on the `Application` composition, and verify the `WakeContract` on `ANDROID_EMU` through WorkManager's test driver, plus a clause that re-arming from inside a running worker does not cancel it
- [x] 4.3 Implement `AndroidBackgroundTime` (the expedited hold work of D6) and verify a contract clause that a hold keeps running after the activity is finished and that `onStopped` fires the expiry
- [x] 4.4 Implement the Android app uploader (`Upload`: in-process PUTs, 4 live transfers then `LIMIT_EXCEEDED`, inline `onFinished`, a hold while any transfer is live, the job journal that reports unfinished jobs as failed at start), resolving the `UploadSourceKind` question, and verify the `UploadContract` on `ANDROID_EMU` against the loopback transfer fixture plus a clause that a job journaled before a process kill is reported failed at the next start
- [x] 4.5 Wire the root: `osSupportsOsDrivenUpload = false`, no `cycleUpload`, the new adapters in `DevicePorts`, the upload events registered by the host; add the wake, background-time and upload-session systems to `ANDROID_REAL_ADAPTERS`; verify a worker cold-start composes no UI (the status host stays unbuilt, checked from the rig's os-record) and `KotlinShellGuardTest` / `detektAppShell` pass
- [x] 4.6 Verify end to end on the emulator (rig build, real library/wake/uploader, local `api/` via `deno task dev:local`, an event created in this session): a photo seeded into `DCIM/Camera` with the app closed uploads; a photo in `Pictures/` and one in `DCIM/Screen recordings` do not; moving the first out withdraws it; force-stop pauses until the next opening; record the steps and results in the PR
- [x] 4.7 Try to measure a large video's upload under `adb emu network speed` within the worker's window; verify the outcome is recorded in design.md — measured: shaping does not reach the `adb reverse` path, so the window is stated as arithmetic, and the resumable-uploads decision goes to the user
- [x] 4.8 Update `docs/architecture.md` (background execution on Android: the works, their triggers, the hold, the journal) and `docs/testing.md` (the new `ANDROID_EMU` bindings and how the MediaStore is seeded), and verify `RunbookSkillsTest` and the `android-emulator` skill still match what they describe
- [x] 4.9 Verify the group: `./gradlew build` (emulator stopped), `./gradlew compileIosMainKotlinMetadata`, the `android-emulator` CI job green, diagrams regenerated

## 5. Sync, archive, the mission text, and the one PR

- [x] 5.1 Update the `## Purpose` of `photo-sharing`, `photo-access` and `background-upload` in `openspec/specs/` for both platforms (`DCIM` on Android; Android's dialog and selection; Android's uploader), and verify each stays one paragraph with at most one decision-record cite
- [x] 5.2 Update the mission in `openspec/config.yaml` (context) and `CLAUDE.md`: Android is no longer a named future; SnapSync is an iOS and Android app; restate the policy law as in 2.9; verify by grep that neither lists Android as a future
- [x] 5.3 Sync and archive the change (on the user's word), then run both archive gates from `openspec/config.yaml` (no placeholder Purpose; the identifier grep over every touched spec) and verify `validate --specs --strict` passes
- [ ] 5.4 Ship the phase as one PR with `/ship internal --keep-workspace` (run by the user) and verify every required check is green and the merge is confirmed
