## Why

Two problems with one root, and one of them is about to get worse.

**Deployment facts are scattered and mostly unguarded.** The device-facing domain is written in **nine
places across four toolchains** (Gradle, Deno, Xcode, Astro) — six of them pinned by nothing. The Apple
team id and bundle id are each written twice, in two languages, unguarded, and they compose the App
Attest `rpIdHash` and the AASA `appIDs`: drift there fails every attestation and stops every universal
link matching, silently. `S3_REGION` and `S3_HOST` restate one fact and can disagree. The guard approach
does not scale, because guards are opt-in — they cover what someone remembered, which is how
`BACKGROUND_UPLOAD_URL_BASE` has gone unpinned. Deploying to a different bunny account today means
editing code.

**A green deploy does not prove the script serves.** `POST /code` + `POST /publish` succeed whether or
not the bundle boots. That is exactly how the previous runtime stayed fail-closed for two weeks with CI
green throughout. The `migrate-runtime-to-bunny` decision record declined a boot probe *conditionally*:
config in source made the failure class impossible, so prevention replaced detection — and it wrote the
trigger down, verbatim: *"Anything that reintroduces platform-side required config reintroduces the
silent-corpse failure with nothing watching."* A sibling change is adding `BUNNY_DATABASE_URL` /
`BUNNY_DATABASE_AUTH_TOKEN` to `readConfig`'s fail-closed list. **The condition has fired.**

Making config portable and adding the probe are one change because portability *removes* the prevention
the probe was traded against. Shipping either alone leaves the system worse than it is now.

## What Changes

- **Deployment facts become composed data.** A new `deployments/` tree holds reusable components and
  per-deployment compositions (`prod`, `local`). A deployment declares `extends`; the rule is a shallow
  merge, later wins, no nesting — deliberately too dumb to grow a templating language.
- **One resolver, one invocation, every rendering.** `scripts/resolve-deployment.py` (stdlib-only Python
  — the one runtime present on `ubuntu-latest`, `macos-26` and every dev machine with no setup step)
  resolves a deployment once and emits **all** renderings together, so no two artifacts can be rendered
  from different deployments. Nothing is committed; every consumer reads a generated file.
- **The resolver's key inventory becomes the contract of record** for each value: its renderers, scope,
  required-if condition, default, and rationale — the new home for the load-bearing comments currently
  in `api/src/config.ts`, now visible to all five consumers rather than to Deno alone.
- **Values are `string | { "env": NAME, "scope"?: "build" }`.** Scope defaults to `runtime`, so baking a
  value into an artifact is always the explicit act. **The renderer set is the whole safety guarantee** —
  a key reaches exactly the artifacts its inventory entry names, and no secret/non-secret classification
  is needed anywhere.
- **Storage becomes a sealed union** (`bunny` | `filesystem`), which also makes a `local`-resolved bundle
  structurally unable to boot on bunny.
- **`readConfig`'s hand-written `missing[]` list is derived** from the resolved declaration.
- **BREAKING (internal):** `gradle.properties`' `snapsync.domain` is replaced by
  `snapsync.deployment=prod`. `api/src/config.ts` stops declaring source constants. `Config.xcconfig`
  hard-`#include`s a generated `Deployment.xcconfig` and loses every value that varies.
- **A new ungated `GET`/`HEAD` `/health`** at the root returns `200 {"sha": "…"}` with `NO_CACHE`,
  reading no storage and doing no crypto. `api-deploy.yml` probes it after `POST /publish` until the
  stamp matches, and gains a concurrency group so back-to-back merges cannot make the probe cry wolf.
- **The probe fails loud and does not roll back.** Measured on run 32748912239: the script-scoped deploy
  key returns **401** on `GET /compute/script/{id}/releases` *and* on `GET /compute/script/{id}`. Release
  rollback exists but is account-key-only, and the account key owns every user's photos and our DNS.
- **`api/src/scripts/`** is created for out-of-bundle Deno programs; `sweep.ts` moves there beside the
  new `probe.ts`.

## Capabilities

### New Capabilities
- `deployment-configuration`: how a deployment is declared, composed, resolved and rendered — the
  authored file tree, the `extends` merge rule, the key inventory (renderers · scope · required-if ·
  default · rationale), the `string | {env}` value form with `scope` defaulting to runtime, the sealed
  storage union, the validation rules (unknown keys rejected, runtime-scope keys may have no baked
  renderer), and the rule that renderers may derive while composition may not.

