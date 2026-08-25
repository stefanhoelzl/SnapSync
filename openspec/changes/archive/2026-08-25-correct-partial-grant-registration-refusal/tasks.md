## 1. The classifier

- [x] 1.1 Add a named constant for `PHPhotosErrorAccessUserDenied` (3311) beside
      `PHOTOS_ERROR_IDENTIFIER_NOT_FOUND` in `RegistrationOutcome.kt`, with its provenance stated as the
      Kotlin/Native Photos klib rather than the error's rendered description — matching how the 3201
      constant documents itself
- [x] 1.2 Add `RegistrationOutcome.DisableRefusedByGrant` at `Severity.Warn`, whose message names the
      refusal, the code, and the two facts that make it safe: the record survives, and the OS does not
      invoke the extension under this grant
- [x] 1.3 Add `RegistrationOutcome.EnableRefusedByGrant` at `Severity.Error`, whose message names the
      partial grant as the cause and states the consequence (nothing registered, no cycle will run) —
      distinct from the generic `Failed` message
- [x] 1.4 Add both classifier branches to `registrationOutcome`, keyed on `errorCode == 3311` and split by
      `enabling`, placed so the 3201 disable branch keeps its current precedence; document at the `when`
      why the direction split exists (opposite consequences), as the 3201 branch already does

## 2. Tests

- [x] 2.1 Assert `Severity.Warn` and the message for a disable refused with 3311, with a comment naming
      why a routine, supported user action must not raise an event
- [x] 2.2 Assert `Severity.Error` and the message for an enable refused with 3311, and assert it is a
      *distinct* outcome from `Failed` so the two cannot be collapsed later without failing a test
- [x] 2.3 Assert the asymmetry directly — same code, both directions, different severities — mirroring the
      existing 3201 asymmetry test
- [x] 2.4 Confirm the existing 3201 and generic-failure assertions still hold (branch precedence unchanged)

## 3. `ios-photokit-upload`

- [x] 3.1 Rename `### Requirement: The OS does not invoke the extension under a limited grant` to
      `### Requirement: The registration cannot be changed under a partial grant`
- [x] 3.2 Rewrite its forcing proof: both directions refused with `PHPhotosErrorAccessUserDenied`, citing
      `changes/archive/2026-08-25-collapse-upload-tier-seam` D11 and D11b, and state the asserted mechanism
      as **contradicted by measurement** while retaining the 2026-07-20 probe's own observation verbatim
- [x] 3.3 State the evidence limits (one device, one point release, the enable reached through a
      development pin) and keep the existing iOS 27 GM expiry trigger
- [x] 3.4 Replace the scenario `Switching to limited deregisters the extension` with the measured
      behaviour, and add scenarios for the inert survivor and for the refused enable
- [x] 3.5 In `A failed extension-registration change is reported, not discarded`, widen the expected-error
      enumeration from one code to two, state it as closed and measurement-gated, add the 3311-on-disable
      carve-out below `Error`, and add the named 3311-on-enable outcome at `Error`
- [x] 3.6 Add the two reporting scenarios (refused disable raises no event but stays in the log; refused
      enable stays an event naming the cause)

## 4. `limited-photo-access`

- [x] 4.1 Correct the second of the three measured platform facts in the Purpose: a partially-granted
      process cannot change its upload-job registration at all, so the extension is never registered from
      `.limited` and the OS never invokes it there
- [x] 4.2 Add the correcting decision record to the Purpose's record list, with its evidence limits, in the
      same shape the 2026-08-06 supersession already uses
- [x] 4.3 Qualify `A limited grant resolves the app-driven mechanism by resolution, not by a branch`: the
      resolved producer **attempts** the relinquish, the platform refuses it under a partial grant, the
      surviving record is inert, and the relinquish remains load-bearing under a full grant
- [x] 4.4 Add the scenario for a refused relinquish not blocking the pump, and adjust the existing
      downgrade scenario from an accomplished deregistration to an attempt

## 5. The in-context digest

- [x] 5.1 Correct `CLAUDE.md`'s limited-photo-access paragraph — replace "(registration succeeds and lies)"
      with the measured fact, keeping the sentence's length and role in that dense paragraph
- [x] 5.2 Confirm the edit sits outside the `## The laws (digest)` section, so `LawsDigestTest` is not
      implicated

## 6. Verification

- [x] 6.1 `./gradlew build` — the classifier's tests run on JVM and the iOS simulator from `commonTest`
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` — confirm the iOS source sets still compile, though no
      `iosMain` file changes in this change
- [x] 6.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` — structural only; it has never
      opened a `.kt` file, so it is not evidence the corrections are true
- [x] 6.4 Re-read the three corrected sites together and confirm no remaining occurrence of "succeeds and
      lies" outside `changes/archive/` (the archive is immutable history and stays as written)

## 7. Ship

- [ ] 7.1 Apply the `internal` changelog label — no user of the app experiences this change — and ship via
      `/ship`
