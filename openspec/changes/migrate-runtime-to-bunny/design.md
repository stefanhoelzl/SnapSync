# Design — migrate the device-facing runtime to bunny Edge Scripting

## Context

The backend is one Deno/Hono bundle that has been deployed to **two** runtimes on every `main` push —
bunny Edge Scripting and Deno Deploy — with the device-facing origin `snapsync.stho.net` (a domain we
own, Bunny DNS) `CNAME`'d to whichever is active. Deno Deploy has been active since
`changes/archive/2026-06-30-add-custom-domain`, because bunny.net was dropping iOS's zero-window upload
SYNs. `backend-deployment` calls the dual deploy *"deliberate insurance"*.

**The insurance is a corpse.** Probing the deployed bunny script:

```
GET https://snap-sync-n8xmz.bunny.run/events/<valid-uuid>   → 400, empty body
GET https://snap-sync-n8xmz.bunny.run/nonsense              → 400, empty body
GET https://snap-sync-n8xmz.bunny.run/                      → 400, empty body
    cdn-requestpullsuccess: True   cdn-requestpullcode: 400
```

Hono answers `404` on an unmatched path, so the app is not running; the CDN reached the origin and the
*script* failed. `GET /compute/script/79725` confirms why — its whole configuration is:

```
EdgeScriptVariables                 Secrets
  BUNNY_STORAGE_ZONE = snap-sync      BUNNY_STORAGE_ACCESS_KEY  (LastModified 2026-07-02)
  BUNNY_STORAGE_HOST = storage.bunnycdn.com
```

`readConfig` requires **ten**. Seven are absent — `PUBLIC_BASE_URL`, `BUNNY_S3_REGION`, `BUNNY_S3_HOST`,
`APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY`, `APNS_TOPIC` — so the parse throws and the script
fails closed at boot. `MonthlyRequestCount: 0`: it has never served a request.

**And the config it *does* hold is wrong.** Its `BUNNY_STORAGE_ZONE` says `snap-sync`. Listing the
account's storage zones:

```
snap-sync-dev         id=1627601  region=DE  files=96  used=11.4 MB   ← the real photos
wfe-staging-bundles / wfe-prod-bundles                                 ← unrelated projects
```

There **is no `snap-sync` zone**. The live runtime (Deno Deploy) has always been configured with
`BUNNY_STORAGE_ZONE=snap-sync-dev`; the bunny script names a zone that does not exist. So even had it
booted, every storage call would have failed — a *fourth* independent breakage. Worse, the wrong name
is what every artifact in the repo agrees on: the provisioning notes in
`archive/2026-06-22-add-bunny-upload-endpoint/tasks.md`, the README, and the script's own variable all
say `snap-sync`. **Only the live runtime's environment knew the truth, and it was the one place not in
git.** Hardcoding the "documented" value would have silently repointed the backend at an empty bucket
and looked exactly like data loss.

The timeline is exact. On **2026-07-02** `add-s3-presigned-downloads` added `BUNNY_S3_REGION` and
`BUNNY_S3_HOST` as required vars and set them on **Deno Deploy only** (`backend-deploy.yml`'s
"Configure Deno Deploy env" step) — while simultaneously **orphaning** `PUBLIC_BASE_URL` by deleting
its only consumer, the download proxy. `app.ts` has not read `config.baseUrl` since. On **2026-07-05**
push infra added four more. Every bunny revision since has been dead, including the code deployed on
**2026-07-09**. CI was green throughout: the deploy is `POST /code` + `POST /publish`, both of which
succeed whether or not the script can boot.

Two structural facts explain why discipline could not have prevented this:

1. **Bunny issues no scoped API keys.** Writing an Edge Script's variables requires the full-access
   account key — which also owns the storage zone holding every user's photos and the `stho.net` DNS
   zone. `backend-deployment` therefore (rightly) forbids CI from holding one. So **CI ships code but
   cannot ship config.** Config lives in a dashboard; dashboards drift.
2. **Nothing pointed at bunny, so nothing could notice.** A benched runtime is an unexercised runtime.

bunny has since fixed the SYN drop. So we repair the runtime, close the drift channel at its root,
promote bunny, and retire Deno Deploy.

