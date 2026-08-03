## Context

`2026-07-21-add-api-version-prefix` mounted the device API under `/api/v1` **and** kept the bare paths as
a deprecated alias, deferring removal to "a later change". The reason was concrete: the device-facing
host is a compile-time constant (the OS-driven upload extension permits exactly one upload host), so an
installed app cannot be repointed and cannot be force-updated.

The dates settle whether the deferral can end:

| | commit | contains `62b236a1` (the prefix) |
|---|---|---|
| `v0.1` | `811d7581`, 2026-07-21 12:06 | **no** — bare paths only |
| prefix | `62b236a1`, 2026-07-21 15:08 | — |
| `v0.2` | `f936b9fc`, 2026-07-31 10:29 | **yes** — `/api/v1` |

`v0.2` has been the released App Store version since 2026-07-31. The only clients that can still speak
bare paths are installs of the `v0.1` build.

The prefix exists in exactly one place in the shipped system: `Config.xcconfig`'s
`BACKGROUND_UPLOAD_URL_BASE = https://snapsync.stho.net/api/v1`. No Kotlin client holds a prefixed path
literal — every HTTP client interpolates `$base/…`. That is why removing the alias is a server-only
change, and also why the various route literals scattered through the specs are ambiguous: some name a
path on the deployed origin, others name a path relative to whatever base was injected.

## Goals / Non-Goals

**Goals:**

- One URL shape for the device API: `/api/v1`. Nothing routes bare.
- The route maps, specs, and tests stop hedging about a second shape.
- A future `/api/v2` stays exactly one more mount line.

**Non-Goals:**

- Keeping any pre-`v0.2` install working. Their breakage is the accepted cost, not a problem to solve.
- Measuring residual bare-path traffic before removing (bunny pull-zone logs would need the account key,
  which CI deliberately does not hold, and the answer would not change the decision).
- Any transition affordance — a `410 Gone`, an "update the app" body, a deprecation log line.
- Changing the world harness's base shape, or any client, or the web/link surface.

## Decisions

**D1 — Delete the mount; add nothing.** The removal is `app.route("/", deviceApi)` deleted, full stop.
*Alternative considered:* a bare catch-all returning `410 Gone` with an "update the app" body, so an old
install fails legibly and the removal leaves a greppable signal. Rejected: it is more code than the
removal itself, it is dead the moment the last `v0.1` install updates, and the audience for a legible
failure is a compile-time-pinned app that cannot act on it. What a bare path returns after this change is
therefore whatever the existing gate and Hono already produce (`401` unauthenticated, since the gate
matches `*` before routing; `404` with a valid token) — left unpinned, because that split is an artifact
of gate ordering, not a contract.

**D2 — Keep the `deviceApi` sub-app.** With one mount left, the sub-app could be collapsed onto `app`
with an explicit `/api/v1` on each route. Rejected: the sub-app *is* the mechanism behind
`backend-deployment`'s "additional versions can be mounted without restructuring" requirement, and
collapsing it would put the prefix into ~15 route literals that today have none. Deleting one line and
keeping the structure is both the smaller diff and the one that keeps the promise.

**D3 — Keep the gate's normalization version-agnostic.** `^\/api\/v\d+(?=\/|$)` stays as-is. Narrowing it
to `v1` buys nothing and would have to be widened again by the change that adds `/api/v2`.

**D4 — Erase the alias, do not memorialize it.** No spec sentence asserting "bare paths are not served",
no regression test pinning their absence. *Alternative considered:* one test asserting a representative
bare device path no longer resolves, so a refactor cannot silently re-add a root mount. Rejected on the
grounds that the alias was a transition artifact and the tree should read as though `/api/v1` is simply
the API; re-adding a root mount is not a failure mode anyone is drifting toward, and the decision record
for the removal is this change, which archives. *Consequence to accept:* nothing mechanically prevents a
future root mount.

**D5 — Prefix route literals by what they name, not by where they appear.** A literal is rewritten to
`/api/v1/…` when it names a path **on the deployed origin**; it stays bare when it names a path
**relative to an injected base**. So the api's own docs and the specs describing the backend's HTTP
surface get the prefix, while `harness-world-model`, `full-stack-harness`, and client-side prose
("`GET /events` succeeds", meaning the call the app composes from its base) do not.
*Alternative considered:* prefix every literal in every spec, so no reader has to know which kind a
sentence is. Rejected because it would make `harness-world-model` contradict `MiniEdge.kt`, which matches
from segment 0 against the base `https://world.edge`.

**D6 — Leave `:test:world` bare.** The world could adopt the production base shape
(`https://world.edge/api/v1` plus a one-line prefix strip in MiniEdge), which would make the prefix
uniform and let every spec use it. Rejected: the world is deliberately base-relative, and pinning a
property of the deployed origin into a fake buys uniformity in prose at the cost of a stripper in the
double.

**D7 — The other specs' literals are an editorial correction, not requirement deltas.** Only
`backend-deployment` carries a delta, because only its requirements change. The ~120 route literals in
the other deployed-surface specs have been describing an alias shape since 2026-07-21 — they were already
not the canonical path — so correcting them changes no requirement's meaning. *Alternative considered:*
a `MODIFIED` delta per spec. Rejected on risk: a `MODIFIED` delta restates a **whole** requirement, and
`event-creation` alone carries 39 literals spread across most of its requirements, so the delta would be
a near-copy of the spec — precisely the shape that silently drops whatever the author did not scroll to.
A surgical, reviewable edit to the main specs is the safer instrument here.

## Risks / Trade-offs

- **A `v0.1` install exists and breaks silently** → Accepted, deliberately. It cannot mint an attest
  token, so every request fails; the status screen simply never progresses. The only remedy is a store
  update, which the store offers automatically. The alternative (never removing the alias) means the
  hedge is permanent.
- **A spec keeps a bare literal that should have been prefixed, or vice versa** → D5's rule is the test:
  does the sentence name the deployed origin? Applied per file, and the two categories are already
  cleanly separated by which capability owns the sentence.
- **The editorial spec edits sit outside the delta mechanism (D7)** → They are visible in the PR diff and
  scoped to route literals only; `openspec validate --specs --strict` runs over the result. The trade is
  reviewability against a delta set large enough to lose content in.
- **No boot probe on deploy** → `api-deploy.yml` succeeds whether or not the script boots (this is
  documented in `backend-deployment` and is why config lives in source). Verification is a post-merge
  curl, not a green CI run.

## Migration Plan

1. Merge to `main`; `api-deploy.yml` bundles and publishes to the bunny Edge Script.
2. Verify against `snapsync.stho.net`: `GET /api/v1/attest/challenge` → `200`; `GET /`, `GET /join`,
   `GET /.well-known/apple-app-site-association` → `200` (unchanged, root-only).
3. Rollback is a revert PR — the alias is one line, and no client, storage key, or presigned URL shape
   depends on this change.

## Open Questions

None.
