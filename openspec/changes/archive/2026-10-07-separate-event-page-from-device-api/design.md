# Design

## Context

See proposal.md, "Why". Current state:

- `site/src/pages/join.astro` makes three calls: `GET /api/v1/events/<id>` (only to read `keyId`),
  `GET /api/v1/events/<id>/files?urls=false`, and per file the download redirect
  `/api/v1/events/<e>/files/devices/<d>/<a>/<role>` (302 to a 7-day presign). It builds those addresses
  itself (`withDownloadUrls`).
- The token gate in `api/src/app.ts` exempts these reads by path (`isPublicEventRead`). The version gate
  exempts all of v1 and v2's download redirect. The app's `GatedPaths.kt` mirrors the token gate's list.
- `/join/<id>` already reads the event row server-side and fills the built page's `%%TOKEN%%`s
  (`routes/event-page.ts`).
- The app's `getEvent` sends no token (`HttpBackend`: `token = null`; `CredentialedBackend`: `observed`,
  not `gated`). The union read is `gated`, and its token is optional on the server (`changes/incremental-union`
  D5).
- v1 is frozen and exists for builds older than 0.4 and for the event page. `legacy-v1.ts` holds the v1
  identity parse and `legacyKeyFor`, the object-name composer that `db.ts` and v2 also use.
- `deploy.yml` deploys `api` and `site` as independent, concurrent jobs.

## Goals / Non-Goals

**Goals:**
- The event page calls no versioned route. Its read has rules written for a browser.
- `/api/v1` is gone: the mount, its routes, its test suites and its v1-only branches in shared code.
- The app sends its token on every event read, ready for the later change that requires it.

**Non-Goals:**
- Requiring a token on v2's event reads, raising `MIN_APP_VERSION`, or removing `isPublicEventRead` /
  `GatedPaths.kt`'s public reads. That is the later change, once this build has rolled out.
- Rate limiting or a challenge (Turnstile, Private Access Tokens) on the web read. The exposure is the same
  as today's public reads. Revisit if the anonymous-read log shows abuse.
- Server-rendering the photo list into the page.
- Changing the app's download redirect or its 7-day presign.

## Decisions

### D1. The browser does not attest; the event id is its credential

A browser has no equivalent of App Attest or a Keystore key attestation. The user controls the page's code,
so no signal can tell the genuine page from a script.
- WebAuthn attestation proves an authenticator's make, not the calling code.
- Private Access Tokens and Turnstile attest "probably a human on a real device". That is anti-abuse, not
  client identity.

So the web surface is authorized exactly as the event page always was: by possession of the event id (plus
the `#k=` key for an encrypted event, which never leaves the browser). This is a statement of fact, not a
weakening. Nothing the device API's attestation proved ever covered the page.

### D2. A root-level web surface, not the page on v2 with an exemption

The page's read moves to `GET|HEAD /web/events/<id>/photos`, beside the site's root routes, outside every
`/api/vN` mount.
- *Rejected: the page calls v2, and the version gate exempts it* (no header, or a `web` marker). That adds
  an exception to the gate meant to have none, and a browser can send any header, so a marker proves
  nothing. It also re-couples the page to device-API versions: it would move again when v2 retires.
- *Rejected: render the list into `/join/<id>`.* Every link preview and crawler would pull the whole list,
  a large event makes the page heavy, and the list can no longer be re-fetched without a reload. It remains
  possible later on top of this design.
- *Why `/web/` and not `/join/<id>/photos`:* the AASA claims `/join/*`, and the Android intent filter
  claims `pathPrefix="/join/"`. `fetch()` never triggers either, but a visitor who opens a photo address
  directly would be handed to the app with a link it cannot parse.

The token gate lists the path with the root routes (exact shape, `^/web/events/[^/]+/photos$`, GET/HEAD
only, never a prefix), with its own comment: a browser surface authorized by event-id possession. The version
gate is untouched, because the path carries no version.

### D3. One endpoint with inline 1-hour presigns, not a web download redirect

The answer carries each resource's presigned storage URL. There is no per-photo web route.
- *Rejected: a web redirect route* (`/web/events/<id>/photos/<d>/<a>/<role>` → 302). It kept the
  existing promise (withdrawal immediate, a link valid for days) at the cost of a second route and a hop
  per file. The spec was changed instead (privacy-security delta): the page's links live about an hour.
- The 7-day expiry the app's redirect signs is forced by iOS background sessions, which resume from the
  redirect target (`changes/incremental-union` D2). A browser uses its links within minutes, so a short
  expiry is free, and it bounds the withdrawal window.
- Signing is a local HMAC (`aws.sign` with `signQuery`), with no network call, so N signatures per read cost
  CPU only. `presignDownloadUrl` gains an expiry parameter; the device routes keep `PRESIGN_EXPIRY_SECONDS`,
  and the web read passes `WEB_PRESIGN_EXPIRY_SECONDS = 3600`.
- A zip of a large event on a slow connection can outlast the hour. The page treats a `403` on a photo GET
  as a lapsed link: it re-reads the list once, matches the remaining items by
  `(deviceId, assetId, role)`, and continues. A second lapse, or an item missing from the new list, fails
  the download as today ("The download shows its progress and can be retried").

### D4. The web answer is the page's own shape