## Goals / Non-Goals

**Goals:**

- Make the bunny Edge Script boot, and make it **structurally impossible** for config to drift again.
- Prove the iOS-facing surface against bunny **on a real device**, before production DNS moves.
- Make bunny the sole device-facing runtime; retire Deno Deploy entirely.
- Cut over with **no window** in which the origin lacks a valid TLS certificate.
- Fold `backend-config` into `backend-deployment` and record *why* the account-key restriction and the
  source-owned config are one argument, not two.

**Non-Goals:**

- Any iOS build. The baked host is `snapsync.stho.net` — the custom domain exists precisely so this is
  a DNS repoint. Installed devices follow.
- Any fallback runtime, warm standby, or deploy-time boot probe. (Deliberately declined — see Risks.)
- Server-side resumable uploads. Deferred; only becomes urgent if the Live-Photo gate fails.
- Any change to the engine, ledger, status projection, UI, or storage layout.

## Decisions

### 1. Non-secret config moves into source; source wins; only two secrets stay in the environment

Of the ten required values, **two** are credentials (`BUNNY_STORAGE_ACCESS_KEY`, `APNS_PRIVATE_KEY`),
one is dead (`PUBLIC_BASE_URL`), and **seven are public facts** — the zone name and storage host, the
S3 region and host, and the APNs key id / team id / topic. The team id and bundle id ship inside every
IPA; the zone name and script host are already committed (`archive/…/tasks.md`,
`UploadConfigTest.kt:10`). Moving them into source is **zero new exposure**.

```
  BEFORE                                  AFTER
  ──────                                  ─────
  10 required env vars                    7 source constants   (git-owned, cannot drift)
  set in a dashboard, by hand             1 deleted            (PUBLIC_BASE_URL — no consumers)
  CI cannot write them                    2 env secrets        (fail-closed, set once, change ~never)
  ⇒ a new required var can ship           ⇒ a new non-secret value ships WITH the code
    with no value  ⇒ dead script            that reads it. Drift is not detected — it is impossible.
```

**Source wins: the environment is not consulted for the seven.** Considered and rejected: let env
*override* the source constants (`{...DEFAULTS, ...fromEnv(env)}`), which would preserve a local/staging
retarget without a code change. Rejected because it re-opens the drift channel in the opposite
direction — a stale platform variable would silently override git, and a wrong `BUNNY_S3_HOST` mints
presigned URLs that `403` at download time while everything else looks green. `createApp({ config,
fetch })` already takes an injected config (that is how the tests drive it), so a local harness can
construct a config literal without `readConfig` at all. That is the better seam.

**Alternative rejected: put the account API key in CI** and have it upsert the variables
(`PUT /compute/script/{id}/variables`). This was the initially-chosen answer and was reversed once
priced: bunny has no scoped keys, so the CI secret would carry authority over the photo storage zone
and the DNS zone, in a public repo, alongside third-party actions. Fixing config drift is not worth
that blast radius when source constants fix it for free.

**`PUBLIC_BASE_URL` is deleted, not relocated.** Keeping a required-but-unused value is the exact shape
of the bug being fixed.

### 2. Verify on a dev IPA against the existing bunny host, before DNS moves

`ios.yml` already accepts a `workflow_dispatch` `upload_host` (HTTPS-only, builds a Debug dev IPA). And
`snap-sync-n8xmz.bunny.run` already exists, already serves valid HTTPS/h2, and — critically — is
**fronted by the same CDN pull zone** production will use. So it is a ready-made test origin with zero
provisioning that exercises the surface that actually differs from Deno Deploy: the pull zone.

Four gates, all on one sideloaded IPA, with production still on Deno Deploy (**zero blast radius**):

