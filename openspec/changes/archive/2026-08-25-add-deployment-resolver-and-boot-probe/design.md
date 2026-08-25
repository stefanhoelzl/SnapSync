## Context

`migrate-runtime-to-bunny` (2026-07-14) moved seven non-secret values out of the Edge Script environment
and into `api/src/config.ts` as source constants, because **bunny issues no scoped API key**: writing an
Edge Script's variables needs the full-access account key, which also owns the storage zone holding every
user's photos and our DNS zone. CI therefore ships code but cannot ship config. Config in a dashboard
drifted, and the dead script's `BUNNY_STORAGE_ZONE` named a zone that does not exist — *"every artifact in
the repo agreed with it; only the running system knew otherwise."*

That record declined a boot probe **conditionally**, and wrote the trigger down:

> **[No deploy-time boot probe]** → *Accepted.* … with config in source, the failure class that actually
> occurred becomes impossible, so prevention replaces detection. **The consequence is that prevention must
> be airtight** … Anything that reintroduces platform-side required config reintroduces the silent-corpse
> failure with nothing watching.

A sibling change adds `BUNNY_DATABASE_URL` / `BUNNY_DATABASE_AUTH_TOKEN` to `readConfig`'s fail-closed
list. The condition has fired.

Meanwhile the source-constant answer solved drift by making the values un-portable, and it only ever
covered the backend. Surveying the tree found the device-facing domain in **nine production homes across
four toolchains**, two guarded, one generated, **six unpinned** — including
`BACKGROUND_UPLOAD_URL_BASE`, the device-facing upload host itself, whose own comment calls it *"the
SINGLE SOURCE of the deployed device-facing host"* while `gradle.properties` twenty lines away in another
file calls itself *"the ONE source."* `TEAM_ID`/`BUNDLE_ID` are written twice in two languages with
nothing checking they agree, and they compose the App Attest `rpIdHash` and the AASA `appIDs`.
`S3_REGION` and `S3_HOST` restate one fact.

The lesson is not that guards are bad but that **guards are opt-in**: they cover what someone remembered.
Generation is total.

## Goals / Non-Goals

**Goals:**

- Make a deployment a **composition of declared components**, so deploying to a different bunny account is
  adding a file and selecting it — not editing code.
- Preserve the property the source-constant answer was protecting: **config ships in the same artifact as
  the code that reads it**, so drift is impossible rather than detected.
- Give every toolchain that holds a deployment fact — Deno, Gradle, Xcode, Astro, the App Store metadata
  — **one upstream source**, replacing checks with construction wherever possible.
- Restore **detection** for the failure class portability reopens: a deploy that green-lights a script
  which cannot boot.
- Keep the bunny **account key** out of CI. Unchanged and non-negotiable.

**Non-Goals:**

- **Rollback.** Measured, not assumed (below). Fail-loud only.
- **Fault (b) — configuration that is present but wrong.** The probe witnesses boot, not correctness. See
  Risks; this is stated as a requirement so the limit is contract rather than commentary.
- **Backend crash reporting.** The DSN becomes *available* in the api bundle; nothing reads it. A separate
  change against `crash-reporting` owns the real questions (does the Deno SDK run under Edge Scripting,
  does an event flush before a top-level throw, what does the 50-subrequest cap do).
- **A standing uptime check.** The probe is a deploy gate. A cron is a later, cheap addition since the
  route exists.
- **Modelling a third axis in a deployment.** `channel` (dev vs release) is a *build* input, orthogonal to
  deployment — the dev-IPA flow is `deployment=prod` + `channel=dev`.
- **Moving release policy into the resolver.** `MARKETING_VERSION`'s computation stays in `ios.yml`; only
  the resulting value passes through.

## Decisions

### 1. Deployment-resolved, not source-owned — and why that is not a reversal

The archived rule is often read as "config belongs in source." What it actually applied was **"is this a
secret, or a public fact?"**, and the property it bought was *config and code ship as one artifact*.
Build-time resolution keeps that property exactly, and adds portability:

```
  bunny env (the 2026-07 corpse)   source constant (today)   resolved at build (this change)
  ────────────────────────────     ───────────────────────   ──────────────────────────────
  portable                yes              NO                          yes
  ships with the code      NO             yes                          yes   ← preserved
  readable / diffable      NO             yes                          yes
  needs the ACCOUNT key   yes              no                           no   ← preserved
```

