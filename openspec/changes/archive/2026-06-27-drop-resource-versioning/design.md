## Context

`Resource.version` (iOS: `PHAsset.modificationDate`) is the engine's content-identity proof, compared
for equality so a `COMPLETED` key whose version changes is re-uploaded. Exploration established that
this signal is unfixable for its purpose: the version is an **asset**-level date stamped on every
**resource**, and it moves on non-content events (favorite, caption, album, re-analysis, iCloud
sync). It cannot tell a real edit from a metadata bump, and a storage-time proxy would compare two
unsynchronized clocks. So the choice is binary — over-upload or ignore edits — and this change ignores
them by making an uploaded resource **immutable**.

```
  ResourceChanged                BEFORE (date = version)      AFTER (immutable)
  ───────────────                ──────────────────────       ─────────────────
  entry COMPLETED/REQUESTED  →   version == ? AlreadyUploaded  AlreadyUploaded
                                 version != ? ReUpload         (always — key exists = done)
  entry FAILED / absent      →   Upload                        Upload

  favorite/caption bump      →   version moves ⇒ ReUpload ✗    re-enumerate ⇒ every key
                                   (re-upload unchanged bytes)   AlreadyUploaded ⇒ no-op ✓
```

## Goals / Non-Goals

**Goals:**
- Remove `version` end-to-end: engine seam, ledger schema + record ops, gallery derivation, rejoin
  seed, and the design source of truth.
- Drop the now-orphaned API `lastModified` field from `bunny-list-endpoint`.
- Preserve existing `COMPLETED` rows across the migration so no mass re-upload happens on app update.

**Non-Goals:**
- No content-hash / etag identity (would re-enable real edit detection — out of scope; edits are
  assumed rare).
- No change to filename composition or discovery (inserts/updates/deletes all stay).
- No change to `bunny-download-endpoint` or `edge-upload-provider`.

## Decisions

**D1 — Immutability is per key.** The decision is purely state-based: `COMPLETED`/`REQUESTED` →
`AlreadyUploaded`, `FAILED`/absent → `Upload`. A `COMPLETED` key is frozen forever. `SyncDecision.ReUpload`
is deleted (its only trigger was a version mismatch). Accepted blind spot: re-editing an
already-uploaded resource is never re-uploaded.

**D2 — Keep processing `updated` identifiers.** Discovery is unchanged. Removing `version` is what
makes this safe and *beneficial*: an updated asset re-enumerates, every existing key resolves to
`AlreadyUploaded` (the metadata-bump no-op), while a first edit's new resource kinds or a late iCloud
full-res are absent keys that still upload. Strict immutability does not require dropping update
events — it only requires that existing keys never re-upload.

**D3 — Row-preserving migration + a SQLDelight dialect bump.** SQLDelight defaults to the SQLite 3.18
grammar for non-Android targets (no `dialect(...)` is declared today), and `ALTER TABLE … DROP COLUMN`
is a 3.35 feature — so the parser would reject it at *compile* time even though both drivers (iOS
system SQLite, sqlite-jdbc) execute it fine at runtime. We accept the bump to
`sqlite-3-35-dialect:2.3.2` (minimal version that introduces `DROP COLUMN`) and keep `2.sqm` to one
statement: `ALTER TABLE ledgerRow DROP COLUMN version;`. The migration is **row-preserving** (not the
destructive drop-and-recreate of `1.sqm`) so `COMPLETED` rows survive and nothing re-uploads on
update. The dialect change raises the grammar/type-inference floor for the whole module, so the build
+ generated-type re-verification is an explicit task.

**D4 — Rejoin seeds by filename; `updatedAt` = join time.** With no version, the seed matches local
resources to listed filenames and writes `COMPLETED` with `updatedAt` = the join time. The engine's
state-only decision then skips every seeded key as `AlreadyUploaded`, so the "seeded version matches
the producer" requirement is obsolete and removed. Cosmetic effect: after a rejoin, pre-existing
photos read as "backed up ~just now" instead of their storage time — accepted.

**D5 — Delete the API `lastModified`.** It was the only consumer of the storage-clock date and only
fed the cosmetic `updatedAt`; the download/list feature uses `filename`/`size`/`url`. The
`bunny-list-endpoint` entry shape closes to `{ filename, size, url }` and the backend stops reading
bunny's `LastChanged`/`DateLastModified`.

**D6 — design.md edited in lockstep.** §2.2 (the `Resource`/`version` text, the `ReUpload` arm, the
decision table, `LedgerEntry`), §3.1 (the `version =` derivation), §4 (the list response shape), and
the test-coverage list are updated as part of this change.

## Risks / Trade-offs

- **Re-edits never re-upload (accepted).** The deliberate consequence of immutability; edits are
  assumed rare for a one-way personal backup.
- **Dialect floor raise (low).** 3.35 may add reserved keywords or shift type inference; the ledger
  is one trivial table (`key` is the only borderline identifier), so the realistic blast radius is
  nil — but the build + generated-type check is mandatory, not assumed.
- **Migration correctness (low).** A row-preserving `DROP COLUMN` keeps `COMPLETED` rows; verified by
  a migration test that opens a pre-migration DB with rows and asserts they survive without the
  column.
- **`updatedAt` cosmetic regression after rejoin (accepted).** Seeded rows show the join time.
