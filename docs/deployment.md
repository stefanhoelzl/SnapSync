# Deployment and release

How SnapSync's backend (`api/`), site (`site/`) and iOS app are configured, deployed, released and kept
tidy. This is an engineering doc, not a contract: the user-facing promises live in `openspec/specs/`, and
the reasons behind each choice live in the decision records cited per section
(`openspec/changes/archive/<id>`). If this doc and a workflow file disagree, the workflow is right; fix
this doc.

Architecture (state, the token gate, the HTTP contract, the code layout) is in
[`architecture.md`](architecture.md). Running things locally (the rig, tests, simulators) is in
[`testing.md`](testing.md).

---

## 1. Deployment configuration

### What a deployment is

Four toolchains (Deno, Gradle, Xcode, Astro) and the App Store listing all need the same facts: which
domain, which storage zone, which Apple team and bundle id, which build channel. None of them can import
another's source, so a **deployment** declares these facts once and one resolver renders them for
everyone.

```
deployments/prod.json                  production: extends the components below
deployments/maintenance.json           prod + "maintenance": true (only difference; see §2)
deployments/local.json                 the local rig: filesystem storage, domain 127.0.0.1:8080
deployments/components/build.json      build-scope values: sha (GITHUB_SHA), channel (SNAPSYNC_CHANNEL)
deployments/components/policy.json     eventCapacity, eventWindowMaxSeconds, eventLifetimeSeconds,
                                       attestTokenTtlSeconds
deployments/components/apple.json      bundleId, teamId, apnsKeyId, appStoreUrl, appAttestRootCa
deployments/components/android.json    androidPackageName, playStoreUrl, the attestation roots/trust/digests,
                                       the Firebase values
deployments/components/storage-*.json  storage kind / zone / host / s3Region + the access-key env reference
deployments/components/prod-core.json  domain + the env references for every secret, shared by prod and
                                       maintenance
```

Rules the resolver (`scripts/resolve-deployment.py`) enforces:

- **Composition is a shallow merge** of top-level keys, in `extends` order, the deployment's own keys
  last. Components may not `extends`. No deep merge, no interpolation, no conditionals. Two deployments
  that must agree share a component; one deployment never extends another.
- **The key inventory in the resolver is the reference** for every key: which renderings it reaches, its
  scope, when it is required, its default, and why it exists. An unknown key, a missing required key, a
  missing component, a nested `extends`, or an unknown storage kind fails resolution and names the file
  and key. A failed resolution writes **nothing**.
- **A value is a literal or an env reference** `{ "env": "NAME", "scope"?: "build" | "runtime" }`.
  `runtime` is the default: the rendering carries the variable's *name* and the consuming program reads
  it when it runs. `build` means the resolver reads the variable now and bakes the value. Baking always
  has to be asked for.
- **The rendering set is the whole containment rule.** A key appears only in the renderings its
  inventory entry lists. A runtime-scope key may not name a baked rendering. A value taken from the
  environment may not go into a rendering that has no escaping (the xcconfig).
- **Storage is a sealed kind.** Which secrets are required follows from the kind. A filesystem-kind
  deployment declares no storage or database credentials, so a dev run *cannot* address the production
  store. It also fails to boot on the Edge runtime.
- **Every call site names its deployment.** There is no default; an unknown name fails.
- **Renderings are generated and never committed.** A consumer run without the resolver fails on a
  missing file rather than using a stale value.

Renderings, all emitted by one invocation (`python3 scripts/resolve-deployment.py <name>`):

| Rendering | Path | Read by |
|---|---|---|
| json | `api/src/deployment.ts` | the Deno bundle, the sweep, the migration scripts |
| properties | `build/deployment.properties` | Gradle; also the deploy workflow reads `domain=` from it. **Literals only** |
| gradle-json | `build/deployment.json` | Gradle, for the Android build's values nobody reviewed (the crash-reporting DSN and environment), in a grammar that escapes |
| xcconfig | `iosApp/Configuration/Deployment.xcconfig` | Xcode build settings, entitlements, `Info.plist` substitutions. **Literals only** |
| plist | `iosApp/Configuration/Deployment.plist` | copied into **both** the app and the extension bundle |
| metadata | `build/metadata/**` | both stores' listings, rendered from `metadata/listing/` (§6) |
| site | `site/src/deployment.json` | the Astro site |

`scripts/resolve_deployment_test.py` runs in `ci.yml`'s `metadata` gate, on every push.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe`.

### Why config lives in the bundle, not in platform env vars

Bunny has **no scoped API key**. Writing an Edge Script's environment needs the account key, and that
key also owns the storage zone with every user's photos and the `stho.net` DNS zone. So CI holds only
the script-scoped **deploy key**. CI can ship code, but it cannot ship platform config. This once left
the backend unable to boot for two weeks while CI stayed green.

Resolving config into the bundle means every non-secret value ships with the code that reads it. The
backend **never** reads a non-secret value from the environment, so a stale platform variable cannot
override it. **Do not "fix" config drift by giving CI the account key.** That would put every user's
photos within reach of CI.

Decision record: `changes/archive/2026-07-14-migrate-runtime-to-bunny`.

### Secrets

The deployment declares the **names**. Values come from the environment of whichever program runs.

| Env secret | Where it is set | Meaning |
|---|---|---|
| `BUNNY_STORAGE_ACCESS_KEY` | Edge Script env; GH secret for `nightly-cleanup` and the `site` deploy job | storage-zone **password** (the `AccessKey`; also the S3 secret for presigned URLs). **Not** the account key |
| `APNS_PRIVATE_KEY` | Edge Script env | APNs Auth Key `.p8` **PEM contents** (not a path) |
| `ATTEST_TOKEN_KEY` | Edge Script env | HMAC key for device tokens and attest challenges |
| `BUNNY_DATABASE_URL` / `BUNNY_DATABASE_AUTH_TOKEN` | Edge Script env; GH secrets for the `api` deploy job and `nightly-cleanup` | the relational store |
| `FCM_SERVICE_ACCOUNT_KEY` | Edge Script env | the Firebase service account's **JSON key file contents** (not a path), role *Firebase Cloud Messaging API Admin*. **Optional**: absent, the backend boots and the FCM sender skips every Android token |
| `SENTRY_DSN` | GH secret, `ci.yml` (`ios-build`, `android-build`) | build-scope; baked only into distributed builds (§5), on both platforms |

- The backend validates every declared runtime secret **once at startup**. A missing or blank one throws,
  and the script does not boot. The one exception is `FCM_SERVICE_ACCOUNT_KEY`: without it Android members
  get no silent wake (their photos arrive on the next opening), and nothing else changes. Then the post-publish probe (§2) fails the deploy. The required set is
  derived from the deployment's declaration, not from a second list in code.
- ⚠️ **Order matters when you add a secret**: set it in the Edge Script environment **before** merging the
  code that reads it. If you merge first, the next deploy produces a bundle that cannot boot. Removing a
  secret is safe in either order.
