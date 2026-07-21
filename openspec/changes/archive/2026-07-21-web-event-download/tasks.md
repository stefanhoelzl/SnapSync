## 1. Download page asset

- [x] 1.1 Create `backend/src/download.html` — a single **self-contained** page: inlined CSS, inlined JS,
      and an inlined zip writer (hand-rolled STORE-only, no vendored lib), **no third-party resources**
      (Decision 5, `web-event-download` "fully self-contained").
- [x] 1.2 In its JS, read `location.hash`, decode the `#v=3&d=<base64url(json)>` payload with the **same
      wire contract as `event-link`** to extract `eventId`; on a missing/malformed fragment show the
      "invalid or expired link" state (`web-event-download` "resolves the event client-side").
- [x] 1.3 On load, fetch the event name (`GET /events/:id`) and the union (`GET /events/:id/files`)
      same-origin; on a `404` marker, show the invalid-link state and offer no download.
- [x] 1.4 On the download action, `fetch` each union entry's presigned URL, assemble an **in-memory** zip
      `Blob` (no service worker, no server zip), and trigger a normal browser download; zip the **whole
      union** (`web-event-download` "in-memory zip", Decision 4).
- [x] 1.5 Render both a "Download all photos (zip)" control and a "Get SnapSync" App Store link,
      unconditionally (no User-Agent branching).

## 2. Backend routing (`backend/src/app.ts`)

- [x] 2.1 Import the page at build: `import DOWNLOAD_HTML from "./download.html" with { type: "text" }`
      (mirror the `LANDING_HTML` pattern) + a `DOWNLOAD_ETAG` mirroring `LANDING_ETAG`.
- [x] 2.2 Replace the `GET|HEAD /join` `302`-to-App-Store with a `200` serving `DOWNLOAD_HTML`: identical
      bytes for every link, reads no storage, `public` cache directive + ETag/304 (`event-link` MODIFIED,
      `web-event-download` "static no-app download page").
- [x] 2.3 In the attestation middleware whitelist: add `GET`/`HEAD` on `/events/:eventId` and
      `/events/:eventId/files` as ungated (`publicRead`), **method-scoped** — non-`GET`/`HEAD` methods on
      `/events/:eventId/…` and `POST /events` stay gated; update the `/join` whitelist comment
      (`device-attestation` MODIFIED, entries 7–9).
- [x] 2.4 Union route (`GET /events/:id/files`): served tokenless — realized by 2.3 (the middleware is the
      sole gate; the handler carries no own token check), `Cache-Control: no-store, …` unchanged
      (`bunny-list-endpoint` MODIFIED).
- [x] 2.5 Marker route (`GET /events/:id`): served tokenless — realized by 2.3 (no own handler check);
      `POST /events` still requires a token (`event-creation` MODIFIED).

## 3. Backend tests (`backend/test/`)

- [x] 3.1 `eventlink.test.ts`: `GET /join` (no token) returns `200` with the download page — not `302`,
      not `401`; the response is byte-identical for two different fragments and reads no storage.
- [x] 3.2 `attest.test.ts`: `GET /events/:id` and `GET /events/:id/files` **without a token** are served
      (`404`), not `401`.
- [x] 3.3 `attest.test.ts`: writes stay gated (regression guard) — `POST /events`, device-manifest
      `PUT`/`DELETE`, `POST …/notify`, and `POST` on the read paths → `401`; `GET /files/devices/:id`
      without a token → `401`. (`GATED` table also updated: the two GET reads removed.)
- [x] 3.4 `download.test.ts` (new): the served page offers download + install, and is self-contained —
      no external `src`/`srcset`/`<link>`/`<script src>`/off-origin import.

## 4. Validate & verify

- [x] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate web-event-download --strict` is green.
- [x] 4.2 Backend suite green: `cd backend && deno task test` (161 passed) + `deno task check` + `deno lint`
      + `deno fmt` all clean.
- [x] 4.3 First-deploy verification: deployed to the live Bunny script (79725) via `proton-env`; live
      checks on `snapsync.stho.net` pass — `GET /join` → 200 HTML page, `GET /events/<uuid>` &
      `…/files` no token → 404 (not 401), `POST /events`/`…/notify` no token → 401, AASA 200. Presigned-URL
      CORS confirmed `ACAO:*` (origin-independent). Remaining manual eyeball: a real event's end-to-end
      browser zip on Android/Firefox/Safari.
