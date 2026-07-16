## Context

The `snapsync://` config deeplink was chosen in `2026-06-18-add-deeplink-config` to avoid owning a
domain and an AASA file, on the premise that "the app is always installed when provisioning an installed
app." That premise held while the link was a provisioning mechanism for one's own device. It broke when
`2026-06-27-event-invite-qr` turned the link into something you hand to *other people*, and that change
recorded the gap rather than closing it.

Today the primary invite channel is a QR displayed in-app and scanned by the recipient's stock Camera
app; the secondary is a `UIActivityViewController` share of the raw `snapsync://` string. The secondary
channel is already inert — messengers linkify `http`/`https` only, so a shared custom-scheme string
arrives as dead text. The primary channel works, but only for people who already have SnapSync.

Constraints that shaped this design:

- **The `eventId` is the upload capability.** The edge authorizes by event id alone. Anything that
  handles the link handles a credential.
- **The backend is one bunny Edge Script behind a pull zone**, and everything must hold *as observed
  through the pull zone* (`backend/README.md:11`). Every route is attestation-gated by default; the
  ungated list is closed and spec-enforced.
- **`:app:ios` is wiring-only and untested.** All logic lives in tested `domain`/`capability` modules,
  so the decoder must stay in `commonMain` and the Swift host must stay a pass-through.
- **iOS does no deferred deep linking.** A link tapped before install is gone after install.

### What was established empirically

These were tested rather than assumed, on real hardware, before the design was fixed:

| Claim | Method | Result |
|---|---|---|
| The Camera app honors AASA on a scanned QR | QR of a verified-AASA third-party link | **Yes** — the app opened, not Safari |
| iOS delivers the **fragment** to the app | QR of `…/wiki/QR_code#Risks` | **Yes** — the app opened *on the Risks section* |
| WhatsApp's in-app browser hijacks universal links | link tapped in WhatsApp | **No** — "Open Link" opened the app |
| Apple's own apps can proxy for an AASA test | curl `apps`/`maps`/`music.apple.com` | **No** — empty file / 404 / HTML. **They do not use AASA at all.** |
| The App Store listing exists | `itunes.apple.com/lookup?id=6781692480` | **No** — `resultCount: 0` |

Two of those deserve emphasis for whoever reads this next. **Apple's first-party apps are special-cased
in the OS, not AASA-wired** — so an `apps.apple.com` QR is a worthless test target that appears to pass.
And **Apple publishes its cached copy of every AASA** at
`https://app-site-association.cdn-apple.com/a/v1/<domain>`, readable from anywhere with no device: it
returns 404 for `snapsync.stho.net` today and flips to 200 when this lands. That endpoint is the cheapest
check we have and the reason CDN staleness is tolerable (below).

## Goals / Non-Goals

**Goals:**

- An invite reaches someone who does not yet have SnapSync.
- The `eventId` never transits a server, preserving the property `deeplink-config` asserts today.
- One authoritative codec; producer and consumer cannot drift.
- The decoder stays pure, structural, `commonMain`, and never throws.
- Better link fidelity in messengers, as a consequence rather than an aim.

**Non-Goals:**

- **Deferred deep linking.** No fingerprint matching, no clipboard handoff, no third-party SDK. A fresh
  installer re-taps the original link.
- **A rich `/join` landing experience.** See the decision below.
- **Changing the payload.** Format and version are untouched.
- **Solving the pre-publication 404.** Accepted and temporary; see Risks.
- **Establishing bunny's access-log posture.** Moot here (the fragment never reaches them) and
  pre-existing for presigned S3 URLs (`backend/README.md:64`).

## Decisions

### 1. The payload rides in the fragment, not the query string

`https://snapsync.stho.net/join#v=3&d=<base64url>`.

Browsers never transmit the fragment. So even when the link is opened *without* the app — the one case
that reaches our infrastructure at all — the pull zone sees exactly `GET /join` and nothing more. The
`eventId` stays off the wire, out of Bunny's edge logs, and out of any cache key.

This is the crux of the whole design, and it deserves being stated plainly: **`deeplink-config`'s
sentence "there is no server, token, or Universal Link" bundles three claims, and only one of them is
changing.** "No token" is untouched. "No server" — meaning both self-containment and *no server ever sees
the capability* — is preserved, but no longer for free. Under a custom scheme it was incidental: no
server could see a `snapsync://` URL at all. Under a Universal Link it is *purchased*, by the fragment.
Moving the payload to `?` would look like tidying and would silently forfeit it. The spec therefore says
`SHALL … never the query string` rather than merely describing the shape.

