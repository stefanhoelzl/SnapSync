# Probe findings — device-speaks-v2

Measurements this change's design rests on. Each records what was run, against what, when, and what would
falsify it. A claim not measured here is not measured anywhere in this change.

**Method.** SE2 `00008030-0018703A1A7A402E`, **iOS 26.6 (23G71)**, **2026-08-28**. A hand-signed Debug rig
build from the working tree at `1b1597bc`, pointed at a local backend behind a cloudflared tunnel, so the
origin's own request log is observable. The byte route exercised is **v1** — the probe deliberately does not
need v2 to exist, because v1 ignores a query string entirely (measured separately against the real handler:
`201`, storage key untouched, row recorded normally). Twelve seeded assets, six admitted by the membership's
policy, six uploaded by the OS.

The probe added, to the composed byte destination only: a `?filename=<capture name>` query on **alternating**
resources (`filename.hashCode() % 2`), and two custom headers `x-snapsync-app-version` and
`x-snapsync-probe-key`. Alternating arms were used so one invocation measures both a query-bearing and a
query-free destination without a second build. Three of six resources took each arm.

---

## 1. The destination URL round-trips through the OS job store, byte for byte

**The finding D2 rests on.** What the extension composed and what the OS handed back on the ack path are the
same string, query included.

Composed (`PROBE-CREATE`, at `createJob`):

```
https://<host>/api/v1/files/devices/<D>/ABEBF471-…_L0_001-primary.jpg?filename=IMG_7502.JPG
```

Returned (`PROBE-ACK`, in `drainTerminals`, a different process invocation):

```
absoluteString = https://<host>/api/v1/files/devices/<D>/ABEBF471-…_L0_001-primary.jpg?filename=IMG_7502.JPG
path           = /api/v1/files/devices/<D>/ABEBF471-…_L0_001-primary.jpg
query          = filename=IMG_7502.JPG
```

Across all six acked jobs: three carried `query=filename=IMG_75xx.JPG` and three carried `query=null` —
exactly matching which arm each took, so the null is the absence of a query rather than a stripped one.

**Why it matters.** The `destinationPath` column is matched against `job.destination.URL.path`. That value
is preserved exactly, so the match is sound. The query surviving additionally means the rejected alternative
(recomposing the key from the URL) was *also* viable — recorded so the decision reads as a choice between
two working options rather than the only one that worked.

**What would falsify it.** A returned job whose `absoluteString` differs from the composed destination in
any byte; an iOS release that normalizes the stored request. ⏰ Re-measure at the next iOS major, with the
other PhotoKit platform facts.

## 2. Custom headers survive the job store **and** reach the origin

Both were previously unmeasured, and they are different survivals of the same header.

**Through the store** — read back from `job.destination.allHTTPHeaderFields` on the ack path:

```
Content-Type=image/jpeg | Authorization=Bearer … | x-snapsync-app-version=0.1
                                                 | x-snapsync-probe-key=ABEBF471-…-primary.jpg
```

**To the wire** — observed at the origin on the real `PUT`, whose `user-agent` is `assetsd (unknown version)
CFNetwork/3860.700.1 Darwin/25.6.0`, i.e. the OS's own daemon and not this app:

```
… content-type=image/jpeg … x-snapsync-app-version=0.1 | x-snapsync-probe-key=ABEBF471-…-primary.jpg
```

**Why it matters.** `min-app-version` requires the byte `PUT` to declare the app version, and that request is
issued by the OS outside any client this app controls. It arrives. This was the single most likely way the
change could have been blocked outright.

**Prior belief corrected.** The repo held that *"an arbitrary header survived that handoff"*, evidenced only
by `Authorization` — a standard header the OS has its own reasons to keep. A genuinely bespoke `x-snapsync-*`
header is now measured, in both directions.

## 3. The OS sends **no** preflight `OPTIONS`

Zero `OPTIONS` requests reached the origin across the whole session (`grep -c` over the request log = 0),
against six byte `PUT`s.

**Why it matters.** `OPTIONS` on a v2 path carrying no version header is refused **`426`** (measured against
the real handler on 2026-08-28), which contradicts `api-endpoints`' standing requirement that a preflight be
answered ungated so it cannot break the plain-`PUT` upload. That contradiction is real but **unreachable by
this path**: the uploader does not preflight. It was the risk most likely to make this change fail on device
having passed everything else, and it does not.

**What would falsify it.** An `OPTIONS` appearing at the origin from `assetsd`; a resumable-upload
negotiation being introduced. Note the observed `PUT` already carries `upload-complete: ?1` and
`upload-draft-interop-version: 6`, so the OS *is* speaking a resumable-upload draft — a future version of it
could add a preflight.

