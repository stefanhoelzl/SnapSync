## Context

`both-uploaders-active` let the app and the ≥26.1 extension both run upload cycles over one App-Group ledger. Each
cycle projects the device manifest (`DeviceManifestProducer`) and PUTs it to
`/api/v2/events/:e/devices/:d/manifest`. The route replaces the whole asset set, so the last write wins. A
skip-if-unchanged marker (`eventId json`, one shared App-Group file, `IosDeviceManifestStore`) suppresses a publish
whose projection has not changed since the last *successful* one.

Verified against the tree, the race is narrower than "the last PUT wins". The process whose older PUT lands last
also saves its marker, so in the common order marker and server agree on the older snapshot, and the next cycle
republishes the newer one. It sticks only when:
1. **the orders cross**: the server commits A (older) after B, but the device saves B's marker after A's; or
2. **a response is lost**: A's PUT commits but its response never arrives, so A saves no marker, and B's newer marker
   stands over A's older server state.

Either way the marker names the current projection while the server holds an older one. Every cycle then skips
until the manifest next changes, and after the event's last change nothing ever will. `device-manifest` currently
accepts this as "bounded and self-healing".

The manifest is a function of **two** inputs: the ledger rows, and the selection policy built from the config at
the start of the cycle. The policy's bounds (`direction`, `minPhotoDate`, `maxPhotoDate`) change only through
`ReconfigureEvent`'s save. `MembershipRefresh` rewrites only `name`, and `endsAt`/`deletesAt` when they are absent;
the policy never reads `endsAt`. The download-suppression reader grows, but a suppressed asset is filtered out
before a row is ever recorded. The album denylist changes outside the app, and stays unversioned (below).

Constraints: no lock may be held across the HTTP request (two processes, and `0xdead10cc` kills a suspended process
holding a lock on a shared-container file); the deployed database is shared with real users; the v1 route is frozen;
the one-writer and atomic-publish rules of `database` stand.

## Goals / Non-Goals

**Goals:**
- The backend can never end up holding a manifest older than one the device has already published. Ordering is
  guaranteed, not healed.
- The device can never skip forever while the server holds a different snapshot.
- Old builds keep working; either side can roll back.

**Non-Goals:**
- Preventing duplicate or extra publishes. Both are fine; ordering is the only goal.
- Changing what the manifest declares, or the fan-out beyond "only when the publish won".
- Versioning the album denylist (see Decisions §6).
- Any change to v1.

## Decisions

### 1. The backend orders; the device does not serialize

The backend stores a publish only if its version is not older than the one it holds. **Rejected:** (B) the app
republishes on every foreground regardless of the marker. That only heals staleness, and needs a foreground to
come. **Rejected:** holding the App-Group database lock across the PUT. The processes already read the ledger in
the right order; the race is in the network, and a cross-process lock held across a network call is exactly what
iOS kills (`0xdead10cc`).

### 2. The version: one counter, bumped by every manifest-relevant change, read first

A one-row table `manifestVersion(value INTEGER NOT NULL)` in the ledger database, seeded to 0 by `11.sqm` and by
the `CREATE`.

- **Ledger changes bump it through triggers**, so no write path can forget: `AFTER INSERT` and `AFTER DELETE` on
  `ledgerRow`, and an `AFTER UPDATE` whose `WHEN` clause fires only if a projected column really changed
  (`assetId`, `key`, `creationDate`, `role`, `contentType`, `originalFilename`, compared `OLD IS NOT NEW`). Never on
  `state` or `destinationPath`: the v2 manifest carries no upload state, and a bump per finished upload would force
  a republish per cycle for nothing. A `WHEN` clause rather than `UPDATE OF`, because `recordUnlessSettled`'s upsert
  names every detail column in its `SET` list, and `UPDATE OF` would fire on unchanged values.
- **A reconfigure bumps it** after its config save has landed (§4).
- **A cycle reads it first**, before the config read and before `manifestRows()`. The trigger bumps inside the
  same transaction as the change. So a cycle that read N saw every change up to N, and any change its projection
  misses happened later and carries a higher number.

**Rejected:** bumping the counter inside the projection's read transaction (the handoff's original rule). It orders
the ledger half only: a slow cycle that read the config before a reconfigure could still take the higher number
with the older policy. **Rejected:** a clock or a per-process counter. Neither is ordered with the snapshot.

**The counter survives the reset family.** `clear()` and `resetTo()` delete only `ledgerRow`, and their row
deletes and inserts bump the counter like any other change. So a join, a leave or a device reset moves it forward,
never back.

### 3. The skip marker includes the version

Marker = `eventId version json`; a publish is skipped only when all three match. Reading the counter first gives one
number to snapshots whose contents can differ: A and B both read N, a change commits between their row reads, A
builds stale S1 and B builds S2, and equal versions are accepted. If S2 lands first and S1 second, the server holds
S1; if B saves its marker last, a content-only marker says S2 is published, and the next cycle (N+1, S2) would skip
forever. With the version in the marker, `(N, S2) ≠ (N+1, S2)`, so the device republishes as N+1 and the server
takes it.

Why this suffices: while the counter still reads N, no manifest-relevant change has happened since any cycle read
N, so every publish numbered N carries the same content. The server keeps the highest version it has seen. So if
the marker equals the current `(N, S)`, the server holds S.

Cost: one republish per counter bump that did not change the projection. The trigger's column filter keeps that
rare.

### 4. The reconfigure bump runs after the save, in the same step

