# Backend-derived upload state — probe findings

**Date:** 2026-08-25 · **Device:** iPhone SE2 (`00008030-0018703A1A7A402E`), **iOS 26.5.2** ·
**Mac:** macos-26 runner, Xcode 26.6 · **Kotlin/Native:** 2.4.0 klibs ·
**Database:** a throwaway Bunny Database (public preview), reached over the internet from a laptop

Four independent probes behind the decision to move the backend's relational state into a database.
Each section states what was measured, what it settles, and **what would invalidate it**.

Two probes were run against a **test** database created for this purpose and deleted afterwards; the
iOS probes ran against the local backend rig (`deno task dev:tunnel`) over a cloudflared quick tunnel.

> ⏱️ **Every database timing below includes laptop→Bunny round-trip.** Median `SELECT 1` was
> **104 ms** (samples: 285, 125, 46, 104, 42). An Edge Script sits in-region — an earlier in-script
> spike measured ~12–13 ms warm — so treat absolute numbers as an upper bound and compare only
> ratios. The row counts and verdicts are unaffected.

---

## 1. bunny native Storage honours `Range` — existence costs one byte

`GET` with `Range: bytes=0-0` against the storage API, `AccessKey` header:

```
   171 B JSON     → 206 Partial Content   Content-Length: 1   Content-Range: bytes 0-0/171
   31.9 MB .mov   → 206 Partial Content   Content-Length: 1   Content-Range: bytes 0-0/31905340
   absent key     → 404
   control (no Range) → 200               Accept-Ranges: bytes
```

**Settles:** an existence check against the authoritative store costs **1 byte and 1 RTT**, works
identically on large binaries, and 404s cleanly on absence. `Content-Range` returns the object's
**total size** for free, so size is recoverable per-object after the fact — at one subrequest each,
which is far worse than counting at upload time but is a real fallback.

**Does not settle:** whether `Range` is honoured through the CDN pull zone (this was measured at the
storage API directly, which is where the edge talks to it).

**Expiry trigger:** a bunny Storage API change. `Accept-Ranges: bytes` is advertised, so a silent
regression would show as a `200` with a full `Content-Length`.

---

## 2. ⛔ iOS does not stop sending when the server answers early

The question: can the backend short-circuit an upload whose bytes it already holds, and save the
device's radio time? Measured on device against the local rig, with `pymobiledevice3 pcap` capturing
what the phone actually transmitted, filtered to the tunnel's IP.

The seeded library's flat-colour assets encode to ~51 KB regardless of pixel dimensions, which is
too small to measure anything about bytes on the wire — a `NOISE` seed kind was added to `:test:rig`
to produce incompressible assets (686 KB, then 9.2 MB).

**Instrument calibration** — 10 control uploads of 686 KB, expected payload 6.86 MB:

```
   measured outbound 6.98 MB   → 1.7 % over payload (TCP/IP headers). pcapd is not dropping packets.
```

**Results** (outbound bytes, device → origin):

| payload | arm | outbound | PUTs | verdict |
|---|---|---|---|---|
| 686 KB × 10 | control (stored) | 6.98 MB | 10 | reference (payload 6.86 MB) |
| 686 KB × 10 | early `201`, body unread | 6.87 MB | 10 | **sends everything** |
| 9.2 MB × 3 | control (stored) | 27.69 MB | 3 | reference (payload 26.35 MB) |
| 9.2 MB × 3 | early `201`, body unread | 33.21 MB | 4 | **sends everything, plus a re-send** |
| 9.2 MB × 3 | early `409` + marker header | 32.19 MB | 3 | **sends everything** |

At 9.2 MB each asset takes 4.3–6.1 s to transfer (server-observed body arrival) and the early
response lands within milliseconds, so the device had ~99 % of the window in which to stop. It did
not — on either status class.

**Settles:**
- iOS **accepts** a `201` sent before the body is read and reports the upload **successful**, on both
  tiers — OS-driven PhotoKit and app-driven background `URLSession`. Across the session: **167
  objects stored against 231 ledger rows `COMPLETED`** — 64 assets the device believed it uploaded
  did not exist anywhere.
- iOS **does not stop transmitting** for either a success or an error response. An early return
  saves the edge→bunny store, and **nothing** on the device's radio.

