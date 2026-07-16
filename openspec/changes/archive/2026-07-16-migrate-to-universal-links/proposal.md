## Why

An invite dead-ends for anyone who does not already have SnapSync: both the QR and the shared
`snapsync://` string presuppose the app is installed, and a custom-scheme link a stranger taps does
nothing at all. `changes/archive/2026-06-27-event-invite-qr/proposal.md` named this gap explicitly and
parked it — *"An HTTPS universal link with an App Store fallback would be a separate, backend-touching
change."* This is that change.

The original decision (`changes/archive/2026-06-18-add-deeplink-config/design.md`) chose a custom scheme
because it "avoids owning a domain/AASA file and works once the app is installed — which is always true
when provisioning an installed app." All three supports have since expired: we own `snapsync.stho.net`
(`2026-06-30-add-custom-domain`), the backend already serves public HTML at `GET /`
(`2026-07-15-add-marketing-page`), and "the app is always installed" stopped being true the moment
sharing a link with *other people* became a feature. With the App Store submission imminent, a
dead-ending invite becomes a user-facing failure rather than an alpha-only wart.

## What Changes

- **BREAKING** — the `snapsync://` custom URL scheme is **removed**. `CFBundleURLTypes` comes out of
  `Info.plist`; the decoder no longer accepts it. Links already shared in the wild stop working and fail
  closed to `InvalidConfigLink`, exactly as `v=1`/`v=2` payloads do.
- **BREAKING** — the canonical event link becomes `https://snapsync.stho.net/join#v=3&d=<base64url>`.
  The payload rides in the **fragment**, never the query string, so the `eventId` — which *is* the upload
  capability — never reaches the backend, its CDN, or their access logs.
- **BREAKING** — the dev/test launch-env trigger is renamed `SNAPSYNC_DEEPLINK` → `SNAPSYNC_EVENT_LINK`
  and now carries an HTTPS URL. Every runbook that names the old variable changes with it.
- The capability `deeplink-config` is **renamed** `event-link`; `ConfigDeeplink.kt` → `EventLink.kt`;
  `CONFIG_SCHEME`/`CONFIG_HOST` → `LINK_ORIGIN`; `encodeConfigUrl`/`decodeConfigUrl` →
  `encodeEventUrl`/`decodeEventUrl`. `EventLinkPayload` already reads correctly and is unchanged.
- The app gains `com.apple.developer.associated-domains` = `applinks:snapsync.stho.net`. The extension
  does **not** — it never handles URLs.
- The backend gains two ungated routes: `GET|HEAD /.well-known/apple-app-site-association` (static JSON)
  and `GET|HEAD /join` (302 to the App Store listing). Neither reads storage nor carries a side effect.
- The payload format is **unchanged** and `v` stays **3** — only the URL form moves, and the prefix
  already signals that. A `v=4` would encode a fact the prefix encodes.
- The link domain is single-sourced from `gradle.properties` into `LINK_ORIGIN` and, via
  `Config.xcconfig`, into the entitlement. A `:test:architecture` guard asserts the backend's copy agrees,
  because Gradle cannot reach `backend/src/`.

## Capabilities

### New Capabilities
- `event-link`: The SnapSync event link — an HTTPS Universal Link (`https://snapsync.stho.net/join#…`)
  whose payload is carried in the fragment, its payload contract, the pure structural decoder, the
  `ConfigSource`/`ConfigStore` seams, the iOS Keychain-backed store, the authoritative QR generator that
  is the link's single encoder, the AASA the link depends on, and the `GET /join` App Store fallback that
  makes an invite reach someone who does not yet have the app. Supersedes `deeplink-config`.

### Modified Capabilities
- `deeplink-config`: **REMOVED** — renamed to `event-link`, which carries every requirement forward with
  the URL form changed from `snapsync://config?v=3&d=…` to `https://snapsync.stho.net/join#v=3&d=…`.
