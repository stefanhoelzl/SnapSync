## ADDED Requirements

### Requirement: Tombstone reclamation

The event phase SHALL reclaim every **tombstone** — an event directory `events/<eventId>/` carrying **no
marker object AND no manifest object** — with a **single recursive delete** of `events/<eventId>/`, which
removes the nested empty `devices/` directory in the same operation. Reclamation SHALL be idempotent and
`404`-tolerant, so a re-run over an already-reclaimed directory is a harmless no-op.

A tombstone is the husk of an event the sweep has already deleted: bunny retains a directory after its last
object is removed, so every deleted event leaves one behind, and a tombstone is indistinguishable from a
live event until its marker is read.

A tombstone SHALL NOT be treated as a deleted event, and SHALL NOT join the surviving-event set the asset
phase evaluates bytes and retention floors against.

The recursive delete SHALL be applied **only** to a tombstone. An event directory that still holds any
manifest object SHALL be deleted object-by-object — manifests first, the marker **last** — so an interrupted
run leaves a still-existing event that the next run reclaims cleanly.

Reclaiming a tombstone is race-free, and only because its marker is absent: the manifest-write and leave
routes both gate on event existence (capabilities `device-manifest`, `leave-event`), so a marker-absent
directory authorizes no write and nothing can appear inside it between classification and deletion.

The sweep SHALL NOT reclaim a device byte partition `files/devices/<deviceId>/`, even when every byte in it
has been collected and the device is an active member of no surviving event. The byte upload is **ungated**
by design (capability `bunny-upload-endpoint`) — it reads no marker, because bytes are device-partitioned
and event-independent — so a recursive directory delete could destroy an upload that landed after the
listing and was therefore never seen. That photo is recorded as uploaded in the device's own ledger
(capability `sync-ledger`) and would never be sent again. The husk is retained deliberately: it costs one
listing per run and distorts no count.

#### Scenario: A swept event's directory is reclaimed

- **WHEN** the event phase enumerates an event directory whose marker is absent and whose `devices/` listing
  holds no manifest object
- **THEN** the sweep deletes `events/<eventId>/` recursively, removing the directory and its empty
  `devices/` child

#### Scenario: A marker-less directory holding manifests is not a tombstone

- **WHEN** an event directory's marker is absent but its `devices/` listing still holds at least one
  manifest object
- **THEN** the sweep treats it as **incomplete**, deletes its manifests object-by-object and its marker
  last, and applies no recursive delete

#### Scenario: A reclaimed tombstone contributes nothing to the asset phase

- **WHEN** the event phase reclaims a tombstone
- **THEN** that directory contributes no referenced byte keys and no retention floor, exactly as a deleted
  event does

#### Scenario: Reclamation is idempotent

- **WHEN** a run reclaims a tombstone that a prior interrupted run had already removed
- **THEN** the delete is `404`-tolerant and the run continues without recording an error

#### Scenario: A device byte partition is never reclaimed

- **WHEN** a device is an active member of no surviving event and every byte under
  `files/devices/<deviceId>/` has been collected
- **THEN** the sweep deletes the byte objects but leaves the directory in place, issuing no directory delete
  against the byte partition

## MODIFIED Requirements

### Requirement: Stale-event deletion

The event phase SHALL delete every **stale event**, and SHALL leave every other event untouched. An event
is stale when **any** of the following holds:

- **past its deadline** — `now` is later than the marker's derived delete-by
  (`max(createdAt, startsAt) + lifetimeSeconds`, per capability `event-limits`; a marker carrying no
  `lifetimeSeconds` derives it from the configured lifetime constant);
- **empty** — the event's `events/<eventId>/devices/` listing carries **at least one** manifest object
  and **no device resolves to `active`** (every enrolled device's winning manifest is its departed
  `.left.json` sibling, per capability `device-manifest`);
- **incomplete** — the marker is missing **while at least one manifest object remains**, or the marker
  carries no `startsAt`, `endsAt`, or `capacity`, or a field that cannot be parsed.

A missing marker is therefore **not** on its own a stale event. A directory with no marker **and** no
manifest object is a **tombstone**, reclaimed under *Tombstone reclamation* and counted as no event at all;
only a marker-less directory that still holds manifests is the **incomplete** case deleted here.

An event whose `devices/` listing is **empty of manifest objects entirely** SHALL NOT be treated as empty:
it has been minted but never joined (`POST /events` always produces a zero-device event, because the
creator confirms through the same join gate a scanned QR uses), and it SHALL survive until its deadline
like any other event.

Emptiness is **opportunistic reclamation, not a guarantee**. A leave whose backend notify never lands
leaves an active manifest behind, so an abandoned event may never empty; the deadline is the only bound
that always holds, and no requirement, client behavior, or user-facing statement SHALL be written as if
emptiness were assured.

