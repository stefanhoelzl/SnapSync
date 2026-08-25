## Context

The joined screen's health is derived by one function in `:ui:presentation`:

```kotlin
// StatusContainerHost.syncHealth
val upload   = arrowOf(shown = progress.synced < progress.total, pulsing = progress.pending > 0)
val download = arrowOf(shown = download.downloaded < download.total, pulsing = download.inFlight > 0)
if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) SyncHealth.InSync else SyncHealth.Syncing(…)
```

It reads three independently-refreshed sources, each of which seeds a placeholder zero and each of
which is a `StateFlow` — a type whose current value always exists:

| source | seed | refreshed by |
|---|---|---|
| `OwnDeviceGalleryStatusSource._size` | `MutableStateFlow(0)` | a PhotoKit enumeration (~6 s on a 224-asset library) |
| `ReadingLedgerCountsSource._counts` | `LedgerCounts.ZERO` | a local SQLite `aggregates()` read |
| `InMemoryDownloadStatusSource._progress` | `DownloadProgress(0, 0)` | a union read + download-store count |

`LedgerBackedSyncStatusSource` `combine`s the first two with permission. `combine` over `StateFlow`s
emits on the first dispatch, because all three already "have a value" — so `SyncStatus.Loading` is one
frame, not a gate, and `Ready(pending = 0, completed = 0, total = 0)` is published before any read.
`synced = min(completed, total) = 0`, `0 < 0` is `false` on **both** arms, and the screen shows a
check mark reading "In sync".

`sync-status/spec.md:27` already says `Loading` is *"never a placeholder, guess, or default"*, and its
"Ledger-backed source" requirement already says `Ready` waits *"once all three have each produced a
first value"*. Both are satisfied vacuously: a seed **is** a value. `gallery-status` states the rule
outright — *"never a placeholder or negative sentinel"* — and then licenses the seed one clause later
(*"`N` remains at its seeded `0`"*). The contract was right; the type could not carry it.

`SNAPSYNC-16`'s dump (build 0.3(605), iPhone XR, iOS 18.7.9) shows the second half of the story. The
member's previous session lasted nine seconds and never read anything, because `Foreground.run()`
awaits `pumpForeground()` before it starts the poll and launches `refreshStatus()`, and the pump awaits
an upload cycle whose discovery walk stays outstanding across suspension:

```
17:53:57.980  → pump.onForeground              app opened
17:54:06.968  === app entering background ===  app closed — a 9-second visit
              (no `gallery: N=` line: refreshStatus never ran)
18:06:44.283  → pump.onForeground              app reopened
18:06:52.387  ← pump.onForeground (774395ms)   the 17:53 call returns, 12.9 min later
18:06:52.440  gallery: N=71                    the first honest total in a 2-hour log
```

## Goals / Non-Goals

**Goals:**

- Make "the count has not been taken" a value the type system carries, so it cannot be read as "there
  is nothing to do".
- Hold `SyncStatus.Loading` until every input the snapshot needs has actually been read.
- Stop sequencing the foreground status refresh behind the upload pump.
- Make the un-counted state reachable in tests — today it is not, which is why nothing caught this.
- Keep a **counted** zero settling the screen exactly as it does now (a download-only membership with
  its imports complete legitimately reads "In sync").

**Non-Goals:**

- Making the honest total arrive *faster*. On a backlogged device the first read still lands 8–10 s
  after foreground. The causes — a discovery walk outstanding across suspension, a cap-truncated upload
  cycle that never advances its cursor — are owned by separate investigations and are out of scope
  here.
- Any change to what is uploaded, downloaded, or recorded. This change alters what the app **reports**.
- A user-visible "could not count your library" state. See D5.
- Scoping `LedgerCounts.completed` to the current membership. The ledger's `aggregates()` counts every
  row in the table regardless of `eventId` provenance or capture window, while the total counts only
  the current admitted set — two populations compared by `completed >= total`. That is a real latent
  hazard, but it is a different defect with no dump behind it yet, and folding it in would widen this
  change into the ledger's retention rules.

