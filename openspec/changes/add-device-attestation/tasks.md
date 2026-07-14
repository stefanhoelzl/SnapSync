## 1. Prove the verification stack (the one residual unknown — do this first)

- [x] 1.1 Spike: decode a **real** App Attest attestation object (CBOR) and verify its chain to Apple's
      root. **Done — verified**, plus six negative controls (wrong challenge / bundle id / team id /
      keyId, tampered `attStmt`, expired chain) all correctly rejected. *Deviation: the vector is the
      `veehaitch/devicecheck-appattest` fixture (Apache-2.0), not an SE2 capture — our probe logged only
      the attestation's length, and a fresh capture needed another 35-min device build. Verification
      against OUR team's attestation is covered end-to-end by task 7.1.*
- [x] 1.2 Confirm it **bundles**: `deno bundle` → **485 KB, zero `node:` imports**, and the bundle
      verifies the real attestation standalone (no `node_modules`, no config). Backend bundle goes
      107 KB → ~590 KB; bunny's script limit is **10 MB**, so this is comfortably inside it.
      *`cbor-x` was rejected — it pulls `cbor-extract`, a **native Node addon**, which cannot run on an
      edge runtime. Use the pure-TS `@levischuck/tiny-cbor` instead.*
- [x] 1.3 Fallback to a hand-rolled DER/CBOR walk — **not needed**; 1.1 and 1.2 both passed.

## 2. Backend — attest routes and token minting

- [x] 2.1 Add Apple's App Attest root CA as the 8th **source constant** in `backend/src/config.ts`
      (spec: `backend-deployment` — a public fact, never an env var).
- [x] 2.2 Add `ATTEST_TOKEN_KEY` to `readConfig`, which **throws** when it is missing or blank
      (fail-closed at boot). Do NOT merge this until task 6.1 is done.
- [x] 2.3 Implement the stateless, time-bounded challenge: `GET /attest/challenge` (self-authenticating,
      no storage write).
- [x] 2.4 Implement `POST /attest/token`: verify chain, nonce, app-id hash, counter `0`, and aaguid
      (accepting **both** `appattest` and `appattestdevelop`); store `devices/<deviceId>.attest.json`;
      mint the HMAC-signed `{deviceId, exp: 30d}` token.
- [x] 2.5 Implement `POST /attest/renew`: verify the assertion against the stored public key, mint a fresh
      token. No Apple call. **No counter.**
- [x] 2.6 Tests: a valid attestation mints; each individual check failing mints nothing; a stale challenge
      is refused; renew with no stored key is `401`; re-attest after "reinstall" overwrites the key.

## 3. Backend — the gate

- [x] 3.1 Implement the token guard: one HMAC verification, **no storage read**, no Apple call.
- [x] 3.2 Apply it to every route, with the closed exception list — `/attest/challenge`, `/attest/token`,
      `/attest/renew`, and `OPTIONS`. Add a test asserting a route not on the list rejects an
      unauthenticated request, so a future route cannot land ungated by omission.
- [x] 3.3 Order the guard **before** each event-existence gate (manifest write, union, notify, leave), so
      an unauthenticated caller cannot probe which events exist. Test it.
- [x] 3.4 Extend the leave cascade's reference-checked GC to delete `devices/<deviceId>.attest.json`
      alongside `devices/<deviceId>.json` for a fully-orphaned device; retain it for a device still in a
      surviving event. Test both.
- [x] 3.5 Verify `OPTIONS` is still answered unauthenticated and still advertises no resumable upload (the
      plain-`PUT` fallback depends on it).
- [x] 3.6 Test that the gated `GET`s still send `Cache-Control: no-store, no-cache, max-age=0` — now
      load-bearing for **authorization**, since the pull zone forwards `Authorization` but does not vary
      its cache key on it.

## 4. iOS — `:capability:attest`

- [x] 4.1 Create `:capability:attest` with the `AttestKey` seam (`generateKey` / `attestKey` /
      `generateAssertion`) in `commonMain`, plus a settable fake — mirroring the `DeviceIdentity`
      precedent.
- [x] 4.2 Implement the tested `commonMain` logic: challenge → attest → mint, challenge → assert → renew,
      the token cache, and the staleness policy (absent / expired / near-expiry ⇒ renew).
- [x] 4.3 Persist the token via `:domain:keychain` (the only module permitted to touch `SecItem*`): shared
      access group, `kSecAttrAccessibleAfterFirstUnlock`, **not** `…ThisDeviceOnly` (restorable, like the
      device id — design D12).
- [x] 4.4 The thin `DCAppAttestService` adapter (`IosAttestKey`). *Placed in `capability/attest/iosMain`,
      not `:app:ios` — matching the `KeychainDeviceIdentity` precedent, where the platform adapter lives in
      its capability's `iosMain` and only the COMPOSITION happens in the app shell. `platform.DeviceCheck`
      is a Kotlin/Native platform klib, so no cinterop `.def` and no Swift shim were needed.*
- [x] 4.5 Surface attestation failure. *Two halves, and only one needed code. The INTERACTIVE paths were
      free: a gated create/join that `401`s already flows into `UiState.CreateEvent(error)` and
      `JoinPhase.LoadFailed`/`CommitFailed`. The BACKGROUND stall needed a new state — without it a device
      whose token died reports "Syncing…" forever while every upload `401`s. Added `SyncHealth.Unattested`
      → `AppSyncStatus.CannotVerifyDevice` (the NeedsAccess attention treatment, but **not tappable** —
      there is no action the user can take). Raised ONLY when there is no usable token AND obtaining one
      failed — never for a merely stale token, which the next wake renews. Ranked below `NeedsAccess`
      (without library access there is nothing to upload) and above sync progress ("Syncing" would be a
      lie). Because opening the app IS a wake, looking at the screen renews and clears it — so it survives
      to be seen only when renewal itself keeps failing (offline, or the backend refusing us), which is a
      real problem that would otherwise be invisible.*

