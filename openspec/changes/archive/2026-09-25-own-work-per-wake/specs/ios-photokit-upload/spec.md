## MODIFIED Requirements

### Requirement: In-extension discovery by full enumeration

On each `process()` invocation, the extension SHALL discover work itself (the system does not enumerate)
by **enumerating the library** through the shared `UploadDiscovery` binding (`IosDiscovery`):
`PHAsset.fetchAssets` narrowed by the membership's selection policy (capability `photo-selection-policy`).
There SHALL be **no** persisted discovery cursor: no change token is archived, stored, loaded or cleared,
and no `fetchPersistentChanges(since:)` walk is made. Every cycle's walk is complete in itself, so a
short-lived wake needs nothing from the previous one.

The extension SHALL walk **afresh** on every `process()` call and SHALL NOT use the app process's walk memo
(capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk"): it holds no walk, no
candidate set and no library change token across `process()` calls, in memory or on disk. The memo buys
its saving by holding the last walk's candidates in memory, and the extension's memory limit is **32 MB**
(see "How the operating system invokes the extension is recorded as measured") — a cycle leaves about
12 MB of headroom, and exceeding the limit is a jetsam kill the system answers with a relaunch loop, not
an error. Its walk is also not the cost the memo removes: the extension's whole `process()` measured
0.6–1.4 s (decision record: `changes/own-work-per-wake`, D9).

The cursor was an efficiency optimization only, and its own contract said so: a cold start with no stored
token re-enumerated the whole library, which the ledger made harmless. It cost a durable App-Group key, a
port with its iOS store and fake, a clear effect threaded through every re-baselining caller, and an
absence protocol that existed only because a change feed reports a deletion once, as an event. A full
enumeration reports what **is**, so presence is recomputed every cycle (capability `sync-ledger`,
"Deletion is a presence diff over an authoritative walk").

A walk yields exactly two kinds of fact that exist nowhere else, both recorded in the ledger before any
upload job is created:

- every admitted resource the engine judged to be new work, recorded `DISCOVERED` in one batch write
  (capability `sync-ledger`);
- the manifest detail of every already-recorded row still lacking it, backfilled.

It also yields a **deletion**, when it is authoritative: the in-window rows of assets it did not return are
deleted before those facts are recorded. The walk reads resources only for assets the ledger does not fully
know (capability `sync-ledger`, "A walk re-reads only the assets the ledger does not fully know"), so its
cost follows the photos that are new or unenriched, not the size of the member's in-window library.

The discovery SHALL report `fullEnumeration` when the library was readable, and SHALL NOT report it when
the read reported the library not readable; in that case it SHALL return no candidates. A cycle over an
unreadable library then costs an idle pass: nothing is recorded and nothing is deleted.

#### Scenario: Every cycle enumerates the in-scope library
- **WHEN** `process()` runs
- **THEN** the extension enumerates the library narrowed by the membership's policy, and reads no persisted
  change token

#### Scenario: The extension does not reuse a previous walk
- **WHEN** `process()` runs again while the photo library is unchanged since the previous call's walk
- **THEN** the extension enumerates the library again, and no memoized walk, candidate set or change token
  was held from the previous call

#### Scenario: A restart needs no stored state from the previous walk
- **WHEN** the extension process is torn down after a cycle and later re-invoked
- **THEN** it enumerates the in-scope library again, and the ledger answers "already uploaded" for keys
  already recorded, so no duplicate job is created

#### Scenario: A cap-truncated cycle loses no discovered work
- **WHEN** a cycle records `DISCOVERED` rows for every admitted new-work resource and then stops creating
  jobs because `creationRequestForJob` raised `PHPhotosErrorLimitExceeded`
- **THEN** the next wake resumes the un-created remainder from the ledger

#### Scenario: An unreadable library costs an idle pass
- **WHEN** the library read reports the library not readable
- **THEN** the discovery returns no candidates and does not report `fullEnumeration`, so the cycle records
  nothing and deletes nothing

### Requirement: How the operating system invokes the extension is recorded as measured

The capability SHALL record, with its evidence and an expiry trigger (re-measure at the next iOS major), how
the operating system invokes the upload extension, because no clause can observe it — a clause runs only
inside a call — and every scheduling decision in this tier rests on it. Measured on an SE2, iOS 26.6,
2026-09-23:

- a `process()` call runs for about **60 s** and is then killed, **without** `notifyTermination`;
- `notifyTermination` arrives about 55 ms after every **normal** return, so it marks the end of a cycle, not a
  kill, and SHALL NOT be reported as one;
- after `process()` returns `PROCESSING`, the next call comes **5 min** later;
- after a killed call the system backs off (the next calls came about 6 and then 11 min later), and neither a
  library change nor a re-registration triggers a call meanwhile;
- outside a backoff, enabling the registration triggers a call within about 1 s and a new photo within about
  3 s; a job finishing triggers none;
- a job the extension creates inside `process()` is uploaded only after that call returns; when the call returns
  `PROCESSING` after creating jobs, the next call comes within about a second, with the uploads done in between
  (the five-minute cadence above was measured after calls that created nothing);
- a retry-spent job is presented with no `resource`;
- job states settle within 0.1–5 s of creation or retry; a failed job is presented in **both** the retry and
  the acknowledge set with no error, a retry-spent one in the acknowledge set only, and acknowledging a job
  removes it from both.

How a call **ends** was measured further on the same device (SE2, iOS 26.6, 2026-09-24; decision record
`changes/own-work-per-wake`, D8):

- the 60 s end is **assetsd's own timer**, **60.0 s** from the call's start, and it ends the process with
  `SIGKILL`: no signal, callback or notice of any kind reaches the process first;
- `notifyTermination` follows **only** normal returns — it never precedes or announces the kill;
- `ProcessInfo.performExpiringActivity`'s expiry callback (`expired == true`) **never fires**: the activity's
  assertion is created inactive, because assetsd's assertion, not the extension's, defines the process's
  lifetime;
- the extension's memory limit is **32 MB** (enforced by runningboardd); a cycle wrapped in extra machinery
  exceeded it, was jetsam-killed, and the system relaunched it into a kill loop.

The extension SHALL therefore build **no cooperative stop**: no expiry handler, no stop flag, and no
self-chosen deadline, because no signal exists to drive one, and a budget measured by the extension's own
clock is a clock of ours — the thing this capability's host app no longer keeps anywhere (capability
`ios-app-shell`). The same holds for any of its work: the extension SHALL NOT bound a unit of its work with a
timeout of its own choosing. That includes the device-manifest publish, whose former 12 s bound
(`deviceManifestTimeoutMs`) lived in the **shared** `UploadCycle` and so bounded the app's uploader too; it is
removed from the cycle, for **both** tiers (capability `ios-app-shell`, "Time is up is learned only from the
operating system", which states the rule for the app process). The per-request HTTP timeout still bounds each
request the publish makes — it is a property of one request, not a deadline on the work. What makes this safe is the
shape of the work, not a stop: a `process()` call measured 0.6–1.4 s of work, far inside 60 s, every unit
is a safe retry (ledger writes are idempotent upserts, a job not yet created is found in the ledger's
`DISCOVERED` rows), and the next invocation continues where a killed one stopped. Nor SHALL any code or
requirement rely on `notifyTermination` or `performExpiringActivity` as a warning of the end.

#### Scenario: A cycle returns normally

- **WHEN** `process()` returns and the system then calls `notifyTermination`
- **THEN** the extension records the end of a cycle at `Info`, and does not report a termination

#### Scenario: A cycle overruns the budget

- **WHEN** a `process()` call runs past about 60 s
- **THEN** the process is killed with no notice, and the next call comes only after a backoff, so work that
  must finish in one call is sized well inside the budget

#### Scenario: No warning precedes the kill

- **WHEN** a `process()` call reaches assetsd's 60.0 s timer while an expiring activity is open
- **THEN** the process receives `SIGKILL` with no `notifyTermination` and no `expired == true` callback
  first, so no code path can run on the way out

#### Scenario: The extension keeps no clock of its own

- **WHEN** the extension's cycle publishes the device manifest or runs any other unit of its work
- **THEN** the unit is not bounded by a timeout the extension or the shared cycle chose (only the
  per-request HTTP timeout bounds each request); it ends when it completes, fails, or the process is
  killed, and a killed unit is retried by the next invocation

#### Scenario: Exceeding the memory limit kills the extension

- **WHEN** the extension's resident memory exceeds 32 MB during a `process()` call
- **THEN** the process is jetsam-killed, with no error returned to the call, and the system may relaunch it
  repeatedly
