## 1. The resolve seam (`:domain` `ports/`)

- [x] 1.1 Land the already-written `KeychainResolution` outcome type and the `onResolution` callback
      on `resolveOrMint` (currently uncommitted on `no-sync`); confirm the default keeps existing
      callers source-compatible
- [x] 1.2 Extend the resolve to the strict order of design D3: `Unavailable` → throw · shared `Found`
      → return · `Absent` → consult a supplied legacy reader → adopt · else mint. Model the legacy
      reader as an injected lambda so the ordering stays pure and platform-free
- [x] 1.3 Add an `Adopted` outcome to `KeychainResolution`, distinct from `Found` and `Minted`
- [x] 1.4 `commonTest`: unavailability short-circuits before the legacy read (no adopt, no mint);
      shared-group hit never consults the legacy reader; adoption returns the legacy value verbatim
      and persists it; nothing found anywhere mints exactly once
- [x] 1.5 `commonTest`: a caller that supplies **no** legacy reader (the extension) never adopts and
      never mints on `Absent`

## 2. Explicit access-group addressing (`:adapter:ios:ext-safe`)

- [x] 2.1 Add an optional access-group parameter to `IosKeychain`, defaulting to today's unscoped
      behavior so the other four items are untouched (design D2)
- [x] 2.2 Apply the group to **every** operation: `read`, `write`, `delete`, `migrateAccessibility` —
      a partially-scoped item is worse than an unscoped one
- [x] 2.3 Introduce the shared-group constant, single-sited, composed to
      `<TEAM_ID>.app.snapsync.shared`
- [x] 2.4 `KeychainDeviceIdentity`: address the shared group, supply the unscoped legacy reader for
      the adoption branch, and confirm `delete()` is group-scoped so adoption cannot touch the
      out-of-group item (design D4)
- [x] 2.5 Verify `KeychainConfigReader` still searches unscoped and is unchanged

## 3. Minting is the app's alone

- [x] 3.1 `KeychainDeviceIdentity` gains an explicit mint-capability distinction so the extension's
      construction can neither adopt nor mint (design D5)
- [x] 3.2 `UploadExtensionRoot`: on absence, skip the cycle without creating upload jobs and log the
      skip; confirm it composes the non-minting variant
- [x] 3.3 `SnapSyncRoot`: composes the minting variant (unchanged behavior)
- [x] 3.4 Confirm the extension's existing `KeychainUnavailable` handling still short-circuits ahead
      of the new absence path

## 4. Observability

- [x] 4.1 Extend the already-written `deviceIdentity` log line to report `adopted` alongside
      `read`/`minted`
- [x] 4.2 Confirm both roots emit it exactly once per process, with the id verbatim

## 5. Guards (`:test:architecture`)

- [x] 5.1 Pin the access-group literal: exactly once in production Kotlin
- [x] 5.2 Cross-check it against `TEAM_ID` in `Config.xcconfig` and the group declared in **both**
      entitlements files, and assert the two entitlements files agree with each other
- [x] 5.3 Pin the unscoped-search exception: exactly one production seat, and it is
      `KeychainConfigReader`
- [x] 5.4 Update `LawsDigestTest` / the digest if the guard inventory wording requires it

## 6. Correct the falsified prose

- [x] 6.1 `KeychainDeviceIdentity`: remove the claims that the id is "identical across the app and the
      upload extension (one shared Keychain item)" and survives reinstall unconditionally; state what
      is now true and why the group is named
- [x] 6.2 `IosKeychain`: replace "no `kSecAttrAccessGroup` is set … the item lands there by default"
      with the explicit-addressing rationale, and note the one deliberate unscoped seat
- [x] 6.3 `domain/.../ports/Keychain.kt`: document the resolution order and why unavailability
      outranks absence and adoption

## 7. Dev re-sign (design D7)

- [x] 7.1 Rework the `CLAUDE.md` ssh-mac re-sign block to build entitlements from the repo's
      `.entitlements` files with `$(AppIdentifierPrefix)`, `$(ASSOCIATED_DOMAIN)` and
      `$(APS_ENVIRONMENT)` expanded, plus `get-task-allow`
- [x] 7.2 Add the post-sign assertion that no wildcard survives into either signed binary, and
      document that it supersedes the hand-narrowing of `associated-domains`
- [x] 7.3 Record why the profile is a grant and not a claim, so the next wildcard key is not
      rediscovered the hard way

## 8. Verify

- [x] 8.1 `./gradlew build` green (compiles all targets, JVM tests, architecture guards)
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` green
- [x] 8.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 8.4 On device: install, launch, pull both `Documents/debug.log` files, confirm the two
      `deviceIdentity` lines report the **same** id — the acceptance test for this change.
      VERIFIED 2026-07-20: app `DD92FAC9 via=read` (16:23), extension `DD92FAC9 via=read` (16:37),
      both clean reads from the explicitly-addressed shared group, no mint anywhere.
- [ ] 8.5 On device: confirm a freshly uploaded photo is **not** re-imported (`reconcile: … 0 foreign
      planned` for the device's own upload), which is the original symptom.
      NOT VERIFIED ON DEVICE — requires upload-capable activity that was deliberately not forced (it
      would upload synthetic photos to a real event). Mechanism is identical addressing, proven to
      converge in 8.4; the own-vs-foreign discrimination is covered by the resolve unit tests.
- [ ] 8.6 Expect and note the one-time re-import of previously-uploaded photos (design D6); confirm it
      terminates rather than looping.
      MOOT ON THIS DEVICE — the shared group already held the id, so both processes resolved via a
      clean `read`; the adoption branch never fired and there was no re-import wave to observe. The
      adoption path is covered by the resolve unit tests, not by this device.
- [x] 8.7 `./gradlew architectureDiagrams` and commit if anything moved
