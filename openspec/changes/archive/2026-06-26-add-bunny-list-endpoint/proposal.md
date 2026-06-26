## Why

The backend can be written to but not **read**: there is no way to ask it what has already been
stored for an event. Confirming an upload landed today means logging into the bunny dashboard
(root `CLAUDE.md`, "Verify real uploads"). More importantly, a device that **re-joins an event
after a reinstall** mints a fresh `deviceId` (the App Group is wiped on uninstall, so
`IosDeviceIdStore` re-mints) and loses its ledger — it cannot tell which photos it already
uploaded without asking storage. The stable identity that survives a reinstall is the
**`filename`** (it embeds the PHAsset `localIdentifier`), and the prior uploads sit under the
*old* `deviceId`, so the device must be able to enumerate **all** stored objects for the event,
across every device, and match by filename.

This change adds the read surface that enables that: a credential-free, per-event file listing on
the existing backend. The on-device ledger pre-seeding / reconciliation that consumes it is a
**separate later change** (it crosses `:domain:engine` and the extension's single-writer path);
this proposal ships only the backend endpoint it will call.

## What Changes

- A new read route `GET /event/<eventId>/files` on the existing Hono app (`backend/src/app.ts`),
  returning a **flat JSON array** of every stored object for the event, aggregated **across all
  devices**.
- Because bunny native Storage LIST is **per-directory (non-recursive)**, the handler fans out:
  list `<eventId>/` to get the device sub-directories (`IsDirectory == true`), then list each
  `<eventId>/<deviceId>/` for its files, and flatten.
- Each entry is the normalized shape `{ filename, deviceId, size, lastModified }` — mapped from
  bunny's `ObjectName` / `Length` / (`LastChanged` ∥ `DateLastModified`); the `deviceId` comes from
  the directory the file was listed under. `contentType` is **not** included (bunny's canonical
  List Files schema does not reliably return it, and the consumer only needs the filename).
- **Authorization is the event id alone** (no token, no registry) — the same capability model as
  the upload endpoint. The handler uses the env-held storage `AccessKey` to call bunny LIST and
  never exposes the account API key.
- Outcomes: malformed `eventId` (not a UUID) → `400` (reuses `validateUUID`); a valid id with no
  objects (empty **or** unknown event — indistinguishable without a registry) → `200 []`; any
  upstream LIST failure mid-fan-out → `502` (faithful — never a partial list); any other method or
  unmatched path → `404` (Hono default, matching upload).
- Ships to **both** bunny Edge Scripting and Deno Deploy through the single `createApp` Hono app —
  no deployment, CI, or config changes.

## Capabilities

### New Capabilities
- `bunny-list-endpoint`: a read-only, per-event file listing on the backend. `GET
  /event/<eventId>/files` streams a flat array of `{ filename, deviceId, size, lastModified }`
  across all devices in the event, authorized by possession of the event id, by walking bunny
  native Storage LIST per-directory. Faithful (`502` on any sub-failure, never partial); `200 []`
  for an empty/unknown event; `400` for a malformed id.

### Modified Capabilities
- None. `bunny-upload-endpoint` (write) is untouched; `backend-deployment` already ships the single
  `createApp` app to both targets, so the new route rides along with no spec change.

## Impact

- **Modules**: `backend/src/app.ts` (new `GET` route + per-directory fan-out and entry mapping);
  `backend/test/app.test.ts` (new tests, upstream bunny LIST mocked). `validators.ts`
  (`validateUUID`) reused as-is.
- **CI / deploy / config**: none — same app, same `backend-deploy.yml`, same env (`BUNNY_STORAGE_*`
  / `AccessKey`).
- **Docs**: `docs/design.md` §4 gains a one-line note that the backend now exposes a per-event read
  listing; root `CLAUDE.md` "Verify real uploads" may later point at it instead of the dashboard
  (optional, non-blocking).
- **Out of scope / deferred**: the on-device ledger pre-seed / reconciliation consumer (separate
  change); pagination / large-event caps; `contentType`; CORS/OPTIONS; a per-device sub-route; and
  any event-metadata resource at `GET /event/<id>` (the path is deliberately left free for it).