The record rejected **"env overrides source"** (`{...DEFAULTS, ...fromEnv(env)}`) because it reopens drift
in the other direction — a stale platform variable silently overriding git. This is neither: an authored
file states exactly one source per value, and nothing shadows anything.

**Alternative rejected — GitHub repository Variables as the store.** They would put values outside the
repo entirely. Rejected because Gradle and Xcode builds must work locally, so a committed default would be
needed anyway, leaving two mechanisms for one concept. The values are public facts (the record priced
committing them at *"zero new exposure"*), so portability is served by the **structure being swappable**,
not by the values being absent.

### 2. One resolver, in Python, because no CI job has both Deno and Gradle

Measured:

```
                     Deno   Java/Gradle   Python3
  api-deploy.yml       ✓         ✗           ✓
  build.yml            ✗         ✓           ✓     ← setup-java only
  ios.yml / ssh-mac    ✗         ✓           ✓     ← ios.yml:138 already runs
                                                     python3 on macos-26, no setup step
```

A Deno resolver would force `setup-deno` into the Gradle workflows and make `./gradlew build` — which
CLAUDE.md calls *"the canonical check"* — require Deno on every contributor's machine. A Gradle resolver
would make `api/`'s checks need a JVM, which `api-deploy.yml` deliberately does not install. Stdlib-only
Python is the only runtime already present on both sides, and the repo already depends on it
(`scripts/appicon.py`, `.github/scripts/*.py`). Stdlib-only sidesteps the PEP-668 caveat `ios.yml:357`
records, which concerns `pip install`, not imports.

**Alternative rejected — native composition per toolchain** (TS object spread, Gradle merge, no resolver).
It removes the generator but states each deployment's component list once in TypeScript and again in
Kotlin. Duplicating a *resolver* is tolerable; duplicating a *deployment definition* is not.

### 3. Resolve once, emit every rendering

The CLI is `resolve-deployment.py <deployment>` with no `--emit`. Five renderings are produced together at
fixed paths: `api/src/deployment.ts`, `build/deployment.properties`,
`iosApp/Configuration/Deployment.xcconfig`, `build/metadata/…`, `site/src/deployment.json`.

Per-rendering invocation would allow `ios.yml` to render the xcconfig from `prod` while something else
rendered the json from `local` — **artifacts that disagree**, precisely the bug class this change exists to
remove. Emit-all makes that unrepresentable, and gives *"did you run the resolver?"* one answer for the
whole repo. The cost — a consumer writes files it does not read, so a Gradle build overwrites a
`deployment.ts` the rig resolved as `local` — is benign: the rig imports its config at startup, and the
next `deno task check` wants `prod` anyway.

### 4. Values are `string | { "env": NAME, "scope"?: "build" }`, and the renderer set is the guarantee

A separate `secrets` block was designed and dropped. The danger it guarded against was misidentified:
`{env: NAME}` is not `{...DEFAULTS, ...fromEnv}` — it declares one source with nothing to shadow. And both
things a tier would protect (no secret literal in a file; no public fact from env) require knowing *which
keys are which* either way, and the resolver already needs a key inventory to reject unknown keys.

`scope` defaults to **`runtime`**, deliberately asymmetric: baking a value into an artifact is the
explicit act, and a value that is not baked cannot leak into one.

The safety rule rests on the **renderer set**, not on a secret/non-secret classification — which is
necessary anyway (`zone` has no business in an xcconfig, `APP_NAME` none in the api bundle) and turned out
to be sufficient. The Sentry DSN forced this: it is a *write-only ingestion key*, already extractable from
any shipped IPA, and deliberately baked into Release archives — so "build scope never carries a secret" was
already false here. With renderer sets, the DSN simply reaches the artifacts its entry names.

```
  key         renderers                              scope    default
  accessKey   { json }                               runtime
  sentryDsn   { json, xcconfig }                     build    ""
  sha         { json }                               build    "dev"
  domain      { json, xcconfig, properties, metadata, site }
  zone        { json }                               runtime
```

Two rules the inventory validates rather than asserts: a **runtime**-scope key may have no baked renderer
(an xcconfig, a `.properties`, the metadata and a static site have no runtime to defer to); a **build**-scope
key must name only renderers it belongs in.

Both scopes are read at exactly one moment. The resolver reads process env **only** for build-scope keys
and copies runtime markers through verbatim, so the bundle carries the variable *name* and never the value
— which is what `config.ts` already does with `ENV_ACCESS_KEY` today. A resolver that resolved runtime
secrets would bake them into `dist/main.js` and require CI to hold them, which it must not.

