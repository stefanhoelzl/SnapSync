# Tasks

## 1. Backend: the event's zone

- [x] 1.1 Add migration `api/migrations/0009_event_zone.sql` (nullable `zone TEXT` on events) and regenerate the schema shape; verify `api/test/migrations.test.ts` and `schema-shape.test.ts` pass
- [x] 1.2 Add `validateZone` to `api/src/validators.ts` (an IANA zone accepted by `Intl.DateTimeFormat`, max 64 chars); accept an optional `zone` on `POST /events` (absent or unusable → NULL, never a refusal; kept off the wire) and store it; verify new cases in `validators.test.ts` and `v1.test.ts` (Europe/Berlin stored, unknown name and offset stored as NULL with a 201, absent accepted)
- [x] 1.3 Document the field and the column in `docs/architecture.md` (backend contract and storage) and verify the doc names migration 0009

## 2. Backend: the per-event page

- [x] 2.1 Add pure render functions (event row + member counts + now → title, og:description, heading, facts, state; HTML-escaped; dates via `Intl` in `zone ?? "UTC"`) in a new `api/src/routes/event-page.ts`; verify unit tests cover escaping (`<script>`, quotes, `&`), pre-start / running / ended-settling, the Berlin-host Saturday case, and UTC fallback
- [x] 2.2 Add `GET|HEAD /join/:eventId` to `site.ts`: malformed → 404, missing → 404, completed → 410 (invalid view, generic title, no name), closed → 200 rendered as ended, store/storage failure → 502; fill the storage template; `NO_CACHE` + `Referrer-Policy: no-referrer`; verify with route tests in `api/test/app.test.ts`, each status and header asserted
- [x] 2.3 Admit `GET|HEAD /join/*` in the token gate (`app.ts`) and add `{"/": "/join/*"}` to the AASA components; verify the AASA test asserts both components and an unauthenticated `/join/<uuid>` is not 401
- [x] 2.4 Add `Referrer-Policy: no-referrer` to the constant `/join` response too; verify in the route test
- [x] 2.5 Update `docs/architecture.md`'s site-routing section (per-event render, no cache, template placeholders) and verify it no longer claims `/join` is the only event-page object

## 3. Site: template and island

- [x] 3.1 Add the placeholders to `site/src/pages/join.astro` (title, og:title, og:description, heading, facts block with status pill, members and settled dots, available-until, state marker) and styles from the approved mockup; verify `site/scripts/check-selfcontained.ts` passes and the built `join/index.html` contains every placeholder exactly once
- [x] 3.2 Extend `site/src/lib/invite.ts` with a path decoder (`/join/<uuid>` + optional fragment) and a builder of the Play referrer `v=3&d=` from an identifier; verify `site/scripts/invite.test.ts` covers both forms, a bad UUID, and that the referrer of a path link equals the fragment link's
- [x] 3.3 Change the island: path form → identifier from the path, facts from the HTML, fetch only the union; fragment form → `location.replace("/join/<id>")`; invalid fragment → invalid view as today; verify with `deno task` site tests and by opening both forms against `deno task dev:local`
- [x] 3.4 Rewrite the privacy policy's event-page item in `site/src/pages/index.astro` (D8) and verify `api/test/landing.test.ts` or the site build still passes, with the old "never sent" sentence gone

## 4. App: decode both forms

- [x] 4.1 Make `EventLink`'s decoder accept `<origin>/join/<uuid>` with an optional hints fragment (encoder unchanged); a malformed path is an `InvalidConfigLink`; verify `EventLinkTest` (path form, path + hints, bad UUID, trailing slash, fragment form unchanged)
- [x] 4.2 Add `<data android:pathPrefix="/join/" …>` to `app/android/src/main/AndroidManifest.xml`; verify an `adb shell am start -a VIEW -d https://<host>/join/<uuid>` on the emulator opens the join screen (`snapsync-android`)
- [x] 4.3 Add a `:test:integration` scenario: a path-form link delivered through `/os` link opens the join screen for that event, and a malformed path shows the not-valid message; verify `./gradlew :test:integration:test`

## 5. App: zone on create and share title

- [x] 5.1 Add `zone` to `CreateEventRequest`, read from the process `Clock`'s zone at each create by `BackendEventCreation` (wired in `backendServicesFor`); send it in `HttpBackend.createEvent` (the mock ignores it); verify `HttpBackendTest` (sent / omitted), `BackendServicesTest` (read at the create, not at composition) and the api's stored-zone tests — the zone is off the wire, so no port contract can observe it
- [x] 5.2 Change `SystemUi.share(text)` to `share(text, title)`, pass the event name from the joined screen's share command, and record the title in `SystemUiMock`; verify a presentation/integration test asserts the share carried the event's name
- [x] 5.3 iOS: share an `NSURL` via a `UIActivityItemSource` answering `LPLinkMetadata(title, originalURL)`; verify `./gradlew compileIosMainKotlinMetadata` and, on the SE2 (`snapsync-device`), that the share sheet header shows the event name
- [x] 5.4 Android: put `EXTRA_TITLE` on the `ACTION_SEND` intent; verify on the emulator that the chooser preview shows the event name

## 6. Integration

- [x] 6.1 Run `./gradlew build` and `cd api && deno task test`; verify both are green and `./gradlew architectureDiagrams` leaves no diff
- [x] 6.2 On a local backend (`local-backend`), open `/join/<uuid>` with `curl` (no script): the HTML names the event, dates, members and status; a completed event answers 410 with no name, a closed one renders as ended; verify the og tags with a preview debugger-style fetch
- [x] 6.3 Run `npx --yes @fission-ai/openspec@1.13.2 validate server-rendered-event-page --strict` and verify it passes
