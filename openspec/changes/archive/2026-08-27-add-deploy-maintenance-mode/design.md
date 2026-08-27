## Context

`api-deploy.yml` orders the migration **before** the publish. That is deliberate and stays: a failed
migration fails the run with the previous bundle still live, which is the recoverable state, and the
reverse order puts new code on an old store with nothing watching. Two properties of the current code are
already stronger than the problem statement assumes, and neither needs work:

- **The migration is atomic per migration.** `migrate()` applies each entry's statements *plus* its version
  record through one `db.batch` (libSQL `"write"` mode). A migration is applied and recorded, or neither.
- **The failure path already keeps the old bundle.** The migrate step is not `continue-on-error` and is
  ordered before the deploy step.

What is left uncovered is the interval between the migration landing and the newly published bundle
actually serving. In that interval the **previous** bundle — written against the previous schema — answers
requests against the migrated store.

Three platform facts bound every option:

1. **CI cannot write Edge Script environment variables.** Bunny issues no scoped API key; writing them
   needs the full-access account key, which also owns the storage zone holding every user's photos and our
   DNS zone. `backend-deployment` states this and forbids admitting that key to CI. So a maintenance flag
   cannot be an environment variable set by CI, and any "flip a switch" mechanism must be reachable with
   the script-scoped deploy key alone.
2. **Edge Scripting runs V8 isolates across ~119 PoPs**, request-dispatched by a per-PoP reverse proxy. In
   the deployed topology there is no process-wide memory a single request can write and every other request
   can read.
3. **`probe.ts` observes one hostname**, which resolves to one PoP. Nothing available to CI observes the
   other PoPs.

## Goals / Non-Goals

**Goals:**

- No request served by a bundle whose schema assumptions do not match the store it reaches — to the limit
  of what a single-endpoint probe can witness, stated honestly rather than implied.
- The common deploy — no pending migrations — costs nothing: same single publish, same duration, no
  window.
- A rollback path that exists at all. Today a failed run leaves whatever is live, live.
- Every failure mode either self-recovers or fails loudly naming the commit. None ends in a silent outage.

**Non-Goals:**

- **Cross-migration atomicity.** `migrate()` stays atomic per migration, not per run. Shipping v4+v5 where
  v4 lands and v5 fails leaves the store at v4, which no bundle is written against. This is roll-forward
  only, fixed by hand — the same posture the workflow already takes toward a broken published script. An
  interactive transaction spanning the whole run would fix it, but resting the deploy path on SQLite's
  transactional-DDL behaviour *as bunny Database implements it* is a platform-capability claim, and this
  change does not carry the measurement that would settle it.
- **Backward-compatible (expand/contract) migrations.** A discipline that would remove the window entirely
  rather than gate it. Rejected for now because it is a rule with no mechanical gate — it covers what
  someone remembered — and because v2 and v3 in the current list are exactly the destructive shapes it
  forbids. Nothing here prevents adopting it later; a maintenance window and additive migrations compose.
- **Proving all PoPs swapped.** See Risks.
- **A settle wait after the first probe.** Considered and declined: it would convert an unmeasured unknown
  into an arbitrary constant that reads as a guarantee it does not provide.

## Decisions

### D1 — The gate is a prefix, not a route list, and not a port decorator

The middleware matches `/api/` and returns `503`. No per-route classification of "does this touch the
database or storage".

The FastAPI-shaped alternative was explored seriously, because it has the better property on paper: wrap
the `Db` object and the user-data `fetch` in `createApp`, and every handler is gated *because it used the
port*, derived rather than declared. The seams support it — every database access in the backend goes
through one `Db` object (24 free functions in `db.ts` all take it as their first argument; `app.ts` touches
it directly in only two places), and `fetchImpl` splits cleanly into user data (the byte `PUT`) and the
public site proxy.

It is rejected because `app.ts` contains ~30 `catch` blocks, several of which **deliberately swallow** to
stay best-effort. `markUploaded`'s catch logs and returns `201` anyway; `readPushToken` does
`catch { return null }`. A thrown maintenance error would be absorbed by exactly those: during the window a
byte upload would stream to storage, silently lose its `resources` row, and tell the device the upload
succeeded — after which the device never re-uploads. The port decorator would *create* a corruption path
while closing one, and it collides directly with the law that absence is never silent.

A prefix is also not the closed-list failure mode it superficially resembles. A closed list rots because a
new route can be omitted from it; a prefix cannot be omitted from, and `/api/v2` inherits the gate without
anyone remembering to add it.