| Gate | Why it is not optional |
|---|---|
| A large streaming `PUT` lands in the zone | The original SYN drop. Also closes the `OPTIONS` fallback and the accepted-`2xx` question by construction — a landed object means the whole handshake worked *through the CDN*. |
| A **Live Photo** paired video lands | The spec's own open caveat. Edge Scripting's 30 s budget is **CPU**, so a pure I/O passthrough is cheap — but the pull zone in front has a documented **60 s request timeout** (`http_timeout`), which Deno Deploy has no equivalent of. This is the one failure whose fix is real work (server-side resumable uploads), so it must be known **before** the escape hatch is deleted. |
| **APNs h2** actually wakes the device | `apns.ts` sends over HTTP/2 via the runtime `fetch`; that code has never run on bunny. Failure here is **silent by construction** — `notify` returns `202` regardless, because per-token failures are best-effort. A bunny that cannot negotiate h2 to Apple would silently kill upload-completion pushes and nothing would report an error. |
| The **URLSession tier** (iOS 18–26.0) works | The SE2 is on iOS 26.5, so the default run exercises only the PhotoKit tier. `SNAPSYNC_FORCE_URLSESSION_UPLOAD` covers the other for one extra `dvt launch --env`. The two tiers probably share the OS networking stack and pass or fail together — but that is a guess, and one launch converts it into a fact. |

Live Photo runs on **WiFi** (accepted: the 60 s CDN timeout is likelier to bite on cellular, but the
OS schedules the extension on its own cadence and this is judged acceptable).

The dev IPA writes into the **live** `snap-sync` zone — both runtimes always wrote to the same storage.
Use a **fresh event id** (per CLAUDE.md, or the rejoin-reconcile seeds existing objects as `COMPLETED`
and nothing uploads), then clean up via the leave cascade or `scripts/reset-storage.ts`.

### 3. Repair the dark script from the branch, not from `main`

Three constraints collide: verification must precede the DNS flip; verification needs a script that
**boots**; and the deploy step is `if: github.ref == 'refs/heads/main'`. Taken literally, the fix would
have to be on `main` before it could be verified — but the PR that puts it on `main` is the PR that
flips DNS.

The escape is that the bunny script is **dark**: DNS points at Deno Deploy and its request count is
zero, so anything can be deployed to it with no blast radius. The deploy protocol is two calls with the
script-scoped key we already hold in `.proton.yaml`:

```
POST /compute/script/79725/code      (DeploymentKey: …)
POST /compute/script/79725/publish
```

So: build the bundle from the branch, push it by hand, verify, **then** merge. The PR stays one PR, the
`main`-only gate is untouched, and CI needs no change. The deployed code diverges from `main` only
while the script is dark; phase 3 merges *before* it flips, so at the moment bunny goes live the two
agree.

### 4. Pre-provision the certificate via DNS-01, then repoint

Bunny's default free-cert flow is HTTP-01 and *"your custom domain must point to Bunny using a CNAME
record before SSL validation can succeed"* — a chicken-and-egg that would leave `snapsync.stho.net`
resolving to bunny with **no valid cert** for seconds-to-minutes. Under default ATS every upload, event
creation, and join fails during that window. Uploads retry, but joins are user-visible failures.

Bunny documents an escape (Seamless Migration): add the hostname to pull zone `6048703` →
`POST /pullzone/{id}/requestExternalDnsCertificate` → publish the returned `_acme-challenge` TXT in the
`stho.net` Bunny DNS zone → `POST /pullzone/{id}/completeExternalDnsCertificate` → **then** flip the
`CNAME`. HTTPS is valid from the first millisecond after the repoint. Renewals fall back to HTTP-01
automatically once DNS points at bunny.

### 5. Harden the listing cache header

bunny documents **`no-cache`** as the origin directive that suppresses pull-zone caching and **never
mentions `no-store`** — which is all the listing routes currently send. Origin `Cache-Control` is
respected by default (`CacheControlMaxAgeOverride: -1`) and Smart Cache never caches
`application/json`, so this is probably fine; but "probably fine, undocumented" is not a good enough
basis for a stale union serving expiring presigned URLs. Send `no-store, no-cache, max-age=0` and stop
relying on undocumented behavior. A CDN in front of the origin is new — Deno Deploy had none.

### 6. Fold `backend-config` into `backend-deployment`

`backend-config` exists because config *"is shared by every endpoint… not owned by any single
endpoint"* — still true, so it cannot fold into an endpoint spec. But after this change config is
**7 constants in git + 2 secrets provisioned once on the platform**, which is precisely a statement
about *how the backend is deployed*. `backend-deployment` is the only shared home, and the fold lets
the spec state the causal chain that was previously invisible:

