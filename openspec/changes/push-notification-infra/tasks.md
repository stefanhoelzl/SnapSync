## 1. Backend config — APNs credentials (`backend/src/config.ts`)

- [ ] 1.1 Add `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY`, `APNS_TOPIC` to `Config` and the
  `readConfig` fail-closed inventory (throw naming any missing/blank var), alongside the storage vars.
- [ ] 1.2 `deno test`: `readConfig` throws when each APNs var is absent/blank; succeeds when all present.

## 2. APNs sender (`backend/src/apns.ts`)

- [ ] 2.1 Implement ES256 JWT signing with WebCrypto from the `.p8` PEM (header `{alg:ES256,kid}`,
  claims `{iss,iat}`); cache/reuse the signed token, re-signing when older than one hour.
- [ ] 2.2 Implement `sendSilent(tokens)`: per token, select host from `env` (`production`→
  `api.push.apple.com`, `sandbox`→`api.sandbox.push.apple.com`; unknown→skip), `POST /3/device/<token>`
  via `fetch` (auto HTTP/2) with `apns-topic`, `apns-push-type: background`, `apns-priority: 5`, body
  `{"aps":{"content-available":1}}`; skip non-`apns` `kind`.
- [ ] 2.3 Isolate per-token failures — record each token's outcome (status/skip), never throw out of the
  batch; return the outcomes.
- [ ] 2.4 `deno test` against a mocked `fetch`: correct host per env, headers + silent body, JWT reused
  within lifetime, unknown-env/non-apns skipped, one `410`/error doesn't stop the batch.
- [ ] 2.5 Verify Deno's `fetch` negotiates HTTP/2 to the APNs host (staging send or integration check);
  if it cannot, drop in a minimal raw HTTP/2 client behind the same `sendSilent` interface.

## 3. Backend routes (`backend/src/app.ts`)

- [ ] 3.1 Add `PUT /devices/:deviceId/config` (deviceId UUID-gated, ungated by event): write the JSON
  body to `devices/<deviceId>/config.json` (`Content-Type: application/json`) via one unconditional
  upstream `PUT`; `2xx` only on confirmed store, else `5xx`; `400` non-UUID, `404` unmatched/wrong-method.
- [ ] 3.2 Add `POST /event/:eventId/notify`: marker gate (`404` absent / `502` non-404 read failure);
  LIST `events/<eventId>/device/` for members; read each `devices/<deviceId>/config.json`; call the
  sender best-effort (skip absent/unparseable/no-token members; swallow per-send failures); respond
  bare `202`; `502` only if the member LIST fails; `400` non-UUID; `404` unmatched/wrong-method.
- [ ] 3.3 Update the `app.ts` header route/layout comments and `backend/README.md` for the two new
  routes and `config.json`.
- [ ] 3.4 `deno test`: config-write happy path + key + faithful `5xx`; notify gate `404`/`502`,
  all-members-pushed, member-without-token skipped, per-send failure still `202`, empty members → `202`,
  member LIST failure → `502`.

## 4. Device push module (`:capability:push`)

- [ ] 4.1 Create the module (`capability/push/build.gradle.kts`, `settings.gradle.kts` include;
  `jvm()` + `iosSimulatorArm64`; deps `:capability:device-id`, Kermit, the shared HTTP client seam);
  no third-party push dependency.
- [ ] 4.2 `commonMain`: `PushTokenSource` seam (token + `env`, rotation, settable test fake) and
  `PushReceiver` seam (silent-push receipt); `env` is an injected compile-time value.
- [ ] 4.3 `commonMain`: `PushRegistration` use-case — build+send `PUT <host>/devices/<deviceId>/config`
  with `{pushToken:{kind:"apns",token,env}}` via an injected HTTP client seam; string-building only, no
  crypto/eventId; absorb non-2xx/errors without throwing.
- [ ] 4.4 `commonTest` (JVM + `iosSimulatorArm64`): registration PUTs the exact URL+body; failed write
  is absorbed; no eventId anywhere; re-register same token is idempotent.
- [ ] 4.5 `iosMain`: adapters — acquire the APNs token via `UIApplication` registration (feeding
  `PushTokenSource`) and a `PushReceiver` impl that logs receipt (Kermit).

## 5. iOS app shell wiring (`:app:ios`, `iosApp/`)

- [ ] 5.1 `iosApp/iosApp/iosApp.entitlements`: add `aps-environment` (`development`/`production` by
  build config); `iosApp/iosApp/Info.plist`: add `remote-notification` to `UIBackgroundModes`.
- [ ] 5.2 `iosApp/Configuration/Config.xcconfig` → `Info.plist`: bake `APNS_ENV`
  (`sandbox`/`production`) per build configuration; expose it to Kotlin for `PushTokenSource`.
- [ ] 5.3 `iOSApp.swift` `AppDelegate`: `registerForRemoteNotifications` on launch; forward
  `didRegister…DeviceToken` (encoded token + `env`) and `didReceiveRemoteNotification` to Kotlin via
  `SnapSyncRoot`; log `didFailToRegister…`; no decision logic in Swift.
- [ ] 5.4 `SnapSyncRoot` (`app/ios/src/iosMain`): compose `:capability:push` into the live root —
  route the token to `PushRegistration` (host + `deviceId` from device-identity), route receipt to the
  logging `PushReceiver`; call the OS fetch completion handler.

## 6. Test world + integration

- [ ] 6.1 `:test:world` MiniEdge/BackendStore: serve `PUT /devices/<id>/config` (store `config.json`)
  and `POST /event/<id>/notify` (enumerate members → read configs → record intended sends via an
  operator-inspectable sink); keep config out of the file listing/union.
- [ ] 6.2 `:test:integration`: assert a device registration writes the config document, and that a
  `notify` over the real stack fans out to exactly the event's members carrying a token (skipping
  members without one).

## 7. Apple provisioning (operator runbook)

- [ ] 7.1 Mint an APNs Auth Key (`.p8`) for team `E9Z8BADH58` via the App Store Connect API/portal;
  record its key id.
- [ ] 7.2 Set `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY` (the `.p8` PEM), `APNS_TOPIC=app.snapsync`
  as backend runtime env on the active deployment target (same category as `BUNNY_STORAGE_ACCESS_KEY`).

## 8. Verify

- [ ] 8.1 Backend green: `cd backend && deno fmt --check && deno lint && deno check && deno test`.
- [ ] 8.2 App green: `./gradlew build` (incl. `:capability:push`, `:test:integration`) and
  `./gradlew compileIosMainKotlinMetadata` (iOS proxy).
- [ ] 8.3 On-device end-to-end: sideload a dev IPA (registers a **sandbox** token), confirm
  `devices/<id>/config.json` landed, `POST /event/<id>/notify`, and observe the receive log in
  `idevicesyslog`.
- [ ] 8.4 `npx --yes @fission-ai/openspec@1.4.1 validate push-notification-infra --strict` passes.
