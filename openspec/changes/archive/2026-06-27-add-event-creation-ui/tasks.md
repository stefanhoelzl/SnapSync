## 1. Capability module: seams + status model

- [x] 1.1 Create the `:capability:event-creation-ui` module (Kotlin Multiplatform, `commonMain` + `commonTest` + `iosMain`, Ktor + kotlinx-serialization), wired into `settings.gradle.kts` and dependency-flow boundaries
- [x] 1.2 Define `CreationStatus` in `commonMain`: `Idle`, `InFlight`, `Failed(reason)` with a sealed `reason` distinguishing invalid-name from transient/server
- [x] 1.3 Define the ports: `EventCreator { fun create(name: String) }` (fire-and-forget) and `CreationStatusSource { val creationStatus: StateFlow<CreationStatus> }`, plus a `MutableCreationStatusSource` for the use-case to drive (mirroring `MutableEventStatusSource`)

## 2. Capability module: HTTP client + create use-case

- [x] 2.1 Implement `HttpEventCreator(client: HttpClient, host: String)` in `commonMain`: `POST <host>/event` with `{ "name": <trimmed> }`, parse `201 {eventId,name,createdAt}`; map `400` → invalid-name reason, any other non-2xx / transport / parse → transient reason (mirror `HttpEventFilesSource`)
- [x] 2.2 `commonTest` with `MockEngine`: `201` parses the eventId; `400` → invalid-name failure; `502`/transport/parse → transient failure; request posts the trimmed name to `<host>/event`
- [x] 2.3 Implement the create use-case: on `create(name)` set `InFlight`, call the client, on success funnel `eventId` into the provision path (an injected `onProvision(prev,new)` + `ConfigStore.save`), on failure set `Failed(reason)` and leave config untouched; never inspect permission
- [x] 2.4 `commonTest` for the use-case: success provisions (onProvision + save invoked, config saved) and never sets a success status; failure sets `Failed` and does not save; permission is never read

## 3. Design system: AppTextField + remove SetupCard

- [x] 3.1 Add `AppTextField(value, onValueChange, placeholder, enabled, maxLength)` to `:domain:ui:components` (Material 3 contained inside; no appearance/`Modifier`/M3 types in the signature); enforce `maxLength` and the `enabled` guard
- [x] 3.2 Add `:domain:ui:components` tests: signature is appearance-free, input refused beyond `maxLength`, disabled field never calls `onValueChange`
- [x] 3.3 Delete `SetupCard` and its tests (no remaining consumer after the gate retires)

## 4. Presentation: UiState + reduction + intents

- [x] 4.1 Add the create-layer `UiState` variants: a create-input state carrying an optional inline error (matched to the `Failed` reason) and `CreatingEvent`; remove `UiState.Setup`
- [x] 4.2 Update the reduction so `config == null` reduces to the create layer from `creationStatus` (`InFlight`→CreatingEvent, `Failed`→input+error, `Idle`→input), outranking permission/join/snapshot; leave rungs 2–4 unchanged
- [x] 4.3 Wire `CreationStatusSource` into `StatusContainerHost` (new constructor seam with an inert default, like `eventStatusSource`); add `onCreateEvent(name)` calling `EventCreator.create`; retain `onOpenUrl`
- [x] 4.4 Make the create-screen inline error serve both a sticky `Failed` create error and the transient invalid-deeplink effect; keep `SetupEffect`/effect type covering the invalid-link case
- [x] 4.5 Update `StatusContainerHostTest` (and remove setup-gate-specific assertions): config-absent rungs (Idle/InFlight/Failed), config-present rungs unchanged, `onCreateEvent` invokes the creator, invalid deeplink flashes transient + changes nothing

## 5. UI: create-event screen

- [x] 5.1 Render the create layer in `StatusScreen` within `ScreenLayout`: `AppTextField` (name, capped 100), `PrimaryButton` (disabled until trimmed-non-empty) invoking `onCreateEvent`, a passive "scan a QR to join" hint, and the inline error line
- [x] 5.2 Render `CreatingEvent` as a preparing indicator (no input); ensure the create layer shows no leave action
- [x] 5.3 Local Compose state holds the field value; submit passes the trimmed name through the container (value not in `UiState`)

## 6. Desktop harness

- [x] 6.1 Update `PanelController`: stand-in config cell typed `EventConfigPayload?`; add a stand-in creation-status cell implementing `CreationStatusSource` and a no-op `EventCreator`
- [x] 6.2 Add control-panel creation presets (Idle / InFlight / Failed×reason) effective only while config is absent; config-off now reveals the create screen
- [x] 6.3 Update harness tests/UI checks for the new config-off (create screen) and the creation presets

## 7. iOS wiring

- [x] 7.1 In `SnapSyncRoot`, construct the real `HttpEventCreator(darwinHttpClient(), host)` (host from Info.plist `BackgroundUploadURLBase`, as rejoin does) and the create use-case, injecting the existing `onProvision`/`ConfigStore.save` so create reuses the join path
- [x] 7.2 Inject `CreationStatusSource` into the container; expose an `onCreateEvent` entry on the root mirroring `onOpenUrl`
- [x] 7.3 Verify `compileIosMainKotlinMetadata` (Linux proxy) is green

## 8. Integration tests

- [x] 8.1 In `:test:integration`, assemble the real `event-creation-ui → presentation` path: a fake `EventCreator` driving `CreationStatus`, asserting `UiState` across Idle→InFlight→(provision→config present→Joining) and Idle→InFlight→Failed
- [x] 8.2 Assert config-absent always shows the create layer regardless of permission, and config-present preserves the existing permission/join/sync precedence

## 9. Docs + spec hygiene

- [x] 9.1 Update `docs/design.md §2`: in-app event creation now exists; the create landing screen replaces the setup gate; correct the "no event creation in-app" line
- [x] 9.2 Update module map in `CLAUDE.md` / `docs/design.md` to list `:capability:event-creation-ui`
- [x] 9.3 Run `./gradlew build` (all targets + JVM tests, headless UI tests) green; run `compileIosMainKotlinMetadata` green

## 10. Archive

- [ ] 10.1 After merge, run the OpenSpec archive so `setup-gate` is removed, `event-creation-ui` is added, and the design-system/sync-status-screen/desktop-test-harness deltas fold into `openspec/specs/`
