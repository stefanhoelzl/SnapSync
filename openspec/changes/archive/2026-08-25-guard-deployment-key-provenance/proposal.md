## Why

The deployment resolver moved `TEAM_ID` and `ASSOCIATED_DOMAIN` out of the committed
`iosApp/Configuration/Config.xcconfig` into the generated `Deployment.xcconfig`. Every reader inside the
build was migrated with them; one reader outside it was not. The `ssh-mac-build` skill's manual re-sign
step still awks both keys out of `Config.xcconfig`, which now names them only in a header comment — so
both variables resolve to the **empty string**, and the step signs anyway.

The result is a valid signature claiming the wrong identity. `$(AppIdentifierPrefix)` expands to a bare
`.`, so the binary is signed with keychain group `.app.snapsync.shared` instead of
`E9Z8BADH58.app.snapsync.shared`, and `$(ASSOCIATED_DOMAIN)` expands to nothing. The app installs,
launches, and looks completely normal while having **no device id** (`device-identity` names the group
explicitly, so the read throws `errSecMissingEntitlement` -34018 and the app-scope error boundary logs
rather than aborting) and matching **no universal link**. The device id is written once and never
rewritten, so the mistake is frozen permanently on the device. Observed 2026-08-25 while building a dev
IPA.

Nothing caught it. The re-sign step's existing guard greps the signed entitlements for `*` — deliberately
key-agnostic, so it catches whichever wildcard Apple adds next — but `.app.snapsync.shared` contains no
wildcard. The guard tests for the **absence of a leaked grant**; it cannot see a substitution that
produced garbage. `codesign -v` passed too: the signature is valid, it just claims the wrong identity.

## What Changes

- **Fix the reader.** `ssh-mac-build`'s re-sign step reads `Configuration/Deployment.xcconfig`, and
  **fails closed** — refusing to sign — when either value comes back empty, instead of substituting it.
- **Add a positive post-sign assertion** beside the existing negative one: the signed entitlements of
  **both** binaries (app and extension — they must land in the same group or they hold different device
  ids) carry the real team prefix, and the app carries the associated domain. Absence of a wildcard is
  not presence of the right prefix; the two guards ask opposite questions and both are kept.
- **Guard the reference in the repo**, so the next moved key cannot leave a reader behind: a text gate in
  `:test:architecture` asserting that no file outside the resolver reads a fragment-owned key out of
  `Config.xcconfig`. This is the half that fires at the moment a key moves — the only moment anyone could
  have caught this — and it is the half a skill's prose cannot provide, because prose is advice an agent
  may or may not follow.
- **Correct four stale comments** that still cite `Config.xcconfig` as the home of keys it no longer
  carries (`KeychainDeviceIdentity.kt`, `iosApp.entitlements` ×2, both `ExportOptions*.plist`). Comment-
  only; the guard above would have flagged them, which is the point.

- **Correct the `rsync` step in the same skill** — folded in on request, and honestly unrelated to the
  invariant above. macOS 26 ships **openrsync** (protocol 29), not GNU rsync, so the documented
  `rsync -az --delete` dies with the remote's usage dump and `protocol data stream (code 12)`, naming
  nothing. Found while verifying this change on hardware; neither delta covers it, because it is a
  runbook correction rather than a contract change.

Deliberately **not** in scope: asserting the signed artifact from `./gradlew build`. That binary exists
only on a Mac at sign time, and the Linux guards scan the source tree — the artifact assertions have to
live in the sign script, which is why both halves ship together rather than one standing in for the
other.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-configuration`: adds a requirement that a key's readers follow it to the rendering that
  owns it, and that a reader resolving an empty value fails closed rather than substituting it. The
  existing "Renderings are generated, never committed" requirement covers a **missing** rendering; it
  does not cover a reader still pointed at the key's former home, which reads a file that exists and
  gets nothing.
- `architecture-guards`: adds the executable gate for the above — no file outside the resolver reads a
  fragment-owned key out of `Config.xcconfig`.

## Impact

- `.claude/skills/ssh-mac-build/SKILL.md` — the re-sign step (source file, fail-closed checks, positive
  post-sign assertion) and the `rsync` step (`--protocol=29`, ssh wrapper instead of an inline `-e`).
  Hand-written, not generated: `openspec update` does not touch it.
- `test/architecture/src/test/kotlin/app/snapsync/architecture/` — the new text gate. Non-vacuity floor
  required, per the capability's standing rule that a guard scanning nothing fails.
- `adapter/ios/ext-safe/.../KeychainDeviceIdentity.kt`, `iosApp/iosApp/iosApp.entitlements`,
  `iosApp/ExportOptions.plist`, `iosApp/ExportOptionsDevelopment.plist` — comment corrections only.
- No production Kotlin behavior changes; no module graph change; no backend or API surface.