- There is **no admin or bypass credential** anywhere in the backend.
- FCM: the Firebase project's public values — `firebaseProjectId`, `firebaseApplicationId`, `firebaseApiKey`,
  `firebaseSenderId` — live in `deployments/components/android.json`. The project id reaches the api (the FCM
  sender addresses `projects/<id>/messages:send` and sends only to tokens registered under it) and the Android
  build; the other three reach only the Android build, which starts Firebase from them (there is no
  `google-services.json` and no google-services plugin). Empty values start no Firebase and send no FCM push.
  Set the service-account key **before** merging code that depends on it, like any secret.
- APNs: `apnsKeyId` / `teamId` / `bundleId` live in `deployments/components/apple.json`. Update that
  file if the key is rotated. The APNs topic is derived from the bundle id. The key is a one-time
  provisioning for team `E9Z8BADH58`. Decision record: `changes/archive/2026-07-05-push-notification-infra`.
- Store pages: `appStoreUrl` (`apple.json`) and `playStoreUrl` (`android.json`) reach the site and their
  platform's build — the site's two badges and each platform's update notice (capability `app-update-required`).
  `playStoreUrl` is **empty until the Play listing is public** (production launch): empty shows no Play badge,
  gives the Android notice no button, and leaves the event page's install-referrer path dormant. Setting it — to
  exactly `https://play.google.com/store/apps/details?id=<androidPackageName>`, which the resolver enforces
  because the event page appends `&referrer=<invite>` — switches all three on: the site on its next deploy, the
  Android notice in the next Android build. Decision record: `changes/archive/2026-10-01-play-badge-and-install-referrer`.

### iOS: the baked values

- `Deployment.plist` carries what the app and extension read at runtime: the device-facing upload base
  (`uploadBase`, with exactly **one** `/api/vN` prefix), the APNs environment, the crash-reporting
  environment and DSN, and the App Store URL shown on the update-required screen. It is a property list
  because it escapes `//`. The xcconfig grammar treats `//` as the start of a comment, which once
  truncated the DSN to `https:` and shipped four silent TestFlight builds (644–673).
- `BackgroundUploadURLBase` is **also** authored in each bundle's own `Info.plist`, because iOS reads it
  from there to validate the background-upload registration. The resolver's `DEVICE_API_PREFIX` pins
  both. `ios-build` checks after archiving that the two are **exactly equal** in both bundles (§4). Moving
  the API version means changing both carriers together.
- The URL scheme is derived from the host: `http` for a loopback IP literal, `https` for everything else.
  No ATS exception ships.
- **Pointing a dev build at another backend** means selecting or editing a deployment and re-running the
  resolver. For a cloudflared tunnel, write the minted host into `deployments/local.json` and re-resolve.
  No `xcodebuild` override can reach a bundled resource, and CI has no input for it. Runbooks:
  `local-backend` and `ssh-mac-build` skills.

---

## 2. Backend deploy

### Topology

- **One runtime: bunny Edge Scripting.** No second runtime and no warm standby. A bunny outage is a
  SnapSync outage. Uploads are delayed, not lost, because the app retries forever.
- The device-facing origin is **`snapsync.stho.net`**, a `CNAME` in our Bunny DNS zone pointing to the
  pull zone in front of the Edge Script, with a publicly trusted cert. Because we own that name, **changing
  the runtime is a DNS repoint, never a new iOS build**. That matters because the upload extension allows
  exactly one host, fixed at compile time. Keep it that way: never bake a provider hostname.
  Decision record: `changes/archive/2026-06-30-add-custom-domain`.
- Photo **downloads** skip the runtime entirely. They are presigned S3 GET URLs on bunny's
  `<region>-s3.storage.bunnycdn.com`.
- Anything a device depends on must hold **as seen through the pull zone**. The pull zone may answer
  `OPTIONS` itself, and it caches based on `Cache-Control`. That is why listings and `/health` send
  `no-store, no-cache, max-age=0`.
- Storage `host` must be the zone's **main** region (read-after-write consistent), never a replica.
- Site routing is source code in the bundle (the closed static-path allowlist proxying the storage
  `site/` prefix). There are **no pull-zone edge rules**, so disaster recovery is "redeploy the bundle,
  repoint DNS".

### Gates (pre-merge) and the deploy workflow (post-merge)

- **`ci.yml` → `api-test`** runs on every push to every branch with **no path filter**, because a
  merge gate that is never posted blocks merges forever. It resolves `prod`, then runs `deno fmt
  --check`, `deno lint` (with the local plugins declared in `deno.json`, including the complexity rule),
  `deno task check` (type-checks `src/`, `src/dev/`, `src/scripts/`, `src/lint/`), `deno task test`,
  `deno task schema:check`, and a bundle that must carry the commit sha.
  `deno task test` deliberately has **no `--allow-net`**, so no test can reach the real zone.
  Decision record: `changes/archive/2026-08-27-make-api-tests-required`.
- **`ci.yml` → `migration-rehearsal`** posts on every push but does work only when `api/migrations/`
  differs from the merge base with `main`. It copies the deployed store into a local `sqld`
  (`src/scripts/rehearsal-copy.ts`: **pseudonymised** by a per-run key, store to store in memory,
  `__bunny_migrations` byte-exact, counts only in the log), runs the deploy's own
  `bunny db migrations apply` against it through a loopback TLS proxy (`src/scripts/tls-proxy.ts` — the CLI
  refuses unencrypted URLs and has no flag to lift it), then `assert-schema.ts`. That catches, before merge,
  the two failures that used to surface only in the deploy's window: **history drift** (an applied file edited
  — c19b6c4b edited comments in 0001–0003 and 0004 then failed three deploys) and **data** (a precondition
  that aborts, a constraint the real rows break). It holds only the **read-only** database token. Drift made
  outside a branch (a hand edit to `__bunny_migrations`, a CLI release changing its checksum) still surfaces
  at the deploy.
- **`deploy.yml`** runs on push to `main` only, in one concurrency group (`deploy`,
  `cancel-in-progress: false`), so every published bundle is probed by the run that published it. Jobs:
  - `changes` decides whether the api changed since the commit that is **live** (read from `/health`),
    not since the previous push. Paths: `api/`, `deployments/`, `scripts/resolve-deployment.py`,
    `.github/workflows/deploy.yml`. It fails open: an unreadable `/health`, or a sha not in history,
    means "deploy". So a pending run that gets displaced loses nothing.
  - `api` publishes the Edge Script (below).
  - `site` builds `site/` and mirror-deploys it to the storage `site/` prefix (upload new files, delete
    stale ones, never clear first). It runs on every push and uses **only** `BUNNY_STORAGE_ACCESS_KEY`.
  - `appstore-metadata-apply` (§6).
  - ⚠️ **No job in `deploy.yml` may ever be a required check**: it never runs on a PR, so a required check
    from it would block every merge. Nothing in it re-runs a gate. Merges are rebase-only, so the
    deployed commit is not the exact commit that was checked; what backs it is the strict ruleset plus
    the boot probe.
  Decision record: `changes/archive/2026-09-22-unify-main-deploy-workflow`.

### The `api` job