Rejected alternatives: gating only writes (reintroduces per-route classification, and a *read* against a
renamed table fails anyway); gating only database routes (the byte route streams to storage *and* writes
`resources`, so the distinction does not cleanly exist); Hono context `Variables` as a real `Depends()`
(closest structural analogue, but requires rewriting every handler's database access and does not fix the
swallow hazard).

### D2 — The gate runs before the attestation gate

An unauthenticated request during the window gets `503`, not `401`. It is cheaper (no HMAC verification)
and it is truthful: the service is unavailable, and the caller's credentials are not what is wrong. Nothing
is leaked by answering before authentication — `/health` already discloses the bundle's commit publicly,
and the repository is public.

### D3 — The flag ships inside the bundle

`maintenance` is a build-scope key in the resolver's inventory, rendered only into the Deno bundle,
defaulting to false. It is the same shape `sha` and `channel` already have: a value that varies per build
rather than per deployment, resolved at build time.

This is forced by fact (1) above. Every dynamic alternative was examined:

- **An endpoint plus a shared secret** cannot hold the state itself — fact (2). A `POST` reaches one isolate
  at one PoP; every other PoP and every cold start still serves normally. An endpoint can only *write*
  shared state.
- **The relational store** as that shared state works, and CI already holds its credentials, which makes
  the endpoint redundant — but it costs a database read on every `/api/` request, on a store whose
  read-your-writes from the edge `api/README.md` records as **unmeasured**, with a standing rule to
  re-confirm before relying on it. And the flag would live inside the thing being migrated.
- **A storage object** is the one place an endpoint would earn its keep, by lending CI a credential it is
  forbidden to hold. It costs a storage round trip per `/api/` request, and resurrects the admin bearer
  secret that was deliberately retired so that a device token is the only credential this backend accepts.

A baked flag needs no shared state, no per-request read, and no new credential. It propagates by the only
mechanism CI has: publishing code.

### D4 — Two deployments over one shared component, not nested `extends`

`deployments/maintenance.json` cannot extend `deployments/prod.json`: `resolve-deployment.py` refuses a
component that itself declares `extends`, and the merge is documented as *"deliberately too weak to grow a
templating language"* — deep merge is excluded precisely so a resolved value stays predictable from reading
one file.

So `prod.json`'s own keys (the domain and its environment references) move into a new component, and both
`prod.json` and `maintenance.json` extend it — the latter adding `"maintenance": true` and nothing else.
Duplicating prod's keys into a second file was rejected: it would put the domain and four credential
references in two places with nothing binding them, which is the exact drift class the resolver exists to
make impossible.

### D5 — The window is entered only when a migration is actually pending

`migrate.ts --pending` compares `appliedVersions(db)` against `MIGRATIONS` and exits **0** for none
pending, **10** for pending, and any other code for a genuine failure.

Distinct codes rather than 0/1 because `deno run` also exits non-zero on a crash. Collapsing "pending" and
"the check blew up" makes a crash indistinguishable from an answer — and if a crash were read as *none
pending*, CI would publish the new bundle and only then fail at the migrate step, producing new code on an
un-migrated store: the exact outcome this change exists to prevent. The workflow treats any unrecognised
code as fatal.

It reuses `migrate.ts` rather than a new program so that "what would this apply?" and "apply it" share one
code path, one config reader and one comparison. Two implementations of that comparison is the pair that
must never disagree.

The alternative — always enter the window — has the honest argument that one code path is the path every
deploy exercises, which is the reasoning `migrations.ts` itself uses for always replaying. It is rejected
because most deploys change no schema, and imposing an outage window on a route-only or comment-only change
buys nothing.

### D6 — `/health` reports `{sha, maintenance?}`, and signals unreachability with a bare non-200

Both bundles carry the same commit, so a sha match can no longer distinguish them; the probe needs the
maintenance state to tell "the maintenance bundle is live" from "the real one is". The first probe asserts
`maintenance: true` before migrating; the second asserts `maintenance: false`, without which a run that
failed to lift the window would go green.

Neither probe overlaps the migration — the first runs strictly before it and the second strictly after — so
there is no need for the maintenance bundle to carry a different `/health`. One code path.

### D7 — `/health` verifies storage reachability, and stops asserting foreign keys

**Adding storage** closes a gap `backend-deployment` currently states outright: the probe witnesses that
the script booted, not that any configured value is *correct*, and a present-but-wrong
`BUNNY_STORAGE_ZONE` boots and probes green. That was the other half of the 2026-07 outage and nothing has
watched it since.