`[{deviceId, assetId, resources: [{role, filename, url}]}]`: what `zipEntries` and `fileAssociatedData`
need, and nothing more (no `key`, no `contentType`, no `creationDate`, no cursor header). It is built by its
own small serializer over the same assembly the union uses (`unionRows` plus the completeness filter,
extracted into a shared function in `routes/shared.ts`), so the two cannot disagree about which photos are in
the event. The device union's wire can then change without breaking the page.

- No token is read. An `Authorization` header is ignored, not verified: a browser surface has no credential
  to check.
- The read is logged through `logUnionFetch` with `deviceId = null` and no trigger. That is today's
  anonymous-read record, so "What a visit leaves behind" holds unchanged.
- `Cache-Control: NO_CACHE`, because the answer carries time-limited links.
- `404` for an absent event (`gateEvent`), `502` for a read failure. The page maps them as it does today.

### D5. `keyId` is rendered into the page

`eventFilling` fills a new token, `%%KEY_ID%%`, into `data-key-id` on the page root: the event's key id, or
empty for an unencrypted event. The `pending` and `invalid` fillings fill it empty. The key id is not secret
(it is an HKDF tag of the key, `docs/architecture.md`), and the page already renders the row it comes from.
The page's metadata call goes away entirely. The site build's leftover-token check covers the new token like
the others.

### D6. v1 is retired, and its paths answer `426`, never `404`

The `/api/v1` mount is removed. Every `/api/v1/*` request is answered by one middleware placed where the
version gate sits: `426 {error: "app too old", minAppVersion}`, with `NO_CACHE`.
- *Why not a bare `404`:* a pre-0.4 build reads `404` on `GET /events/<id>` as a deleted event, which is one
  of the two witnesses of its self-leave (`docs/architecture.md`). Retirement must never look like
  deletion. `426` is a status those builds treat as a failure and retry; it does not delete anything locally.
- *Why not keep v1 behind the gate a while:* the page was v1's only caller that could be moved. Builds
  older than 0.4 cannot be updated in place, and they predate the update notice, so waiting buys nothing for
  them.

Removed: `routes/v1.ts`, the v1 branches in `attestRoutes` (the flat body, `401` on a stale challenge) and
`db.ts` (the `legacy` publish mode and its resource upsert), the union's `version === 1` presign branch, the
`identityFromLegacyKey` parse, `v1.test.ts`, and `legacy-v1.test.ts`'s parse cases. `legacyKeyFor` is still
used by v2 and `db.ts`. It moves to a non-legacy home (`api/src/object-names.ts`), with its tests.
`splitVersion`'s `ApiVersion` narrows to `2`; a `/api/v1` path still resolves as a prefix, so the retirement
middleware can match it.

The architecture laws still hold for the sweep and migrations: no migration is needed. v1's rows are
ordinary rows, and the v1-only behaviour lived in code, not schema.

### D7. The app's event read carries its token, and the server checks it when sent

- `Backend.getEvent(token: String?, eventId)`; `CredentialedBackend.getEvent` = `gated { … }`;
  `HttpBackend` passes it. `verifiesToken` adds the event read, the same rule as the union's: public, but a
  sent token is checked. `HttpBackendTest`'s pin (a token is passed only where `verifiesToken` holds) and
  `GatedPathPinTest` cover it.
- The server's `GET /events/<id>` checks a token when one is sent (`401` for a bad one), as the union does
  (D5 of `changes/incremental-union`), so a `401` is a verdict the app recovers from by re-attesting. Absent,
  the read stays anonymous: old v2 builds are unaffected.
- `BackendMock` and the Backend contract gain the same rule.
- First contact now attests before the join preview (`gated` mints on demand). The join itself already
  needs a token, so a device that cannot attest could not join before either. Whether the preview's failure
  shape changes is the later change's question, when the token becomes required.

## Risks / Trade-offs

- [Up to an hour's withdrawal window on the page's links] → accepted in the spec delta. It applies only to
  a page loaded before the withdrawal. A fresh load lists only what is shared.
- [`api` and `site` deploy concurrently, so for minutes one runs ahead of the other] → new api + old page:
  the old page's v1 calls get `426` and it shows "couldn't load". New page + old api: `/web/…` is `404`, and
  the page shows the invalid view. Both clear on reload once both jobs finish (about minutes). Accepted:
  no data is at risk, and the page carries no state. If that is too much, the PR can be merged in two
  steps (web surface first), at the cost of one more deploy.
- [Pre-0.4 builds stop syncing with no notice] → inherent to retiring v1. They cannot show the update
  notice. `426` keeps them from mistaking it for a deleted event.
- [A bot holding a leaked link can pull fresh presigns hourly] → no worse than today, where the redirect
  signs per request. The anonymous-read log shows it, and rate limiting is the documented next step.
- [`%%KEY_ID%%` left unfilled by an old api during the deploy window] → the page treats a value that is
  not a key id (fails the key-id shape) as "unknown". It then shows the "couldn't load" status, never
  decrypts with a guessed key, and never claims the event is unencrypted.

## Migration Plan

One PR, three commits, in order: (1) the web surface, the `keyId` rendering and the page's switch; (2) the
v1 retirement; (3) the app's token on `getEvent`. No schema migration, and no deploy flag. Rollback is a
revert: the `api` deploy restores v1 and the old site page is restored by the `site` job.

Follow-up (a separate change, after the build with commit 3 has rolled out): raise `MIN_APP_VERSION`, require
the token on v2's event reads, and remove `isPublicEventRead` and its `GatedPaths.kt` mirror.
