## Why

Discovery of an event's *later* photo additions is **foreground-only** today: nothing tells a joined
device that a peer has uploaded new photos, so it can only catch up when the user next opens the app.
There is no backend→app channel at all — no APNs entitlement, no token registry, no sender. This
change builds the **generic delivery pipe**: a device registers its APNs push token with the backend,
the backend can send a **silent** push to every device in an event, and the app receives it. It
deliberately stops at the pipe — the first *use case* (waking the app to download new photos) is a
follow-up that wires a caller to the notify endpoint. Silent push is a **best-effort accelerant**
layered over the existing foreground discovery and the `BGProcessingTask` download backstop, not a
delivery guarantee.

This change builds on `device-namespace-reorg`, which reserved `devices/<deviceId>/` — the push token
lives in `devices/<deviceId>/config.json` there.

## What Changes

- **Device push-token registration.** The device acquires its APNs token and registers it via
  **`PUT /devices/<deviceId>/config`**, gated by **deviceId possession** (the same capability model as
  uploads). The backend writes `devices/<deviceId>/config.json` =
  `{ pushToken: { kind: "apns", token, env } }` (`env` ∈ `sandbox`|`production`, `kind` a discriminated
  union so a future transport slots in). The app registers on launch once the token arrives, and on
  token rotation.
- **Event notify endpoint.** New **`POST /event/<eventId>/notify`** — gated on the event marker —
  enumerates the event's members (`events/<eventId>/device/` LIST), reads each member's `config.json`,
  and sends a **fixed silent** (`content-available: 1`, no alert/sound) push to every member's token.
  It returns a **bare `202`** with no per-device results. **No production caller is wired** — the
  triggering use case is deferred.
- **APNs sender.** A backend sender: ES256 **JWT** provider auth from a `.p8` key (reused ≤1h), APNs
  host chosen per token `env`, `HTTP/2` `POST /3/device/<token>` with the `apns-topic`/`apns-push-type:
  background` headers. Implemented as ~fetch + WebCrypto (no native dependency); the notify endpoint's
  fan-out calls it best-effort (per-token failures are logged, never fail the request).
- **iOS receive path.** The app gains the **`aps-environment`** entitlement and the
  **`remote-notification`** background mode, registers for remote notifications, forwards its device
  token (+ compile-time `env`) into a Kotlin seam, and routes an incoming silent push to a
  `PushReceiver` seam whose infrastructure-phase implementation **just logs** (provable via
  `idevicesyslog`).
- **APNs backend config.** New fail-closed runtime env: `APNS_KEY_ID`, `APNS_TEAM_ID`,
  `APNS_PRIVATE_KEY` (the `.p8` PEM), `APNS_TOPIC` (`app.snapsync`) — same runtime-env category as the
  storage `AccessKey`, **not** CI/deploy secrets.

Out of scope / deferred: any production trigger of `POST …/notify` (the download-discovery use case),
user-visible alerts or a permission prompt, pruning of APNs-rejected (410) tokens, and non-APNs
transports.

## Capabilities

### New Capabilities

- `device-config-endpoint`: the backend `PUT /devices/<deviceId>/config` write — deviceId-gated,
  writes `devices/<deviceId>/config.json` with the `pushToken` document; last-write-wins; faithful
  outcome.
- `apns-push-sender`: the backend APNs provider — ES256 JWT auth, env→host selection, silent
  `content-available` payload, `HTTP/2` send to `/3/device/<token>`, per-token outcome; no native
  dependency.
- `event-notify-endpoint`: the backend `POST /event/<eventId>/notify` — marker-gated, member
  enumeration + per-member `config.json` read, best-effort silent fan-out, bare `202`.
- `push-registration`: the device-side `:capability:push` — the `PushTokenSource` seam, the tested
  registration use-case that writes the device config, registration timing (launch + rotation), and
  the `PushReceiver` receive seam (infra-phase logging).

### Modified Capabilities

- `backend-config`: adds the APNs provider credentials (`APNS_KEY_ID`, `APNS_TEAM_ID`,
  `APNS_PRIVATE_KEY`, `APNS_TOPIC`) to the fail-closed runtime-env inventory.
- `ios-app-shell`: adds the `aps-environment` entitlement + `remote-notification` background mode, the
  launch-time remote-notification registration forwarding the device token to the push seam, and the
  incoming-push forward to the `PushReceiver` seam.

## Impact

- **New module `:capability:push` (Kotlin):** `commonMain` `PushRegistration` (tested on JVM +
  `iosSimulatorArm64`) + `PushTokenSource`/`PushReceiver` seams; `iosMain` platform adapters (acquire
  token via `UIApplication`, log-on-receive). No third-party dependency (KMP push libs are all
  Firebase — rejected). Wired only in `:app:ios`.
- **Backend (`backend/`, Deno/TS):** new `backend/src/apns.ts` (JWT + fetch sender); `backend/src/app.ts`
  gains the config write + notify routes and the fan-out; `backend/src/config.ts` gains the APNs env;
  `backend/**/*.test.ts` (routes + sender against mocked fetch); `backend/README.md`.
- **iOS shell (`iosApp/`, untestable wiring):** `iosApp.entitlements` (`aps-environment`),
  `Info.plist` (`UIBackgroundModes += remote-notification`), the `AppDelegate` remote-notification
  registration + receipt forwarding, and the compile-time `APNS_ENV` from `Config.xcconfig`.
- **Test harnesses / `:test:world`:** the notify + config routes and a config store added to the
  MiniEdge/BackendStore so `:test:integration` can assert registration + fan-out over the real stack.
- **New Apple Developer setup:** an APNs Auth Key (`.p8`) minted for the team; its id/team/key
  provisioned as backend runtime env (via the App Store Connect API / portal).