## Decisions

### D1 — The un-counted total is unrepresentable, not policed

`GalleryStatusSource.size` becomes `StateFlow<Int?>`. `null` means "never enumerated"; every `Int` is a
count someone took.

*Alternatives rejected.* **(a) A guard in `syncHealth`** (`total == 0 && completed == 0 → Loading`):
cannot distinguish a real empty membership from an unread one, so it either lies about the empty case
or keeps lying about the unread one, and it re-creates the ambiguity for the next reader of the seam.
**(b) Persisting the last-known `N`**: removes the blank frame but shows a stale number, and still
seeds `0` on a first-ever launch — the exact case that matters most, a member who has just joined.
**(c) A companion `hasCounted: StateFlow<Boolean>` beside `size`**: two facts that must agree, which is
the shape this change exists to remove.

### D2 — Both counts, and both arms

`LedgerCounts` gains read-ness; so does `DownloadProgress`. The arrow rule is **conjunctive** — "In
sync" requires *both* arrows hidden — so a single un-read arm is sufficient to carry the whole screen
to a false settled state. Fixing only the upload arm relocates the defect rather than removing it: the
next member to join an event with foreign photos outstanding would hit it through the download arm on
their first launch. `sync-status`'s own download requirement already worries about this shape, in the
words *"with no false 'all downloaded' state"*.

Carried as a flag on the existing data classes (`LedgerCounts.read`, and the equivalent on
`DownloadProgress`) rather than by making every field nullable: the counts are meaningless
individually, so read-ness belongs to the tuple, not to each number.

### D3 — A counted zero still settles

A non-contributing membership carries the deny-everything rule, admits nothing, and publishes `0`
through the **ordinary** admission path — there is no short-circuit branch to special-case (the policy
now arrives complete, one derivation, capability `photo-selection-policy`). That `0` is a **counted**
zero: it publishes `Ready`, hides the upload arrow, and lets a download-only membership read "In sync"
exactly as today. Only the never-refreshed state is `null`. This is the line that keeps D1 from being a
behaviour regression for the memberships that legitimately have nothing to share, and it is pinned by
its own test on both the source and the composed core.

### D4 — The pump joins the fan-out; it does not gate it

`Foreground.run()` today:

```
reloadConfig() → refreshAttestation() → pumpForeground() → statusPoller.start() → coroutineScope { launch … }
                                        ^^^^^^^^^^^^^^^^ awaits a whole upload cycle
```

becomes:

```
reloadConfig() → refreshAttestation() → statusPoller.start() → coroutineScope {
    launch { pumpForeground() }   launch { refreshStatus() }   launch { reconcile() }   launch { … }
}
```

`run()` still awaits every child, so *"a trigger flow never outlives its own run"* holds and the
duration the shell reports to the OS is unchanged — only the internal ordering moves.
`statusPoller.start()` is `scope.launch`-backed and non-blocking, so hoisting it is free; it also
restores the poller's own documented assumption, that *"foreground entry already refreshes the status
sources"*, which has not been true on this path.

`reloadConfig()` stays first: every child reads the config StateFlow it repairs. `refreshAttestation()`
stays second for the reason its comment gives — a fetch must not race the token being minted for it.

*Alternative rejected.* Leaving the flow and fixing the pump instead (returning from `onForeground`
before the drain): the pump's await is what makes the flow's completion report to the OS truthful, and
removing it trades a status bug for a background-execution bug.

### D5 — An enumeration failure is caught, logged, and named — but gets no UI state

`OwnDeviceGalleryStatusSource.refresh` has no `runCatching`, and it runs inside `coroutineScope { launch
{ … } }`, where a throw cancels its siblings. `refreshStatusSources()` wraps it and logs at `Error`
severity.

The consequence is stated rather than hidden: if the walk always fails, `N` stays `null` and the screen
reads the neutral line indefinitely. That is a soft silence, and *"absence is never silent"* would
argue for a third state ("could not count your library"). We are not adding one now — one un-counted
state is honest in both causes, the `Error` log is the channel that says which, and a new user-visible
state needs copy, design, and a story for what the member is supposed to do about it. Revisit if a dump
shows a persistently failing enumeration.