## 4. `resource` is nil on the succeeded path; `localIdentifier` is populated

Every acked job reported `state=4` (succeeded) with `resourceNil=true`, confirming the documented reason the
key cannot be read from `job.resource`.

`job.localIdentifier` **is** populated and stable-looking — e.g. `BB3BCA1C-13CA-484D-8306-AAC8DD70A71B/L0/300`.

**Why it matters.** The rejected alternative (c), mapping the job's `localIdentifier` to the key, is
therefore **not impossible**, contrary to a conclusion drawn on 2026-08-28 from a crash that was later proved
to be the unrelated registration regression. It remains rejected on its merits — durable state, a second id
space, and a write window — not on feasibility. The design record says so.

## 5. Two facts about the registration regression, established in passing

- The extension **registers**: `osExtension.enabled: true` on this build, where every attempt before
  `1b1597bc` returned `PHPhotosErrorDomain -1`. The appex's `Info.plist` carried
  `BackgroundUploadURLBase = https://<tunnel>/api/v1`, composed from `$(UPLOAD_SCHEME)` and `$(UPLOAD_HOST)`,
  confirming the substitution resolves through a tunnel host as well as a production one.
- `POST /os/photokit-ext/processRawValue` — the rig verb that aborted the app all day — **works**, returning
  `processing`. That abort was entirely the missing plist key and had nothing to do with the verb.

---

## What is still not measured

- **Whether the daemon's `BackgroundUploadURLBase` matching rule is host-, origin- or prefix-scoped.**
  `ios-photokit-upload` declines to assert one. This change moves `uploadBase` to `/api/v2` while that key is
  composed with `/api/v1`, so the two must move together; the post-archive agreement assertion is what
  enforces it. Measuring the rule itself would need a build whose key and upload path deliberately disagree.
- **Whether a rolled-back build tolerates a ledger at a newer schema version** (task 1.5). Unrelated to the
  device; verify against SQLDelight's version handling.
- **Anything under a partial photo grant.** The OS does not invoke this extension there at all
  (`limited-photo-access`), so this probe says nothing about it.

---

## Rollback: what an older build does with a newer ledger (task 1.5)

**Question.** This change adds `7.sqm` (schema version 7 → 8), a nullable `destinationPath` column. If a
build carrying schema 8 runs and the operator then rolls back to a build carrying schema 7, does the old
build refuse the database, downgrade it, corrupt it, or open it?

**What was run.** A JVM probe over `JdbcSqliteDriver` against a real on-disk SQLite file
(2026-08-28, this worktree): create the ledger at the current schema, add a column and stamp
`PRAGMA user_version` one AHEAD of the build's own `LedgerDatabase.Schema.version`, insert a row, close;
then reopen and perform exactly what every SQLDelight driver does on open — read the stored version and
migrate only if the file is behind — followed by an old-shaped `SELECT` naming only the pre-existing
columns.

**Measured.**

```
PROBE current schema.version = 8
PROBE wrote at user_version = 9
PROBE stored=9 vs schema=8
PROBE file is AHEAD or equal — no migration attempted, database opened as-is
PROBE old-shaped read = [k-primary.jpg]
```

**Answer.** A build behind the file's schema **opens it and reads it**. SQLDelight compares the stored
`user_version` to its own and migrates only upward; it has no downgrade path and raises nothing when the
file is ahead. The rows remain readable because the migration is **additive and nullable** and because no
statement in `Ledger.sq` uses `SELECT *` — every query names its columns, so a column the old build has
never heard of is simply not selected. (That is a property worth keeping: a single `SELECT *` would turn
this from "works" into a column-count mismatch at runtime.)

**So the rollback story is: roll back freely, and lose only what the new column bought.** An old build
writes rows with `destinationPath` NULL and resolves returned upload jobs by the last-path-component
recovery instead — which is correct for it, because a build carrying schema 7 also bakes the `/api/v1`
base and therefore creates v1-shaped destinations, which that recovery reads. Rows the NEW build wrote
keep their `destinationPath`; the old build ignores the column rather than tripping over it.

**What would falsify it.** A statement added to `Ledger.sq` using `SELECT *`; a future migration that is
not purely additive (a dropped or renamed column, or a NOT NULL addition); or a SQLDelight major version
that changes the open-time version comparison. The first is the one to watch — it is a one-line change
that silently removes this property.

---

## On device, end to end against a real v2 backend (tasks 12.5 – 12.7)

**What was run.** SE2 / iOS 26.6, 2026-08-28. A Debug build with `-Psnapsync.rig=true`, resolved against
a local `deno task dev:tunnel` rig over a cloudflared tunnel, so every request crossed real HTTPS. Both
bundles verified before install: `uploadBase` = `https://<tunnel>/api/v2` and the `BackgroundUploadURLBase`
carrier in **both** `Info.plist`s byte-equal to it; `CFBundleShortVersionString` = `0.4`.