### Modified Capabilities
- `backend-deployment`: non-secret config is no longer *source-owned* but *deployment-resolved* (still
  shipping in the same artifact as the code that reads it, so the anti-drift property is preserved);
  the standing "there is deliberately no boot probe" position reverses; the closed list of root paths
  gains `/health`; a new requirement gates the deploy on a post-publish probe and states plainly what a
  green probe does **not** prove.
- `architecture-guards`: the requirement asserting the backend's domain copy **cannot** be single-sourced
  ("generating it would couple two deliberately independent pipelines") is falsified — all four copies
  become single-sourced, and the guard collapses to a staleness check on one generated artifact. The pin
  inventory changes, which this spec states is deliberately a spec delta.
- `ios-testflight-delivery`: `APS_ENVIRONMENT`/`APNS_ENV` stop being a `Config.xcconfig` default and are
  derived from a single `channel` discriminator (closing a three-way "MUST agree" that today holds only
  by comment); the `MARKETING_VERSION` floor and the `SENTRY_DSN` injection seam move to the generated
  fragment.
- `ios-ci`: `BACKGROUND_UPLOAD_URL_BASE`'s default no longer flows from `Config.xcconfig`; the
  `upload_host` dispatch override is expressed as a deployment selection.
- `ios-appstore-metadata`: the listing files become templates; the domain-derived `privacyPolicyUrl` and
  `marketingUrl` are rendered rather than committed literals.
- `event-limits`: capacity, window maximum and lifetime are resolved deployment values rather than
  "source constants", with the two-distinct-constants requirement and the inject-a-`Config`-in-tests
  posture unchanged.

## Impact

**Code.** `api/src/config.ts` (rewritten around the resolved import; `Config` derived from a generated
`.d.ts` union), `api/src/app.ts` (the `/health` route and one gate entry), `api/src/main.ts`,
`api/src/dev/config.ts` (shrinks — `kind: filesystem` requires no secrets; it keeps supplying only the
runtime-discovered tunnel host), `api/src/scripts/{sweep,probe}.ts`, `api/deno.json` (tasks chain the
resolver; the test task's permission set is untouched, so no test gains `--allow-net`),
`domain/build.gradle.kts`, `iosApp/Configuration/Config.xcconfig`, `site/src/layouts/Layout.astro`,
`site/src/pages/join.astro`, `metadata/**`, `test/architecture/**` (`EventLinkDomainTest` collapses;
`RuntimeIdentityTest`'s `TEAM_ID` read changes source).

**CI.** `api-deploy.yml` (resolver step, probe step, concurrency group), `nightly-cleanup.yml`,
`site-deploy.yml`, `build.yml`, `ios.yml` — each gains one resolver invocation.

**Tests.** Eight `api/test/*` files currently hardcode `snap-sync-dev` / `snapsync.stho.net`; they move
to explicit fixture `Config`s. The resolver gets its own suite (merge order, unknown key, unknown
storage kind, missing component, env markers, renderer-set violations).

**Not changed here, but named.** `apns-push-sender`'s requirements say "the configured key id / team id",
which stays true — only its Purpose sentence calling them source constants needs correcting at sync.
`crash-reporting`'s Purpose says the DSN *"exists only as a CI secret
baked into Release archives"*; with the DSN's renderer set including the api bundle that sentence needs
correcting at sync, and shipping an unread value brushes the `PUBLIC_BASE_URL` rule
(*"keeping a required-but-unused value is the exact shape of the bug being fixed"*) — narrowing the
renderer set to `{xcconfig}` is a one-line inventory edit if a reviewer objects. Backend crash reporting
itself is a deliberate follow-up against `crash-reporting`. `web-site` and `ci-build` are touched but
their requirements are unchanged.

**Verification.** Everything here is Linux-verifiable except the iOS leg, which reaches signing identity,
build numbers and crash reporting — the path that ships to real users. Both of its Mac-only facts are now
measured on `macos-26`: the hard `#include` resolves (run 32797542771 — a signed archive built with
`BUNDLE_ID`/`TEAM_ID` living only in the fragment), and the archive bakes the resolved values (run
32798482386, observed `base=https://snapsync.stho.net/api/v1 bundle=app.snapsync apns=sandbox`). The
second is a standing `ios.yml` assertion rather than a one-off check.