The job bundles (`deno task bundle` → `dist/main.js`, one self-contained file under the 10 MB limit; the
deploy action uploads the file as-is). It then decides the path with
`src/scripts/migration-plan.ts`, which has **three** outcomes. Exit `0` means none pending. Exit `10`
means pending. Anything else is **fatal**, because treating a crash as "none pending" would publish onto
an un-migrated store.

- **Nothing pending** (the usual case): `assert-schema.ts` compares the live store to `schema.sql` →
  publish → probe (`--maintenance=false`) → archive.
- **A migration is pending**:
  1. Read the live sha from `/health`, and prove its archived `bundle-<sha>` artifact can be retrieved.
     If it cannot, refuse to open the window.
  2. Publish the **maintenance bundle**: the same commit resolved from `deployments/maintenance.json`.
     While it serves, every `/api/` route answers `503` + `Retry-After` before the token gate. Root routes
     (`/`, `/join`, the AASA, `/_astro/*`, `/health`) keep serving.
  3. Probe that the window is **open**.
  4. `bunny db migrations apply --dir migrations …` (the platform runner).
  5. `assert-schema.ts` again, inside the window.
  6. Re-bundle from `prod` → publish → probe that the window is **closed** → archive `bundle-<sha>`.
  7. **On any failure after the window opened**, republish the archived bundle of the previously live
     commit and probe that the window is lifted.

The maintenance flag is a build-scope key, off by default, rendered only into the backend bundle. It
ships **in the bundle** because CI cannot write the script's env. What code is published is CI's only
lever. Decision record: `changes/archive/2026-08-27-add-deploy-maintenance-mode`.

### Boot probe and `/health`

`POST /code` + `/publish` succeed whether or not the script can boot, so a green publish proves nothing.
`src/scripts/probe.ts` polls `https://<domain>/health` through the **device-facing origin**, so it also
covers DNS, the cert and the pull zone. It passes only when the response names **this** commit's sha
**and** the expected maintenance state. `/health` itself runs `SELECT 1` on the store and a listing on
the storage zone, and answers `503` if either is unreachable. A missing `maintenance` field means the
window is closed. The probe retries causes that time can fix (connection errors, 5xx, 404, a different
sha, the wrong window state) until a deadline, and fails at once on causes that waiting cannot fix.

What it **does not** prove: that a configured value is *correct*. A wrong but present value still boots.

⚠️ **One observation, ~119 points of presence.** The probe sees the one PoP the hostname resolves to, and
bunny publishes no propagation guarantee. The maintenance guarantee is "very likely no request met a
bundle that disagreed with the schema", never "certainly".

### Rollback

- Every green deploy archives `bundle-<sha>` as a GitHub Actions artifact (90 days, the platform
  maximum). The archive is **not** in the storage zone: the deploy job must not hold that key, and a
  rollback must still work when bunny is what is failing. Bunny's own release re-publish needs the
  account key (the deploy key gets `401` there).
- Only a **migrating** deploy rolls back automatically. There is **no rebuild fallback**: a missing
  archive fails loudly and names the commit. If a **non**-migrating deploy goes red, the live bundle stays
  live and a human fixes forward.

### Credentials the deploy path holds

| Job | Holds | Never holds |
|---|---|---|
| `api` | `BUNNY_SCRIPT_ID`, `BUNNY_DEPLOY_KEY` (script-scoped), `BUNNY_DATABASE_URL`/`_AUTH_TOKEN` (it runs the migrations) | the storage key, the account key |
| `migration-rehearsal` (ci.yml, every branch) | `BUNNY_DATABASE_URL`, `BUNNY_DATABASE_READONLY_TOKEN` | any write-capable credential |
| `site` | `BUNNY_STORAGE_ACCESS_KEY` | the account key |
| `nightly-cleanup` | `BUNNY_STORAGE_ACCESS_KEY`, the database pair | the account key, any Edge Script credential |

Do not add the storage key to the `api` job. The database pair is a deliberate, bounded exception,
because CI applies the migrations.

### Schema migrations and their safety rules

Files: `api/migrations/NNNN_*.sql` (ordered, applied at most once, checksummed in the store) and
`api/schema.sql` (**generated** by `deno task schema`, which replays the migrations; committed;
`schema:check` fails CI when it is stale). Read `schema.sql`'s diff to see what a migration did,
including anything it silently **dropped**. A freshness check cannot catch that.

Two runners apply the same files. The deploy uses `bunny db migrations`. Local runs, the rig, the tests and
the schema generator use `api/src/dev/replay.ts`, because the CLI only accepts `libsql://` / `https://` /
`wss://` URLs.

The rules (the checked ones are enforced by `migrations.test.ts`):

- ⚠️ **A migration migrates its data; it never drops it.** A table rebuild copies the rows into its
  replacement. (Checked.)
- ⚠️ **A narrowing migration refuses rather than discarding rows.** Put the precondition **inside the SQL
  file** as a statement that aborts, so it runs where the rows are and inside the migration's
  transaction. The abort message must name the query that lists the offending rows (SQL cannot
  interpolate a count). A refusal fails the deploy while the previous bundle keeps serving. (Checked.)
- ⚠️ **Row copies name their columns on both sides. Never `INSERT … SELECT *`.** It maps columns by
  position, so a reordering rebuild silently swaps same-typed values. (Checked.)
- **Foreign-key enforcement is genuinely OFF for each migration, not deferred.** It is set outside the
  transaction on the same connection. `DROP TABLE` fires `ON DELETE CASCADE`, so rebuilding a referenced
  table would otherwise empty its children and still report success. Both runners must do this the same
  way.
- **Never edit an applied migration — not even a comment.** The checksum refuses it, and
  `migration-rehearsal` fails the branch that does.
- **A new TEXT column is pseudonymised in the rehearsal copy by default.** If a migration must match on its
  values (an enum, a timestamp), add it to `VERBATIM` in `rehearsal-copy.ts` — only if it is not secret.
- **Every served API version keeps its behaviour.** That version's wire-contract tests must pass
  **unmodified**. A test that asserts a retired column is re-expressed to assert the same fact and is
  listed in the change.
- **No reverse migrations.** The store holds only rebuildable state (devices republish manifests and
  re-attest), so the recovery from a broken migration is the same as the recovery from a lost store.
- ⚠️ **Atomic per migration, not per run. Ship one migration per deploy.** If a later migration in a run
  fails, the store is left at a version no bundle expects, including the rollback bundle. The only fix is
  rolling forward by hand.
