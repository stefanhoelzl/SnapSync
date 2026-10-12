# Tasks

## 1. Production creates plain events

- [x] 1.1 `adapter/generic/app/.../dev/InertDevControls.kt`: `createsPlainEvents()` answers `true`; KDoc says a shipped
      build creates plain events until encrypted creation is re-enabled (`PENDING_CLEANUPS.md`). Update
      `InertDevControlsTest` to expect `true` ("a shipped build creates plain events, whose invite every app opens").
      Verify: `./gradlew :adapter:generic:app:jvmTest`.
- [x] 1.2 KDoc of `DevControls.createsPlainEvents` (`domain/ports`) and `EventKeyMinting` (`domain/services/.../crypto/EventKeys.kt`):
      production answers `true` for now (plain), a rig build defaults to encrypted; cite `PENDING_CLEANUPS.md`.
      Verify: `./gradlew compileIosMainKotlinMetadata` and a read of the diff.
- [x] 1.3 `DevControlsContract` (D3): `SHIPPED_EVERY_CONTROL_IS_AS_SHIPPED` expects `createsPlainEvents → true`;
      `SWITCHED_…` expects `false` ("the channel switched encryption on"); update `DevControlsState.SWITCHED`'s and the
      contract's KDoc. `RigDevControlsContractTest` sets `encrypts = true` for `SWITCHED`. Verify:
      `./gradlew :adapter:generic:app:jvmTest :test:rig:jvmTest :test:architecture:test` (ClauseCoversTest,
      ContractCoverageTest green).
- [x] 1.4 Confirm the rig and mock defaults are unchanged (`RigDevControls.encrypts`, `DevControlsMock.encrypts` stay
      `true`) and no integration test relied on the production answer. Verify: `./gradlew :test:integration:test`.

## 2. Documentation and the Privacy Policy

- [x] 2.1 `site/src/pages/index.astro`: remove the Privacy Policy paragraph from "In an event created with SnapSync 0.6
      or later" through "…keeps neither the key nor the unencrypted photo." (D4), keeping the sentence flow and the
      "Legal basis" line. Verify: `cd site && npm run build` (or the repo's site-build task) and read the rendered
      paragraph.
- [x] 2.2 `docs/architecture.md` ("The encrypted file format"): production creates plain events until re-enabled; a
      rig build creates encrypted by default. `CLAUDE.md` (the `:domain` Crypto entry: "creation is encrypted unless…")
      and `.claude/skills/rig-channel/SKILL.md` (`device/encrypt-new-events`: "like a shipped one, encrypts by
      default" → a shipped build creates plain for now). Verify: `./gradlew :test:architecture:test` (RunbookSkillsTest)
      and a grep for "0.6" / "shipped one, encrypts" leaving no stale claim.

## 3. PENDING_CLEANUPS.md

- [x] 3.1 "The floor itself": the deadline becomes "before the promote that re-enables encrypted creation"; keep the
      SQL check and the record cites; note the 2026-10-12 measurement (5 memberships in 2 open events). Verify: read.
- [x] 3.2 Rename "At the 0.6 promote" to "At the promote that re-enables encrypted creation"; its "Why not earlier"
      and "Ship" lines refer to that release. Verify: read.
- [x] 3.3 Add "Re-enable encrypted creation" (unlock: `MIN_APP_VERSION` ≥ 0.5 merged): what (InertDevControls + test +
      KDoc, DevControls/EventKeyMinting KDoc, DevControlsContract and the rig binding back, `invite-link` spec via its
      own OpenSpec change (MODIFY "Every invite form opens in every future version" back to encrypted creation with
      the keyed path form, and "Only the whole invite opens an event's photos" back to "every event this version
      creates"), the Privacy Policy paragraph — its removed text
      verbatim, with "0.6" replaced by the enforcing release's version — `docs/architecture.md`, `CLAUDE.md`, the
      rig-channel skill), why, and the order: floor → re-enable → claim → promote. Record:
      `openspec/changes/defer-encrypt-by-default`. Verify: read.
- [x] 3.4 "Once `MIN_APP_VERSION` ≥ 0.6 …" sections: retarget to the version that re-enables encrypted creation where
      they depend on 0.6 creating encrypted events ("0.5 still creates plain events" now holds for 0.6 too). Verify:
      read.

## 4. Gate

- [x] 4.1 `./gradlew build` green, and `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` plus
      `validate defer-encrypt-by-default --strict` green.
- [x] 4.2 With the sync/archive (not before — `VerifiesGateTest` reads the main specs): re-point every `@Verifies`
      naming `invite-link` / "The invite link format stays openable forever" to "Every invite form opens in every
      future version", and `EncryptedEventIntegrationTest.an_encrypted_event_s_invite_carries_its_key` to scenario
      "An encrypted event's invite carries its key". Verify: `./gradlew :test:architecture:test` after the sync.
