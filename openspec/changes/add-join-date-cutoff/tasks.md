## 1. Cutoff foundations (time source + format)

- [x] 1.1 Activate `kotlinx-datetime` (already in `gradle/libs.versions.toml`) as a dependency of the
  `commonMain` module(s) that need "now"/conversion; verify the iOS source sets still compile via
  `./gradlew compileIosMainKotlinMetadata`.
- [x] 1.2 Add an injected `Clock` seam (DI, not `expect`/`actual`) as the single origin of "now" for the
  cutoff; wire a real `Clock.System` in the composition roots and a fixed clock in tests.
- [x] 1.3 Implement cutoff formatting in `commonMain`: `now → UTC "yyyy-MM-dd'T'HH:mm:ss'Z'"` and
  `local date+time (@ device TimeZone) → UTC …Z`, byte-identical to `NSISO8601DateFormatter()` output
  (second precision, `Z`, no offset, no fractional seconds).
- [x] 1.4 `commonTest` (runs JVM + `iosSimulatorArm64`): pin the exact `…Z` shape; assert the
  lexicographic `creationDate >= cutoff` compare (in-scope, out-of-scope, equal-instant, empty
  `creationDate` → excluded); assert a fetched `createdAt` string is reused verbatim.

## 2. Cutoff data model (EventConfig + deeplink payload)

- [x] 2.1 Add `minPhotoDate: String?` to `EventConfig` (`:capability:config`); update `ConfigStore.save`
  idempotency to compare `eventId`, `name`, and `minPhotoDate`.
- [x] 2.2 Persist `minPhotoDate` in the shared-group Keychain store; ensure the upload extension reads
  `eventId` **and** `minPhotoDate` cross-process.
- [x] 2.3 Add optional `minPhotoDate: String?` to `EventLinkPayload` and extend the pure decoder to
  accept it (validate as a non-empty string), still rejecting genuinely unknown keys; keep the canonical
  QR encoder emitting `eventId` only.
- [x] 2.4 `commonTest`: `EventConfig` round-trip incl. `minPhotoDate`; `save` idempotency on all three
  fields; decoder accepts `{eventId, autoJoin, minPhotoDate}` and rejects unknown keys.

## 3. Upload + manifest filtering

- [x] 3.1 Add the byte-upload filter in the shared `UploadCycle` (`:capability:upload`): drop resources
  whose asset `creationDate < min(cutoffs across memberships)` before the engine hand-off; express as a
  `min` reduction (v1 = single cutoff; `null` = whole-library). Ensure it covers both the full and the
  incremental (change-token) walks.
- [x] 3.2 Wire the membership cutoff into `DeviceManifestProducer.produce(startDate = cutoff)` at the
  three call sites currently hardcoding `null` (`UploadExtensionRoot`, `UrlSessionUploadController`,
  `:test:world`), keeping the accumulator device-global (projection filters; accumulator retains all).
- [x] 3.3 `commonTest`: `UploadCycle` excludes pre-cutoff resources on both walk types; a `null` cutoff
  is whole-library; `projectDeviceManifest`/`DeviceManifestProducer` lists only in-scope assets while the
  accumulator still holds the excluded ones.
- [x] 3.4 Scope the own-device status total by the cutoff (`OwnDeviceGalleryStatusSource`) so the joined
  screen reaches "in sync" (pre-cutoff assets never upload and must not inflate `N`); wire it in both roots; tests.

## 4. Design system — date/time component

- [x] 4.1 Add the `App*` date/time input component in `:domain:ui:components` wrapping M3
  `DatePicker` + `TimePicker`, with a semantic-only signature (plain date-time value + change callback +
  enabled; no `Modifier`/M3 type). Keep the M3 imports contained to the module.
- [x] 4.2 `:domain:ui:components` `jvmTest` (offscreen): value renders, picking reports the new value,
  `enabled = false` opens no picker; assert no M3 type escapes the signature.

## 5. Join gate — cutoff row, createdAt default, provision-with-cutoff

- [x] 5.1 Parse `createdAt` from `GET /events/:id` in the join details fetch (`:capability:join` /
  `MetaDto`); carry it into the loaded phase.
- [x] 5.2 Add the capture-date cutoff row to the join screen's loaded phase (`:domain:ui`): prefilled
  default = fetched `createdAt`, an "Only from now" `SecondaryButton` snapping to the injected-clock now,
  and the new App date/time component for manual picks (bounds unrestricted). Thread the chosen cutoff
  through the confirm intent (`:domain:presentation`) — `JoiningEvent` keeps its shape.
- [x] 5.3 `JoinEvent.join` accepts and persists the chosen cutoff: on 201 enrollment, save
  `EventConfig(eventId, name, minPhotoDate)`.
- [x] 5.4 `autoJoin` auto-confirms with default cutoff = fetched `createdAt`, or the deeplink's explicit
  `minPhotoDate` when present.
- [x] 5.5 `commonTest`/presentation tests: loaded phase seeds default from `createdAt`; "Only from now"
  sets now; manual pick crosses on confirm; confirm persists the cutoff; autoJoin uses createdAt /
  explicit cutoff; 404/load-fail unchanged.

## 6. Create routes into the join gate

- [x] 6.1 Change the create use-case (`:capability:event-creation-ui`): on `201`, route the minted
  `eventId` into the pending-join gate (auto-routed, **not** auto-confirmed) instead of provisioning
  directly; on success return `creationStatus` to `Idle`; on failure `Failed(reason)` with no gate.
- [x] 6.2 Make the reduction give a pending interactive join (scan or auto-routed create) precedence over
  the `creationStatus`-derived create layer while `config == null` (`:domain:presentation`).
- [x] 6.3 Presentation tests: a successful mint opens `JoiningEvent` for the real `eventId`; a failed
  mint shows the inline create error and opens no gate; cancel after mint leaves no config (harmless
  orphan); config-present leaves the create layer.

## 7. Dev/test deeplink cutoff (headless loop)

- [x] 7.1 Ensure `SNAPSYNC_DEEPLINK` with `minPhotoDate` forces the cutoff on an `autoJoin` launch;
  confirm end-to-end on device that a fresh event uploads only post-cutoff photos (verify via the bunny
  storage zone, not the status counts).

## 8. Harness + integration coverage

- [x] 8.1 Full-stack world (`:test:world`): drive a real cutoff (replace the hardcoded `null`) so the
  world inspector can set a membership cutoff; forge harness renders the join cutoff row.
- [x] 8.2 `:test:integration` (`commonTest`, JVM + simulator): assert the event union **excludes** a
  device's pre-cutoff assets from other members' downloads (objects land, ledger `COMPLETED` only for
  in-scope; foreign import skips pre-cutoff), and a `null` cutoff behaves as today (whole-library).

## 9. Validate & wrap

- [x] 9.1 `./gradlew build` green (JVM tests + all targets) and `./gradlew compileIosMainKotlinMetadata`
  green (iOS source-set proxy).
- [x] 9.2 `npx --yes @fission-ai/openspec@1.4.1 validate add-join-date-cutoff --strict` passes; update
  copy to the "sharing/syncing event photos" framing (no "backup" language) on the new cutoff row.
- [ ] 9.3 Branch → PR → `/ship`; then archive the change (`openspec archive`).