**Does not settle:** behaviour through bunny's pull zone rather than cloudflared. Note this can
*never* be measured from the origin — a proxy terminates the device's connection in the rig and in
production alike, so any origin-side byte count reflects what the proxy forwarded. Only device-side
capture can answer it, which is what was done here.

**Expiry trigger:** the next iOS major. n=1 device, one point release.

---

## 3. `PHAssetResourceUploadJob` carries response headers but no status code

From `klib dump-metadata` (Kotlin/Native 2.4.0, `ios_arm64`), the declared surface is exactly:

```
   resource · destination · responseHeaderFields · state · type · error
```

**No `statusCode`.** So the OS-driven tier cannot see an upload's HTTP status; it sees a five-case
state enum and an `NSError`.

`responseHeaderFields` **is** populated on a **failed** job — verified end-to-end on device, a custom
header crossing the backend, the pull zone and PhotoKit into Kotlin:

```
   [spike] state=3 error=null headers={cf-ray=…, cf-cache-status=DYNAMIC,
                                       x-snapsync-already-stored=1, content-length=0, …}
```

**Settles:** a marker header is the only channel by which the backend can say anything to the
OS-driven tier beyond success/failure. It works — but per §2 it has nothing left to unlock, since no
status class saves bandwidth.

**Expiry trigger:** a Kotlin version bump changes the declared set (the klib tracks the SDK
Kotlin/Native was built against, not the iOS version on the phone).

---

## 4. Bunny Database behaviour

Against a throwaway database, using the proposed schema at realistic scale (1 event, 10 devices,
2 000 assets each → 20 000 `event_assets`, 30 000 `resources`).

### 4.1 Constraints are enforced by default

| | result |
|---|---|
| `PRAGMA foreign_keys` default | **`1` — ON**, unlike stock SQLite |
| FK violation on a bare `execute()` | rejected (`SQLITE_CONSTRAINT`), no pragma needed |
| FK violation inside `batch()` | rejected |
| pragma value in the *next* request | still `1` |
| `ON DELETE CASCADE`, two levels | events → memberships → event_assets, all removed, 299 ms |
| `batch()` atomicity | rolls back on duplicate-PK **and** on FK violation |
| interactive `transaction()` (baton) | works, 108 ms, alive after 7 s idle |

**Settles:** foreign keys can be relied on without a per-connection pragma, and `batch()` — one HTTP
request — is atomic, so it is preferable to an interactive transaction on latency alone.

**Expiry trigger:** a provisioning change that turns `foreign_keys` off would disable every
constraint **silently**. If the design depends on constraints, it should assert the pragma's value at
startup rather than trust this measurement. The docs also gate baton sessions behind "contact us",
yet they worked — that permission could be withdrawn.

### 4.2 Consistency — no staleness observed

Write followed immediately by a read: **10/10 saw their own write**. A separate **read-only** token
also saw a fresh write immediately.

**Does not settle** — and this is the important caveat. This was a laptop against a *test* database,
not an Edge Script against the production one, and replica routing is exactly the kind of thing that
differs. `config.ts` already records the same hazard for storage: *"a stale replica read is the one
failure mode that would delete live data."* If the sweep is ever allowed to delete on the database's
word, this must be re-confirmed **from the edge**.

### 4.3 Scale

```
   populate (multi-row INSERT)          2 840 rows/s
   capacity count                          33 ms
   empty-event test                        44 ms
   union, full event (30 000 rows)    373–541 ms   ← join is 61 ms; the rest is row transfer
   GC root set (30 000 keys)              227 ms
   full-state replace (2 000 assets)      460 ms
   database size                          12.3 MB  → ~1.6 M assets before the 1 GB ceiling
   8 concurrent write batches               8/8, 64 ms
   bound parameter limit                   32 766  (40 000 fails: "too many SQL variables")
```

The union's plan uses the indices as intended (`sqlite_autoindex_event_assets_1`, then
`resources_by_asset` twice). Its cost is dominated by **transferring** rows, not by the query —
a count-only variant of the same join is 61 ms.

Documented limits: **1 GB per database**, 50 databases per account, both "raised upon request", and
the service is in **public preview**. Durability: writes acknowledged on the primary's WAL, with a
**10-second maximum data-loss window** on primary failover.