*Alternatives considered.* **Query string** — conventional, and what the first sketch used; rejected
because it leaks the capability to infrastructure whose logging posture is undocumented rather than
affirmatively safe. **`/e/<uuid>` path form** — prettiest and shortest QR; rejected because it discards
the `v`/`d` envelope, leaving the version and every dev/test key (`autoJoin`, `minPhotoDate`,
`direction`, `saveToAlbum`) without a carrier, and `SNAPSYNC_EVENT_LINK`'s `autoJoin=true` with nowhere
to live.

*Why this is safe.* Fragment delivery is **not documented** by Apple — `webpageURL` is specified only as
"the URL that the user is accessing." It was therefore tested (above) and proven. Two further facts back
it: AASA's `components` schema treats `#` as a first-class matchable key (Apple's own example excludes
`#no_universal_links`), which would be incoherent if fragments weren't delivered; and the known
fragment-related failure — reported as `https://x/#/user?id=ABC` — is people asking AASA to match a `?`
*nested inside* a `#`, which we do not do, and which cannot touch us because our decoder is a hand-rolled
`startsWith` + split that never constructs `URLComponents`.

### 2. `snapsync://` is removed outright, not kept alongside

The decoder accepts one prefix. Two canonical forms would make "what does a QR contain" unanswerable and
violate the single-codec invariant.

