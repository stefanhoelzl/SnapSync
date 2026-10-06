# Design

## Context

- `/join` serves ONE constant object, `site/join/index.html`, proxied from storage (`api/src/routes/site.ts`). Its
  island decodes the fragment, fetches `GET /api/v1/events/<id>` (the name) and `…/files?urls=false` (the union), and
  counts and zips in the browser, once on load.
- Link previewers (the sharer's phone for iMessage, WhatsApp and Signal; the chat servers for Telegram, Slack and
  Discord) run no script and never send a fragment. No preview can name the event unless the request carries it.
- The fragment rule came from `migrate-to-universal-links` D1 and `web-event-download` D3. It kept the identifier, a
  bearer capability, out of CDN logs, cache keys and URL-logging middleboxes. Chat servers already hold the whole
  message, fragment included.
- The app's decoder matches the strict prefix `<origin>/join#` (`EventLink.kt`). Every installed app rejects any
  other shape, which is why this change is phase 1 of 2 (proposal).
- `startsAt`/`endsAt` are canonical `yyyy-MM-ddTHH:mm:ssZ` instants, compared lexicographically as capture cutoffs
  and parsed by installed apps. The backend stores no zone.
- `GET /events/:id` already answers `name`, `startsAt`, `endsAt`, `closedAt`, `completedAt`, `deletesAt` and
  `members {active, final}`.
- Approved mockup (interview): status pill, name, dates, member count, and after the end settled dots, "X of N done
  sharing" and "Available until …". The bubble's image is the app icon.

## Goals / Non-Goals

**Goals:**
- Every app from this release opens `/join/<uuid>`, so phase 2 can switch the shared form.
- `/join/<uuid>` is rendered on the server, so previews and script-less browsers see the event.
- One rendering path: a fragment link opened in a browser lands on the same page.