### 4.4 The capacity gate can be made exact

`event-limits` currently states: *"the count is read-then-write without coordination (bunny has no
compare-and-set): concurrent first enrollments may transiently overshoot, accepted."* That premise
does not survive the move.

10 devices racing for 3 slots:

```
   naive read-then-write             → 10 enrolled   ← reproduces today's overshoot
   inside interactive transactions   →  3 enrolled   (918 ms)
   single conditional INSERT…SELECT  →  3 enrolled   (158 ms)
```

The **full production rule** works as one statement — known device always passes, new device refused
at capacity, leaving frees no slot:

```
   10 new devices racing, capacity 3   →  exactly 3
   new device after a departure        →  REFUSED
   departed device rejoins             →  reuses its slot, total stays 3
   active device re-enrols             →  idempotent (rowsAffected=1)
```

**Settles:** the overshoot caveat can be deleted rather than carried forward, with no transaction and
no read-then-write.

### 4.5 Two schema corrections

**`TEXT PRIMARY KEY` accepts NULL.** Only `INTEGER PRIMARY KEY` implies `NOT NULL` in SQLite —
measured: an explicit `INSERT … VALUES (NULL)` into a `TEXT PRIMARY KEY` column succeeded, while the
same column declared `PRIMARY KEY NOT NULL` rejected it. Every text primary key in the schema needs
an explicit `NOT NULL`, or a stray `undefined` inserts a NULL-keyed row instead of failing.

**`rowsAffected = 0` conflates two answers.** The conditional-insert capacity rule returns 0 both
when the event is **at capacity** (→ `409`) and when the event **does not exist** (→ `404`), because
the capacity subquery yields NULL for a missing event and the `WHERE` is then false. Distinguishing
them has to be deliberate — this is the absence-collapse the module rules legislate against
("absence is never silent"), reintroduced by a SQL idiom rather than by a `catch`.

## 5. The DEPLOYED store's capabilities (2026-08-25)

Measured against the **production** bunny Database from a workstation via `proton-env`, using a throwaway
probe that creates and drops run-id-tagged scratch tables (no production row touched):

```
   PRAGMA foreign_keys (default)              1 — ON
   a dangling reference is rejected           yes
   ON DELETE CASCADE removes the child        yes
   CREATE TABLE … STRICT                      accepted
   STRICT rejects a wrong-typed value         yes
   batch() rolls back on a failed statement   yes
```

**Settles:** every constraint the schema rests on holds on the real store, not merely on the test database
§4 measured — foreign keys, two-level cascade, and batch atomicity. And **`STRICT` is adopted**: it was
withheld until a run showed not just that the keyword parses but that it ENFORCES, because a syntax an
engine accepts and ignores is worse than not using it. Both engines the schema meets — bunny Database and
the `node:sqlite` behind the tests and the local rig — reject a wrong-typed value.

**Expiry trigger:** re-run after any bunny Database platform announcement; it is in public preview. The
boot probe asserts `foreign_keys` on every deploy regardless, because a provisioning change that turned
enforcement off would disable every constraint **silently**.

---

## Incident during this work

A probe deployed by hand to the live Edge Script took the production backend down for ~12 minutes,
and the first rollback did not fix it.

**Cause:** `api/src/deployment.ts` is **generated and gitignored**. `deno task dev:tunnel` runs
`config:local`, which had rewritten it for the `local` deployment. A bundle built from an apparently
clean tree therefore carried local config; `readConfig` threw at module scope and every route
answered `400`. The rollback bundle was built from the same poisoned source and failed identically.

**Lessons, both already written down elsewhere and both ignored:**

1. `git status` cannot see a gitignored generated file. Verify a **built artifact** by grepping it
   for the expected deployment values — that is what eventually diagnosed this.
2. `POST /code` + `/publish` return `204` whether or not the bundle can boot. `api-deploy.yml`'s
   header says exactly this, which is why `3a93f5f4` added the post-publish probe. A hand-deploy
   bypasses that probe and has no other check.

A hand-built bundle also leaves `/health` reporting `{"sha":"dev"}`, since the CI stamp step never
ran — which is a useful tell that a hand-deploy is live.
