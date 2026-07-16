## 1. Portal and signing prerequisites

- [x] 1.1 Enable the **Associated Domains** capability on the `app.snapsync` bundle ID via the App Store Connect API (`proton-env -- uvx --from codemagic-cli-tools app-store-connect bundle-ids …`). Do **not** enable it on `app.snapsync.BackgroundUpload` — the extension never handles URLs. Confirm with `bundle-ids list --json`.
- [x] 1.2 Regenerate `DEV_PROVISIONING_PROFILE_BASE64`: enabling the capability invalidates the **app's** profiles (the extension's are untouched, but the secret is a tar of both). Re-export both `embedded.mobileprovision` files, tar, and `gh secret set`. Skipping this makes dev sideloads silently open Safari instead of the app — no error, no log line.
- [x] 1.3 Record the baseline: `curl -o /dev/null -w '%{http_code}' https://app-site-association.cdn-apple.com/a/v1/snapsync.stho.net` returns **404** today. This is the signal that flips to 200 when the change lands.

## 2. Single-source the link domain

- [x] 2.1 Add `snapsync.domain=snapsync.stho.net` to `gradle.properties`.
- [x] 2.2 Generate `LINK_ORIGIN = "https://<domain>"` into `:capability:config` `commonMain` from that property (build-time generation; no hand-maintained copy).
- [x] 2.3 Add the domain to `iosApp/Configuration/Config.xcconfig` and reference it from `iosApp/iosApp/iosApp.entitlements` as `applinks:$(…)`. Note `BACKGROUND_UPLOAD_URL_BASE` already pins the same host — do not couple them; the guard in §6 asserts agreement.
- [x] 2.4 Add the domain constant to `backend/src/config.ts` (Gradle cannot reach `backend/`; see design §7).

## 3. The codec (capability `event-link`)

- [x] 3.1 Rename `capability/config/src/commonMain/.../ConfigDeeplink.kt` → `EventLink.kt`. Replace `CONFIG_SCHEME`/`CONFIG_HOST` with the generated `LINK_ORIGIN`; set `PREFIX = "$LINK_ORIGIN/join#"`.
- [x] 3.2 Rename `encodeConfigUrl`/`decodeConfigUrl` → `encodeEventUrl`/`decodeEventUrl`. Keep the hand-rolled `startsWith` + split — do **not** introduce `URLComponents`-style parsing (design §1).
- [x] 3.3 Split the payload on `#` rather than `?`. `v` stays **3**; `EventLinkPayload` is unchanged.
- [x] 3.4 Update `capability/config/src/jvmMain/.../QrGeneratorMain.kt` — it encodes via the shared codec, so it should need no logic change; verify its emitted URL is the HTTPS form.
- [x] 3.5 Update every call site: `StatusContainerHost.inviteUrl()`, the create/join paths, `SnapSyncRoot`.

## 4. iOS app shell

- [x] 4.1 Remove `CFBundleURLTypes` from `iosApp/iosApp/Info.plist` entirely (and its stale "carries the S3 config" comment).
- [x] 4.2 Add `com.apple.developer.associated-domains` = `[applinks:$(…)]` to `iosApp/iosApp/iosApp.entitlements`. Do **not** add it to `BackgroundUploadExtension.entitlements`.
- [x] 4.3 Rename the launch-env trigger `SNAPSYNC_DEEPLINK` → `SNAPSYNC_EVENT_LINK` in `SnapSyncRoot.kt`. `.onOpenURL` in `iOSApp.swift` stays a pass-through — no Swift parsing, and the full URL **including the fragment** is forwarded.

## 5. Backend

