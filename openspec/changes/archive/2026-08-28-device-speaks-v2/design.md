## Context

`/api/v2` is served and unchanged by this change. What moves is the device.

Four v2 routes differ from v1; everything else is the shared router and identical under both prefixes.
The byte `PUT` names identity in its path and requires a capture-name query; the per-device listing answers
in identity terms and mints no `url`; join is its own route and the only one deciding capacity; the manifest
is a contribution-only sub-resource, and there is no notify route because the fan-out is the publish's
effect. Every v2 request must also declare the calling build's marketing version or be refused `426`.

Two properties of v2 make the crossing cheap and are load-bearing here: the stored object name is
**byte-identical** across versions (`legacyKeyFor` mirrors the client's `uploadKey`), so a device finds its
bytes where it left them and re-uploads nothing; and v1 stays served, so rollback is a rebuild rather than a
migration.

The constraint that shapes everything else is that **the OS performs the byte upload**. The extension hands
PhotoKit a destination request and the process dies; hours later the OS returns a finished job, and the
device must write the outcome into a ledger row. Under v1 the ledger key *was* the destination's last path
segment, so recovery was free — an accident of formatting, never a decision. v2 removes the accident.

## Goals / Non-Goals

**Goals:**

- Move every device-facing request to `/api/v2` with no change in semantics.
- Recover a returned upload job's ledger row from what the OS actually persisted, deliberately rather than
  by luck, and fail loudly when it cannot.
- Give `min-app-version` the client half it shipped without, so a refusal reaches the user as an instruction
  rather than as silence.
- Make three currently-silent absences loud: an unrecoverable job, a permanent listing-shape mismatch, and a
  version refusal.

**Non-Goals:**

- **The manifest declaring intent.** It needs no wire change — `publishStatements` reads only `assetId`,
  `creationDate` and `resources[].role`, and today's `DeviceManifest` JSON crosses v2 verbatim — so there is
  no freeze pressure to fold it in.
- **Pending as a difference against the listing**, and **ledger retirement**.
- **Retiring v1**, which is gated on install-base decay, not on this.
- **Making retraction meaningful to recipients** (see Risks).
- **Fixing the extension-registration regression** — that is `restore-upload-url-base`, which this change
  depends on but does not contain.

## Decisions

### D1 — One change, because the contract says so

The work *could* be staged: every Ktor adapter takes its `host` independently, so the metadata seams could
speak v2 while `EdgeUploadRequestProvider` stayed on v1, isolating the riskiest piece. Both versions are
served, one schema backs them, and object names are identical — it would work.

`backend-deployment` forbids it: the baked base *"SHALL carry **exactly one** version prefix … A build's API
version is therefore a property of the build, **never of the request path it composes — there is no
per-route version selection**."* That requirement is what makes a rollback one string and a bug report name
one version, and a build that speaks two versions has no single answer to "what does this device speak" —
which is precisely what `min-app-version` exists to be able to name. The staging path is closed deliberately,
not overlooked.

### D2 — A returned job is matched by its **destination path**, recorded in the ledger

**Decision.** `ledgerRow` gains a `destinationPath` column, written by the **existing** `UploadStarted` write
(which already carries the `UploadRequest`). On the ack path the adapter matches
`job.destination.URL.path` against it. A `NULL` column or a miss falls back to the v1 recovery
(`lastPathComponent`), which is correct for jobs created by the outgoing build and deletable a release later.

The klib settles the shape of the problem: a returned `PHAssetResourceUploadJob` declares exactly
`localIdentifier`, `resource`, `destination`, `responseHeaderFields`, `state`, `type`, `error`, and
`creationRequestForJobWithDestination(destination:resource:)` takes two arguments with no options
dictionary. **The destination request is the only slot we can write a fact into and read back.** Within it,
the choice is the URL or the headers.

**Alternatives.**

*(a) Recompose the key from the URL* — `uploadKey(assetId, role, query["filename"])`. Zero new state, and
the v2 URL carries the key's three semantic components rather than v1's formatted string, which is a better
foundation than what it replaces. Rejected because it depends on the **query** surviving the job store,
which is unmeasured, and it re-couples the ledger key to the URL shape, paying this cost again at the next
change.

*(b) A bespoke header* (`x-snapsync-key`). Headers are known to survive the store — the adapter already
reads `Content-Type` back off the stored destination — but that is a *standard* header the OS has its own
reasons to keep, and our own code notes the OS returns headers *"as the OS stored them rather than as we
spelled them"*, i.e. it normalizes. A custom header surviving is unmeasured.

*(c) Map the job's `localIdentifier`* to the key in a side table, via
`placeholderForCreatedAssetResourceUploadJob`. Rejected: it adds durable state and a second id space, it has
a write window between the change committing and the mapping landing (this tier has no routine orphan
sweep), and the placeholder's usability is unmeasured. An earlier investigation concluded this route was
*impossible*; that conclusion was drawn from a crash later proved to be the unrelated registration
regression, so it is recorded here as **unmeasured, not refuted**.

**Why (d) wins over (a).** The ledger already holds the mapping — it has `key`, `assetId`, `role` and an
index on `assetId` — so the only question is which column to match on, and the destination path is the one
fact we can be sure the OS kept, because it must `PUT` to it. It also satisfies the state-and-authority law
in its own terms: *every fact recoverable via ports, keyed by identifiers the external system persisted.*
And it makes the ledger key permanently independent of the URL shape, so a future v3 costs nothing here.

**What it costs.** The row must exist. Three ways it may not — a kill between `createJob` and the
`UploadStarted` write, a prune, or a rejoin's `resetTo` — but with the key in hand and no row, `markTerminal`
already logs *"applied to no row"* and does nothing, so "cannot find the row" and "found the key, no row"
have the same outcome: the asset is re-discovered and re-uploaded idempotently to the same object name.
One behaviour does change: `adjudicateFailure` currently re-creates a `FAILED` row for a pruned key, and
under the lookup it cannot. That is an improvement — `resetTo` exists to drop phantom rows, and re-minting
one behind its back is the bug that pattern guards against — but it is a change, and it is stated here
rather than discovered.

### D3 — The listing is decoded strictly; there is no round-trip invariant

**Decision.** `HttpDeviceFilesSource`'s DTO requires `assetId`, `role` (as the `ResourceRole` **enum**, not a
`String`) and `filename`; the key is recomposed through `uploadKey`.

