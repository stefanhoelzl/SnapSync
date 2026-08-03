## 1. Server

- [x] 1.1 Delete the bare-alias mount in `api/src/app.ts` (`app.route("/", deviceApi)`), keeping the
      `deviceApi` sub-app and the `app.route("/api/v1", deviceApi)` line, and rewrite the two-line mount
      comment above them so it describes one versioned mount and how a future `/api/v2` is added — with no
      "grace period" / "delete this to end it" prose left.
- [x] 1.2 Rewrite the `app.ts` file header (the VERSIONED PREFIX block, lines ~5–10): every route in the
      header's map is written `/api/v1/…`, the "also available bare / deprecated alias" sentence is gone,
      and the web/link exception (`/`, `/join`, the AASA stay at the root) is kept.
- [x] 1.3 Update the auth-gate comments (~lines 499–520) so they explain the `^/api/v\d+` normalization as
      the version-agnostic prefix strip it is, with no reference to a bare alias. The regex itself is
      unchanged (design D3).
- [x] 1.4 Sweep the rest of `api/src/` for alias prose (e.g. `src/dev/serve.ts`'s printed
      `BACKGROUND_UPLOAD_URL_BASE` line already carries `/api/v1` — confirm, change nothing else).

## 2. Tests

- [x] 2.1 `api/test/app.test.ts`: bake `/api/v1` into `BYTE_PATH`, `DEVLIST_PATH`, `DEVMANIFEST_PATH`,
      `CONFIG_PATH` and prefix every inline device-route literal (`"/events"`, `"/events/nope"`,
      `"/events/nope/files"`, `"/events/nope/notify"`, `"/files/devices/nope"`, `"/nope"`). Leave the
      web/link cases (`/`, `/join`, the AASA, `/_astro/*`) bare.
- [x] 2.2 `api/test/app.test.ts`: delete the now-redundant mirror test
      `prefix: /api/v1 device routes resolve identically to the bare paths` and its section banner.
- [x] 2.3 `api/test/attest.test.ts`: prefix the `GATED` route table (and any other device-route request)
      so the gate tests run against `/api/v1`; delete the three duplicated `/api/v1` gate tests
      (`refuses an unauthenticated request UNDER /api/v1`, `accepts a valid token UNDER /api/v1`,
      `the /api/v1/attest/* issuers need no token`) now that the base tests carry the prefix, and rewrite
      the section banner. **Keep** `gate: web/link paths are NOT served under /api/v1`.
- [x] 2.4 `api/test/download.test.ts` and `api/test/eventlink.test.ts`: verified — every request in
      both files is `/join` (web/link), so nothing to prefix. `api/test/landing.test.ts` untouched.
- [x] 2.5 Add no test asserting bare paths are gone (design D4) — verify by review that none was added.
- [x] 2.6 `cd api && deno task check` (fmt, lint, check, test) is green.

## 3. api docs

- [x] 3.1 `api/README.md`: rewrite the "Versioned prefix" contract banner so it states that every
      device-API route is served under `/api/v1` and the web/link routes stay at the root — no grace
      alias, no "ending the grace period" instruction.
- [x] 3.2 `api/README.md`: prefix every route literal in the contract map and in the "Methods" bullet
      (~line 168), and drop the "each served under both `/api/v1/…` and the bare path" clause. Leave the
      web/link entries (`/`, `/join`, the AASA) bare, and leave storage-key prose alone (stored keys are
      bare by contract — that is a different sense of "bare").

## 4. Deployed-surface specs (editorial, per design D5/D7)

Rule for every literal: prefix it when it names a path **on the deployed origin**; leave it bare when it
names a path **relative to an injected base**. Read each occurrence; do not blanket-replace.

- [x] 4.1 `openspec/specs/event-creation/spec.md` (~39 literals).
- [x] 4.2 `openspec/specs/bunny-upload-endpoint/spec.md` (~18) — do **not** touch the retired
      `PUT /event/<eventId>/file/<filename>` / `PUT /files/device/<deviceId>/<filename>` prose: its "v1"
      is resource versioning, not `/api/v1`.
- [x] 4.3 `openspec/specs/device-attestation/spec.md` (~17).
- [x] 4.4 `openspec/specs/device-config-endpoint/spec.md` (~8) and
      `openspec/specs/event-notify-endpoint/spec.md` (~7).
- [x] 4.5 `openspec/specs/event-leave-endpoint/spec.md` (~6), `openspec/specs/event-limits/spec.md` (~5),
      `openspec/specs/bunny-list-endpoint/spec.md` (~5).
- [x] 4.6 `openspec/specs/web-event-download/spec.md` (~2) and
      `openspec/specs/scheduled-cleanup/spec.md` (~1).
- [x] 4.7 `openspec/specs/backend-deployment/spec.md`: none outside the delta-owned requirement — the one
      candidate was inside it, so the file is left to the delta (verified: no diff).
- [x] 4.8 Confirm the base-relative specs are left bare: `harness-world-model`, `full-stack-harness`,
      `join-event`, `ios-app-shell`, `event-creation-ui`, `event-rejoin-reconciliation`,
      `photo-selection-policy`, `photo-download`, `leave-event`, `event-link`. `:test:world`
      (`World.host`, `MiniEdge.kt`) is unchanged.
- [x] 4.9 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` is green.

## 5. Verify

- [x] 5.1 `./gradlew build` is green (nothing Kotlin-side should move; this catches an accidental touch).
- [x] 5.2 Grepped `api/`, `openspec/specs/`, `CLAUDE.md` for alias prose: the only hits are the unrelated
      event-lifecycle "grace period" (sweep/config) and the migration one in `CLAUDE.md`, plus
      `openspec/specs/backend-deployment/spec.md`'s alias requirement — which this change's delta replaces
      at sync time, so it is pending, not missed.
- [ ] 5.3 Post-merge (operator): `GET https://snapsync.stho.net/api/v1/attest/challenge` → `200`;
      `GET /`, `GET /join`, `GET /.well-known/apple-app-site-association` → `200`.
