## 1. Discharge the Stage-2 gate

- [x] 1.1 Verify the distribution facts in this repository and record them in `design.md` D1:
      `git log -1 74d2b848` (step 11a, the fallback's origin), `git log -1 94f0bfe5` (step 13b, the
      finale), `git merge-base --is-ancestor <each> v0.1`, and `git tag -l 'v*'` showing `v0.1` is
      the first release. Any of these coming back false halts the change.

## 2. Delete the legacy-Keychain fallback from the read path

- [x] 2.1 Delete
      `adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/config/KeychainConfigReader.kt` in full
      — the read, the accessibility-class repair, and `deleteLegacyItem()`.
- [x] 2.2 `FileBackedConfigStore`: drop the `keychainReader` constructor parameter, call
      `configReadViaFile` with the file read alone, and make `clear()` file-only (the Keychain-first
      ordering existed solely to stop the fallback resurrecting a completed leave). Rewrite the class
      KDoc and the `read()`/`clear()` docs so nothing describes a fallback.
- [x] 2.3 `ports/ConfigPorts.kt`: reduce `configReadViaFile` to `(file: ConfigFileRead) -> ConfigRead`
      — `Content` decodes, `Missing` → `None`, `Failed` → `Unavailable` — and delete `configReadFrom`
      along with its now-unused `KeychainRead` import.
- [x] 2.4 `ports/ConfigPorts.kt` docs: `ConfigFileRead.Missing` says *definitively not joined, nothing
      else consulted*; the `ConfigFileRead` KDoc points `isConfigFileAbsence` at
      `:adapter:ios:ext-safe` (it left `model/` in `ce1f75c3`) and states that the classifier is now
      solely load-bearing for the leave decision.

## 3. Record the safety net that disappears (documentation only — no logic change)

- [x] 3.1 `adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/config/ConfigFileAbsence.kt`: add to
      the KDoc that with the fallback deleted this classifier is the only vote on the leave decision,
      that a wrong not-found is an uncaught logout, and that widening the whitelist is a change to
      the leave decision. **Do not touch the function body** — `design.md` D2 records why.

## 4. Retire the config pair's runtime-identity pin

- [x] 4.1 `test/architecture/.../RuntimeIdentityTest.kt`: remove `(app.snapsync.config, eventconfig)`
      from `keychainPairs` and from `unscopedKeychainSeats`, replacing the two explanatory comments
      with the retirement and the stated blind spot (a *scoped* reconstruction is not pinned).
- [x] 4.2 `test/architecture/.../PlatformIdentifierTest.kt`: drop `ports/ConfigPorts.kt` from the
      `deferred` pins — deleting `configReadFrom` removed the file's only `KeychainRead` use, so the
      exact-in-both-directions pin goes stale and the gate fails (as designed). Record in the KDoc
      that the debt was discharged by deletion, not by the Keychain family's reshape.
- [x] 4.3 `test/architecture/.../ConstructorBlockingTest.kt`: the grandfathering note cites a Keychain
      fallback that no longer exists — correct the prose; the exemption itself stays.

## 5. Tests

- [x] 5.1 Delete `domain/src/commonTest/kotlin/app/snapsync/ports/ConfigReadTest.kt` — every case it
      pinned is `configReadFrom`'s, and the function is gone (`design.md` D4 accounts for the
      coverage).
- [x] 5.2 `domain/src/commonTest/kotlin/app/snapsync/ports/ConfigFileReadTest.kt`: drop the five
      fallback cases (migrate, absent-fallback, unreadable-fallback, failed-migrate, and both
      compare-and-repair races); restate `Missing → None` as *definitive, consulting nothing*; keep
      every file-side case (valid, unusable, foreign, failed) and update the file KDoc.

## 6. Documentation

- [x] 6.1 Root `CLAUDE.md`: the `:adapter:ios:ext-safe` module line drops `KeychainConfigReader` from
      both the adapter list and the `SecItem*` owner list.
- [x] 6.2 `app/ios/CLAUDE.md`: the App-Group and Keychain-group bullets stop describing a live
      fallback and a legacy config item.

## 7. Verify

- [x] 7.1 `./gradlew build` passes (JVM tests + all targets compile, `:test:architecture` included).
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` passes — the Linux-runnable proxy for the iOS
      source sets. No backticked test name may contain a comma (Kotlin/Native rejects it).
- [x] 7.3 `npx --yes @fission-ai/openspec@1.5.0 validate retire-legacy-config-fallback --strict`
      passes.
- [x] 7.4 Grep the tree for surviving references to `KeychainConfigReader`, `configReadFrom`, and
      `deleteLegacyItem` outside `openspec/changes/archive/` — there must be none in code or in
      `openspec/specs/` prose that this change's deltas do not already replace.

## 8. Sync-time follow-ups (NOT part of the implementation commit)

- [ ] 8.1 Sync `enforce-port-boundary` **before** this change. Its delta introduces the
      *platform-identifier gate* requirement that this change's `architecture-guards` delta modifies;
      the reverse order throws "MODIFIED failed … not found".
- [ ] 8.2 At `sync`/`archive`, hand-edit the two main specs' `## Purpose` prose, which no delta can
      carry: `openspec/specs/event-link/spec.md` still calls the iOS store *"with a read-only
      legacy-Keychain fallback until the post-ship Stage-2 change"*, and
      `openspec/specs/event-rejoin-reconciliation/spec.md` should name this change's decision record
      beside the existing ones.