**Non-Goals:**
- Changing what the app shares or QR-encodes (phase 2).
- Raising the minimum app version (phase 2).
- Server-rendering the photo count or the download (they stay the island's).
- Localizing the site (English only, dates in English).
- An event photo as the preview image.

## Decisions

### D1. Path form `/join/<uuid>`, hints in an optional fragment
`/join/<uuid>` with no version field: the path shape is the version, and the decoder accepts exactly two forms. The
development-only hints (`autoJoin`, `minPhotoDate`, `maxPhotoDate`, `direction`, `saveToAlbum`) ride in an optional
fragment (`/join/<uuid>#autoJoin=true&…`), parsed with the same keys as the `d` payload and honoured only under
`InviteLinkHints.Honoured`, as today. The encoder is unchanged in phase 1.
*Alternatives:* the `v=3&d=` envelope in the query (`/join?v=3&d=…`), which keeps one codec but is longer and
opaque; `/join?e=<uuid>`; `/join/v4/<uuid>`. The interview chose the path form for its readability.

### D2. A server route renders the built page as a template
`site/` keeps building one `join/index.html`, now carrying placeholders (`<title>`, `og:title`, `og:description`,
the heading, a facts block, a state marker). `GET|HEAD /join/:eventId`:
1. Validates the UUID (malformed → 404 with the invalid view).
2. Reads the event and its member counts (the same queries as `GET /events/:id`). Missing → 404; `completedAt`
   (photos deleted) → 410. Both render the invalid view with a generic title ("SnapSync — link expired") and name
   no event. A **closed** event still has its photos until the sweep completes it, so it renders as ended, with
   every member done sharing (as today's island did: it treated only `completedAt` as expired).
3. Fetches the template from storage and replaces each placeholder with an HTML-escaped value (name capped at the
   100-character limit already enforced).
4. Answers `Cache-Control: NO_CACHE` and `Referrer-Policy: no-referrer`.

A database or storage failure answers 502 with no invalid view. The existing "check your connection and reload"
message is the island's, for a failed photo-list fetch. The render functions are pure (event row + now → strings) and
unit-tested apart from the route.
*Alternatives:* bunny edge rules or an HTMLRewriter (not available, and the api owns site routing by design); a
second Astro page per state (still constant, so still needs substitution).

### D3. No cache, one storage read and one database read per view
A per-event page depends on the clock (status) and on member counts, and a cached copy would key the CDN cache on the
capability. Each view costs one storage GET for the template and one row plus one count read. Expected volume is a
handful of views per event plus preview fetches.
*Alternative:* `max-age=60`, rejected: cache keys holding identifiers, and lagging counts.

### D4. The event keeps the host's IANA zone
A nullable `zone TEXT` column (migration `0009`), set from an optional `zone` on `POST /events`. It is validated by
its shape (a name, never a bare offset like `+02:00`) and by constructing `Intl.DateTimeFormat(…, {timeZone})`. An
absent **or unusable** value stores NULL and **never refuses the create**: a zone only labels dates on the web, so a
phone whose zone name the runtime does not know must still create its event (a 400 here was the first sketch; it
traded a wrong weekday for a failed create). The column stays off the wire (`publicEvent` strips it). The app sends
the zone its `Clock` port already reads (`TimeZone.currentSystemDefault().id`). Dates render with
`Intl.DateTimeFormat("en-GB", {timeZone: zone ?? "UTC", weekday, day, month})`. `startsAt`/`endsAt` are untouched.
*Alternatives:* an offset in the date column (breaks the lexicographic cutoff compare and installed parsers); an
offset column (wrong across a DST change inside the 30-day window); no dates in previews.

### D5. The island: identifier from the path, facts from the HTML, fragment links redirected
On `/join/<uuid>` the island takes the identifier from `location.pathname`, trusts the server-rendered facts, and
fetches only the union for the count and the zip. On `/join` with a valid fragment it calls
`location.replace("/join/" + eventId)`. On an invalid fragment it shows the invalid view as today. The Play referrer
stays `v=3&d=<payload>`, rebuilt from the identifier, so the app's referrer decoder is untouched.

### D6. Links claim `/join/*`
The AASA gains `{"/": "/join/*"}` beside `{"/": "/join"}`, and the Android intent filter gains
`android:pathPrefix="/join/"`. A malformed path still opens the app, which reports a damaged invite (`join-event`).
The token gate (`app.ts`) admits `GET|HEAD /join/*`.

### D7. Share sheet title
`SystemUi.share(text)` becomes `share(text, title)`. On iOS the activity item is a `UIActivityItemSource` that answers
the link as an `NSURL` and `LPLinkMetadata(title = name, originalURL = url)` (`platform.LinkPresentation` is in the
prebuilt klib). On Android, `EXTRA_TITLE` goes on the `ACTION_SEND` intent. The mocks record the title.

### D8. Privacy policy
`site/src/pages/index.astro`'s event-page item drops "never sent when the page itself loads" and states that the link
holds the event's identifier; that opening it sends the identifier to SnapSync's service, which shows the event's
name, dates and member counts; and that anyone with the link can see this and download, like a key.

## Risks / Trade-offs

- [The identifier now reaches bunny's access logs and any URL-logging middlebox on the visitor's network] → Accepted
  in the interview. `Referrer-Policy: no-referrer` stops it leaking onwards; the policy text says so.
- [Apple's AASA CDN copy lags a deploy] → Phase 1 only *accepts* the path form. Nothing shares it until phase 2, by
  which time the CDN copy has long refreshed.
- [The edge runtime's `Intl` may lack full time-zone data] → The zone validation test runs against the real `api/`
  under Deno (`:adapter:generic:app:jvmTest` and `api-test`). If bunny's runtime differs, the fallback is UTC and the
  failure is a wrong date, never a failed page.
- [A server-side previewer counts as a page view] → Views are not recorded (`privacy-security`: a visitor leaves no
  trace), so no extra record appears.
- [In phase 1, chat previews stay generic], because the app still shares the fragment form → Phase 1 delivers only
  the share-sheet title. Previews arrive with phase 2.

## Migration Plan

1. Deploy api and site together: migration `0009` (additive, nullable), the new route, the AASA change, the policy
   text. Rollback: redeploy the previous bundle; the column is ignored.
2. Release the app (decoder for both forms, Android filter, zone on create, share title).
3. Phase 2 (separate change, after phase-1 adoption): share and QR-encode `/join/<uuid>`, and raise the app-version
   gate.
