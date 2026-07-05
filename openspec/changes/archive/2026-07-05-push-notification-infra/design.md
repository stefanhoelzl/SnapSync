## Context

SnapSync has no backend→app channel. Uploads land straight in bunny storage via a stateless proxy, and
a joined device only discovers a peer's *later* additions when the user next foregrounds the app
(`docs/design.md §1`: "Discovery of later additions is foreground-only"). There is no APNs entitlement,
no token registry, no sender anywhere in the tree.

This change adds the **generic delivery pipe** and nothing more:

```
device ──register──▶ PUT /devices/<id>/config ──▶ devices/<id>/config.json { pushToken:{kind,token,env} }
caller ──trigger──▶ POST /event/<id>/notify ──▶ members = LIST events/<id>/device/
                                              ──▶ read each devices/<mid>/config.json
                                              ──▶ apns-push-sender ─(silent)▶ each token
device ◀──silent push── APNs ──▶ AppDelegate ──▶ PushReceiver (logs, this phase)
```

It builds on `device-namespace-reorg`, which reserved `devices/<deviceId>/` so `config.json` sits
beside `files/` without polluting the per-device listing. Constraints from `CLAUDE.md` /
`docs/design.md`: `:app:ios` is wiring-only and untested (all logic behind seams in tested modules);
`commonMain` logic runs on JVM **and** `iosSimulatorArm64`; the backend is fail-closed and stateless
(object presence is the registry, no DB); DI, not `expect`/`actual`.

## Goals / Non-Goals

**Goals:**
- A device can register its APNs token; the backend can silently push every member of an event; the app
  receives it — all provable end-to-end on a real device via `idevicesyslog`.
- Keep the substantive logic in tested modules (`PushRegistration` in `commonMain`; the sender in
  `deno test`) with only OS-owned bits (token acquisition, push receipt) as thin app-shell wiring.
- No third-party dependency on either side.

**Non-Goals:**
- Any **production caller** of `POST …/notify` — the download-discovery use case is a follow-up. This
  change wires no trigger; the receiver only logs.
- User-visible alerts / a notification permission prompt (silent pushes need neither).
- Pruning APNs-rejected (`410`) tokens from storage; non-APNs transports; delivery guarantees (silent
  push is a best-effort accelerant over foreground discovery + the `BGProcessingTask` backstop).

## Decisions

### Decision 1 — Silent APNs push, token (`.p8` ES256 JWT) auth

APNs is the only OS-sanctioned way to wake a backgrounded/killed iOS app; a socket is impossible.
Silent `content-available` pushes need no user permission. Provider auth is token-based (one `.p8`,
ES256 JWT reused ≤1h) — no certificate files, consistent with the repo's "keys in env" posture.
*Alternative:* certificate auth (rejected — cert lifecycle + files); FCM (rejected — a Google SDK +
second token layer for a pure Apple/bunny app).

### Decision 2 — Hand-rolled sender: fetch + WebCrypto, no native dep

The sender signs the JWT with WebCrypto (ES256) and sends via plain `fetch` (which ALPN-negotiates
HTTP/2, all APNs requires). This matches the backend's existing tiny-fetch-dep posture (`aws4fetch`)
and is portable across Deno Deploy and bunny Edge. *Alternative:* the `apns2` npm lib (rejected —
Node-oriented `node:http2`, runs on Deno only through the shakiest part of Node-compat; more surface,
less control). **Risk to verify in implementation:** that Deno Deploy's outbound `fetch` negotiates
HTTP/2 to `api.push.apple.com`; if not, drop to a minimal raw HTTP/2 client. It should — Deno's fetch
does h2.

### Decision 3 — deviceId is the capability for config writes

`PUT /devices/<deviceId>/config` is gated by **possession of the deviceId** alone — the per-install
Keychain UUID is unguessable and already authorizes that device's byte partition. No new auth model,
no event id required (a token is device-scoped, event-independent). *Alternative:* an event-existence
gate on the config write (rejected — couples device-scoped registration to an event; a pure-consumer
registers before/without joining). The **notify** route, by contrast, *is* event-addressed and keeps
the marker existence gate like the union.

### Decision 4 — Config lives in `config.json`, token as a `kind`-discriminated union

