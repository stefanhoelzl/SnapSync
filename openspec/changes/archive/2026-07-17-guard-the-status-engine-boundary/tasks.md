## 1. Prove the claim is false before rewriting it

The spec asserts a **classpath fact** — the most mechanically checkable kind of claim there is. Do not
argue it; run it.

- [x] 1.1 Compile a probe importing `app.snapsync.engine.LedgerWriter` into `:domain:status`.
      *It compiles clean (`:domain:status:compileKotlinJvm`, 0 errors). So "`:domain:engine` is not on its
      compile classpath and no ledger type is reachable from status code" is **false on both halves**, and
      has been since the sentence was written — nothing ever ran it.*
- [x] 1.2 Establish **why**, so the rewrite states a truth rather than a different guess.
      *`domain/gallery/build.gradle.kts:16` declares `api(project(":domain:engine"))`, and its own comment
      says why: "The resource-enumeration seam returns engine `Resource`s … so engine types appear in this
      module's public API." `domain/status/build.gradle.kts:18` depends on gallery; `api` propagates. The
      leak is not a mistake — it is gallery's API being honest.*
- [x] 1.3 Establish what **is** true, or the correction overshoots the other way.
      *`grep app.snapsync.engine domain/status/src` → **zero**. Status declares no engine dependency and
      names no engine type. `ledger-free-status` really did buy that; it just did not buy the classpath.*

## 2. Say the true thing, in both specs

- [x] 2.1 `sync-status`: the scenario stops asserting the classpath and asserts what is guarded — status
      declares no engine dependency and no file references `app.snapsync.engine`, by import or fully
      qualified. Record **why** the classpath is not clean, so the next reader does not "restore" the
      stronger claim and re-introduce a sentence that is already false.
- [x] 2.2 `architecture-guards`: add the rule. Include what it does **not** forbid — status consuming
      `Resource` by inference from `enumerate()` — because a guard whose scope is unstated gets widened by
      the next person until it fails on the legitimate case.
- [x] 2.3 Build both deltas **from the current main spec** and diff them. *The first attempt at 2.1 failed
      its own assertion: the paragraph I meant to replace no longer existed, because yesterday's sweep had
      already edited that requirement. Retyping from the audit's line numbers would have silently written a
      stale paragraph back into the contract.*

## 3. Guard it

- [x] 3.1 Add `StatusEngineBoundaryTest` to `:test:architecture`, mirroring `KeychainContainmentTest`:
      Konsist over **source text** (a fully-qualified `app.snapsync.engine.LedgerBackend` imports nothing),
      scoped to `:domain:status` production files.
- [x] 3.2 Mirror its **vacuity check** too. This guard filters harder than the keychain one — module path,
      then test-source exclusion — so it can scan nothing by accident after a rename or a source-set move.
      Assert the scope is real and that `LedgerBackedSyncStatusSource` is in it: if the real projection is
      not being read, the rule passes no matter what anyone writes into status.

## 4. Verify the guard is not theatre

- [x] 4.1 Plant the probe from 1.1 and confirm the guard **fails** on it, naming the file.
      *It does — and the same probe still compiles, which is the whole point: the compiler is content, and
      the guard is not. That is the gap `architecture-guards` exists to cover.*
- [x] 4.2 Remove the probe; confirm the guard passes. *Zero failures — it is a ratchet, not a repair.*
- [x] 4.3 `./gradlew build`, `compileIosMainKotlinMetadata`, `openspec validate --specs --strict`.

## 5. Hand off the question this does not answer

- [x] 5.1 Record that the classpath is still not clean, and what would clean it: moving `Resource` out of
      `:domain:engine` into a leaf both engine and gallery depend on. Rejected **for now** — `Resource` is
      imported across `:capability:upload`, `:capability:upload-url`, `:capability:membership` and both iOS
      upload tiers, and `:domain:engine` is a leaf, so it inverts an edge rather than adding one. That is
      real risk for an invariant no code violates and, after this change, cannot violate silently. Written
      down rather than left implicit: the failure this repo keeps hitting is not wrong decisions, it is
      decisions taken and never recorded (D3).