### 5. Storage is a sealed union; `local` is a different kind, not a variant

`storage: { kind: "bunny", … } | { kind: "filesystem", … }`, resolved by a pure, tested resolver — the same
idiom as `model/`'s sealed `CompositionMode`. It deletes the fiction in `dev/config.ts`, which spreads
`storageConfig()` and then overrides `s3Host` to point at the rig, pretending the shim is a bunny zone.

Required secrets follow the kind: `bunny` requires the storage access key, `filesystem` requires none — so
`local` legitimately declares no secrets, as a consequence rather than an exemption, and no file ever
contains a secret value.

It also earns something unplanned: **a `local`-resolved bundle cannot boot on bunny** (there is no
filesystem in an Edge Script). "Right commit, wrong deployment" therefore fails closed and the probe
catches it as a dead script — which is why `/health` carries no deployment field.

### 6. Composition is dumb; renderers may derive

`extends` is a shallow merge, later wins, **no nesting** — components may not themselves extend. Anything
it cannot express is a signal to restructure the data, not the resolver. Deep merge was rejected: it is the
point at which nobody can predict a resolved value by reading one file, which for config deciding which
bucket holds users' photos is the wrong trade.

Derivation lives in **renderers**, deterministic and single-sited. The xcconfig renderer derives
`APS_ENVIRONMENT` / `APNS_ENV` / `SENTRY_ENVIRONMENT` from one `channel` value, so three settings that
today *"MUST agree"* by comment can no longer disagree. It also forces `SENTRY_DSN` empty unless
`channel=release`, making *"absence is the off-switch"* structural rather than procedural. `attestAppId`
stays derived in `config.ts` — only Deno needs it, so the resolver never learns about it.

### 7. Nothing is committed; Xcode gets a hard `#include`

Every rendering is gitignored and regenerated. Manual Xcode use is not a workflow here (iOS builds go
through `ssh-mac`), so `ios.yml` runs the resolver before *"Archive signed device app"* — it already has
`run:` steps at :110 and :131 to sit beside. `Config.xcconfig` uses a **non-optional** `#include`, so a
missing fragment is a build error rather than a stale value.

This also removed a mechanism: with nothing committed, a workflow that skips the resolver hits a missing
module — loud, at every consumer. The "placeholder that cannot work" sentinel we were designing around
became unnecessary.

### 8. The type is generated from the inventory, in the SAME module as the value

`typeof resolved` would reflect whichever deployment happened to be on disk, so under `prod` the type is
the bunny shape and the sealed union means nothing to the compiler — `dev/serve.ts`, which must work with
the filesystem shape, would be type-checked against the wrong one. The resolver emits the union from the
inventory instead: a real discriminated union that forces narrowing on `kind`, carrying the inventory's
rationale as JSDoc. No hand-written `Config` to duplicate, and CI still type-checks `prod` alone.

**Implementation correction: one `.ts`, not `.json` + `.d.ts`.** The design first paired a JSON rendering
with a declaration file. That does not work — a `.d.ts` beside a `.json` does not type a JSON import,
because Deno infers the type from the JSON itself, so applying the union would have needed an unchecked
`as` assertion. Emitting a typed `const` in one module makes the **compiler check the emitted data against
the emitted type**, and it earned that immediately: it caught `sha` leaking into `ResolvedDeployment` (it
belongs beside `config`, not inside it) and then caught the union needing to span the whole deployment
rather than just `storage`, since `apnsPrivateKey`/`attestTokenKey` are `kind==bunny` keys. Narrowing uses
exported type guards, because a nested discriminant does not narrow the outer type.

`buildSha` goes in `Deps` beside `now` — not in `Config`, which means configuration, and not a bare import,
which `app.test.ts` could not override.

### 9. The tunnel host is a timing impossibility, not a policy question

`dev:tunnel`'s hostname is minted by cloudflared **inside the running process** (`serve.ts:55`), after every
static import has been evaluated, and is *"RANDOM PER SESSION"* (`tunnel.ts:10`). The resolver runs before
`deno run`. No schema — `{env}`, a `tunnel` deployment, anything — can source a value that does not exist
yet. `local.json` carries `127.0.0.1:8080` and `devConfig(publicHost)` survives to override the one
genuinely dynamic value. It *shrinks*: under `kind: filesystem` it no longer fakes four secrets.