The hazard this closes is that v1 and v2 both answer with a field called `filename` and mean opposite things
— v1 the object key, v2 the capture name — and today's DTO decodes either cleanly with
`ignoreUnknownKeys = true`. Seeding capture names as ledger keys would leave every real key unseeded and
re-upload the entire library on a rejoin, silently.

**Alternative rejected: a domain invariant** asserting each seeded name round-trips as a key. It is
**vacuous** once the key is recomposed — a constructed key round-trips by construction. Typing the enum also
subsumes the case it was meant to catch: an unknown role fails at the decode.

A pleasing robustness property falls out: recompose consumes only the *extension* of `filename`, and the
object key and the capture name share it, so even a backend that mistakenly returned the object key yields
the correct key.

### D4 — A parse failure is not a transport failure

Strict decoding turns "silently seeds the wrong keys" into `Result.failure`, which the reconciler treats as
*"deferring uploads this cycle"* and retries. That is right for a network blip and wrong for a permanent
shape mismatch, which would defer forever behind a warning.

So the seam distinguishes them, and a parse failure is reported at `Error` — the severity that reaches crash
reporting — rather than sitting inside a retry loop that cannot succeed. This is the same rule applied at
three seams in this change (see also D2's unrecoverable job and D5's refusal), and it is the repo's own:
*"'nothing' and 'couldn't tell' are different answers wherever their consequences differ."*

### D5 — `426` is detected once, and is a top-level state

**Decision.** The shared client's interceptor already detects `401` and calls `onRejected` to drop a dead
token. `426` is the identical shape: one detection point, covering every metadata seam and the ungated
attest bootstrap, parsing `minAppVersion` out of the body. It sets a read-model `StateFlow` that presentation
observes directly (reads do not cross `flow/`), cleared on the next successful response.

It surfaces as a **fourth top-level `UiState`**, not a `SyncHealth` variant, because an obsolete build can
neither create, join nor sync — the refusal precedes the token and applies to every route, so it supersedes
the other states rather than qualifying one.

