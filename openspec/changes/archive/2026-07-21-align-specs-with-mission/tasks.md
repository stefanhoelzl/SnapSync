## 1. Domain — retire the whole-library projection path (`:domain`)

- [x] 1.1 In `domain/src/commonMain/kotlin/app/snapsync/model/DeviceManifest.kt`, change
      `projectDeviceManifest`'s `startDate: String?` to `startDate: String`, delete the
      `startDate == null` branch, and rewrite the kdoc (drop "or **all** of them when [startDate]
      is `null` (the whole-library scope …)"; note the cutoff is required per
      `photo-selection-policy`).
- [x] 1.2 Tighten the manifest producer's matching `startDate` parameter
      (`DeviceManifestProducer.produce`, `feature/membership`) to non-null the same way. The
      production caller (`compose/UploadCore.kt` `onDiscovery`, `cutoff: String`) is already
      non-null — no call-site change there.
- [x] 1.3 Update `domain/src/commonTest/kotlin/app/snapsync/feature/membership/DeviceManifestProducerTest.kt`:
      replace the six null-`startDate` call sites (three labeled, three positional) with an explicit early cutoff (e.g.
      `"0001-01-01T00:00:00Z"` where the test wants every asset admitted), keeping each test's
      assertion intent (sorting, date filtering, deletion handling) unchanged.
- [x] 1.4 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` pass.

## 2. Mission home

- [x] 2.1 Append the mission to `openspec/config.yaml`'s `context:` block — the statement from
      `design.md`'s Context (share-during-event, short-lived, gallery-to-gallery, no accounts)
      plus the three named futures (Android · paid events with a small-free tier · concurrent
      multi-event membership) and one line: "current contract is single active membership; do not
      deepen that assumption without naming it." Preserve the existing archiving-gates text
      byte-for-byte — append only.
- [x] 2.2 Extend `CLAUDE.md`'s opening project paragraph with the compressed mission line and the
      three named futures, so in-repo agents see it without opening openspec/.

## 3. Spec deltas (this change's `specs/` tree; applied to `openspec/specs/` at archive)

- [x] 3.1 Two requirement-level deltas live in this change's `specs/` tree (`device-manifest` —
      one MODIFIED requirement; `event-creation` — two MODIFIED requirements, prose-only,
      scenarios unchanged); the CLI applies these at archive. Six Purpose-level updates live in
      `purpose-edits/<capability>.md` (`photo-selection-policy`, `join-event`, `upload-lifecycle`,
      `event-limits`, `device-attestation`, `apns-push-sender`) because the CLI parses only
      requirement deltas — apply each full-replacement `## Purpose` to its main spec **by hand at
      archive time**, then diff to confirm only the intended paragraphs changed (per the
      config.yaml MODIFIED-delta warning).
- [x] 3.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes after apply.
