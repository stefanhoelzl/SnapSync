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
| properties | `build/deployment.properties` | Gradle; also the deploy workflow reads `domain=` from it |
| xcconfig | `iosApp/Configuration/Deployment.xcconfig` | Xcode build settings, entitlements, `Info.plist` substitutions. **Literals only** |
| plist | `iosApp/Configuration/Deployment.plist` | copied into **both** the app and the extension bundle |
| metadata | `build/metadata/**` | the App Store listing (domain-derived URLs rendered from templates) |
| site | `site/src/deployment.json` | the Astro site |

`scripts/resolve_deployment_test.py` is the `resolver-test` required check.

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
| `SENTRY_DSN` | GH secret, `ios.yml` | build-scope; baked only into distributed iOS builds (§5) |

- The backend validates every declared runtime secret **once at startup**. A missing or blank one throws,
  and the script does not boot. Then the post-publish probe (§2) fails the deploy. The required set is
  derived from the deployment's declaration, not from a second list in code.
- ⚠️ **Order matters when you add a secret**: set it in the Edge Script environment **before** merging the
  code that reads it. If you merge first, the next deploy produces a bundle that cannot boot. Removing a
  secret is safe in either order.
- There is **no admin or bypass credential** anywhere in the backend.
- APNs: `apnsKeyId` / `teamId` / `bundleId` live in `deployments/components/apple.json`. Update that
  file if the key is rotated. The APNs topic is derived from the bundle id. The key is a one-time
  provisioning for team `E9Z8BADH58`. Decision record: `changes/archive/2026-07-05-push-notification-infra`.

### iOS: the baked values

- `Deployment.plist` carries what the app and extension read at runtime: the device-facing upload base
  (`uploadBase`, with exactly **one** `/api/vN` prefix), the APNs environment, the crash-reporting
  environment and DSN, and the App Store URL shown on the update-required screen. It is a property list
  because it escapes `//`. The xcconfig grammar treats `//` as the start of a comment, which once
  truncated the DSN to `https:` and shipped four silent TestFlight builds (644–673).
- `BackgroundUploadURLBase` is **also** authored in each bundle's own `Info.plist`, because iOS reads it
  from there to validate the background-upload registration. The resolver's `DEVICE_API_PREFIX` pins
  both. `ios.yml` checks after archiving that the two are **exactly equal** in both bundles (§4). Moving
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

- **`api.yml` → `api-test`** runs on every push to every branch with **no path filter**, because a
  required check that is never posted blocks merges forever. It resolves `prod`, then runs `deno fmt
  --check`, `deno lint` (with the local plugins declared in `deno.json`, including the complexity rule),
  `deno task check` (type-checks `src/`, `src/dev/`, `src/scripts/`, `src/lint/`), `deno task test`,
  `deno task schema:check`, and a bundle that must carry the commit sha.
  `deno task test` deliberately has **no `--allow-net`**, so no test can reach the real zone.
  Decision record: `changes/archive/2026-08-27-make-api-tests-required`.
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
- **Never edit an applied migration.** The checksum refuses it.
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

## 4. iOS CI

`.github/workflows/ios.yml`, on `macos-26` with the runner's GM Xcode (never a beta). Triggers: push to
any branch (tags excluded, and **no path filter**, so docs-only merges build and deliver too), plus
`workflow_dispatch` on any ref. Newer pushes cancel older runs on the same ref. `~/.gradle` and `~/.konan`
are cached. Signing material is **never** cached.

Three parallel **merge gates**. None of them may `needs:` another, because a red gate would then skip a
required check and block merges. `ios-deliver` (§5) needs **all three**; a job that joins the gates joins
its `needs:` in the same change, or delivery stops consulting it (`ios-contracts` once shipped as a gate
without it, so red contracts and journeys still reached TestFlight):