For each stale event the sweep SHALL delete its `metadata.json` marker and every `<deviceId>.json` /
`<deviceId>.left.json` manifest under `events/<eventId>/devices/`, manifests first and the marker **last**.
The sweep SHALL NOT notify the event's members before deleting it. Deletion SHALL be idempotent — a re-run
over an already-deleted event is a harmless no-op.

#### Scenario: An event past its deadline is deleted

- **WHEN** an event's marker gives a derived delete-by earlier than `now`
- **THEN** the sweep deletes its marker and all its manifests

#### Scenario: An emptied event is deleted

- **WHEN** an event's `devices/` listing carries manifest objects and every device resolves to
  `departed`
- **THEN** the sweep deletes its marker and all its manifests, even though its deadline has not passed

#### Scenario: An event with one active member is left intact

- **WHEN** an event within its deadline has at least one device resolving to `active`
- **THEN** the sweep deletes nothing for that event

#### Scenario: A minted-but-never-joined event is not empty

- **WHEN** an event within its deadline has no manifest objects at all under `devices/`
- **THEN** the sweep deletes nothing for that event, and it survives until its deadline

#### Scenario: An incomplete marker is deleted

- **WHEN** the sweep reads a marker that carries no `startsAt`, `endsAt`, or `capacity`
- **THEN** the event is treated as stale and deleted exactly as an expired one

#### Scenario: A missing marker with manifests remaining is incomplete, not a tombstone

- **WHEN** an event directory has no marker object but its `devices/` listing still holds a manifest object
- **THEN** it is stale as **incomplete**, its manifests are deleted object-by-object, and it is counted as a
  deleted event

#### Scenario: Deletion sends no notification

- **WHEN** the sweep deletes any stale event
- **THEN** it dispatches no push and makes no notify request; members discover the deletion on their own
  next foreground details fetch (capability `leave-event`)

### Requirement: Dry-run, best-effort, and a run summary

The sweep SHALL support a **dry-run** mode that logs every event, **tombstone**, and asset it *would* delete
and deletes nothing. A real run SHALL delete **best-effort** per object — a single delete failure SHALL be
logged and SHALL NOT abort the run (deletes are idempotent and `404`-tolerant) — and SHALL emit a
**summary** over three tiers, each reporting the count **deleted** and the count **kept**: **events**,
**devices** (a device counted once regardless of how many of its global config/attestation records exist),
and **files** (byte objects) — with the file tallies additionally carrying the **total byte size** of each,
so the summary reports storage actually reclaimed and not merely an object count.

The **events** tier SHALL count **events**: a reclaimed tombstone is not an event and SHALL appear in
neither the deleted nor the kept count. The two event counts therefore do **not** partition the directories
enumerated under `events/`, and no consumer of the summary SHALL assume they do. A tombstone SHALL contribute
no tier of its own — its reclamation is self-extinguishing, so an events-deleted count that reports only
real events is the signal that it happened.

The summary SHALL also carry a single **error** count (the number of per-object failures tolerated during
the run). When the sweep runs on a GitHub Actions runner (the `GITHUB_STEP_SUMMARY` environment variable is
present), it SHALL additionally render the summary to the job's **Summary** panel; off-CI (the variable
absent) this is a no-op. The run SHALL exit non-zero only on a **systemic** failure (an authentication
failure or an inability to list storage at all), so a stuck sweep is visible while transient per-object
errors are not treated as failures.

#### Scenario: Dry-run deletes nothing

- **WHEN** the sweep runs in dry-run mode
- **THEN** it logs the events, tombstones, and assets it would delete and performs no delete

#### Scenario: Dry-run names each tombstone it would reclaim

- **WHEN** the sweep runs in dry-run mode over a zone holding tombstones
- **THEN** each one is logged individually, so the directories a real run would remove can be inspected
  before any deletion

#### Scenario: A reclaimed tombstone is counted in neither event tier

- **WHEN** a real run reclaims a tombstone
- **THEN** the summary's events-deleted and events-kept counts both exclude it, and the run adds no other
  tier for it

#### Scenario: The summary reports deleted and kept across the three tiers

- **WHEN** a run completes
- **THEN** its summary gives, for events, devices, and files, both the count deleted and the count kept,
  and for files the total byte size of each — plus a single tolerated-error count

#### Scenario: The summary renders to the CI job summary panel

- **WHEN** the sweep runs on a GitHub Actions runner
- **THEN** it also writes the summary to the job's Summary panel; run off-CI, it writes no such panel

#### Scenario: A per-object delete failure does not abort the run

- **WHEN** one object delete fails during a real run
- **THEN** the failure is logged, counted as an error, the sweep continues, and the run still completes
  with its summary

#### Scenario: A systemic failure exits non-zero

- **WHEN** the sweep cannot authenticate or cannot list storage at all
- **THEN** the run exits non-zero
