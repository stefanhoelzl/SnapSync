## Why

The device dev/test loop can already **join** any existing event headlessly (`SNAPSYNC_EVENT_LINK`
with `autoJoin`, plus `minPhotoDate`/`direction`/`saveToAlbum` overrides), but it cannot **create**
one: `POST /events` mints an event and then routes into the pending-join gate **non-auto-confirmed**,
which waits for a tap. So exercising a new membership from scratch — and testing more than one
membership shape, which needs more than one event because direction/cutoff/album are fixed at join —
requires a human at the device to create events by hand. There is also no headless way to **leave** an
event and return to the unjoined resting state. This change closes both gaps so the on-device loop is
fully scriptable over USB.

## What Changes

- Add a `SNAPSYNC_CREATE_EVENT` developer launch-environment trigger carrying a `base64url(JSON)`
  payload (`name` required; `startsAt`, `autoJoin`, `minPhotoDate`, `direction`, `saveToAlbum`
  optional). It mints an event via the existing attest-gated `POST /events`, then:
  - with `autoJoin` → forwards a synthesized `autoJoin` event link through the **existing tested join
    gate** (`autoConfirm`), landing a live membership in one launch;
  - without `autoJoin` → **mint-only**, emitting the greppable oracle line `created eventId=<uuid>`
    and joining nothing.
- Add a `SNAPSYNC_LEAVE` presence-triggered developer launch-environment variable that leaves the
  current membership (cancel downloads, stop the producer, clear config, notify the backend) and is a
  no-op when unjoined.
- Apply the membership-mutating triggers in one **ordered, sequential** application —
  `leave → create → event-link` — so combinations are meaningful and correctly sequenced. This folds
  the existing `SNAPSYNC_EVENT_LINK` application into the same ordered path; its observable behavior is
  **unchanged**.
- **CREATE is non-idempotent** (unlike every existing trigger): each cold launch with the variable set
  mints a *new* backend event. This is inherent — the backend mints a fresh UUID per `POST` — and is
  documented as the honest contract (unset the variable after the mint), not treated as a defect.
- Extend forge inertness: a forge launch (`SNAPSYNC_FORGE_STATE`) ignores `SNAPSYNC_CREATE_EVENT` and
  `SNAPSYNC_LEAVE` exactly as it already ignores `SNAPSYNC_EVENT_LINK` (structurally — the forge shell
  holds no route to the live stack).

All three triggers keep the existing posture: injectable only via a developer launch, read **once per
process**, inert in production with **no** compile-time guard.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ios-app-shell`: add two new developer launch-environment trigger requirements (`SNAPSYNC_CREATE_EVENT`
  and `SNAPSYNC_LEAVE`), including the CREATE non-idempotency contract and the mint-only oracle line;
  note the ordered `leave → create → event-link` membership application (event-link behavior unchanged);
  and extend the "forge wins over an event link" scenario to cover create/leave.

## Impact

- **`:domain` `model/`**: a new field pair on `LaunchDirectives` (`createEvent: String?`,
  `leave: Boolean`) with `commonTest` coverage; a new strict `base64url(JSON)` codec + DTO
  (`CreateEventPayload`) mirroring `decodeEventUrl`, tested in `commonTest`.
- **`:domain` `feature/creation`**: a new tested `HeadlessCreate` use-case that owns the `CreateOutcome`
  branch (mint → forward synth link / log id / log failure), composed onto `AppCore` via `snapSyncApp`.
- **`:app:ios` (`SnapSyncRoot`, `MainViewController`)**: wiring only — parse the two new directives,
  apply them through one ordered shell-routed membership application (forge shell no-ops it), await
  attestation before the create `POST`. Untested by rule.
- **Backend**: no change — reuses the existing attest-gated `POST /events` and the leave endpoint.
- **Docs**: root `CLAUDE.md` on-device runbook gains both variables, the `created eventId=<uuid>`
  oracle, and the ⚠️ non-idempotency warning (opposite of the "leave `SNAPSYNC_EVENT_LINK` set" advice).