- **The deploy asserts the live shape** (`assert-schema.ts`, which ignores comments, `IF NOT EXISTS`,
  quoting, whitespace and the runner's bookkeeping table). This also catches a hand edit made through
  `bunny db shell`.
- **One-time data cutovers are not committed.** They run once from a scratchpad with credentials from
  secrets-env. The plan and the output go into the change's design record.
- **Destructive decisions read the primary.** The store is a single primary with no replica today. The
  sweep still decides deletions inside an interactive transaction. If replicas ever appear, re-measure
  read-your-writes from the edge before letting anything destructive act on an ordinary read.

Decision records: `changes/archive/2026-08-25-record-uploads-in-database`,
`changes/archive/2026-08-26-migrations-preserve-their-data`,
`changes/archive/2026-09-07-adopt-bunny-cli-migrations`.

### Provisioning (once)

1. With the Bunny **account API key**, outside CI: an **S3-enabled** storage zone (DE), a Database, and
   the Edge Scripting app. Record its **script id** and a **deploy key**. Set the runtime secrets (table
   above) on the Edge Script.
2. GitHub secrets: `BUNNY_SCRIPT_ID`, `BUNNY_DEPLOY_KEY`, `BUNNY_DATABASE_URL`,
   `BUNNY_DATABASE_AUTH_TOKEN`, `BUNNY_STORAGE_ACCESS_KEY`. The account key **never** goes into CI.

### Operator warnings

- ⚠️ **There is no whole-zone storage reset, and there must never be one.** `snap-sync-dev` is the
  *only* zone. The deployed backend uses it, so it holds real TestFlight and App Store users' photos.
  Clean up **targeted only**: a fresh event id, a leave, or deleting one event's or one device's objects.
- Running `src/main.ts` directly targets the **real** zone and database and needs every secret. Use the
  local rig instead (see [`testing.md`](testing.md)).

### Edge Scripting limits worth knowing

- **30 s CPU** per request. CPU, not wall-clock, so streaming bytes through is cheap. What would break
  it: buffering a body (`request.bytes()` overflows the **128 MB** isolate) or per-byte CPU work.
- **No documented wall-clock limit on the script, but the pull zone has a 60 s request timeout.** That is
  the real ceiling for a large Live Photo video over a slow link. If it ever bites, the fix is
  server-side resumable uploads.
- 10 MB script, 500 ms startup, **50 subrequests** per request, 128 env vars.
- The relational store is **public preview**: 1 GB per database, up to **10 s of data loss** on primary
  failover, 32 766 bound parameters per statement. Acceptable because every row can be rebuilt by a
  device round-trip.

---

## 3. Scheduled jobs: the nightly sweep

The sweep is the only thing that deletes events. It runs outside the Edge Script, because the Edge
runtime has no scheduler and a whole-store walk would exceed 50 subrequests / 30 s CPU.

- **Workflow:** `.github/workflows/nightly-cleanup.yml`, cron `17 3 * * *` (03:17 UTC), plus
  `workflow_dispatch` with `dry_run` (`gh workflow run nightly-cleanup.yml -f dry_run=true`: logs what
  it would delete and deletes nothing). Concurrency group `nightly-cleanup`, never cancelled.
- **Program:** `api/src/scripts/sweep.ts`, resolved against `prod`. It imports the Edge Script's own
  `db.ts`, `lifecycle.ts`, `storage.ts` and config, so the rules cannot drift. It **marks from the
  database and deletes from storage**. It makes no request to the Edge Script and sends no notification.
  Members find out the event is gone on their next foreground fetch.
- **Event phase:** delete every event past its derived delete-by (`max(createdAt, startsAt) +
  lifetimeSeconds`, the guarantee) or **empty** (has memberships and none are active). Emptiness is
  opportunistic, not promised. An event nobody ever joined is not "empty". The decision runs inside an
  interactive transaction against the primary. One `DELETE` cascades memberships and assets.
- **Asset phase** (over the surviving events): collect a byte under `files/devices/<id>/` only if no
  surviving event references it **and** it was uploaded before the earliest `startsAt` of the events
  that device is active in (no events means +∞). The `resources` row is deleted **before** the byte, so a
  crash leaves an orphan byte that the next run collects. A device with no membership left loses its
  `devices` row **only after its last token has expired**. Deleting it earlier would push the device into
  a re-attestation loop every night.
- **Storage it touches:** only `files/devices/`. Never `site/`, and never the legacy `events/` and
  `devices/*.json` objects (kept as the database migration's rollback path).
- **Failure mode:** best-effort per object. A single failed delete is logged and counted. The run exits
  non-zero only on a systemic failure (auth, or storage cannot be listed at all). The summary (events,
  devices, files deleted and kept, byte sizes, error count) goes to the job's Summary panel.
- ⚠️ The old guard that refused to sweep an empty store is gone. If the database stops describing this
  zone, the sweep would delete every byte in it.

Decision records: `changes/archive/2026-07-21-nightly-cleanup`,
`changes/archive/2026-07-24-decouple-event-window-from-lifetime`,
`changes/archive/2026-08-25-record-uploads-in-database`.

---

## 4. CI

`.github/workflows/ci.yml` holds **every merge gate**. Triggers: push to any branch (tags excluded, and **no path
filter**, so docs-only merges build and deliver too), plus `workflow_dispatch` on any ref. Newer pushes cancel older
runs on the same ref and event, `main` included. `~/.gradle` and `~/.konan` are cached; signing material is **never**
cached.

**Required checks: every gate, plus `check-label`** (§7, pull-request-triggered, its own file). `/ship` owns the
ruleset (read it with `gh api repos/stefanhoelzl/SnapSync/rulesets`) and requires every check that ran on a PR, so a
new gate joins it on the next ship by having run; a **renamed or removed** gate must be dropped in that ship
(`--drop-context`), because a required context never posted again freezes every merge. The aggregate `ci` `needs:`
every gate, runs `if: always()`, and fails unless each succeeded; it is what `ios-deliver` and `android-deliver` wait on. `ios-deliver`, `android-deliver`, `deploy.yml`, `nightly-cleanup`, `screenshots` and
`promote` must **never** be required: none of them runs on a PR push.

**No gate `needs:` another**: each compiles what it needs, so a red gate never skips another's run. The artifact
edges are `ios-build` → `ios-deliver` and `android-build` → `android-deliver`. The marketing version both builds carry
is one script both run (`scripts/marketing-version.py`), not a job they wait on. The shared `commonTest` runs once, on the JVM, in `build`; each platform runs the
same two gates — its build followed by its platform-bound tests on the same runner, and its journeys
(`docs/testing.md`, "Where each test runs"). Build and tests share a runner because together they stay under the
journeys (~8 min against ~10 on iOS), so the critical path does not move and a push holds one macOS runner fewer (the
account gets about three). The journeys stay separate: a one-job-per-platform spike doubled the iOS wall clock (~21
min against ~10).

| Job | Runner | What it does |
|---|---|---|
| `build` | ubuntu | `./gradlew build` (every target compiled, every JVM test, every architecture gate; needs Deno), then the diagrams freshness check: `architectureDiagrams` on the clean checkout must leave `architecture/` unchanged. |
| `metadata` | ubuntu | `openspec validate --specs --strict` (pinned 1.13.2), the resolver's suite (§1), and the App Store listing's offline validation (§6). |
| `api-test`, `migration-rehearsal` | ubuntu | §2, "Gates". |
| `site-build` | ubuntu | The site's build and `npm run check`. |
| `ios-build` | macos-26 | Signed `xcodebuild` archive of the device (`iosArm64`) app: the app's only device compile. **Release** on a delivering run (a push to `main`, or any dispatch), **Debug** otherwise (about 2.4 min faster; a Release-only failure shows up on `main`). It exports no IPA and uploads nothing to Apple. It verifies the baked deployment (below), runs `./gradlew iosPlatformTest` — every module's `iosTest` on the simulator (host `IOS_SIM_KEXE`) — and on a delivering run tars the archive (artifacts lose symlinks and exec bits) and uploads it for `ios-deliver` (1-day retention). |
| `android-build` | ubuntu | `:app:android:assembleRelease`: the plain release, the store's — R8 and resource shrinking over its whole graph, the R8 mapping in `app/android/build/outputs/mapping/release/` (that R8 output RUNS is `journeys (android)`'s, on the rig release). On a delivering run the same R8 run also produces the **bundle** (`bundleRelease`, channel `release`, the DSN, the computed version and the build number as `versionCode`); the job asserts the DSN is in the bundle's dex (compared, never printed) and uploads the unsigned bundle, its mapping and its version for `android-deliver` (1-day retention). Then `./gradlew androidPlatformTest`, `:adapter:android`'s device tests on a Gradle-managed Pixel 6 / API 36 emulator with the transfer fixture served by the build (host `ANDROID_EMU`, KVM). |
| `journeys (ios)` | macos-26 | `scripts/sim-contracts`: builds the rig app (`-Psnapsync.rig=true`, `local` deployment) and ad-hoc signs it (`scripts/sim-sign`). **Nothing overlaps the build**: only after it, with the Gradle and Kotlin daemons stopped, does it boot **one** fresh simulator (a booting simulator slows the build several-fold, and a second fresh one's first-boot work tripled the job). Right after boot it stops the simulator's `apsd` (its reconnect loop to Apple's push sandbox logged a million lines in six minutes and cost ~170 s of CPU through the log daemon; nothing tested needs it) and opens Photos, so the library's first-use preparation starts while the app installs (the first write then took 1–46 s instead of 1:23–4:43). Spotlight is switched off on the runner. It installs the app, grants photo access (pinned `applesimutils`), starts `scripts/transfer-fixture.py` and a local `api/` on a fresh filesystem store (warmed with one request), and launches the app. A timestamped **photo-library readiness** stage then makes the first library write (an asset dated outside every contract's window), because a fresh simulator's library takes minutes to accept one and that wait belongs to the platform, not to the first contract. It then checks the `GET /device` vocabulary, runs every registered port contract over the rig (host `IOS_SIM_APP`), and runs the all-real journeys on a bare JVM, with no Gradle alive next to the simulator (`docs/testing.md` section 7). Fails on any `Failed`/`NotWithin` clause, a refused run, an empty registry, a failed journey (printing its assertion message), or a fixture, backend or app that never answers (it captures a screenshot and the app log in that case). Evidence kept: the fixture's request log, the backend's output with a per-request log, host memory/CPU samples, host and simulator crash reports. Decision record: `changes/archive/2026-09-25-one-simulator-journeys`. |
| `journeys (android)` | ubuntu (KVM) | Builds the rig **release** APK (R8 and resource shrinking as the store build runs them, signed with the debug key; `local` deployment) and the journeys, stops the daemons, boots one emulator and runs `scripts/android-journeys`: a local `api/` reversed into the emulator, the app granted the photo library before its first launch, an adapter choice real for every system Android has an adapter for (only the crash reporter and the iOS-only upload-job queue and extension registration mocked), the `GET /device` vocabulary check, then the same journeys on a bare JVM. |
| `ci` | ubuntu | The aggregate above. |

**The Kotlin/Native cache is one family per job**: `-device-` (`ios-build`: restored inside
`.github/actions/ios-archive`, saved by the job after its simulator tests, so it holds both), `-sim-journeys-`
(`journeys (ios)`), through `.github/actions/konan-restore` and `konan-save`. Each job restores its own family first, so a job whose compile set changes never leaves another partly
cold; the primary key carries the run id so it never hits and every save writes fresh; only `main` saves, and each
save deletes its own family's superseded entries (the repo's 10 GB budget is full).

**Archive verification** (delivering runs): read the bundle id, `uploadBase`, APNs environment,
crash-reporting environment and DSN back out of **both** the app and the nested `.appex`, and compare
each to the resolver's output. The DSN is compared without being printed. Also require each bundle's
`Info.plist` `BackgroundUploadURLBase` to be **exactly equal** to its rendered `uploadBase`. It must be
equality, never a prefix test, because an empty string passes a prefix test. A resource that reached only
one bundle, or a value truncated by a grammar, fails the run here instead of producing a silent build.

---

## 5. TestFlight and Play internal delivery

**Every merge to `main` uploads a signed build to internal TestFlight and to Play's internal testing track**,
automatically, docs-only merges included. It reaches **no external tester**: the builds go to TestFlight's internal
`development` group and Play's internal testers only. Real users get builds only through the store release (§6).

- **`ios-deliver`** (in `ci.yml`, `needs: ci` — every merge gate, both platforms; runs on delivering runs only): downloads and unpacks
  the archive, re-signs and exports an `app-store-connect` IPA **without recompiling**, and uploads it
  with one `app-store-connect publish --whats-new …` call (codemagic-cli-tools). That call waits for the
  build to become visible. There is no `--testflight` flag and no beta-group change. Any red gate — on
  either platform — means nothing is uploaded: `main` is red. Delivery therefore starts only when the
  slowest gate finishes, and a flaky gate skips that commit's upload until it is re-run. Decision record:
  `changes/archive/2026-09-25-deliver-needs-ios-contracts`. The job is **not** required and does **not** use `continue-on-error`: a
  failed delivery shows red and blocks nothing.
- **"What to Test" note**: `<PR title> (#<num>, <short sha>)`, resolved through
  `GET repos/{repo}/commits/{sha}/pulls`. It falls back to `<head subject> (<short sha>)`. On a dispatch
  it uses the operator's note, or `<ref> (<short sha>)`. Arbitrary text reaches the shell only through
  environment variables.
- **Branch dispatch = a real TestFlight build of a branch**: `gh workflow run ci.yml --ref <branch>
  [-f what_to_test="…"]`. It follows every rule of a `main` delivery (every merge gate, Release, production APNs, DSN,
  dSYMs, internal group only). Use it for anything only a distributed build can do, for example the hidden
  diagnostic dump.
- **Build numbers**: `CFBundleVersion` = Android's `versionCode` = `ci.yml`'s `github.run_number` +
  `BUILD_NUMBER_OFFSET` (2000; monotonic across refs) — one number names one run's build in both stores, and an
  Android crash report's `dist`. (Play had seen only `versionCode` 1, the manual first upload.) A local Android build
  is `versionCode` 1, set through `-Psnapsync.versionCode`. Earlier builds (all below 2000) were numbered by the retired `ios.yml`'s run number, and App Store Connect refuses
  a build number not above the last one for the same version, so the new workflow's count starts above it. Never
  lower the offset.
  `MARKETING_VERSION` = `max(floor, latest vX.Y tag with minor + 1)`, compared as integer tuples
  (`v0.9 → 0.10`), computed by `scripts/marketing-version.py` in both `ios-build` and `android-build` on a delivering
  run — the iOS `MARKETING_VERSION`, and Android's `versionName` and declared `APP_VERSION`
  (`-Psnapsync.versionName`); every other build declares the floor. The floor is committed in `Config.xcconfig`, so a major jump (`→ 1.0`) is a PR that
  raises the floor.
- **`android-deliver`** (in `ci.yml`, `needs: ci`, the same runs as `ios-deliver`): downloads the bundle
  `android-build` built, keeps its R8 mapping as artifact **`r8-mapping-<build>`** (90 days), signs the bundle with
  `jarsigner` and the **upload key** (Play re-signs it with the Google-held app signing key — the certificate prod's
  `androidSigningCertDigests` pins — and adds its automatic-protection code), and uploads it with
  `.github/scripts/play_release.py deliver`: ONE Play edit that uploads the bundle, makes it the internal track's one
  release (named `<version> (<build>)`, status `completed`, the same note as TestFlight's cut to Play's 500
  characters) and commits — or, on any failure, is deleted, so nothing half-lands. Not required, no
  `continue-on-error`: a failed upload shows red and blocks nothing, and the next delivery carries a higher number.
  The internal track serves only its **latest** release: a branch dispatch replaces `main`'s for internal testers until
  the next merge. (Play's internal app sharing is not used: it re-signs with a key prod's attestation refuses.) The
  mapping is not uploaded to Play; `/bugsink` retraces against the artifact. `play_release.py status <package>` lists
  every track's releases read-only. On **`main` only**, the same edit also brings the Play **store listing** in line
  with the repo (§6, "Google Play listing delivery"); a branch dispatch never touches it. Secrets:
  `PLAY_UPLOAD_KEYSTORE_BASE64` / `PLAY_UPLOAD_KEYSTORE_PASSWORD` (PKCS12, alias `upload`, key password = store
  password), `PLAY_SERVICE_ACCOUNT_JSON` (service account `play-ci`; locally `PLAY_SERVICE_ACCOUNT_KEY` through
  secrets-env) and `PLAY_CONTACT_EMAIL` (the listing's contact email).
- **Signing**: two persistent certificates imported into an ephemeral keychain in **both** `ios-build`
  and `ios-deliver`: Apple Distribution **and** Apple Development. `archive` also provisions a
  development identity, so without the imported Development cert CI would mint a new one every run and
  hit Apple's per-account cap. Provisioning profiles are cloud-managed through the **Admin** App Store
  Connect API key and `-allowProvisioningUpdates`. No fastlane, no `match`.
  Secrets: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY` (raw `.p8`), `SIGNING_CERT_P12_BASE64`
  / `SIGNING_CERT_PASSWORD`, `SIGNING_DEV_CERT_P12_BASE64` / `SIGNING_DEV_CERT_PASSWORD`. The Team ID is
  committed in `Config.xcconfig`.
- **Uploadability**: a 1024×1024 opaque icon, and `ITSAppUsesNonExemptEncryption = NO` with
  `ExportOptions.plist` `method: app-store-connect`, so nothing waits on a compliance prompt.
- **Channel discriminator.** `SNAPSYNC_CHANNEL` is `release` on delivering runs and `dev` otherwise. The
  resolver derives from it the APNs environment (`production` / `sandbox`, both `APS_ENVIRONMENT` and
  `APNS_ENV`), the crash-reporting environment, and whether a DSN is emitted at all. These values cannot
  disagree. Branch-gate Debug archives and the dev build loop are sandbox with no DSN.

### Crash-reporting DSN and dSYMs

- `SENTRY_DSN` is a GitHub secret, declared as a **build-scope** env reference in `prod-core.json`, and
  rendered **only** into `Deployment.plist` (iOS) and `build/deployment.json` (Android, read into `BuildConfig`
  as escaped string literals) and **only** when the channel is `release`. Never into `deployment.properties`:
  its values are interpolated raw, like the xcconfig's. A stray export on a dev build still produces no DSN.
  No DSN means the SDK never starts, and a bug report is saved on the phone instead of sent. On Android only
  `android-build`'s bundle step of a delivering run resolves `release` (the platform tests after it resolve `dev`). ⚠️ Injecting `SENTRY_DSN` on a dev `xcodebuild` line does nothing, because a build
  setting cannot substitute into a bundled resource. Dispatch the branch instead.
- The Bugsink instance ingests no dSYMs. `ios-deliver` publishes each delivered build's dSYMs as
  artifact **`dsyms-<run_number>`** (= `CFBundleVersion`, 90 days, the platform maximum). The `/bugsink`
  skill symbolicates against it on Linux and fails loudly once it has expired. Copy dSYMs somewhere
  permanent at promote time for any version that must outlive 90 days.
- The Android store build is R8-obfuscated, with line numbers kept (`app/android/proguard-rules.pro`).
  `android-deliver` publishes each delivered build's mapping as artifact **`r8-mapping-<build>`** (= `versionCode`
  = the event's `dist`, 90 days); `/bugsink` retraces against it with R8's own `retrace`, and fails loudly once it
  has expired.

Decision records: `changes/archive/2026-07-14-gate-testflight-on-tests`,
`changes/archive/2026-07-19-remove-alpha-testflight-promotion`,
`changes/archive/2026-07-21-restore-testflight-build-note`,
`changes/archive/2026-07-21-debug-branch-gate-archive`,
`changes/archive/2026-07-21-add-crash-reporting`.

---

## 6. Store release and metadata

### Promote a build you already tested

```
gh workflow run promote.yml -f build_number=2140 -f dry_run=true            # stop after the preflights
gh workflow run promote.yml -f build_number=2140                            # App Store only (the default)
gh workflow run promote.yml -f build_number=2140 -f android=true            # App Store + Google Play
gh workflow run promote.yml -f build_number=2140 -f ios=false -f android=true
```

`build_number` is the build's `CFBundleVersion` and its Android `versionCode`: `ci.yml`'s `run_number` + 2000
(`BUILD_NUMBER_OFFSET`), or, for a build at or below 2000, the retired `ios.yml`'s `run_number`.
There is no `version` input: the store version is **derived** from the build's own marketing version, read from
App Store Connect's build N **for either store** (Play's API carries no version name; both platforms build one
version line). The stores are checkboxes: `ios` (default on) and `android` (default off until Play grants
production access); neither is refused. Play's target is the workflow constant `PLAY_TRACK`, today `alpha` (closed
testing). A promote **always submits**; there is no submit flag. The workflow is a single `ubuntu` job: no Xcode,
no Gradle, no signing, only the existing Admin ASC key and Play service account. Order of steps:

1. Resolve build N in App Store Connect and derive `X.Y`. It must match `^\d+\.\d+$`. Also read whether the `X.Y`
   record already left the editable states **with build N** (a rerun after the App Store submit); with another
   build, fail.
2. **Refuse if tag `vX.Y` already exists.** This happens before any store change.
3. Resolve the origin commit: `build_number` → the delivering run on `main` (`ci.yml` run `build_number − 2000`
   above the offset, otherwise the retired `ios.yml` run `build_number`, by its workflow id) → `head_sha`. If it cannot be resolved, fail; never guess.
4. With `android`: require Play to hold a bundle with `versionCode` N (`play_release.py has`) — asked of the bundle
   list, since the internal track keeps only its latest release.
5. Derive the release notes for both stores (§7). Apple's over 4000 characters fails here, having changed nothing.
6. With `ios`, unless already submitted: find or create the `X.Y` version record and attach the build (idempotent).
   A **newly created** record gets the committed copyright (year of first publication). An existing record's
   copyright is left alone. Upload the listing screenshots, composed from `screenshots/*.png` +
   `metadata/screenshots/en-US.json` (ImageMagick) — only an **editable** version is written to, and the set is
   **replaced**. Apply the `en-US` `whatsNew` and the App Review details (notes from `metadata/review/notes.md`;
   contact details from secrets because the repo is public; "no demo account").
7. **Preflight** every selected store before either submit: `asc review doctor` must report no blocking check;
   Play validates an edit holding the exact release the submit will commit, then the edit is deleted.
   `dry_run` stops here.
8. **Submit Play, then the App Store.** Play: `versionCode` N becomes `PLAY_TRACK`'s one release, named
   `X.Y (N)`, status `completed`, with the compact notes, in a fresh edit — skipped when the track already carries
   N. App Store: `asc review submit` — skipped when step 1 found it submitted. Play goes first because its commit is
   all-or-nothing and it is the likelier refusal, so a refusal there leaves nothing in front of App Review.
9. **Last**, once every selected store accepted: create tag `vX.Y` on the origin commit, its message naming the
   build and the stores (`release 0.12 (build 2140): ios, android`). "Accepted" is the submission going through;
   both stores review afterwards, and a review split ships that version on one store only.

Operator rules:

- ⚠️ **Never push a `vX.Y` tag by hand.** Tags trigger nothing (`ci.yml` and `deploy.yml`
  ignore tags), and a tag that already exists makes that version **permanently unreleasable**, because
  step 2 refuses it.
- ⚠️ **A promote is single-shot per version.** After it succeeds, the tag blocks a re-run, and a store left out
  never gets that version. Correcting an already-promoted version's screenshots or release notes is a **manual
  console upload**. A **failed** run leaves no tag and is simply dispatched again: before the Play commit nothing
  was submitted; between the two submits the rerun skips Play; at the tag it skips both.
- ⚠️ **While the Play app is a DRAFT app** (never published), Play refuses any release outside the internal track
  that is not itself a draft — measured 2026-10-01: *"Only releases with status draft may be created on draft
  app."* An `android` promote therefore fails at its Play preflight, before anything is submitted, until the first
  closed release has been sent for review **by hand in the Play Console** (which needs the listing and declarations
  complete). After that first publication the workflow's path applies.
- ⚠️ `asc review doctor` is not the whole preflight. It once passed a version that `asc review submit`
  then refused (missing `en-US: whatsNew`, run 30632785849). A green gate does not guarantee the submit
  will pass.
- Provenance is not re-checked at release. It is guaranteed at upload, because only green `main` commits
  (and deliberate dispatches) reach App Store Connect. ⚠️ Step 3 resolves only runs on `main`, so a
  branch-dispatched build has no resolvable origin and fails at step 3.
- Concurrency is ONE group for every promote, queued, never cancelled: the tag is per version, so two builds of one
  version must not race to it, and a cancel between the two submits is the worst place to stop.

### Icons and store art

`scripts/appicon.py` is the icon's one master: its geometry draws every icon and store image, and nothing is traced
from a bitmap. One run writes:
- the iPhone icon (`Icon-1024.png`);
- Android's adaptive launcher icon: VectorDrawables under `app/android/src/main/res/` (gradient background, the mark
  inside the 66dp safe circle, and a monochrome layer for themed icons);
- Play's 512×512 icon and 1024×500 feature graphic (`metadata/play/images/`).

Every output is committed and none may be edited by hand. Change the geometry or the colours and re-run.
`--check` asserts the vector path still matches the raster mark.

### Listing metadata

- **One source for both stores.** `metadata/listing/<locale>.json` holds the copy the App Store and Google Play
  share: the name, the description and the marketing, support and privacy-policy URLs. It also has an `apple`
  section (subtitle, keywords, promotional text) and a `play` section (short description).
- **Rendering.** The resolver renders it into `build/metadata/`: App Store Connect's strict-schema files
  (`app-info/`, `version/current/`) and Play's fields (`play/`: title, short and full description, contact
  website). No store's file is committed.
- **Placeholders.** `{{domain}}` is the deployment's domain. Every other `{{word}}` is looked up in the source's
  `words`, which gives each store its own wording; for example `{{gallery}}` is "Photos app" on the App Store and
  "phone's gallery" on Play. An unknown word, or one with no wording for a store, fails the resolver before it
  writes anything.
- **The listing's validation** (`ci.yml`'s `metadata` gate, every push, no credentials): character
  limits (description ≤ 4000, keywords ≤ 100, promotional text ≤ 170, whatsNew ≤ 4000, subtitle ≤ 30),
  URL syntax, and **unknown keys**; then `scripts/validate_play_listing.py` checks Play's half (title ≤ 30, short
  description ≤ 80, full description ≤ 4000, nothing left unfilled), since Play has no offline validator. The tool's
  schema is closed. That is why the review notes and the
  screenshot headlines live in their own files: a new key in the canonical files would fail this gate
  and block merges.
- **`appstore-metadata-apply`** (`deploy.yml`, `main` only, not required): resolves the **editable**
  version (`PREPARE_FOR_SUBMISSION` / `DEVELOPER_REJECTED`) at run time, never through a stored id, and
  overwrites the fields that are present. The committed file wins over console edits. It never touches a
  version in review, never creates one, and does nothing (green) when no version is editable. An
  **omitted** field is never deleted. That is what keeps it from clearing the release's `whatsNew`, which
  the committed files deliberately do not carry.
- The `asc` CLI is pinned and SHA-256 verified (`.github/scripts/asc_fetch.sh`). No fastlane, no Ruby.
  App previews (video) are manual.

### Google Play listing delivery

- **Main only, inside the delivery's one edit.** `android-deliver` renders the listing (`build/metadata/play/`),
  checks it, and stages the committed icon and feature graphic. It composites the phone screenshots from
  `screenshots/android/` (the `play` target). `play_release.py deliver --listing … --images …` then compares each part
  with what Play holds, inside the edit that uploads the bundle:
  - the three text fields, by value;
  - the contact website, and the contact email from the `PLAY_CONTACT_EMAIL` secret (never committed, never
    printed);
  - each image set, by the sha256 Play lists, which is the uploaded file's own (measured).

  Only a difference is written. A merge that changes no copy and no image therefore sends Play nothing to review,
  and one that does joins whatever review is open. There is ONE edit per run, because committing an edit invalidates
  every other open one: a separate listing job would race the delivery.
- **One edit, one fate.** A listing Play rejects fails the delivery too, and nothing lands. The `metadata` gate checks
  Play's limits on every PR so that this stays theoretical.
- **Preview before merging.** `uv run .github/scripts/play_release.py listing-diff <package> build/metadata/play <images
  dir>` (under secrets-env) prints what would change. `--try` also writes it into an edit that is then deleted, never
  committed, so Play validates it.
- **Not in the API, so set by hand in the Console:** the privacy-policy URL, the category and every App-content
  declaration. `metadata/play/declarations.md` records each answer and why.

### Screenshots

Six committed raws in `screenshots/` (3 states × light/dark) feed **both** the App Store listing
(uploaded at promote time only) and the `site/` landing page (on merge). A merge that changes them
changes **no** listing. Each capture is the **real app** — the rig build on a simulator — in a state its real
reduction reached: `screenshots.yml` sets its launch adapters to mock every system but the screen and the app's
foreground life, and `:test:integration`'s capture drives it to each state through the control channel (`Shots.kt`:
`create`, `joining`, `in_sync`) and captures it light and dark. The backend, the photo library, the Keychain and App
Attest are all mocks, so no real member's content can reach the listing. The clock is mocked at a fixed instant and the
status bar overridden, and the event id the `in_sync` QR encodes is fixed, so an unchanged UI captures identically —
save the simulator's anti-aliasing of the Dynamic Island's rim (≤~120 pixels, each ≤2/255), which is noise. `ShotsTest` runs the same scenarios on the JVM
host inside `build`, so they cannot rot between dispatches.

```
gh workflow run screenshots.yml --ref <branch>          # ~11-19 min
RID=$(gh run list -w screenshots.yml -L1 --json databaseId -q '.[0].databaseId')
gh run download "$RID" -n screenshots-raw -D screenshots
# LOOK AT THEM, then: git add screenshots/ && git commit
```

**Android** has its own six raws in `screenshots/android/`, the same three states captured the same way from the
Android rig build on an emulator, by `screenshots.yml`'s `android` job. It uses the DEBUG build, because the capture
resets the mocked systems' saved state through `run-as`; the status bar is SystemUI's demo mode. They feed only the
Google Play listing: `compose_screenshots.sh`'s `play` target composites the light set with the same headlines onto a
1080×1920 (9:16) canvas, since a raw 1080×2400 breaks Play's 2:1 limit, and `android-deliver` uploads them when they
changed (below). The landing page keeps the iPhone raws. An unchanged UI captures byte-identically on the emulator too.

```
gh workflow run screenshots.yml --ref <branch>          # both jobs
RID=$(gh run list -w screenshots.yml -L1 --json databaseId -q '.[0].databaseId')
gh run download "$RID" -n screenshots-android-raw -D screenshots/android
```

⚠️ **Looking at them is the only check there is.** A system notification ("Ready for Apple
Intelligence") landed in 1 of 2 runs under the forge; the rig path reuses the same pre-booted simulator, so the risk
is unchanged. Re-dispatch if one does. On an unchanged UI every raw comes back identical, `create` included, bar
that Dynamic Island noise. A
headline or size change needs no re-capture.

Decision records: `changes/archive/2026-07-19-promote-appstore-builds`,
`changes/archive/2026-07-16-dispatch-driven-release-and-submission`,
`changes/archive/2026-07-16-close-appstore-submission-gaps`,
`changes/archive/2026-07-15-sync-appstore-metadata-from-repo`,
`changes/archive/2026-07-30-upload-screenshots-on-promote`.

---

## 7. Changelog labels

The App Store "What's New" text is **derived from labelled pull requests**. App Store customers read it
verbatim, so the label is a statement to customers. Every PR carries exactly one of:

| Label | Meaning | Release-notes heading |
|---|---|---|
| `enhancement` | a user can experience something new | **New** |
| `bug` | a user-visible symptom is gone | **Fixed** |
| `internal` | no customer sees this | excluded |

**The three places that must agree on these names.** Nothing checks them against each other, so change
all three together or none:

1. **`.github/scripts/release_notes.py`**: `CATEGORIES` (label → heading, in output order) and `EXCLUDE`
   (`internal`). This is the **only** place that maps a label to a heading. There is deliberately **no
   catch-all**: an unlabelled PR appears under no heading and is named in the report, rather than having
   its engineering title published.
2. **`.github/workflows/check-label.yml`**: the `check-label` required check on every `pull_request`
   (`opened`, `labeled`, `unlabeled`, `synchronize`). It loops over `enhancement bug internal` against
   the PR's **live** labels (`gh pr view`, not the event payload, which races a label applied just after
   opening) and fails if none is present. This is the only thing that *prevents* an uncategorized change.
3. **`/ship` and the ruleset.** `/ship` (global skill) applies the label as it opens the PR: feature →
   `label.feature`, bugfix → `label.bugfix`, otherwise `label.internal`. The defaults are `enhancement` /
   `bug` / `internal`, and `.ship/config.json` could override them (today it does not). `/ship` also owns
   the live branch ruleset that makes `check-label` required.

How the derivation works:

- The range runs from the nearest ancestor `vX.Y` tag of the origin commit to the origin commit. It is
  open-ended for the first release. Commits are mapped to PRs through GraphQL `associatedPullRequests`
  (which works across rebase merges). Only PRs merged into the default branch count, deduplicated.
- It reads **nothing out of the commits being described** (no config file in the range), so every build
  App Store Connect holds stays promotable. The GitHub release-notes generator was rejected for exactly
  this reason: it reads config from the target commit.
- Rendering: plain text for Apple. Each heading is followed by `- ` bullets in ascending PR number. Each
  bullet is the **PR title** with `type(scope):` stripped, a leading `Fix`/`Fixes`/`Fixed` stripped, and
  the first letter capitalized. No numbers, links, authors or markdown. **So the PR title is the App
  Store bullet**: `.ship/pr-title.md` holds the policy for writing it.
- Google Play gets a **compact** form (`--play-changelog <file>`), because Play takes 500 characters: the same
  bullets with no headings, `New` before `Fixed`, and the lines that do not fit dropped whole from the end and
  counted in a closing `…and N more improvements`. It is met by construction, never refused. The range is the
  same for both stores, so an iOS-only release's notes may name an Android change; that is accepted.
- An all-`internal` range yields the committed fallback: *"Under-the-hood improvements and fixes."*
- The changelog goes to `--changelog <file>`. A reconciliation report goes to stdout for the run summary:
  both stores' notes, counts, the excluded `internal` roster, and uncategorized PRs or unassociated commits listed
  separately. The changelog has **one** consumer, the promote workflow. There is no GitHub Release and no
  committed CHANGELOG.

Preview any range before dispatching a promote:

```
GH_TOKEN=$(gh auth token) python3 .github/scripts/release_notes.py \
  --repo stefanhoelzl/SnapSync --target <origin-sha> --previous vX.Y
```

Decision records: `changes/archive/2026-07-31-derive-release-notes-from-pull-requests` (current),
`changes/archive/2026-07-31-derive-release-notes-from-labels` (the labels and the gate).