- `device-attestation`: the closed ungated-route list grows from 5 entries to 7 — `GET|HEAD /join` and
  `GET|HEAD /.well-known/apple-app-site-association`, both exact-path and method-scoped, on the same
  terms as the `GET /` marketing exception.
- `ios-app-shell`: **four** requirements. The shell declares `applinks:` and registers **no**
  `CFBundleURLTypes`; the composition root forwards an HTTPS link; the dev trigger becomes
  `SNAPSYNC_EVENT_LINK` carrying an HTTPS URL (and is noted as bypassing AASA, so it tests the decoder,
  not the link); the protected-data requirement's citation is updated.
- `event-invite-qr`: the derived invite link is the HTTPS event link (requirement renamed off
  "deeplink"), and a scan **without** the app now reaches the App Store rather than dead-ending.
- `join-event`: the gate decodes an event link; a malformed link opens the app and shows the invalid-link
  error rather than dead-ending in a browser, because the AASA matches the path only.
- `event-creation-ui`: the create screen owns the **event-link** intent (requirement renamed off
  "deeplink").
- `ios-photokit-upload`, `ios-url-session-upload`: each names a `snapsync://` config in its
  re-provision/lifecycle requirement; reworded to the event link. No mechanism changes.
- `architecture-guards`: **ADDED** a guard that the event-link domain agrees across the entitlement, the
  app's `LINK_ORIGIN`, the served AASA, and the backend's constant — the seam single-sourcing cannot
  close, where drift is silent.
- `event-album`, `leave-event`, `desktop-test-harness`, `photo-selection-policy`: citation-only updates —
  each names capability `deeplink-config`, which no longer exists. No behavior changes.

**Purpose-only edits (no delta possible).** OpenSpec deltas are requirement-level, so a spec whose
*Purpose* alone changes cannot carry a delta file. Five Purposes are therefore edited directly in
`openspec/specs/` as tasks: `marketing-site` (its claim that `GET /` is "the **sole** public,
unauthenticated route" becomes false), plus `ios-app-shell`, `event-invite-qr`, `join-event`, and
`architecture-guards` (each cites `deeplink-config`). `marketing-site` is **not** a modified capability —
no requirement of it changes; `/join` and the AASA belong to `event-link`.

## Impact

**Code.** `capability/config` (the codec, its constants, its tests); `iosApp/iosApp/Info.plist`
(`CFBundleURLTypes` removed); `iosApp/iosApp/iosApp.entitlements` (+associated-domains);
`iosApp/Configuration/Config.xcconfig` (+the domain); `gradle.properties` (+`snapsync.domain`);
`backend/src/app.ts` (two routes + two gate exceptions), `backend/src/config.ts` (+the domain),
a new AASA source constant; `test/architecture` (a new domain-agreement guard); `app/ios`
(`SNAPSYNC_EVENT_LINK`).

**Portal / signing.** Associated Domains must be enabled on the `app.snapsync` bundle ID, which
invalidates that bundle ID's provisioning profiles. CI self-heals (`CODE_SIGN_STYLE=Automatic` +
`-allowProvisioningUpdates`); the ssh-mac loop does **not** — its baked
`DEV_PROVISIONING_PROFILE_BASE64` secret must be regenerated or dev sideloads silently lose Universal
Links with no error. Only the app half changes; the extension's bundle ID gains no entitlement.

**Accepted, temporary.** `GET /join` redirects to `https://apps.apple.com/app/id6781692480`, which
returns **404 today** — the listing is not published (`iTunes lookup → resultCount: 0`). Until Submit,
the bootstrap path this change exists to build lands on an Apple error page. This is accepted because
submission is imminent; a TestFlight bridge was considered and rejected as throwaway work.

**Docs.** `CLAUDE.md`, `app/ios/CLAUDE.md`, and every on-device runbook naming `SNAPSYNC_DEEPLINK`.

**No impact.** The payload format, the ledger, the upload tiers, the attestation mechanism itself, and
`marketing-site`'s page content are untouched.
