## MODIFIED Requirements

### Requirement: Scheduled runner with direct storage access

The sweep SHALL run as a **scheduled GitHub Actions job** (a nightly cron plus `workflow_dispatch`),
never inside the Edge Script — the platform has no scheduler, and its per-request 50-subrequest / 30 s
CPU caps make a whole-storage sweep impossible in-edge. The job SHALL operate on Bunny storage
**directly**, authenticating with the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`) supplied as
a **GitHub Actions secret scoped to this workflow** — never the Bunny account API key, and never a
credential granted to the deploy workflow.

The storage-zone `AccessKey` SHALL be the sweep's **only** credential. The sweep SHALL make **no request
to the Edge Script** and SHALL hold no key authorizing one: it reads and deletes storage, and does
nothing else. This is what lets a cleanup job hold no credential against the live device-facing API.

The sweep SHALL import the backend's shared storage helpers, lifecycle classifier, and config constants
(the same modules the Edge Script uses) rather than reimplement layout or lifecycle rules. All storage
reads the sweep's correctness depends on SHALL be served from the storage **main region** (the
`BUNNY_STORAGE_HOST` deployment invariant), so a concurrently-written manifest is visible and the sweep
never deletes data out from under an active device.

#### Scenario: The sweep runs on a nightly schedule

- **WHEN** the scheduled time arrives
- **THEN** the GitHub Actions job runs the two-phase sweep against the configured storage zone

#### Scenario: The sweep is manually dispatchable

- **WHEN** an operator dispatches the workflow
- **THEN** it runs the same sweep on demand

#### Scenario: The sweep uses the zone AccessKey, not the account key

- **WHEN** the sweep performs any storage read or delete
- **THEN** the request carries the storage-zone `AccessKey` and never the Bunny account API key

#### Scenario: The sweep never calls the Edge Script

- **WHEN** a full sweep run completes
- **THEN** it has made no request to the device-facing API, and its configuration carries no credential
  that would authorize one

#### Scenario: The sweep shares the Edge Script's lifecycle and layout logic

- **WHEN** the sweep classifies an event or resolves membership
- **THEN** it uses the same imported modules the Edge Script uses, so no second copy of the rules can drift

### Requirement: Stale-event deletion

The event phase SHALL delete every **stale event**, and SHALL leave every other event untouched. An event
is stale when **any** of the following holds:

- **past its deadline** — `now` is later than the marker's derived delete-by
  (`max(createdAt, startsAt) + lifetimeSeconds`, per capability `event-limits`; a marker carrying no
  `lifetimeSeconds` derives it from the configured lifetime constant);
- **empty** — the event's `events/<eventId>/devices/` listing carries **at least one** manifest object
  and **no device resolves to `active`** (every enrolled device's winning manifest is its departed
  `.left.json` sibling, per capability `device-manifest`);
- **incomplete** — the marker is missing or carries no `startsAt`, `endsAt`, or `capacity`, or a field
  that cannot be parsed.

An event whose `devices/` listing is **empty of manifest objects entirely** SHALL NOT be treated as empty:
it has been minted but never joined (`POST /events` always produces a zero-device event, because the
creator confirms through the same join gate a scanned QR uses), and it SHALL survive until its deadline
like any other event.

Emptiness is **opportunistic reclamation, not a guarantee**. A leave whose backend notify never lands
leaves an active manifest behind, so an abandoned event may never empty; the deadline is the only bound
that always holds, and no requirement, client behavior, or user-facing statement SHALL be written as if
emptiness were assured.

For each stale event the sweep SHALL delete its `metadata.json` marker and every `<deviceId>.json` /
`<deviceId>.left.json` manifest under `events/<eventId>/devices/`. The sweep SHALL NOT notify the event's
members before deleting it. Deletion SHALL be idempotent — a re-run over an already-deleted event is a
harmless no-op.

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

#### Scenario: Deletion sends no notification

- **WHEN** the sweep deletes any stale event
- **THEN** it dispatches no push and makes no notify request; members discover the deletion on their own
  next foreground details fetch (capability `leave-event`)
