# Tasks — uniform-adapter-tree

## 1. Rename

- [x] 1.1 `git mv adapter/generic adapter/generic/app`; `git mv adapter/fake adapter/generic/fake`
- [x] 1.2 `settings.gradle.kts` includes → `:adapter:generic:app`, `:adapter:generic:fake`

## 2. Ride-alongs (one diff, per the 13a-class playbook)

- [x] 2.1 Consumer `project()` declarations: `adapter/ios/ext-safe`, `app/desktop`, `app/ios`,
      `app/ios/extension`, `test/world` (2), `test/integration`
- [x] 2.2 Doc-comment mentions: `:domain` (SnapSyncApp/UploadCore/Time), `:ui:presentation`
      (CutoffFormatter), `:test:world` (World/Fakes/InMemoryStoreContractTest), build-file
      headers (leaf vocabulary recorded), `ios.yml` comment
- [x] 2.3 `ModuleSetTest.targetModules` — the two entries renamed
- [x] 2.4 `FakeHonestyTest.fakeRoot` → `adapter/generic/fake` (coverage moved, not shrunk);
      red-proof re-run: planted public `var` → red naming the file → restored → green
- [x] 2.5 CLAUDE.md module rows (both), `app/ios/CLAUDE.md` (2 mentions)
- [x] 2.6 `./gradlew architectureDiagrams` regenerated (`architecture/`)
- [x] 2.7 RuntimeIdentityTest green (no pinned literal touched); SQLDelight generated-package
      check: `packageName`s pinned, impl sub-package internal-only,
      `verifyCommonMainLedgerDatabaseMigration` green

## 3. Spec delta

- [x] 3.1 MODIFIED restatement of `module-architecture`'s "The module set withholds; packages
      organize" (the module list) and "Ports are the I/O boundary named for the need" (the
      placed-by-linkage vocabulary, now axis-qualified)
- [x] 3.2 MODIFIED restatement of `architecture-guards`' "The fake-honesty gate" (module name)
- [x] 3.3 Mechanical reference fixes in the eight specs carrying placement prose
      (event-creation-ui, gallery-status, harness-world-model, ios-app-shell,
      ios-photokit-upload, ios-url-session-upload, join-event, sync-ledger); archives untouched