`refreshStatusSources()` also reorders: the cheap local reads (ledger `aggregates()`, download
projection) run **before** the ~6 s walk, so the counted total and counted completed arrive together
rather than the total arriving alone and the screen briefly reporting `0 of N`.

### D6 — The fakes carry the null, and default to it

`InMemoryGalleryStatusSource(state: MutableStateFlow<Int?>)`, secondary constructor defaulting to
`null`. This satisfies `FakeHonestyTest` (the port contract plus a constructor taking initial state) and
buys the thing that actually matters: the un-counted state becomes forgeable. Today
`LedgerBackedSyncStatusSourceTest` constructs its fake over a cell that already holds a count, so no
test in the repository can reach the state every real device is in at launch.

The forge presets and the world inspector pass a real `Int` wherever they mean a count, so their
meaning is unchanged.

## Risks / Trade-offs

- **A nullable port ripples through every consumer (all in-repo).** → The compiler enumerates them;
  there is no reflective or serialized access to this seam. The blast radius is `:domain` `feature/status`,
  `:ui:presentation`, `:adapter:generic:fake`, `:app:desktop`, `:test:world`, `:test:integration`.

- **A member who previously saw an instant "In sync" now sees "Syncing…" first.** → That is the point,
  and it is the only honest reading. The neutral line is existing, designed copy
  (`SyncHealth.Loading` → "Syncing…"), not new UI.

- **On a backlogged device the neutral line persists for 8–10 s after foreground.** → Honest but
  unpleasant. Named as a non-goal; the underlying latency belongs to the cap-truncation and
  suspended-walk investigations. This change does not make it worse — it stops concealing it.

- **A permanently failing enumeration parks the screen at "Syncing…" forever.** → Mitigated by the
  `Error`-severity log (D5), which reaches Bugsink on production builds via `crash-reporting`. Not
  mitigated in the UI, deliberately.

- **`LedgerCounts` read-ness could be mistaken for a third count.** → It is a boolean on the tuple, set
  by exactly one place (a successful read), and the un-read value is a named companion constant rather
  than a constructible state.

- **The reordering in `Foreground.run()` changes concurrency, and concurrency changes are where
  ordering bugs hide.** → The children were already concurrent with each other; only the pump moves
  into the same block. The one new interleaving is "pump runs while `refreshStatus` runs", which is
  already the steady state on the poller path and after every pump cycle's own refresh.

## Migration Plan

None required. No persisted data, no schema, no wire format, and no backend contract changes; the
nullable value exists only in memory and is rebuilt on every launch. A rollback is a straight revert.

## Verification, and one thing left unmeasured

Verified: `./gradlew build` (JVM tests, `:test:architecture`, `detektAppShell`),
`compileIosMainKotlinMetadata` (the iOS source sets), `architectureDiagrams` (the `Foreground`
transcription now shows `pumpForeground()` inside the `par concurrent` block, and a stale diagram fails
the build), and the forge harness driven headlessly — the `in_sync` preset renders the settled
checkmark unchanged, and the `Loading` preset renders the neutral "Syncing…" line a cold launch now
reaches. Every new test was confirmed to **fail against the old behaviour** before being kept.

Run on a real iOS runtime (simulator, iOS 26.x, ad-hoc signed, against the local dev backend): the
app builds, installs, joins, and — the part that matters — **counts the total promptly on a cold
launch**. `=== app process start ===` at 21:50:54.072, then `gallery: N=9 own admitted asset(s) in
66ms` at 21:50:54.802 — **730 ms** later, on the foreground path. `onForeground` itself returned in
**3 ms**, confirming D4's reordering is inert on the OS-driven tier where the pump is a no-op. So the
nullable seed resolves to a real count on a real runtime and wedges nothing.