```
   bunny has no scoped API keys
              │
              ▼
   CI may hold only the script-scoped deploy key   ──►  CI cannot write env vars
              │                                                    │
              ▼                                                    ▼
   least privilege preserved                       config must be git-owned some other way
              │                                                    │
              └────────────────────┬───────────────────────────────┘
                                   ▼
                     non-secret config → source constants
                                   ▼
                        drift becomes impossible
```

Without that link written down, a future reader deletes the account-key requirement as unnecessary
paranoia and silently re-opens the drift channel.

### 7. `main.ts` collapses to the SDK serve

The `if ("Bunny" in globalThis)` branch exists solely because the SDK's Deno path binds
`127.0.0.1:8080`, *"which Deno Deploy can't route to"*. With Deno Deploy gone, `127.0.0.1:8080` is
exactly what local dev wants — it is already what the README's local-run command expects. The branch's
entire reason for existing dies with Deno Deploy.

## Risks / Trade-offs

- **[bunny becomes load-bearing with no fallback runtime]** → *Accepted, not mitigated.* A bunny outage
  is a SnapSync outage; recovery means provisioning a new runtime from scratch. The mitigating fact is
  that the engine retries forever, so uploads are **delayed, never lost**. This is a deliberate choice:
  the two-week corpse demonstrated that an unexercised, unmonitored fallback is not a fallback, and
  keeping one costs a live surface that rots.

- **[No deploy-time boot probe]** → *Accepted.* A three-line `curl` asserting `GET /events/<uuid>` →
  `404` (not `400`) would have caught the corpse on 2026-07-02 and would catch a dead revision from
  *any* cause. It was considered and declined: with config in source, the failure class that actually
  occurred becomes impossible, so prevention replaces detection. **The consequence is that prevention
  must be airtight** — CI cannot tell a booting script from a dead one, since `POST /code` +
  `POST /publish` succeed either way. Anything that reintroduces platform-side required config
  reintroduces the silent-corpse failure with nothing watching.

- **[Silent failure modes nothing now catches]** → *Accepted.* A SYN-drop regression (uploads stop
  landing; ledger retries forever; UI shows pending, no error), a pull-zone config change that starts
  caching listings, a cert expiry, or APNs h2 failing (`notify` returns `202` regardless). This is a
  single-operator TestFlight app; the operator notices when their own photos stop uploading.

- **[Live-Photo paired video vs the 60 s CDN request timeout]** → *Mitigated by gating.* Verified
  on-device before the escape hatch is deleted. If it fails, the fix is server-side resumable uploads —
  real work, not a config flip — and the migration should stop until it exists.

- **[The dev-IPA verification writes into the live storage zone]** → *Mitigated.* There is only one zone
  (`snap-sync-dev`). Use a fresh event id; clean up via the leave cascade or `reset-storage.ts`.