**Known coverage gap:** the byte `PUT` is OS-performed and never passes through Ktor, so a `426` there
surfaces as an upload failure rather than this state. Accepted — the metadata calls run every cycle and
detect the condition within seconds — and recorded rather than left to be discovered.

### D6 — The minimum rises to `0.4`, and the **floor** rises with it

`MIN_APP_VERSION = "0.4"` is the first version that speaks v2. It refuses nothing that exists: the gate
applies only to `/api/v2` (`if (version !== 2) return await next()`), and every current install speaks v1.

The trap is that `MARKETING_VERSION` is **computed** for Release builds (`max(floor, latest tag + minor 1)`
= `0.4` today) but **left empty for Debug builds, which bake the `Config.xcconfig` floor** — measured at
`0.1` on a rig build. A minimum above the floor therefore refuses every dev and sideload build, including
the ones used to test this change on device, against the local rig as well as production, because
`MIN_APP_VERSION` is a source constant shared by all deployments.

So the floor moves to `0.4` too, and CI asserts **`MIN_APP_VERSION <= floor`** — not `<= computed version`,
which would have passed a minimum of `0.3` while silently breaking the dev loop. Because the computed value
is a `max` over the floor, the floor is the binding constraint and the assertion covers both paths. The
coupling — *raising the minimum requires raising the floor* — becomes a build failure rather than folklore.

Consequence to accept: dev builds sit one minor behind released builds from here on, so a dev build's
version no longer indicates which release it corresponds to.

### D7 — `appStoreUrl` is fixed, and reaches the device through the plist

Measured: `https://apps.apple.com/app/id6781692480` → **404**; the country-scoped form → **200**. The backend's
`GET /join` redirect — the whole no-app fallback for someone scanning an event QR — has been pointing at a
dead page, while `site/` carried a hardcoded workaround and a comment explaining exactly why. One value
stated twice, and the copy nobody exercised was the wrong one.

The value is corrected once and its projection widened to `[JSON, SITE, PLIST]`: the site reads it, and the
device reads it from `Deployment.plist` so the refusal screen can offer a link rather than only a version.
It is a URL, so it must reach the plist and **never** the xcconfig, where `//` opens a comment — the failure
that shipped four mute builds and that `deployment-configuration` exists to prevent.

### D8 — The device issues no notify; the publish is the trigger

`EventNotifier` is deleted with v1's route. The device's remaining lever is whether it publishes at all, and
skip-if-unchanged already suppresses a no-op write — so the effective trigger is close to today's.

It is not identical, and the difference is stated rather than smoothed: today the device fires only when
`promoted > 0 && published`, so a **retraction** (rows marked absent, a narrowed cutoff, a reconfigure)
notifies nobody; under v2 the publish itself fans out. That widening was investigated as a possible hidden
improvement and is **not** one — see Risks.

### D9 — The harness learns v2 first, as its own step

`:test:world`'s mini-edge models v1 structurally: no join route, no `…/manifest` sub-path, `putManifest`
sets `ACTIVE` (publish *is* enrolment), and the listing returns object names. It is the only place this
change is testable on JVM, so it moves first, mechanically, with no production Kotlin in the diff — the same
discipline `add-v2-device-api` used when it split its API tests before migrating.

## Risks / Trade-offs

**[The destination URL may not round-trip through the OS job store]** → The whole of D2 rests on
`job.destination.URL.path` coming back as sent. It is strongly evidenced — v1 recovers keys from that URL in
production every day, so the URL object survives — but the *query* is separately unmeasured, and the OS
demonstrably normalizes headers. Settled by one device measurement: create a job with a query-bearing
destination and a custom header, read both back on the ack path. The probe is written and re-appliable
(`scratchpad/probe.patch`); it cannot run until the registration regression is fixed.

