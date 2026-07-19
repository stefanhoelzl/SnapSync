# Tasks — rename-extension-module

## 1. Rename

- [x] 1.1 `git mv app/ios/photokit-extension app/ios/extension` (5 files, pure moves)
- [x] 1.2 `settings.gradle.kts` include → `:app:ios:extension`

## 2. Ride-alongs (one diff, per PLAN 13a)

- [x] 2.1 `project.pbxproj`: embedAndSign run-script task path + 2× `FRAMEWORK_SEARCH_PATHS`
- [x] 2.2 `.github/actions/ios-archive/action.yml` compile task path; `ios.yml` comment
- [x] 2.3 Root `build.gradle.kts` `appShellSources` entry
- [x] 2.4 `ExtensionSafetyTest.extensionLinkedRoots` + its doc comment (coverage moved, not shrunk)
- [x] 2.5 CLAUDE.md module row, `app/ios/CLAUDE.md` (framework table, gradle-task line, root path);
      `UploadKeys.kt` doc-comment module name
- [x] 2.6 `./gradlew architectureDiagrams` regenerated (`architecture/`)
- [x] 2.7 Beacon `targetModules`: verified already `:app:ios:extension` — no edit, measure only

## 3. Spec delta

- [x] 3.1 MODIFIED restatement of `ios-photokit-upload`'s "Background upload extension target"
      (the two module-seat mentions; capability id unchanged)