The token is stored as `{ pushToken: { kind:"apns", token, env } }`. `kind` future-proofs a second
transport without reshaping the document; `env` (reported by the device from its compile-time
`aps-environment`) lets the sender pick the APNs host per token — essential because the dev loop is
**sandbox** (sideloaded) while TestFlight is **production**, against one backend. *Alternative:*
backend tries production then falls back to sandbox on `BadDeviceToken` (rejected — doubles requests
for every dev token, adds fallback logic); a single backend-wide env (rejected — dev + TestFlight
can't share one backend).

### Decision 5 — Notify is best-effort and returns a bare 202

`POST /event/<id>/notify` fixes the payload (silent, all members, no exclusion) and returns `202` with
no per-device results. It keeps the event-existence gate (`404`/`502`) and fails `502` only if it
cannot *enumerate* members; per-member config-read or push failures are swallowed. This matches the
best-effort nature of silent push and keeps the endpoint a simple fire-and-forget trigger for the
future caller. *Alternative:* return per-device sent/failed results (rejected by the interview — not
wanted; and misleading given APNs is itself best-effort).

### Decision 6 — New `:capability:push` module; receipt is a thin logging seam

Device logic lives in a new `:capability:push` (`commonMain` `PushRegistration` + `PushTokenSource` /
`PushReceiver` seams, tested; `iosMain` adapters), wired in `:app:ios`. Receipt is a no-op-that-logs
`PushReceiver` so the pipe is observable now and a real handler drops in later without touching the
app-shell wiring. *Alternative:* fold into `:capability:config` or `:capability:device-id` (rejected —
those are client-side event provisioning and identity; overloading them blurs concerns).

## Risks / Trade-offs

- **[Deno Deploy HTTP/2 to APNs unproven]** → Verify with a `deno test` / staging send during
  implementation; fall back to a raw HTTP/2 client if `fetch` won't negotiate h2. Low likelihood.
- **[Silent push is throttled / undelivered to force-quit apps]** → Accepted by design: it is an
  accelerant, not a guarantee; foreground discovery and the `BGProcessingTask` backstop remain the
  correctness path.
- **[Stale tokens accumulate]** (`410 Unregistered` never pruned) → Accepted this phase; the sender
  reports the status for a future pruning pass; last-write-wins keeps a rotated token current.
- **[APNs `.p8` is a real secret in backend env]** → Same runtime-env category and handling as the
  storage `AccessKey`; never in source, never a workflow secret; minted/rotated via the ASC API/portal.
- **[Untestable receive path]** → Only the thinnest wiring (token acquisition, push receipt) is
  untested `:app:ios`; the tested `PushRegistration` and the `deno test` sender cover the logic. Proven
  on-device via the sideload loop → `idevicesyslog`.

## Migration Plan

1. Provision an APNs Auth Key (`.p8`) for team `E9Z8BADH58` (ASC API / portal); set `APNS_KEY_ID`,
   `APNS_TEAM_ID`, `APNS_PRIVATE_KEY`, `APNS_TOPIC=app.snapsync` as backend runtime env.
2. Backend: `backend/src/config.ts` (fail-closed APNs env), `backend/src/apns.ts` (sender),
   `backend/src/app.ts` (config write + notify routes + fan-out), tests + README.
3. Device: new `:capability:push` (registration + seams, `commonTest`); `:app:ios` AppDelegate
   registration/receipt wiring; `iosApp.entitlements` (`aps-environment`), `Info.plist`
   (`remote-notification`), `Config.xcconfig` `APNS_ENV`.
4. Test infra: config store + notify/config routes in `:test:world` MiniEdge/BackendStore;
   `:test:integration` asserts registration write + fan-out.
5. Verify: `deno test`; `./gradlew build`; `./gradlew compileIosMainKotlinMetadata`; on-device — a
   dev IPA registers a **sandbox** token → `POST /event/<id>/notify` → `idevicesyslog` shows the
   receive log. Rollback = redeploy the prior backend bundle; the app change is inert without a caller.

## Open Questions

- **Deno Deploy outbound HTTP/2** — confirmed-or-fallback during implementation (Decision 2 / Risk 1).
  No product/API ambiguity remains; the download-discovery caller is intentionally out of scope.
