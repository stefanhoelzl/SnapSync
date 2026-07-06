## Why

Joining an event today uploads and shares a device's **entire** photo library — every uploaded
asset also enters the event union, so other members download it. A joiner has no way to scope their
contribution to the photos that belong to the event, forcing an all-or-nothing choice between
sharing years of unrelated photos or not joining. This adds a per-device capture-date cutoff, chosen
at join, so a member contributes only the photos taken from a moment they pick onward.

## What Changes

- On the join confirmation screen, a **datetime row** lets the joining device choose a capture-date
  cutoff. Only photos with `creationDate >= cutoff` are uploaded **and** listed in the device's event
  manifest (so the cutoff governs both this device's backup and what other members can download).
- The default cutoff is the event's **`createdAt`** (already returned by `GET /events/:id`), with an
  **"Only from now"** shortcut and a manual **date+time** picker (bounds fully open).
- **BREAKING (flow):** creating an event now `POST`s the event and then **auto-routes into the same
  join confirmation gate** every user hits — the creator picks a cutoff and joins like anyone else —
  instead of provisioning directly. A cancelled create leaves a harmless member-less event marker.
- The cutoff is **per-device**, **immutable after join** in v1, stored on the per-event membership
  (`EventConfig.minPhotoDate`), and **never sent to the backend**.
- The device manifest's per-event projection is **fed the cutoff** (a mechanism already specified but
  previously fed `null`); the **device-global accumulator is preserved**.
- Byte upload gains a cutoff filter in the shared `UploadCycle` — `creationDate >= min(cutoffs across
  memberships)` (a single cutoff in v1) — covering both upload tiers and both discovery walks.
- An **optional dev/test deeplink cutoff key** rides alongside `autoJoin` (auto-join's default cutoff
  is `createdAt`).
- **No backend change:** `GET /events/:id` already returns `createdAt`; only the client parser starts
  reading it.
- Prose reword in `device-manifest` and `bunny-list-endpoint`: "the event's start" → "the device's
  configured start for that event" (no behavioral change to the union).

Deliberately **future-proofed but not built**: editable cutoffs and joining multiple events with
different cutoffs. These drive three shaping decisions — cutoff stored per-membership, the
accumulator kept device-global, and the byte filter expressed as a `min` over memberships — so
neither future requires a rework.

## Capabilities

### New Capabilities
- `photo-date-cutoff`: the per-device, per-event capture-date cutoff — its data model (carried on the
  membership, immutable in v1, with the UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` format invariant that makes the
  lexicographic compare correct), the injected time source (a `Clock` + local→UTC formatting for "now"
  and manual picks), and the shared-`UploadCycle` byte-upload filter (`creationDate >= min(cutoffs)`).

### Modified Capabilities
- `join-event`: the confirm gate's loaded phase gains the cutoff row (default = fetched `createdAt`,
  an "Only from now" shortcut, a manual date+time picker); confirming provisions with the cutoff; the
  details fetch parses `createdAt`; create auto-routes into this gate; `autoJoin` uses `createdAt`
  (or a dev-supplied cutoff).
- `event-creation-ui`: create `POST`s then routes into the join gate instead of provisioning directly.
- `deeplink-config`: `EventConfig` carries `minPhotoDate`; the deeplink gains an optional dev/test
  cutoff key (strict decoder updated).
- `device-manifest`: the per-event projection is fed the device's configured cutoff (the accumulator
  stays device-global); prose reworded off "the event's start."
- `bunny-list-endpoint`: prose reworded off "the event's date-filtered projection" (the union still
  trusts each manifest as-is; no behavioral change).
- `design-system`: a new `App*` date/time input component wrapping Material 3 `DatePicker` + `TimePicker`.

## Impact

- **Code**: `:capability:config` (`EventConfig.minPhotoDate` + Keychain, cross-process to the
  extension; deeplink payload), `:capability:join` (`JoinEvent.join` persists the cutoff; details
  fetch parses `createdAt`), `:capability:event-creation-ui` (`CreateEvent` routes to the gate),
  `:capability:upload` (`UploadCycle` byte filter), `:domain:gallery`
  (`DeviceManifestProducer.startDate` wired), `:domain:ui:components` (new component) + `:domain:ui`
  (date row on the join screen), `:domain:presentation` (thread the cutoff through the confirm intent;
  `JoiningEvent` keeps its shape).
- **Dependencies**: activate `kotlinx-datetime` (already declared in `gradle/libs.versions.toml`,
  currently unused) in the relevant `commonMain` modules; inject a `Clock` (DI, not `expect`/`actual`).
- **Backend**: none — `GET /events/:id` already returns `createdAt`.
- **Tests**: `commonTest` for cutoff formatting + the lexicographic compare; `:test:integration`
  asserting the event union excludes a device's pre-cutoff photos from other members' downloads; the
  forge harness renders the date row; `:test:world` drives a real cutoff (replacing the hardcoded
  `null`).
- **Dev loop**: the headless launch deeplink can carry an explicit cutoff; `autoJoin` defaults to
  `createdAt`.