- **[The APNs Auth Key PEM (`W34NF6UMVU`) was printed into an agent transcript]** → *Accepted, not
  rotated (operator's call).* It was surfaced while lifting `APNS_KEY_ID` out of the Deno Deploy env —
  the only place the value existed. The key signs push notifications for `app.snapsync` and grants **no**
  access to photos, storage, DNS, or the backend; the worst an attacker could do is send silent/background
  pushes to installed devices. Rotation is browser-only (the `app-store-connect` CLI has no APNs-key
  command and Apple exposes no public API for them), so it is a manual portal chore that can be done at
  any time: revoke the key, mint a new one, update the `APNS_KEY_ID` constant and the Edge Script secret.

- **[Deployed bunny code temporarily diverges from `main`]** → *Mitigated by ordering.* Only while the
  script is dark (zero traffic). Phase 3 merges before it flips.

## Migration Plan

```
 PHASE 0 — repair, from the branch          (bunny is dark: 0 requests, nothing can break)
   ├─ config-in-source change written on the branch
   ├─ Edge Script 79725, via proton-env + account key:
   │     ADD    secret  APNS_PRIVATE_KEY          ← without it, still will not boot
   │     KEEP   secret  BUNNY_STORAGE_ACCESS_KEY  ← already present
   │     DELETE var     BUNNY_STORAGE_ZONE (20657), BUNNY_STORAGE_HOST (20658)
   │                    (inert under source-wins; deleted so the dashboard stops being
   │                     a place config can live at all)
   ├─ deno bundle → POST /code + /publish          (deploy key, proton-env)
   └─ curl gates:  /events/<uuid>          → 404, NOT 400      ← the script boots at last
                   OPTIONS /files/…        → 204?              ← is the 2026-06-26 "the CDN
                                                                  shadows OPTIONS" finding stale?
                                                                  The probe suggests the CDN now
                                                                  FORWARDS to origin.
                   GET /files/devices/<uuid> ×2 → 200 [], no-store…, cdn-cache: MISS both times

 PHASE 1 — prove it on device               (production still served by Deno Deploy)
   └─ ios.yml dispatch, upload_host=https://snap-sync-n8xmz.bunny.run → sideload
      → the four gates (fresh event id): big PUT · Live Photo · APNs h2 wakes device · URLSession tier

 PHASE 2 — certificate, ahead of the flip
   └─ pull zone 6048703: add hostname snapsync.stho.net
      → requestExternalDnsCertificate → publish _acme-challenge TXT → completeExternalDnsCertificate

 PHASE 3 — cutover
   ├─ merge the PR    → CI deploys bunny; the Deno Deploy step is gone
   │                    (the Deno Deploy APP keeps serving its last bundle — still a live rollback)
   └─ flip the CNAME  → snapsync.stho.net → bunny pull zone       ◄── the migration. TTL 300 s.
      verify: a real upload lands; a join succeeds; a push wakes a device

 PHASE 4 — teardown                          (only now is it safe)
   └─ delete the Deno Deploy app + the DENO_DEPLOY_TOKEN GitHub secret
```

**Phase 4 must trail phase 3.** Tearing down the Deno Deploy app before the `CNAME` moves takes
production down with it. Between merge and flip, Deno Deploy serves its last-deployed bundle — stale
but correct, and a working DNS-repoint rollback for as long as it exists.

**Rollback.** Before phase 4: repoint the `CNAME` back to `alias.deno.net` (TTL 300 s, so ~5 minutes).
After phase 4: there is none — see the first Risk.

## Open Questions

**Both are now RESOLVED** — phase 0 landed, the script boots, and `curl` answered both without a device.

- ~~**Does the pull zone still shadow `OPTIONS`?**~~ **No — the finding was stale.**
  `archive/2026-06-26-migrate-ios-upload-to-bunny` found the CDN answering `OPTIONS` itself with a
  generic `200`, shadowing the script's `204`, and called it *"the #1 device check"*. Against the
  repaired script, `OPTIONS /files/devices/<uuid>/x.jpg` returns **`HTTP/2 204`** with
  `allow: PUT, OPTIONS` and `cdn-requestpullcode: 204` — the CDN **forwards to origin and relays the
  script's own response**. bunny changed this behavior since June. The device will see exactly the
  non-resumable `204` the `bunny-upload-endpoint` contract requires.

- ~~**Does the pull zone honor the listing cache headers?**~~ **It does not cache listings — and the
  hardening was load-bearing.** Two successive `GET /files/devices/<uuid>` both returned `200 []` with
  `cdn-cache: MISS`. But the edge **collapses** the origin's three directives: we send
  `no-store, no-cache, max-age=0` and the device receives **`cache-control: no-cache`**. That is
  precisely the directive bunny documents as cache-suppressing, and precisely the one this change added.
  The pre-change header (`no-store` alone) would have left the listings' cacheability resting on a
  directive bunny's documentation never mentions — so Decision 5 was not paranoia.

One thing phase 0 also settled that was never a question: `POST /events` → `201`, `GET /events/<id>` →
`200`. The **write path authenticates**, which is the proof that overwriting `BUNNY_STORAGE_ACCESS_KEY`
was necessary — the secret previously stored on the script was the password for `snap-sync`, a zone that
does not exist.

What remains unproven is only what needs a device: the four gates in phase 1 (large `PUT`, Live Photo vs
the 60 s pull-zone timeout, APNs h2, the URLSession tier).