**[The OS's preflight `OPTIONS` may be refused]** → Measured on the backend: `OPTIONS` on a v2 path **without**
the version header returns `426`, where v1 returns `204`. The version gate does not exempt `OPTIONS`, which
contradicts `api-endpoints`' standing requirement that a preflight be answered ungated *"so that a
cross-origin preflight the pull zone does not answer itself cannot break the plain-`PUT` upload the iOS
uploader depends on."* Whether it bites depends on whether the OS's preflight carries our composed headers —
device-measurable only. **If it bites, the fix is a backend change this one depends on**, and it is the single
most likely way this change fails on device having passed everything else.

**[The daemon's copy of the base names an API version, and its matching rule is unknown]** → The
registration fix that unblocks this change (`restore-upload-url-base`, merged) restored
`BackgroundUploadURLBase` to both `Info.plist`s, composed as `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1` —
so the value `assetsd` validates the registration against now carries an **API version**. `ios-photokit-upload`
states explicitly that *"the daemon's matching rule — whether it compares host, origin or prefix — is NOT
established, and this spec SHALL NOT assert one."* Moving `uploadBase` to `/api/v2` while that key still
says `/api/v1` is therefore a coin toss between harmless (host- or origin-scoped) and every upload silently
refused (prefix-scoped) — with registration succeeding either way, so nothing reports it. Mitigated by
moving both carriers together and by extending the post-archive agreement assertion to compare them, so a
half-move fails the build. The tempting simplification — make the key host-only, so it never tracks the API
version again — is **unmeasured** and is deliberately not taken here: it is safe under a host or prefix rule
and wrong under an exact-equality one, and this key's failure mode is silence.

**[A rollback now crosses a ledger schema migration]** → v2's device state was otherwise rollback-clean: the
ledger key, object names, manifest JSON and markers are all unchanged. `destinationPath` adds a column, so a
rolled-back build opens a database at a newer schema version. The column is additive and nullable and the old
build never reads it, but SQLDelight's tolerance of a newer `user_version` must be **verified**, not assumed —
this is the one thing that makes rollback more than flipping the baked string.

**[Jobs created by the outgoing build cross into the incoming one]** → The OS job store survives an app
update and the extension is invoked lazily, so a v1-shaped job will be handed to a v2-shaped extension (and
the reverse on rollback). Mitigated by construction: the `NULL`-column fallback reads exactly those jobs the
old way.

**[Silent-push volume rises]** → v2 fans out on every manifest publish, including one that only retracts.
Accepted knowingly by `add-v2-device-api` D10; restated here because the device loses the `promoted > 0`
filter that used to hide it.

**[Retraction still has no recipient-side effect]** → `DownloadController.reconcile` is purely **additive**:
it plans union assets and never prunes, and `PlannedResource` captures the presigned URL at plan time, so an
already-planned asset downloads and imports even after the publisher retracts it. This predates v2 and is
neither caused nor fixed here. It also means waking recipients on a retraction accomplishes nothing, which is
why D8 records the widening as a cost rather than a benefit.

**[Three in-flight changes converge on one renderer]** → `restore-upload-url-base` edits
`render_xcconfig` and the `ios.yml` readback; this change edits `render_plist` and the same readback. A
mis-edit of exactly that file produced both of the failures found while designing this. The regression fix
lands first and this rebases onto it; the readback should end up as one block asserting every device-facing
value, not two blocks grown independently.

**[The change is roughly double the original transport scope]** → Deliberately: the `426` client half, the
`appStoreUrl` fix and the version-floor coupling are all consequences of shipping a v2 client, and each is
silent-when-wrong if deferred. Stated so the diff is not mistaken for a transport change that grew.

## Migration Plan

```
1  world harness speaks v2          mechanical, no production Kotlin, lands first
2  restore-upload-url-base          MERGED (1b1597bc) — the OS-driven tier can register again
3  probe the destination round-trip one device measurement; settles D2 and the OPTIONS risk
4  this change                      transport + key recovery + 426 client half + appStoreUrl + floor
5  raise MIN_APP_VERSION further    a later, separate decision, gated on release history
```

**Rollback** is a rebuild with the baked base flipped back to `/api/v1`; v1 stays served, object names are
identical, and no device state is v2-shaped except the ledger column (see Risks). It is not instantaneous —
the base is compile-time by PhotoKit's constraint — so it costs a TestFlight round and does not reach
installs already carrying the v2 build.

## Open Questions

- **Does `job.destination.URL` come back byte-identical, query included?** Decides whether D2's fallback is
  ever exercised and whether recompose was viable after all. One device measurement.
- **Does the OS's preflight `OPTIONS` carry the composed headers?** Decides whether `api-endpoints`' preflight
  requirement must be repaired in the backend before this can ship.
- **Does a rollback build tolerate the newer ledger schema?** Verify against SQLDelight's version handling
  rather than reasoning about it.
- **Should `BackgroundUploadURLBase` be host-only?** Owned by `restore-upload-url-base`, but it lands here:
  a host-scoped value would mean the v2 flip never touches that plist and the API version is not duplicated.
  Unmeasured, and that key fails silently.

## Archive gates (openspec/config.yaml)

Run before archiving; recorded here because the accounting is the artifact, not the run.

**1. Placeholder Purpose** — no `## Purpose` in `openspec/specs/` contains the CLI's minted
`TBD - created by archiving`. Checked across the whole tree, scoped to the Purpose section. **Pass.**

**3. Dead types** — the types this diff removes that exist nowhere else in the tree are
`CapturingUploader`, `CommitFailedPhase`, `EventNotifierTest`, `FileDto`, `RecordingClient` and
`RecordingStore`. No spec names any of them. **Pass.** It did surface three stale in-code references to
things this change deletes, all fixed here rather than left to rot: `KtorPushHttpClientTest`'s KDoc named
`EventNotifier` as a second consumer of the push client, `MiniEdge`'s KDoc named the world's
`HttpEnrollment`, and `SnapSyncRoot` still imported `JoinOutcome`.

**2. Delta completeness** — every module this diff touches, resolved to its owning capability:

| module | delta, or why none |
|---|---|
| `:domain:feature` | `join-event`, `device-manifest`, `event-rejoin-reconciliation`, `upload-completion-notify`, `min-app-version` |
| `:domain:model` | `edge-upload-provider`, `sync-ledger`, `min-app-version`, `join-event` |
| `:domain:ports` | `join-event`, `event-rejoin-reconciliation`, `sync-ledger`, `min-app-version` |
| `:adapter:generic:app` | `join-event`, `event-rejoin-reconciliation`, `min-app-version`, `sync-ledger` |
| `:adapter:generic:fake` | `sync-ledger` — the in-memory store gains the same column |
| `:adapter:ios:ext-safe` | `ios-photokit-upload`, `min-app-version`, `deployment-configuration` |
| `:adapter:ios:app-only` | `min-app-version` — the URL opener the update screen hands off to |
| `:ui:presentation`, `:ui:screens` | `min-app-version`, `join-event` |
| `:test:world` | `harness-world-model` |
| `iosApp/`, `scripts/`, `deployments/`, `site/`, `.github/` | `deployment-configuration` — one declaration projected to backend, site and device, and the `Info.plist` carrier that moves with it |
| `api/` | `min-app-version` (the minimum itself). The dev rig (`src/dev/**`) is non-gating dev infrastructure with no spec, by the same posture as `ssh-mac.yml` |
| `:domain:compose`, `:app:ios`, `:app:ios:extension` | **none needed** — wiring only. No law in `module-architecture` moved; what these files gained is the graph edges the capabilities above already specify |
| `:app:desktop` | **none needed** — behavior-preserving: one signature followed `commitJoin`'s widened outcome |
| `:ui:components` | **none needed** — `AppIdentityHeader` is an extraction of what the two existing headers already were; neither existing surface changes |
| `:test:integration`, `:test:architecture`, `:test:rig` | **none needed** — tests and guards. `testing-architecture` and `architecture-guards` state where these live and what they assert as kinds; this change adds instances, not kinds |
| `config/`, `CLAUDE.md`, `.claude/` | **none needed** — `complexity-budgets` requires a raised ceiling to carry a stated forcing proof, which is recorded in `config/detekt/compose.yml` itself; `.claude/` is regenerated and holds no contract |
| `architecture/` | generated; `architecture-diagrams`' freshness gate is what asserts it |

⚠️ Two budgets moved **the wrong way** in this change, each with its proof written where the number
lives — `LargeClass` 390 → 400 in `config/detekt/compose.yml`, and `:domain:feature`'s package coverage
floor 92 → 90 in its `build.gradle.kts`. Both are called out in the PR body rather than left to a reader
of the diff.
