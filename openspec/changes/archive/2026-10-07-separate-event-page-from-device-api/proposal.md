# Proposal

## Why

The event page is a browser, and a browser cannot prove it is a genuine SnapSync the way App Attest or an
Android key attestation does: the user controls it, so nothing can show that the real page's code is the
caller. Yet the page reads through the device API, frozen v1, because v2's version gate refuses a caller that
declares no app version. That keeps v1 alive for the page alone, and it leaves the device API's token gate with
a closed list of public event reads. Some entries exist for the page, some for the app, and nothing separates
the two. The page needs a surface of its own, with rules written for a browser, so the device API can serve
devices only and v1 can finally be retired.

## What Changes

- **A web surface of its own.** The event page reads an event's photos through one new, unversioned,
  root-level read, `/web/events/<id>/photos`. It answers GET and HEAD only, takes no token (and ignores one if
  sent), is never cached, and is logged as an anonymous read. Its answer is the page's own shape, independent
  of the device API's photo list. Each photo in it carries a ready-to-use download link, presigned for
  **1 hour**. The page asks for the list again once if a link has lapsed mid-download.
- **The page needs no metadata call.** Whether the event is encrypted, and under which key id, is rendered
  into the event's page by the server, beside the name and dates it already renders.
- **The event page leaves `/api/v1`.** It calls no versioned route at all, so no future API version can
  break it.
- **BREAKING: `/api/v1` is retired.** The mount, its routes and its legacy object-name parse are removed.
  Builds older than 0.4, the only remaining v1 callers, stop syncing. They predate the update notice
  (capability `app-update-required`), so they cannot show one. Every `/api/v1` request is refused as
  "too old", never answered `404`, so an old build cannot mistake the retirement for a deleted event.
- **The app sends its token when it reads an event.** Its event read is the one device-API call the app
  makes without a token today. It goes through the same credentialed path as every other call. As the photo
  list already does, the server checks a token when one is sent (`401` for a bad one) but does not require
  one yet: that tightening, with the `MIN_APP_VERSION` bump it needs, is a
  separate, later change, released once this build has rolled out.
- **Photo links copied from the event page stop being permanent.** A single photo's address taken from the
  event page works for about an hour; a photo withdrawn in that hour may still be served until its link
  lapses. Links the app uses are unchanged.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `privacy-security`: the requirement "A link to a single photo lasts only as long as the photo is shared"
  splits by holder. A link held by a member's app keeps today's promise: it is stable, re-checked on every
  open, and stops serving when the photo is withdrawn. A link copied from the event page works for about
  an hour, so a withdrawal reaches it within that hour.

The page's view, the zip and its progress (capability `event-site`) are unchanged, and so is what a visit
records (capability `privacy-security`, "What a visit leaves behind"). That builds older than 0.4 stop
syncing is a consequence of retiring v1, not a new requirement. No spec describes those builds, and
`app-update-required` only promises the notice to builds that can show it.

## Impact

- **api/**: a new web read beside the site routes. `/join/<id>` fills the event's key id into the page. The
  token gate lists `/web/events/<id>/photos` with the root pages. The `/api/v1` mount, `routes/v1.ts`, the
  v1 halves of `attestRoutes`/`db.ts`, and the parse in `legacy-v1.ts` are removed; the object-name composer
  v2 still uses moves next to its caller. `/api/v1/*` answers `426`. Tests: `v1.test.ts` and
  `legacy-v1.test.ts` are deleted, and the v1 cases in the other suites are removed or moved to v2.
- **site/**: `join.astro` reads the key id from the page and the photo list from `/web/events/<id>/photos`.
  `withDownloadUrls` goes away, and the page re-fetches the list on a lapsed link.
- **api/ (event read)**: `GET /api/v2/events/<id>` checks an optional token, as the union does.
- **App (`:domain:ports`, `:domain:services`, `:adapter:generic:app`, mocks)**: `Backend.getEvent` takes a
  token, `CredentialedBackend.getEvent` becomes `gated`, and `HttpBackend` sends the token.
  `GatedPaths.verifiesToken` covers the event read; its public-read entries stay until the later change.
- **docs/architecture.md**: the route table, the gate's closed list, and the v1 section are removed.
  `docs/deployment.md` loses its v1 references.
- **Out of scope (the later change)**: requiring the token on v2's event reads, raising `MIN_APP_VERSION`,
  removing `isPublicEventRead` and its `GatedPaths.kt` mirror, and any `app-update-required` or
  `join-event` delta that comes with them.
