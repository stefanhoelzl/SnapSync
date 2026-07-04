## 1. Config type split + event-name fetch

- [x] 1.1 In `:capability:config`, introduce `EventLinkPayload { eventId }` as the deeplink wire type; repoint `ConfigDeeplink` encode/decode (and the QR generator) to it. Wire format stays `v=3 { eventId }`.
- [x] 1.2 Add `EventConfig { eventId: String, name: String? }` as the persisted joined-event type; retype `ConfigSource` → `StateFlow<EventConfig?>` and `ConfigStore.save(EventConfig)`.
- [x] 1.3 Update `KeychainConfigStore` (iosMain) to serialize `EventConfig` (eventId + optional name) in the shared Keychain item; keep synchronous seed on construction.
- [x] 1.4 Implement the name fetch: a `GET /event/:id` client (reuse the Ktor client/host wiring from `HttpEventCreationClient`) returning `{eventId, name, createdAt}`.
- [x] 1.5 Wire the provision path: scan-provision saves `EventConfig(eventId, name=null)` immediately, then best-effort `GET /event/:id` → `save(EventConfig(eventId, name))`; create-provision saves `EventConfig(eventId, name)` directly (no fetch); refresh name on foreground entry.
- [x] 1.6 `commonTest`: deeplink codec round-trips `EventLinkPayload`; `ConfigStore` name-only update emits without switch; identical save is a no-op; name fetch fills name and a failed fetch leaves join/sync unaffected.

## 2. Download in-flight signal

- [x] 2.1 In `:capability:download-store`, add a per-resource `enqueued` marker (set when a download is sent to the OS, superseded at staged) and an `inFlightCount()` read (asset-counted). Keep it out of the `SuppressionSource` projection.
- [x] 2.2 Set the enqueued marker in `DownloadController` where downloads are enqueued to the OS; ensure it clears via the existing `markStaged` and the leave/switch non-terminal drop.
- [x] 2.3 Add `inFlight: Int` to `DownloadProgress` (`:domain:status`); populate it in `StoreDownloadStatusSource` from `inFlightCount()`, refreshed on foreground entry alongside `downloaded`/`total`.
- [x] 2.4 `commonTest`: `DownloadStore` contract — enqueued set on send, cleared at staged, excluded when not-yet-enqueued or fully staged; `inFlightCount()` matches; `StoreDownloadStatusSource` surfaces `inFlight`.

## 3. UiState collapse + reduction (`:domain:presentation`)

- [x] 3.1 Reshape `UiState`: keep `Loading`, `CreateEvent(error?)`, `CreatingEvent`; replace `PermissionBlocked`/`InProgress`/`Completed`/`NothingToSync` with a single `Joined(health)` carrying a `SyncHealth` sealed value (`InSync` / `Syncing(uploadArrow, downloadArrow)` / `NeedsAccess(permission)`), each arrow `Hidden`/`Static`/`Pulsing`.
- [x] 3.2 Rewrite `reduceFrom`: config absent → create layer; config present → `Joined`, deriving health from permission (`≠GRANTED → NeedsAccess`), `SyncStatus.Loading` (joined loading), and `Ready` (arrow states from `completed/total/pending` and `downloaded/total/inFlight`; `InSync` when both arrows hidden).
- [x] 3.3 Expose `eventName` from `ConfigSource` (`EventConfig.name`) as a screen param alongside `inviteUrl` (derived via `encodeConfigUrl(EventLinkPayload(config.eventId))`); combine download progress (incl. `inFlight`) into the reduction inputs.
- [x] 3.4 `commonTest` (orbit-test): reduction table for the collapsed states — settled→InSync, remaining→Syncing (per-arrow shown/pulse cases), permission-off→NeedsAccess with config present, config-absent→create layer, latest-snapshot-only.

## 4. Event-home screen + design system (`:domain:ui`, `:domain:ui:components`)

- [x] 4.1 Add the semantic `AppStatusLine` component (health sealed value → arrows + label + tappable attention variant; only attention carries a background; reduced-motion aware; no counts, no appearance params).
- [x] 4.2 Make the share/leave actions flat icon buttons (no resting background); keep glyphs contained in `:domain:ui:components`.
- [x] 4.3 Re-layout `StatusScreen` joined layer as the event home: event **name** title, QR hero + "Scan to join this event", `AppStatusLine`, flat share + leave cluster; render QR/share/leave whenever `Joined` (including `NeedsAccess`).
- [x] 4.4 Update the leave confirm dialog copy to "Leave this event?" with **Stay** / **Leave**; reword create-layer and status copy to sharing/sync framing (drop "back up").
- [x] 4.5 Apply the green brand accent and add dark-theme support in the M3 skin; render the QR dark-on-light on a light card in both themes (no inverted QR).
- [x] 4.6 Compose UI tests (`:domain:ui` jvmTest, headless): event-home layout; each status-line state (InSync / Syncing arrow variants / NeedsAccess tap → request vs settings); QR/share/leave present under NeedsAccess; leave dialog copy.

## 5. Harnesses

- [x] 5.1 Update the forge harness (`:app:desktop:ui`) presets/`PanelController` for the new `UiState` (Joined health states incl. NeedsAccess, arrow shown/pulse) and event name.
- [x] 5.2 Update the full-stack world harness (`:app:desktop`) so the emergent status renders through the new joined layer, including the download `inFlight` signal.

## 6. Verify

- [x] 6.1 `./gradlew build` green (all targets + JVM/Compose tests); `./gradlew compileIosMainKotlinMetadata` green (iOS proxy).
- [x] 6.2 `npx --yes @fission-ai/openspec@1.4.1 validate redesign-event-ux --strict` passes.
- [ ] 6.3 Exercise both desktop harnesses to eyeball the event-home states (In sync / Syncing arrows / Turn on photo access) against the approved mockup, light and dark. (Manual — needs a display; `:app:desktop:ui:run` / `:app:desktop:run`. Headless Compose UI tests in 4.6 cover the states functionally.)
