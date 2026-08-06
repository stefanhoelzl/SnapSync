## 1. The decision, as a tested pure resolver

- [x] 1.1 Add a sealed scene mode to `domain/.../model/` (compose vs defer), resolved from the app's
      activation state, following the `resolveComposition` pattern
- [x] 1.2 Cover the resolver in `commonTest` so it runs on JVM **and** `iosSimulatorArm64`: background and
      inactive resolve to defer; active resolves to compose
- [x] 1.3 Confirm `./gradlew compileIosMainKotlinMetadata` still passes (the Linux-runnable iOS proxy)

## 2. Shell wiring

- [x] 2.1 Dispatch on the sealed mode in `MainViewController()` with a single `when` — the deferred branch
      returns a bare placeholder view controller, the live branch the `ComposeUIViewController`
- [x] 2.2 Answer a scene generation from Kotlin (`onSceneActive`), bound in `ContentView` to `.id(…)` so
      the Compose view is built once, at the first app-level `didBecomeActive`
- [x] 2.3 ~~Remove the SwiftUI App entry point~~ — BUILT, MEASURED, REVERTED. The scene-delegate variant
      worked on a real tap but showed a black screen under `dvt launch` (no scene session is connected),
      breaking the headless screenshot loop. SwiftUI stays; see design D2
- [x] 2.4 Verify Swift still contains zero decisions and `:app:*` Kotlin zero unpinned conditionals —
      `SwiftShellGuardTest`, `KotlinShellGuardTest`, `detektAppShell`

## 3. Spec and generated artifacts

- [x] 3.1 Apply the `ios-app-shell` delta to `openspec/specs/ios-app-shell/spec.md`
- [x] 3.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 3.3 `./gradlew architectureDiagrams` and commit any regenerated `architecture/` output
- [x] 3.4 `./gradlew build` green (compiles all targets, runs JVM tests, runs the architecture guards)

## 4. On-device verification (the acceptance criteria)

- [x] 4.1 Build a dev IPA on the ssh-mac loop and install it on the SE2
- [x] 4.2 **Deferral holds** — MEASURED on a silent-push wake (2026-08-06 00:22:05): a process
      relaunched from dead logged `mode=deferred` ×1, `mode=live` ×0, `onForeground` ×0. A scene
      connected in the background and composed nothing
- [x] 4.3 **Activation composes:** foreground the app and confirm the `MainViewController` entry appears
      after the foreground entry, and the status screen renders correctly
- [x] 4.4 **Background work unaffected** — the same wake received the push, re-registered its push token,
      and reconciled in 678 ms; the union went 59 → 60 assets, i.e. it saw the new photo
- [x] 4.5 **Cold link:** open an event link with the app not running; confirm one `[onOpenUrl]` sharing a
      timestamp with `=== app process start ===`
- [x] 4.6 **Warm link:** open an event link with the app running; confirm one `[onOpenUrl]` with no
      preceding process start
- [x] 4.7 **Link to a scene-less process** — covered in substance: on the cold tap the link reached
      `onOpenUrl` at .646, **73 ms before the first `MainViewController` call**, i.e. while no scene
      existed. The background-woken variant specifically is still unobserved (needs a real wake)
- [x] 4.8 Confirm nothing regressed visually on resume — no flash, no dropped first frame — and that
      safe-area insets and orientation are unchanged after the SwiftUI removal

## 5. Ship

- [ ] 5.1 Open the PR with the `bug` changelog label
- [ ] 5.2 `/ship`
- [ ] 5.3 Archive the change once merged
- [ ] 5.4 **Non-blocking follow-up:** report the investigation upstream on CMP-5978 — two devices, two OS
      majors, two upload tiers, and on-device proof that the scene composes while the app is invisible