### The object name does not move across the version crossing (12.7)

A `PUT /api/v2/files/devices/<d>/ABC-123_L0_001/primary?filename=IMG_0042.HEIC` stored the object
`files/devices/<d>/ABC-123_L0_001-primary.heic` — **byte-identical to the name v1 composes**. Confirmed
twice: once by curl against the rig, and again by the device, whose two uploads landed as
`<assetId>-primary.jpg`. This is what makes the crossing free: a device that has uploaded under v1 finds
its bytes where it left them.

The same request pair also exhibits the hazard the strict listing decode exists for. Asked for the same
device, the two versions answer:

```
v1: [{"filename":"ABC-123_L0_001-primary.heic","url":"…"}]        ← filename IS the storage key
v2: [{"assetId":"ABC-123_L0_001","role":"primary","filename":"IMG_0042.HEIC"}]  ← filename is the CAPTURE name
```

Both carry a field called `filename` and mean opposite things by it. A lenient decode reads either
without complaint.

### A fresh join uploads and the union lists it (12.5)

Joined a self-created event, `direction=upload`. Two seeded assets were admitted by the policy, uploaded,
and the event union listed both — with the capture filenames (`IMG_7512.JPG`) beside the recomposed
storage keys, and `contentType: image/jpeg` (the MIME, not the PhotoKit UTI). The extension's log carries
`PUT …/api/v2/events/<e>/devices/<d>/manifest → 200`: the **contribution-only sub-resource**, not the v1
route that also enrolled.

### A rejoin re-uploads nothing (12.5)

The reconcile ran on a marker mismatch (a switch to a second event) and logged:

```
GET https://<tunnel>/api/v2/files/devices/DD92FAC9-… → 200 (259ms)
joined ae2c1e22-… — reset+seeded 2 file(s), cleared cursor
enumeration: 2 seen, 0 new, 2 already-uploaded
```

The object count did not change. That single `0 new, 2 already-uploaded` is the whole listing change
verified at once: the v2 identity-terms response decoded strictly, the keys recomposed through
`uploadKey`, and the recomposition matched what was actually stored. One wrong character anywhere in that
chain and the device re-uploads its library instead.

⚠️ **A `/device/reset` followed by rejoining the SAME event does NOT exercise this path**, and it looks
like it should. The reset voids the ledger, the cursor and the config — but **not** the extension's
`joinedEventId` marker, so the reconcile takes its "already joined → upload directly" branch, finds an
empty ledger, and re-uploads (harmlessly, over identical names). Measured. To reach the seed, the marker
must actually mismatch: leave, let one cycle clear the marker, then join a different event.

### The extension registers against a v2 base (12.6)

`osExtension.enabled: true`, and `POST /os/photokit-ext/processRawValue` returned `result: "completed"`
with the bytes landing. **This settles a risk the design recorded as unasserted**: `assetsd` validates the
registration against `BackgroundUploadURLBase`, and its matching rule (host, origin, or prefix) is
undocumented — so a base moved to v2 with the carrier left on v1 might have registered fine and had every
upload refused, silently. Both moved together and both registration and uploads work. What remains
unmeasured is the *mismatched* case; the `resolve_deployment_test.py` agreement check exists so it cannot
arise.

### The version gate reaches the screen (capability `min-app-version`)

Built the same source declaring `MARKETING_VERSION=0.3` against the same backend requiring `0.4` — a real
old build meeting a newer backend. The device produced:

```json
{"type":"…Layer.UpdateRequired","minimumVersion":"0.4",
 "storeUrl":"https://apps.apple.com/de/app/id6781692480"}
```

and rendered the update screen. Reinstalling the `0.4` build against the same backend returned it to
`Layer.Joined` with the membership and ledger intact — the refusal clears on the next served response.

This was worth doing on hardware specifically because the wiring it proves — the shell's
`onVersionRefused → AppVersionGate → StatusSources.versionRefusal` plus `bakedAppStoreUrl()` — lives in
`:app:ios`, which is **untested by contract** ("shells are wiring only"). No test in the tree could have
caught it being unconnected.

**And looking at the screen caught something no assertion did**: it read **"HOST AN EVENT"** above
"Update SnapSync", because the screen reached for the nearest existing header (`AppEventHeaderHost`),
whose eyebrow is a hardcoded verb. Fixed by extracting `AppIdentityHeader`, which both existing headers
now delegate to — their own docs already said "only the eyebrow differs". ⏰ The device screenshots
predate the follow-up that returned the hero to its headline-plus-detail shape; that change is covered by
`:ui:screens`' offscreen render tests.