The conventional reason to keep a custom scheme is the web-fallback escape hatch: a page that reads
`location.hash` in JS and offers an "Open in app" button for users whose Universal Link failed to fire.
We forgo it. Test C is what makes that affordable — WhatsApp, the channel we most expected to hijack
links, honors them. The residual surface (Safari address-bar typing, the same-domain rule, WKWebView
browsers we haven't measured) is small, and nothing regresses: every one of those cases is *dead text*
today.

*Alternative considered.* Keep `snapsync://` as a decode-only input; rejected as carrying a second URL
form forever to serve a case measurement suggests is rare.

### 3. `GET /join` is a 302 to the App Store, not a page

*Alternative considered and rejected: serving `LANDING_HTML` at `/join`.* Recorded because the cost is
real and was weighed, not missed. A 302 carries no text, so a fresh installer is never told the one thing
our no-deferred-linking decision depends on — *install, then tap the link again*. They install, press
"OPEN" from the App Store, land on an empty setup gate, and have no reason to suspect the fix is to
re-tap a link they already tapped. A 302 also cannot carry an `apple-itunes-app` smart banner, which
would otherwise hand `app-argument` back to an installed app.

Accepted anyway: the QR is the primary channel and none of this touches it; test C shrank the
installed-user-in-a-browser case to near-nothing; and the fragment makes a per-event page impossible in
principle (the backend cannot read `d`, by construction), so the page could only ever have been generic.
The two decisions are coherent: with no per-event data to render, a redirect is the honest shape.

### 4. AASA matches `/join` only — not the query, not the fragment

```json
{ "applinks": { "details": [
  { "appIDs": ["E9Z8BADH58.app.snapsync"], "components": [{ "/": "/join" }] } ] } }
```

A malformed link then opens the app and surfaces a visible `InvalidConfigLink` rather than dead-ending
invisibly in Safari — a visible failure beats a silent one. Narrow paths keep `/`, `/events/:id`, and
`/attest/*` in the browser; a broad `/*` would hijack our own marketing page into the app. The extension
is excluded: it never handles URLs.

This also sidesteps the documented `#`-plus-`?` matching bug entirely, since we ask AASA to match neither.

### 5. Plain `applinks:`, no `?mode=developer`

`Config.xcconfig` already parametrizes a dev→production split for `APS_ENVIRONMENT`, and the same shape
would have given dev builds CDN-free AASA iteration. Rejected: it is xcconfig plumbing for a value that
changes never, and the cost it buys off — invisible CDN staleness — is not actually invisible. Apple's
CDN endpoint is directly readable (see Context), so a stale AASA is one curl away from being diagnosed.

### 6. Strict origin match in the decoder

`PREFIX = "$LINK_ORIGIN/join#"`, one `startsWith`, byte-identical in shape to today.

Note the *security* argument for this is null and should not be re-invented: with `snapsync://` gone, the
only production paths into `onOpenUrl` are `.onOpenURL` — which fires for a Universal Link only when our
own entitlement names the domain — and the dev launch env var. A foreign origin cannot arrive. Strict
matching is chosen purely because it is *less code*: path-only matching means searching for `/join#`
inside an arbitrary string, which invites edge cases (`https://x/foo/join#…`) that a prefix match does
not have, while declining to use the constant we single-source anyway.

### 7. The domain is single-sourced where it can be, and guarded where it cannot

`gradle.properties` → generated `LINK_ORIGIN`; `Config.xcconfig` → the entitlement. The backend's copy is
**structurally unreachable** from Gradle: `backend/` is a Deno tree deployed by a separate, path-scoped
workflow that ships code only, never config (`backend/README.md:194`), because bunny issues no scoped API
key. So a `:test:architecture` guard asserts the backend constant agrees — modeled on
`DataProtectionEntitlementTest`, vacuity check included.

*Alternative considered.* Generating `backend/src/config.ts` from Gradle; rejected because it would make
a backend-only change depend on a Gradle run, coupling two deliberately independent pipelines.

### 8. `/join` and the AASA belong to `event-link`, not `marketing-site`

They exist solely to make the event link work. `marketing-site` is the public face and the App Store
submission surface — privacy policy, terms, support. Its Purpose is amended only because its claim to be
the "sole public, unauthenticated route" becomes false.

## Risks / Trade-offs

- **`/join` redirects to a 404 until the App Store listing is published** → Accepted, not mitigated.
  Submission is imminent; a TestFlight bridge (`testflight.apple.com/join/pvqgV7Uz`, verified live and
  itself AASA-wired) was considered and rejected as throwaway work for a window measured in days. Until
  then this change delivers only its secondary benefit, link robustness.
- **A stale `DEV_PROVISIONING_PROFILE_BASE64` silently breaks dev sideloads** → the profile lacks
  `associated-domains`, so links open Safari with no error and no log line. Regenerate the secret as part
  of this change; the tasks call it out. CI is unaffected (automatic signing + `-allowProvisioningUpdates`).
- **Links already in the wild break** → accepted. Alpha, and invite QRs are re-derived from the Keychain
  on every render, so almost nothing durable exists. Failure is visible (`InvalidConfigLink`).
- **The capability rename restates six requirements verbatim-except-one-word** in specs unrelated to
  universal links (`join-event`, `event-album`, `leave-event`, `desktop-test-harness`,
  `photo-selection-policy`, plus `architecture-guards`' Purpose) → OpenSpec offers no capability-level
  rename; deltas are requirement-level only. Splitting the rename into a follow-up change was considered
  and rejected in favor of not leaving the tree half-named.
- **Fragment delivery is undocumented by Apple** → proven by test, and the fallback is one constant
  (`…/join#` → `…/join?`) with the AASA, entitlement, backend, payload, and decoder all unchanged. The
  failure is visible, not silent.

## Migration Plan

Order resolves itself and needs no orchestration: the AASA is **inert until an app declares the domain**,
so deploying it early is harmless, and `backend-deploy.yml` (path-scoped, ~1 min) lands long before the
same merge's build reaches TestFlight.

1. Enable **Associated Domains** on the `app.snapsync` bundle ID (App Store Connect API via `proton-env`).
   This invalidates that bundle ID's profiles. The extension's bundle ID is untouched.
2. Regenerate `DEV_PROVISIONING_PROFILE_BASE64` (a tar of *both* profiles; only the app half changed).
3. Merge. Backend deploys; the app follows via TestFlight.
4. Verify: `app-site-association.cdn-apple.com/a/v1/snapsync.stho.net` flips **404 → 200**; the origin
   serves `application/json` with no redirect *through the pull zone*; a real link opens the app on the
   event — which proves the entitlement, the AASA, and fragment delivery in one observation.

**Rollback.** Revert the app build (links revert to inert text; nothing dead-ends worse than today) and
leave the backend routes in place — they are harmless once no app claims the domain.

## Open Questions

- **Does `swcd` log the AASA evaluation to the device syslog?** If so, `idevicesyslog` would observe the
  entitlement↔AASA handshake with no tunnel, no WDA, and no tap. Untested, and deliberately not relied
  on: tests A and B settled the mechanism, so this is only a debugging aid if something breaks later.
- **Do WKWebView-based in-app browsers other than WhatsApp hijack universal links?** Unmeasured. No
  decision hangs on it — every outcome leads to the same action — so it is recorded, not chased.