**The partial-grant total is verified on a real runtime**, which matters because it is the one
behaviour change here that no dump prompted. `applesimutils --setPermissions "photos=limited"` (iOS
14+) puts the app in a genuine partial grant — `permission: LIMITED`, `canChoosePhotos: true` — and the
log then shows:

```
22:13:05.340  → onForeground(mechanism=url_session osSupported=true)   ← LIMITED selects the app-driven tier
22:13:05.581  → pump.onForeground
22:13:05.624  gallery: N=0 own admitted asset(s) in 0ms                 ← the total IS counted
22:13:05.671  selection policy admitted 0 of 0 candidate(s)
22:13:05.726  ← pump.onForeground (145ms)
```

The `gallery: N=` line is the assertion: under the old `GRANTED`-exactly guard no such line could
exist under a partial grant, so `N` would have stayed `null` for the whole session and parked the
screen at its neutral line. `N=0` is correct and is a **counted** zero — a "limited" grant with no
photos selected has an empty scope, and the count over an empty scope is zero, reached in 0 ms with no
library read (`PermissionAwareCandidateSource` resolves LIMITED from the in-memory snapshot).

**Not observable on a simulator: the health frame itself.** App Attest does not exist there
(`App Attest is unavailable in this process — not attesting`), so the joined health is pinned at
`SyncHealth.Unattested`, which by this capability's own precedence outranks every snapshot-derived
value. The `Loading` → `Syncing` transition is therefore masked on that host regardless of what the
projection computes. It is pinned by the integration tests over the composed core instead, each
verified to fail against the old behaviour.

**Measured: D4's reordering shows no PhotoKit contention.** The reordering makes the pump and the
status refresh interleave on the same serial lane, and on the app-driven tier under a **full** grant
both sides walk the library — the cycle's `platform.discoverResources` and the gallery's `N`.
Previously they were strictly sequential. Mutual exclusion is preserved (the lane is serial) and the
ledger read is a single WAL query, so nothing can tear; the open question was cost.

Reproduced by pinning the tier (`POST /device/upload-mechanism?value=…`) under a full grant on an iOS
26.x simulator, 40 in-window assets, two passes on one device and one library:

| pass | pin | cycle walks observed | gallery walk `N=40` |
|---|---|---|---|
| **A** baseline | `idle` | **0** | 31 ms, 21 ms |
| **B** concurrent | `url_session` | **34** (`discoverResources = 40 candidate(s) (25ms)`) | 16 ms, 22 ms |

The gallery walk is not slower while cycles are walking (16/22 ms against 21/31 ms alone) — the
difference is noise in the baseline's favour. No contention at this scale.

**What this does not establish.** 40 assets is ~5× smaller than `SNAPSYNC-16`'s 224, and simulator
PhotoKit is far faster per asset than device PhotoKit (~0.6 ms/asset here against ~27 ms/asset implied
by that dump's ~6 s awake walk). So this rules out a gross contention effect in the interleaved
configuration; it does not certify device behaviour at a real library's scale. Re-measure on an
iOS 18–26.0 device if the foreground ever looks slow after this ships.

**Two dev-infrastructure defects found while measuring** (neither is shipped behaviour, both are worth
their own fix):

1. `SnapSyncRoot.foregroundParams()` builds its `mechanism=` log line with
   `resolveUploadMechanism(osSupported, permission)` — **omitting the `override` argument** — so a
   pinned build logs the *un-pinned* resolution, in the one place an operator looks to confirm a pin
   took. Its KDoc asserts the opposite ("it pins what these two values already report").
2. A pin is not a **transition**, and `UploadArm` reads the resolution "fresh at every transition" — so
   pinning alone never re-resolves. A transition must follow the pin (`/user/reconfigure` serves).
   Together these made a first attempt at this measurement look like it had run when it had not.

## Open Questions

- Should a persistently failing enumeration eventually get its own status line? Deferred (D5) until a
  dump shows one.
- `LedgerCounts.completed` is ledger-global while `total` is membership-scoped, so `completed >= total`
  compares two populations that only coincide by accident. Flagged as a non-goal above; worth its own
  change if a dump ever shows a device settling on stale historical rows.