**Removing the foreign-keys assertion is a reduction in guarantee, and is recorded as its own decision.**
The assertion exists because enforcement being off would disable every constraint *silently* — no error, no
rejected write — making two staleness classes the schema is designed to make unstateable quietly reachable
again. A measurement is not a guarantee, so the probe asserted it rather than trusting it, and
`foreign-keys-off` is the probe's only **terminal** store cause.

Removing it means enforcement is now **trusted**. What that trust rests on: it was measured on the deployed
store, and no code path in this repository can turn it off — only a provisioning change on bunny's side
could. What would falsify it: a bunny Database provisioning or engine change that alters the default. There
is no longer anything in the deploy path that would notice; the next signal would be a data anomaly. If that
trade proves wrong, the assertion returns as its own change with its own argument.

With the terminal cause gone, the `200`-with-a-state-string body has nothing left to carry that a status
code cannot: the only remaining store condition is unreachability, which is retryable, and `probe.ts`
already treats `server-error` as retryable. So `/health` answers a bare non-200 instead. The cost, accepted
explicitly, is diagnostic detail — a red probe reports `server-error` rather than naming which dependency
was unreachable.

The other accepted cost: `/health` is ungated, and it now does real upstream work per hit. Its cheapness
was the *stated* reason serving it unauthenticated was safe. The spec must therefore restate why it is
still safe rather than leave the old rationale standing falsely.

### D8 — Rollback republishes an archived artifact, not a rebuilt commit, and never from bunny storage

Each successful deploy uploads `dist/main.js` as `bundle-<sha>`. The pipeline captures the live commit from
`/health` *before* opening the window; an always-run failure step downloads that commit's artifact and
republishes it.

Rebuilding the previous commit was rejected as the failure path's mechanism: the resolver stamps from
`GITHUB_SHA`, so the stamp would have to be overridden, and the failure path is the last place to add
moving parts. There is deliberately **no** rebuild fallback — a missing or expired artifact fails loudly,
naming the commit, and a human rolls back by hand.

**Keyed by commit, not a single rolling artifact**, because the name is the check. The capture step
asserts that the archive for the LIVE commit exists — an identity claim, not an existence one. A rolling
`bundle-latest` could only answer "something is archived", and the two drift: a NON-migrating deploy that
publishes and then fails its probe leaves that bundle live with nothing archived for it, since that path
deliberately does not roll back. The next migrating deploy would open a window believing it could restore
what was live, and on failure would silently republish an older bundle — reverting a shipped change while
reporting a successful rollback. Keyed by commit it refuses to open the window instead. It also avoids
resting on an unmeasured platform behaviour: v4 artifacts are immutable and names are not unique across
runs, so a query for a rolling name can return several, and picking one would assume an ordering nobody
has measured. The accumulation this costs is ~790 KB per deploy in a public repository, where Actions
storage is free, beside the 390 MB of `dsyms-*` the repo already parks by design.

Archiving into the bunny storage zone was considered and rejected on two grounds. `api-deploy.yml` states
`BUNNY_STORAGE_ACCESS_KEY` **"SHALL NOT be widened further to storage"** for this workflow specifically —
the deploy path already reaches the relational store, and extending it to the zone holding every user's
photos to solve a rollback problem is the trade the config-in-source argument exists to refuse. (The key
*is* held by `site-deploy.yml` and `nightly-cleanup.yml`; the rule is per-workflow least privilege, and the
spec scopes that grant to *"one non-deploy workflow"*.) Independently: the rollback has to work when bunny
is the thing misbehaving, and an artifact on GitHub is a separate failure domain from the vendor being
deployed to. Precedent for the shape exists — `ios-deliver` parks each build's dSYMs the same way.

### D9 — `503` with `Retry-After`, and `NO_CACHE` is load-bearing

`503` is the status HTTP defines for this: RFC 9110 §15.6.4 names *scheduled maintenance* explicitly and
pairs it with `Retry-After`. No shipped SnapSync client reads it; it is sent because the RFC pairs them and
because a human, a proxy or a curl does.