- [x] 5.1 Add the AASA as a source-owned constant (text-import, mirroring `landing.html`): `{"applinks":{"details":[{"appIDs":["<TEAM_ID>.app.snapsync"],"components":[{"/":"/join"}]}]}}`. Path-only match — no `?`, no `#`.
- [x] 5.2 Add `GET|HEAD /.well-known/apple-app-site-association` → the constant, `Content-Type: application/json`, **no redirect**.
- [x] 5.3 Add `GET|HEAD /join` → 302 to `https://apps.apple.com/app/id6781692480`. Read no storage; do not attempt to read the payload (it is in the fragment and never arrives).
- [x] 5.4 In the single `app.use("*")` gate at `app.ts:664`, admit both new paths — **exact-path and method-scoped**, mirroring the existing `path === "/"` exception. Comment each with its `event-link` / closed-list tie.

## 6. Architecture guard

- [x] 6.1 Add a `:test:architecture` guard asserting the domain agrees across the entitlement's `applinks:`, the app's `LINK_ORIGIN`, the served AASA constant, and `backend/src/config.ts`. Model it on `DataProtectionEntitlementTest.kt`, including its vacuity check (fail loudly if a file moved rather than scanning nothing).

## 7. Spec Purpose edits (no delta possible — see proposal)

- [x] 7.1 `openspec/specs/marketing-site/spec.md` — its Purpose calls `GET /` "the **sole** public, unauthenticated route"; amend to name the closed set and point at `event-link` for the AASA and `/join`.
- [x] 7.2 `openspec/specs/architecture-guards/spec.md` Purpose — `deeplink-config` → `event-link`.
- [x] 7.3 `openspec/specs/ios-app-shell/spec.md`, `event-invite-qr/spec.md`, `join-event/spec.md` Purposes — reword `snapsync://` / `deeplink` to the event link.

## 8. Tests

- [x] 8.1 `capability/config` `commonTest`: update every URL literal to the HTTPS fragment form. Add: a retired `snapsync://config?v=3&d=…` URL **fails** to decode; a foreign origin fails; the canonical encode puts the payload after `#` and leaves the query empty.
- [x] 8.2 Keep the existing round-trip, padding-optional-decode, v1/v2-rejection, unknown-key-rejection, and each dev/test-key test — only the URL form changes.
- [x] 8.3 Backend (Deno): AASA → `200`, `application/json`, no redirect, parses, declares only the app's appID and the `/join` path. `/join` → 302 to the App Store, identical for every payload.
- [x] 8.4 Backend regression: both new routes answer **without** an `Authorization` header; the gate is not widened — an unauthenticated `GET /joinx` or `/.well-known/other` still `401`s, and a non-`GET`/`HEAD` method on either named path still `401`s.
- [x] 8.5 Update `:test:integration` and any harness fixtures carrying a `snapsync://` literal.

## 9. Docs

- [x] 9.1 `CLAUDE.md` and `app/ios/CLAUDE.md`: `SNAPSYNC_DEEPLINK` → `SNAPSYNC_EVENT_LINK` in every runbook snippet, with the HTTPS URL form.
- [x] 9.2 Note in the on-device runbook that the env-var trigger **bypasses** AASA — it exercises the decode→join path, not the Universal Link.
- [x] 9.3 Record the AASA CDN check (`app-site-association.cdn-apple.com/a/v1/<domain>`) as the cheap verification, and that **Apple's own apps are not AASA-wired** so they cannot proxy for a link test.

## 10. Build and verify

- [x] 10.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` (the Linux proxy for the iOS source sets).
- [x] 10.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [ ] 10.3 After deploy: `curl -sSI https://snapsync.stho.net/.well-known/apple-app-site-association` **through the pull zone** → `200`, `application/json`, no redirect.
- [ ] 10.4 After deploy: `curl .../a/v1/snapsync.stho.net` on Apple's CDN flips **404 → 200** (proves Apple fetched and parsed it).
- [ ] 10.5 On the SE2: install, open a real event link, confirm the app opens **on the event**. This single observation proves the entitlement, the AASA, and fragment delivery together. A stripped fragment would surface visibly as `InvalidConfigLink`.
- [ ] 10.6 Known and accepted until the App Store listing is published: `/join` redirects to a 404 (`itunes.apple.com/lookup?id=6781692480` → `resultCount: 0`). Do not treat this as a regression.
