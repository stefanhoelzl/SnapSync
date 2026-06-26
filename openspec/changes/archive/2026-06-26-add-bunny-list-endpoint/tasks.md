## 1. List route + per-directory fan-out (`backend/src/app.ts`)

- [x] 1.1 Add a `GET /event/:eventId/files` route to the Hono app (a child route mounted like the
      upload route, or a sibling on the root app). Reject a non-UUID `eventId` with `400` (reuse
      `validateUUID`); rely on Hono's default `404` for unmatched paths / non-GET methods.
- [x] 1.2 Implement the per-directory walk against bunny native Storage List Files using the injected
      `fetch` and `config`: `GET https://<host>/<zone>/<eventId>/` (trailing slash) with the
      `AccessKey` header → parse the JSON array → take entries where `IsDirectory === true` as
      deviceIds. Then for each deviceId, `GET https://<host>/<zone>/<eventId>/<deviceId>/` → take
      entries where `IsDirectory === false` as files. (Percent-encode path segments consistently
      with the upload handler where needed; eventId/deviceId are UUIDs.)
- [x] 1.3 Map each file entry to the normalized shape `{ filename: ObjectName, deviceId, size:
      Length, lastModified }`, reading `lastModified` from whichever bunny field is present
      (`LastChanged` ∥ `DateLastModified`). Flatten all devices' files into one array. Exclude
      directory entries. Return `200` with the array (`[]` when there are no objects).
- [x] 1.4 Faithful failure: if the event-directory List **or** any per-device List returns a non-OK
      status or throws, respond `502` (mirror the upload handler's upstream-error handling) and
      return no partial array. Log the failing key like the upload handler does.

## 2. Tests (`backend/test/app.test.ts`)

- [x] 2.1 Drive the app via `app.request()` with an injected `fetch` fake that serves canned bunny
      List responses keyed by URL (event dir → device dirs; each device dir → files). Assert: a flat
      array across two devices, each entry `{ filename, deviceId, size, lastModified }`, directory
      entries excluded, and `deviceId` set from the directory.
- [x] 2.2 Empty/unknown event: event-dir List returns `[]` → `200 []`. Valid UUID, no objects.
- [x] 2.3 `400` on a non-UUID `eventId` with **no** upstream call made; `404` on a wrong method
      (e.g. `POST`) and on an unmatched path; assert the fake `fetch` was not invoked in the `400`
      case.
- [x] 2.4 Faithful failure: a per-device List that returns `500` (or throws) → endpoint `502`, no
      partial array returned.
- [x] 2.5 `AccessKey` header is present on every upstream List request; the account API key never
      appears.

## 3. Checks & docs

- [x] 3.1 `deno task test`, `deno lint`, `deno fmt --check`, and `deno check src/*.ts` all green
      (the deploy gate set).
- [x] 3.2 Add a one-line note to `docs/design.md` §4 that the backend exposes a per-event read
      listing `GET /event/<id>/files` (capability `bunny-list-endpoint`); keep design.md from
      contradicting code.
- [ ] 3.3 Branch → PR → `/ship` (the single `createApp` app deploys to both targets via the existing
      `backend-deploy.yml`; no CI/deploy/config change).
- [ ] 3.4 Post-merge (manual, optional): `curl` the deployed `GET /event/<id>/files` for an event
      with known uploads and confirm the flattened listing matches the bunny zone — the first
      real-bunny verification of the List response shape.