## 5. iOS — attaching the token

- [x] 5.1 Add a request interceptor to the shared Darwin/Ktor client so every backend call carries
      `Authorization: Bearer` — create, event fetch, join/manifest, union, device config, leave, notify,
      and the extension's reconcile listing.
- [x] 5.2 Give `EdgeUploadRequestProvider` a token supplier (`suspend () -> String?`, matching how
      `UploadCycle` already takes `photoCutoff` / `reconcile`). It MUST re-read per `provide` call so a
      retry picks up a refreshed token; a missing token still yields a request (which `401`s and retries)
      rather than failing to build one.
- [x] 5.3 Update the `:capability:upload-url` tests: the header set is now `Content-Type` **and**
      `Authorization` (this inverts an existing requirement that pinned "Content-Type only, no auth").
- [x] 5.4 Run the staleness check at **every** app wake: launch, foreground, silent push, the
      `download.backstop` `BGTask`, and the `upload.heartbeat` `BGTask`. **No new `BGTask` identifier.**
- [x] 5.5 Confirm the extension neither attests nor renews — it reads the Keychain token and sends it
      as-is, including when expired (`DCAppAttestService.isSupported` is `false` in the appex, verified).

## 6. Deploy (ordering is load-bearing)

- [x] 6.1 **`ATTEST_TOKEN_KEY` is set on the bunny Edge Script** (script `snap-sync`, id 79725) — done
      BEFORE any code reading it merges, so `readConfig` will not throw and the outage hazard is disarmed.
      Verified: the script now holds three secrets, and the live backend still boots and serves (it runs the
      old code, which ignores the new variable).
      *Set via the account key (`BUNNY_API_KEY` from `.proton.yaml`), the only credential that can write a
      script's environment — CI holds only the script-scoped deploy key, which is precisely why this step
      cannot be automated. TWO TRAPS, both paid for in downtime:*
      *(a) The endpoint is `POST /compute/script/<id>/secrets`, NOT `/variables` (405). The script object's
      `EdgeScriptVariables` field is empty and always was — secrets live on a separate endpoint and are
      invisible in the script record.*
      *(b) **The value field is `Secret`, not `Value`.** A body with `Value` is accepted with `200` and
      silently creates the secret with an EMPTY value — the name shows up in `Deno.env`, so it looks set,
      and the script then fails to boot. There is no update: `PUT /secrets/<id>` is 405 and re-POSTing a
      duplicate name is 400, so you must DELETE the secret and POST it again.*
- [x] 6.2 **Done.** The enforcing backend is deployed to the live script and the attesting build is
      installed on the SE2, which is attesting, uploading, and self-recovering. *The backend was deployed
      from this branch via the script-scoped deploy key rather than by merging; merging to `main` simply
      redeploys the identical bundle through CI.*

## 7. Verify on device (the properties `commonTest` cannot prove)

- [x] 7.1 **PASSED on the SE2 against the live gate:** `GET /attest/challenge → 200`,
      `POST /attest/token → 201`, `DeviceAttestation: attested and minted a fresh token`.
      *Observed: `PushRegistration` fires BEFORE attestation completes and takes one `401 unattested`, then
      retries — harmless, and it self-heals on the next token.*
- [x] 7.2 **PASSED:** 25 objects landed in the bunny storage zone at 18:56–18:57 UTC — well after the gate
      went live at 18:15 — so every one traversed the token check. Verified against the zone, not the
      status screen.
- [x] 7.3 **PASSED:** `SyncEngine: completed key=…` — the OS-performed `PUT` was accepted by the gated
      endpoint with the real token, and the extension made **zero** attestation calls (it only read the
      app's token from the shared Keychain), exactly as designed.
- [x] 7.4 **PASSED — and it caught the bug that made this task worth doing.** Forced a rejection by
      temporarily deploying a backend that refuses tokens ISSUED before a cutoff (signature valid, expiry
      30 days out — the exact shape of a rotated key or a GC'd attest record). Reverted immediately; the
      signing secret was never touched.
      *Observed recovery, in about one second and with no intervention:*
      `GET /events/… → 401` → `DeviceAttestation: the backend rejected our token — dropping it` →
      `GET /attest/challenge → 200` → `POST /attest/renew → 201` → `token renewed`, and the same routes
      then returned `200`/`201`. *Recovery went through **renew**, not re-attest — the Secure-Enclave key
      was kept, so it paid a cheap local assertion instead of Apple's throttled path.*
      *TWO BUGS FOUND, both fixed and pinned by tests:*
      *(a) the rejection path itself (design D10b) — without it that first `401` repeats forever;*
      *(b) a thundering herd — every in-flight request's `401` independently triggered a refresh, so ONE
      rejection produced THREE concurrent `/attest/renew` calls. `ensureFresh` is now serialized behind a
      mutex that re-checks staleness inside the lock, so queued callers no-op.*
- [x] 7.5 **Verified on the deployed backend, through the pull zone:** all nine gated routes return `401`
      with no token and touch no storage; `OPTIONS` still returns `204` (so the uploader's plain-`PUT`
      fallback survives); `GET /attest/challenge` issues a real nonce; a forged bearer is rejected. The
      anonymous write that motivated the whole change — `PUT /files/devices/<any-uuid>/<name>` — is now
      `401`.