`Retry-After` is a **poll interval, not an estimate of the window**. Every `503` re-issues it, so a caller
that honours it asks again and gets a fresh hint — the value only has to answer "how long until it is
worth asking again". That matters because the window's length is not predictable from anything we control:
the migration is the *smallest* term (v1–v3 are millisecond DDL batches), while two publishes and their
propagation dominate, and `probe.ts` polls those at 5 s with a 120 s deadline apiece — so the window
plausibly spans ~10 s to ~250 s. Under-estimating is the safer error: too low costs a few wasted requests
during the window, too high keeps a caller away *after* the service is back. Hence 30 s, below even the
fast case, and deliberately not coupled to the probe's own interval, which answers a different question.

`Cache-Control: NO_CACHE` (the existing constant) is not decoration. A pull zone sits in front of every
request, and a cached `503` would outlive the window — turning a bounded, deliberate outage into an
unbounded accidental one. CI cannot configure the pull zone, so the origin header is the only lever, which
makes verifying it through the pull zone part of the work rather than an optional check.

### D10 — No guard against a stray `"maintenance": true` in an authored file

Considered: a resolver test asserting that `prod` resolves the flag false, in the already-required
`resolver-test` job. Rejected as redundant *given D8*: the second probe asserts `maintenance: false`, and
the failure path now republishes the previous bundle — so a stray flag reds the run and self-recovers. The
objection that detection-without-rollback is the wrong posture does not survive rollback existing.

## Risks / Trade-offs

- **One probe sees one PoP of ~119 → stated, not mitigated.** Bunny's own claims conflict ("deployed around
  the world in just seconds" against "deployment to over 119 regions takes just minutes"), and neither is a
  contract. The guarantee this change can honestly offer is *"very likely no request met a mismatched
  bundle"*, not *"none did"*. It goes in the spec in those words. What would tighten it: measuring how long
  `/health` reports a split commit when observed from several networks — deliberately not done here, and
  possibly not doable from one CI runner at all.
- **A partial multi-migration run leaves the store where no bundle fits.** → Roll-forward only, by hand.
  Stated in the spec rather than implied. The bound that keeps it rare: ship one migration per deploy.
- **A cached `503` outlives the window.** → `NO_CACHE`, verified *through the pull zone* rather than at the
  origin. The listing routes already depend on the same header for the same reason, which is evidence but
  not proof for a `5xx`.
- **The window costs real requests.** → Bounded and cheap by measurement, not assumption: `SyncEngine`
  retries forever with no attempt budget, so an upload costs one retry; create/join map the status to
  existing `Transient`/`Failed` states; downloads never touch the script. The user-visible worst case is a
  guest whose join attempt fails once during a deploy.
- **A stuck window is a full API outage.** → The always-run rollback step is the primary recovery. Its own
  failure mode — an expired artifact — fails loudly naming the commit, which is a bounded manual recovery
  rather than an unexplained outage.
- **Foreign-key enforcement is no longer asserted.** → Accepted, argued in D7, with its falsifier named.
  There is no compensating detection; this is a guarantee traded away, not relocated.
- **`/health` becomes an unauthenticated route that does upstream work.** → Accepted. A database query and
  a storage call are individually cheap, but the amplification surface is real and the spec must say why
  serving it unauthenticated is still the right call rather than leave the superseded "cheapest route in
  the backend" rationale standing.

## Migration Plan

1. Land the resolver restructure (D4) and the `maintenance` key first — inert on its own, since nothing
   reads the value yet.
2. Land the `/health` rewrite (D6, D7) and the probe's new cells together; they are one contract.
3. Land the middleware (D1, D2, D9) with the flag defaulting false, so `main` deploys behave exactly as
   today.
4. Land the pipeline last (D5, D8): the pending check, the conditional window, the archive, the rollback.
5. Verify the cache behaviour through the pull zone against the deployed origin before relying on the
   window for a real migration.

**Rollback of this change itself:** every step before (4) is inert with the flag false, so reverting the
pipeline commit restores today's behaviour with no store or bundle state to unwind.

## Open Questions

- What the window's duration actually is, once the pipeline has run a real migration. It is worth knowing
  in its own right, but `Retry-After` is no longer sensitive to it: as a poll interval that every `503`
  re-issues, 30 s converges whether the window turns out to be 10 s or 250 s.
- Whether the artifact retention window is ever short enough to matter in practice. It is set to **90
  days**, which is the platform maximum for a public repository (1–400 applies only to private ones), so
  there is no longer setting available if it turns out to be. It bites only if `api/**` goes untouched
  that long, which has not happened yet, and the capture step catches it before a window opens rather
  than at rollback time.
- Whether expand/contract migration discipline should later replace the window rather than complement it.
  Out of scope here; nothing in this design forecloses it.