### 10. The probe proves identity **and** boot, and touches nothing

A bare `200` cannot distinguish the new deploy from the old corpse still being served — the exact failure
guarded against — so the response carries the commit stamp. `readConfig` runs at module top level in
`main.ts`, *outside* the Hono app: a config failure means the **script** throws, not that a handler 500s.
So the handler is total; it returns `200` or it did not run, and a `503` branch would be dead code.

Discrimination therefore lives in the probe, over (status × body), and only causes that **time can fix** are
retried:

```
  conn refused / 5xx   → retry (script swapping)          deadline 120s, poll 5s
  404                  → retry (old bundle still live)
  200, valid other sha → retry (propagating)
  200, sha == "dev"    → FAIL NOW  (CI supplied no sha — our bug)
  200, unparseable     → FAIL NOW  (something else is answering)
```

Retrying a terminal cause for two minutes turns a clear bug into a timeout. The table is also robust to
bunny behaviour nobody has measured — an HTML error page with a 200, serve-stale-on-error, or the previous
deployment surviving all land in a red cell, the two that should be fast being fast.

`/health` is ungated, exact-path, GET/HEAD, `NO_CACHE` — reusing the existing constant because bunny
documents `no-cache`, not `no-store`, as the origin directive that suppresses its cache. It is probed
through the pull zone at the device-facing origin, because `backend-deployment` already requires endpoint
behaviour to hold *as observed through the pull zone*. Probing the script's own bunny hostname would go
green while DNS, the cert or the pull zone were broken, and would bake a provider vanity hostname into CI —
and is impossible anyway (below).

Exposure is accepted and argued at the gate: the repo is public so the SHA leaks nothing, and the handler
does no I/O, making it the cheapest route in the backend — cheaper than `GET /events/<id>/files`, which is
already ungated, uncacheable, and does a full storage fan-out.

### 11. Fail loud, no rollback — measured

Bunny does support release rollback (`POST /compute/script/{id}/publish` with a release uuid). It is not
available to us. Run **32748912239**, with the script-scoped deploy key:

```
  GET https://api.bunny.net/compute/script/<id>/releases  →  401
  GET https://api.bunny.net/compute/script/<id>           →  401
```

`DeploymentKey` reaches `/code` and `/publish` and nothing else. Rollback needs the account key, which the
deploy workflow is forbidden to hold. The second 401 independently settles §10: CI **cannot discover** the
script's own hostname either. This is a forcing proof with an expiry trigger: re-measure if bunny issues
compute-scoped subuser keys (the by-uuid endpoint's scope list mentions `SubuserAPICompute`, which if real
would also soften the "no scoped API key" premise the whole config argument rests on — worth its own look,
deliberately not chased here).

### 12. The stamp is a generated module, because Deno offers nothing else

`deno bundle` (2.9.3) has **no `--define`** — verified against `--help`; it is an open request
(`denoland/deno#35347`), as are compile-time constants generally (`#9152`). A statically imported JSON
module is not a workaround but the only mechanism, and it works: `deno bundle` inlines
`import x from "./f.json" with { type: "json" }` as `var x = {…}` with full type inference. Expiry trigger:
revisit if `#35347` lands.

`build-info.json` as a separate committed placeholder was designed and then **deleted** by folding the sha
into the resolver with a `"dev"` default — which removed the committed placeholder, its pinning test, and a
second generated-file mechanism.

### 13. Probe placement

`api/src/scripts/probe.ts`, following `sweep.ts`'s precedent: a Deno program a workflow runs, outside the
bundle (`deno bundle` roots at `main.ts`), with `fetch` injected so the decision table and retry policy are
unit-tested against a stub — with **no `--allow-net`**, preserving the guarantee that no test can reach a
real host. A bash retry loop in YAML would put the decision table somewhere untested, and a probe that is
itself wrong is worse than no probe because it manufactures confidence.

`api/src/scripts/` names a distinction that already exists and is half-observed (in-bundle vs not);
`sweep.ts` moves there rather than leaving two homes for one kind of thing.

## Risks / Trade-offs

- **[The probe does not witness configuration correctness]** → *Accepted, and stated as a requirement.* A
  present-but-wrong value boots green. This is fault (b) from the original outage —
  `BUNNY_STORAGE_ZONE=snap-sync` booted fine and named a zone that does not exist — and it is the half the
  probe does **not** restore. Coverage is exactly *the set of faults boot turns into a throw*, so the
  mitigation is structural: put the value in the deployment (readable, diffable, reviewed, blamed) rather
  than in an unreadable platform secret. Bunny's Edge Script secrets *"cannot be viewed once set"*, which
  makes anything living there permanently un-diffable.