The config is a file (`FileBackedConfigStore`) and the counter lives in the ledger database, so the two cannot share
a transaction. The order is the correctness argument. If the bump came first, a cycle could read N+1, then the
**old** config, and publish the old policy as N+1, and nothing would go higher. With the bump after the save, a cycle
holding the pre-bump N is overtaken by N+1. The bump runs inside the `save config` step (save, then bump), so a failed
save bumps nothing. `ReconfigureEvent` takes it as an injected `suspend () -> Unit`, built in `compose/` over the
ledger store. `MembershipRefresh` does not bump, because it never changes a policy bound.

A crash between the save and the bump leaves a new policy without a new number. The marker's content half still
differs, so the next cycle republishes it under the old N. The only gap left is a same-N race inside that crash
window, which is accepted.

### 5. The API: a body field; older is a 2xx that writes nothing

- Body: `{ "version": <non-negative safe integer>, "assets": [...] }`. `parseManifestAssets` already ignores
  unknown fields; a present but invalid `version` → `400`.
- **The compare happens inside the batch.** Statement 1 of the v2 batch:
  `UPDATE memberships SET manifest_version = ?v WHERE event_id = ? AND device_id = ? AND (manifest_version IS NULL
  OR manifest_version <= ?v)`. Every following statement (the `DELETE`, every `INSERT`, however it is chunked) is
  gated `AND EXISTS (SELECT 1 FROM memberships WHERE event_id = ? AND device_id = ? AND manifest_version = ?v)`. It
  is one batch, one transaction: a refused publish changes nothing, and a won one is exactly today's replace.
  Statement 1's `rowsAffected` tells the route whether it won.
- **Equal is accepted.** Equal versions carry equal content (§3), so accepting is harmless, and it lets a retried
  identical publish succeed.
- **A refused publish returns `200`**, like a won one, and wakes nobody. The client treats it as published (§3 shows
  why saving the older marker costs at most one extra publish). **Rejected:** `412`. It is equally safe, but it turns
  a normal outcome into an error the interceptor and the logs would report.
- **A versionless publish** (v2 builds from phases 5–7): statement 1 is
  `UPDATE memberships SET manifest_version = NULL …` and there is no gate. It is today's behavior, and the NULL
  means the next versioned publish always wins.

### 6. The album denylist stays unversioned

A change to an album's membership in WhatsApp or Telegram happens outside the app. The next cycle's content half
of the marker picks it up (same N, different JSON → republish). Two processes racing within that same N could
still let the older denylist's projection land last. That needs an album change and a cross-process race in the
same instant, and is accepted, as agreed.

### 7. The join resets the stored version: a named one-writer exception

`ENROLL`'s `ON CONFLICT (event_id, device_id) DO UPDATE SET state = 'active'` gains `, manifest_version = NULL`. A
re-join starts a new membership, and so a new sequence of manifests. The device id lives in the Keychain and outlives
the App-Group database, so a reinstalled device's counter can restart below what the backend kept. Without the
reset, every publish would be refused forever.

`database`'s one-writer rule gains a **named exception**: the manifest publish writes `memberships.manifest_version`,
and the join writes that column only to clear it at the start of a membership. Nothing merges the two writes and no
other route reads the column. **Rejected:** a join generation (`join_generation` owned by the join;
`manifest_version` plus `manifest_generation` owned by the publish). It honors the rule column by column, but it is
three columns and a three-way comparison for **identical behavior**. Its one order dependency, a pre-rejoin publish
landing after the rejoin, exists in both designs, and needs a previous install's publish to outlive a reinstall.
**Rejected:** a sixth table. It breaks "exactly five tables" and still needs a generation.

## Risks / Trade-offs

- [The trigger names ledger columns, and SQLite refuses `DROP COLUMN` for a column a trigger names] → A future
  column drop must drop and recreate the trigger in its migration. The comment on the trigger says so.
- [A stale pre-leave publish landing after a re-join is accepted over the reset] → It needs a previous install's
  publish to outlive the reinstall; accepted. A plain leave and re-join without a reinstall keeps the counter, so
  it continues upward.
- [`resetTo` inserts thousands of rows, and each trigger fire updates the counter row] → All inside one
  transaction on a one-row table; it costs microseconds per row.
- [An extra republish after a no-op bump] → Duplicates are fine; the column filter keeps them rare.
- [The migration opens `api-deploy`'s maintenance window] → A short planned outage; the user knows.
- [Two processes open `ledger.db` across the `11.sqm` upgrade] → They ship in one bundle, so both run the same
  schema version, and the migration runs under the driver's existing upgrade path.

## Migration Plan

1. **Backend first** (a merge to `main` runs `api-deploy.yml`): `0003` adds the nullable column, and existing rows
   read NULL. Versionless and v1 publishes behave as today. The v1 wire-contract tests pass unmodified.
2. **App** (the same merge, reaching internal TestFlight): `11.sqm` adds the counter table and the triggers; existing
   rows are untouched. The first versioned publish meets NULL and wins.
3. **Rollback, backend**: a reverted bundle ignores the column and the field; a later re-deploy finds the column
   still there. There is no down-migration. The column is inert under old code, so removing it would be a
   roll-forward.
4. **Rollback, app**: an older build sends no version; the backend accepts it and sets NULL. Its SQLDelight schema
   is older than the database's, which is the existing downgrade story and nothing new.

## Open Questions

None blocking. The `sync-ledger` spec says the App-Group container survives a reinstall; `CLAUDE.md` says a reinstall
is a leave, because the config file is missing. This design does not depend on which is true: the join reset covers a
restarted counter, and a surviving counter only continues upward.