| Job | What it does |
|---|---|
| `ios-build` | Signed `xcodebuild` archive of the device (`iosArm64`) app: the app's only compile. **Release** on a delivering run (a push to `main`, or any dispatch), **Debug** otherwise (about 2.4 min faster; a Release-only failure shows up on `main`). A pure gate: it exports no IPA and uploads nothing to Apple. On a delivering run it verifies the baked deployment (below), then tars the archive (artifacts lose symlinks and exec bits) and uploads it for `ios-deliver` (1-day retention). |
| `ios-test` | `./gradlew iosSimulatorArm64Test`: every test source set the simulator target compiles (`commonTest` plus the iOS adapters' `iosTest`). |
| `ios-contracts` | Builds the rig app (`-Psnapsync.rig=true`, `local` deployment) and ad-hoc signs it (`scripts/sim-sign`). **Nothing overlaps the build**: only after it, with the Gradle and Kotlin daemons stopped, does it boot **one** fresh simulator (a booting simulator slows the build several-fold, and a second fresh one's first-boot work tripled the job). Right after boot it stops the simulator's `apsd` (its reconnect loop to Apple's push sandbox logged a million lines in six minutes and cost ~170 s of CPU through the log daemon; nothing tested needs it) and opens Photos, so the library's first-use preparation starts while the app installs (the first write then took 1–46 s instead of 1:23–4:43). Spotlight is switched off on the runner. It installs the app, grants photo access (pinned `applesimutils`), starts `scripts/transfer-fixture.py` and a local `api/` on a fresh filesystem store (warmed with one request), and launches the app. A timestamped **photo-library readiness** stage then makes the first library write (an asset dated outside every contract's window), because a fresh simulator's library takes minutes to accept one and that wait belongs to the platform, not to the first contract. It then checks the `GET /device` vocabulary, runs every registered port contract over the rig, and runs the all-real journeys on a bare JVM, with no Gradle alive next to the simulator (`docs/testing.md` section 7). Fails on any `Failed`/`NotWithin` clause, a refused run, an empty registry, a failed journey (printing its assertion message), or a fixture, backend or app that never answers (it captures a screenshot and the app log in that case). Evidence kept: the fixture's request log, the backend's output with a per-request log, host memory/CPU samples, host and simulator crash reports. Decision record: `changes/archive/2026-09-25-one-simulator-journeys`. |

The full required-check set lives only in the branch ruleset (read it with
`gh api repos/stefanhoelzl/SnapSync/rulesets`). At the time of writing it is: `api-test`,
`appstore-metadata-validate`, `build`, `check-label`, `diagrams`, `ios-build`, `ios-contracts`,
`ios-test`, `resolver-test`, `site-build`, `spec-validate`. `/ship` owns the ruleset, and a context
becomes required by having actually run on a PR. `ios-deliver`, `deploy.yml`, `nightly-cleanup`,
`screenshots` and `ios-appstore-promote` must **never** be required.

**Archive verification** (delivering runs): read the bundle id, `uploadBase`, APNs environment,
crash-reporting environment and DSN back out of **both** the app and the nested `.appex`, and compare
each to the resolver's output. The DSN is compared without being printed. Also require each bundle's
`Info.plist` `BackgroundUploadURLBase` to be **exactly equal** to its rendered `uploadBase`. It must be
equality, never a prefix test, because an empty string passes a prefix test. A resource that reached only
one bundle, or a value truncated by a grammar, fails the run here instead of producing a silent build.

---

## 5. TestFlight delivery

**Every merge to `main` uploads a signed build to internal TestFlight**, automatically, docs-only merges
included. It reaches **no external tester**: the builds go to the internal `development` group only.
Real users get builds only through the App Store release (§6).

- **`ios-deliver`** (`needs: [ios-build, ios-test, ios-contracts]`, every merge gate; runs on delivering runs only): downloads and unpacks
  the archive, re-signs and exports an `app-store-connect` IPA **without recompiling**, and uploads it
  with one `app-store-connect publish --whats-new …` call (codemagic-cli-tools). That call waits for the
  build to become visible. There is no `--testflight` flag and no beta-group change. Any red gate — the
  build, the test suite, or the in-app contracts and journeys — means nothing is uploaded. Delivery
  therefore starts only when the slowest gate (`ios-contracts`) finishes, and a flaky `ios-contracts`
  skips that commit's upload until it is re-run. Decision record:
  `changes/archive/2026-09-25-deliver-needs-ios-contracts`. The job is **not** required and does **not** use `continue-on-error`: a
  failed delivery shows red and blocks nothing.
- **"What to Test" note**: `<PR title> (#<num>, <short sha>)`, resolved through
  `GET repos/{repo}/commits/{sha}/pulls`. It falls back to `<head subject> (<short sha>)`. On a dispatch
  it uses the operator's note, or `<ref> (<short sha>)`. Arbitrary text reaches the shell only through
  environment variables.
- **Branch dispatch = a real TestFlight build of a branch**: `gh workflow run ios.yml --ref <branch>
  [-f what_to_test="…"]`. It follows every rule of a `main` delivery (every merge gate, Release, production APNs, DSN,
  dSYMs, internal group only). Use it for anything only a distributed build can do, for example the hidden
  diagnostic dump.
- **Build numbers**: `CFBundleVersion` = `github.run_number` (monotonic across refs).
  `MARKETING_VERSION` = `max(floor, latest vX.Y tag with minor + 1)`, compared as integer tuples
  (`v0.9 → 0.10`). The floor is committed in `Config.xcconfig`, so a major jump (`→ 1.0`) is a PR that
  raises the floor.
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
  rendered **only** into `Deployment.plist` and **only** when the channel is `release`. A stray export on
  a dev build still produces no DSN. No DSN means the SDK never starts **and** the hidden bug-report
  dialog never opens. ⚠️ Injecting `SENTRY_DSN` on a dev `xcodebuild` line does nothing, because a build
  setting cannot substitute into a bundled resource. Dispatch the branch instead.
- The Bugsink instance ingests no dSYMs. `ios-deliver` publishes each delivered build's dSYMs as
  artifact **`dsyms-<run_number>`** (= `CFBundleVersion`, 90 days, the platform maximum). The `/bugsink`
  skill symbolicates against it on Linux and fails loudly once it has expired. Copy dSYMs somewhere
  permanent at promote time for any version that must outlive 90 days.

Decision records: `changes/archive/2026-07-14-gate-testflight-on-tests`,
`changes/archive/2026-07-19-remove-alpha-testflight-promotion`,
`changes/archive/2026-07-21-restore-testflight-build-note`,
`changes/archive/2026-07-21-debug-branch-gate-archive`,
`changes/archive/2026-07-21-add-crash-reporting`.

---

## 6. App Store release and metadata

### Promote a build you already tested

```
gh workflow run ios-appstore-promote.yml -f build_number=512              # attach, no submit
gh workflow run ios-appstore-promote.yml -f build_number=512 -f submit=true
```

`build_number` is the build's `CFBundleVersion`, which equals the `ios.yml` `run_number` that produced it.
There is no `version` input: the store version is **derived** from the build's own marketing version.
The workflow is a single `ubuntu` job: no Xcode, no signing, only the existing Admin ASC key. Order of
steps:

1. Resolve build N (wait for `VALID`) and derive `X.Y`. It must match `^\d+\.\d+$`.
2. **Refuse if tag `vX.Y` already exists.** This happens before any App Store Connect change.
3. Resolve the origin commit: `build_number` → the `ios.yml` run on `main` with that `run_number` →
   `head_sha`. If it cannot be resolved, fail; never guess.
4. Derive the release notes (§7). If they exceed 4000 characters, fail here, having changed nothing.
5. Find or create the `X.Y` version record and attach the build (idempotent). A **newly created** record
   gets the committed copyright (year of first publication). An existing record's copyright is left alone.
6. Upload the listing screenshots, composed from `screenshots/*.png` + `metadata/screenshots/en-US.json`
   (ImageMagick). Only an **editable** version is written to, and the set is **replaced**.
7. Apply the `en-US` `whatsNew` and the App Review details (notes from `metadata/review/notes.md`;
   contact details from secrets because the repo is public; "no demo account"). These run on every
   release, so a promote without submit still leaves the version ready to submit.
8. If `submit=true`: `asc review doctor` must report no blocking check, otherwise the run fails. Then
   submit.
9. **Last**: create tag `vX.Y` on the origin commit, with the build number in the message.

Operator rules:

- ⚠️ **Never push a `vX.Y` tag by hand.** Tags trigger nothing (`build.yml`, `ios.yml` and `deploy.yml`
  ignore tags), and a tag that already exists makes that version **permanently unreleasable**, because
  step 2 refuses it.
- ⚠️ **A promote is single-shot per version.** After it succeeds, the tag blocks a re-run. Correcting an
  already-promoted version's screenshots or release notes is a **manual console upload**. A **failed**
  run leaves no tag and can simply be dispatched again.
- ⚠️ `asc review doctor` is not the whole preflight. It once passed a version that `asc review submit`
  then refused (missing `en-US: whatsNew`, run 30632785849). A green gate does not guarantee the submit
  will pass.
- Provenance is not re-checked at release. It is guaranteed at upload, because only green `main` commits
  (and deliberate dispatches) reach App Store Connect. ⚠️ Step 3 resolves only runs on `main`, so a
  branch-dispatched build has no resolvable origin and fails at step 3.
- Concurrency is per `build_number` with `cancel-in-progress: true`. A cancelled run may leave an
  **unpublished** editable version's screenshot set partial. The next run restores it.

### Listing metadata

- `metadata/version/current/en-US.json` and `metadata/app-info/en-US.json` hold the text: description, keywords,
  promotional text, support and marketing URLs, name, subtitle and privacy-policy URL. The URLs are
  **templates** with a domain placeholder, rendered into `build/metadata/` by the resolver. Everything
  else is edited directly.
- **`appstore-metadata-validate`** (`appstore.yml`, every push, **required**, no credentials): character
  limits (description ≤ 4000, keywords ≤ 100, promotional text ≤ 170, whatsNew ≤ 4000, subtitle ≤ 30),
  URL syntax, and **unknown keys**. The tool's schema is closed. That is why the review notes and the
  screenshot headlines live in their own files: a new key in the canonical files would fail this required
  check and block merges.
- **`appstore-metadata-apply`** (`deploy.yml`, `main` only, not required): resolves the **editable**
  version (`PREPARE_FOR_SUBMISSION` / `DEVELOPER_REJECTED`) at run time, never through a stored id, and
  overwrites the fields that are present. The committed file wins over console edits. It never touches a
  version in review, never creates one, and does nothing (green) when no version is editable. An
  **omitted** field is never deleted. That is what keeps it from clearing the release's `whatsNew`, which
  the committed files deliberately do not carry.
- The `asc` CLI is pinned and SHA-256 verified (`.github/scripts/asc_fetch.sh`). No fastlane, no Ruby.
  App previews (video) are manual.

### Screenshots

Six committed raws in `screenshots/` (3 forge states × light/dark) feed **both** the App Store listing
(uploaded at promote time only) and the `site/` landing page (on merge). A merge that changes them
changes **no** listing. Each capture is the real status screen rendered by the forge binary over forged
inputs, with no backend, no attestation and no photo library, so no real member's content can reach the
listing.

```
gh workflow run screenshots.yml --ref <branch>          # ~11-19 min
RID=$(gh run list -w screenshots.yml -L1 --json databaseId -q '.[0].databaseId')
gh run download "$RID" -n screenshots-raw -D screenshots
# LOOK AT THEM, then: git add screenshots/ && git commit
```

⚠️ **Looking at them is the only check there is.** A system notification ("Ready for Apple
Intelligence") landed in 1 of 2 runs. Re-dispatch if one does. Only `create` should differ on an
unchanged UI, and only in the 90×32 px wall clock. A headline or size change needs no re-capture.

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
- An all-`internal` range yields the committed fallback: *"Under-the-hood improvements and fixes."*
- The changelog goes to `--changelog <file>`. A reconciliation report goes to stdout for the run summary:
  counts, the excluded `internal` roster, and uncategorized PRs or unassociated commits listed
  separately. The changelog has **one** consumer, the promote workflow. There is no GitHub Release and no
  committed CHANGELOG.

Preview any range before dispatching a promote:

```
GH_TOKEN=$(gh auth token) python3 .github/scripts/release_notes.py \
  --repo stefanhoelzl/SnapSync --target <origin-sha> --previous vX.Y
```

Decision records: `changes/archive/2026-07-31-derive-release-notes-from-pull-requests` (current),
`changes/archive/2026-07-31-derive-release-notes-from-labels` (the labels and the gate).