- **[This reaches the iOS release path]** → *Accepted, unmitigated.* Signing identity, build numbers and
  crash reporting all ship to real users, and that leg is the only part not verifiable on Linux.
- **[Two Mac-only facts]** → *MEASURED, and now permanently gated.* Run 32797542771 built a signed archive
  with `BUNDLE_ID`/`TEAM_ID` living only in the generated fragment, so the hard `#include` resolves —
  provisioning could not have succeeded otherwise, and no `__UNRESOLVED__` sentinel is needed. Run
  32798482386 then turned that inference into an observation *and* a standing assertion: `ios.yml` reads the
  archive's `Info.plist` and checks the baked values against the resolved deployment
  (`base=https://snapsync.stho.net/api/v1 bundle=app.snapsync apns=sandbox`, channel `dev`). The assertion
  exists because signing only *probably* catches a broken fragment; the upload host and the APNs
  environment it would NOT catch are the dangerous pair — a build pointed at the wrong backend, or a
  TestFlight build left on sandbox APNs, looks entirely normal until photos silently stop arriving.
- **[`deno lsp` reports a missing module on a fresh clone]** → *Accepted.* Gitignored renderings mean editor
  errors until a task has run once.
- **[`python3` enters `api/`'s check path]** → *Accepted.* It softens the standing property that the
  backend's checks need nothing beyond its own runtime, in exchange for one resolver instead of two.
- **[CI type-checks `prod` only]** → *Accepted.* `local` can be broken by a change and nobody learns until
  someone runs the rig. Mitigated partly by the generated union forcing narrowing on `kind`.
- **[An unread `sentryDsn` ships in the api bundle]** → *Named, not mitigated.* It brushes the
  `PUBLIC_BASE_URL` rule (*"keeping a required-but-unused value is the exact shape of the bug being
  fixed"*), weakly — it is optional and defaults to `""`. Narrowing the renderer set to `{xcconfig}` is a
  one-line inventory edit.
- **[Eight test files must move to fixtures]** → *Accepted.* They currently pin the real zone and domain,
  which is testing configuration rather than behaviour and makes every deployment change a test change.
- **[A red probe means a broken script is already live]** → *Accepted; see §11.* Detection is the
  deliverable. The spec already mandates setting a new secret **before** merging the code that reads it, so
  this fires only when that ordering is violated — and the post-bundle
  `grep -q "$GITHUB_SHA" dist/main.js` guard moves the "CI never supplied a sha" case earlier still, before
  anything deploys.

## Migration Plan

```
 1  resolver + authored tree + inventory + its own test suite
 2  api/ consumes it — config.ts, generated .d.ts, fixtures for the eight tests
 3  api/src/scripts/ — sweep.ts moves; probe.ts lands
 4  /health route + gate entry + app.test.ts / attest.test.ts pins (both directions)
 5  api-deploy.yml — resolver step, bundle, deploy, probe, concurrency group
 6  nightly-cleanup.yml + site-deploy.yml + the site's two ogUrl literals
 7  Gradle — snapsync.deployment, LINK_ORIGIN from the rendering
 8  Xcode — Deployment.xcconfig fragment, Config.xcconfig hard #include, ios.yml step
 9  metadata templates
10  guards — EventLinkDomainTest collapses; RuntimeIdentityTest's TEAM_ID source moves
```

Steps 1–6 are Linux-verifiable end to end. Steps 7–10 need a Mac run before merge.

**Rollback:** none at the platform level (§11). The repo-level rollback is reverting the PR, which restores
source constants — safe, because nothing external holds state this change creates.

## Open Questions

- ~~The two Mac-only measurements.~~ Settled on `macos-26` (runs 32797542771 and 32798482386) and converted
  into a standing CI assertion; see Risks.
- Does bunny now issue **compute-scoped subuser API keys**? If so, the "no scoped API key" premise under
  the whole config-in-source argument has softened, and rollback becomes reachable without the account key.
  Deliberately deferred.
- The sibling `record-uploads-in-database` change was designing against "the probe lands first"; it now
  lands inside a much larger unit, and `BUNNY_DATABASE_URL` should become a deployment value rather than an
  unreadable bunny secret — closing its fail-wrong blind spot before it opens.
