## 1. Correct the stale references (done ahead of the gate, so it lands green)

- [x] 1.1 `KeychainDeviceIdentity.kt` — the guard composes the prefix from the generated
      `Deployment.xcconfig`, not `Config.xcconfig`
- [x] 1.2 `iosApp/iosApp/iosApp.entitlements` — both the `APS_ENVIRONMENT` and `ASSOCIATED_DOMAIN`
      comments; rewrap to the file's width
- [x] 1.3 `iosApp/ExportOptions.plist` and `iosApp/ExportOptionsDevelopment.plist` — the `teamID`
      provenance note
- [x] 1.4 Re-run `./gradlew :test:architecture:test` — green

## 2. Fix the re-sign reader (`.claude/skills/ssh-mac-build/SKILL.md`)

- [x] 2.1 Point `CFG` at `$SRC/Configuration/Deployment.xcconfig`, with a comment stating why
      `Config.xcconfig` is wrong (it names the keys only in a header comment, so the awk yields empty)
      and that the rendered domain already carries its `applinks:` prefix
- [x] 2.2 Add fail-closed checks after both extractions: refuse to sign on an empty `TEAM` or `DOMAIN`,
      naming the key, the file, and the resolver command that fixes it
- [x] 2.3 Extend the post-sign guard loop with a positive assertion over **both** binaries — the signed
      entitlements contain `$TEAM.app.snapsync.shared` — using `grep -qF`, since `.` is a regex
      metacharacter
- [x] 2.4 Add the app-only associated-domain assertion (the extension declares none)
- [x] 2.5 Keep the existing wildcard guard unchanged, and state in the comment that the two ask opposite
      questions and neither subsumes the other
- [x] 2.6 Note in the skill's warning block that a garbage substitution is a second way into the same
      failure the block already describes, distinct from a donated wildcard

## 3. Add the repo gate (`:test:architecture`)

- [x] 3.1 Derive the guarded key set from `scripts/resolve-deployment.py`'s xcconfig renderer rather than
      hand-listing it, so a newly moved key is covered with zero guard edits
- [x] 3.2 Assert no file outside the resolver extracts a guarded key out of `Config.xcconfig`; match a
      *read* (key adjacent to the filename in an extraction), not co-occurrence
- [x] 3.3 Scan the repository's text surfaces including `.claude/skills/`; exclude `openspec/changes/`
      (this change's own artifacts discuss the split) and the generated `.claude/opsx/` tree
- [x] 3.4 Add non-vacuity assertions on both the scanned file set and the derived key set, per the
      capability's standing rule that a guard scanning nothing fails
- [x] 3.5 Write the failure message to name the file, the key, and the rendering the key now lives in
- [x] 3.6 Verify the gate fires: temporarily re-point the skill at `Config.xcconfig` and confirm a red
      build, then restore
- [x] 3.7 Confirm `Config.xcconfig`'s own header comment, the resolver, and the corrected comments from
      §1 all pass
- [x] 3.8 Declare the scanned surfaces as `tasks.test` inputs (added during apply — the probe in 3.6
      revealed the task went UP-TO-DATE on a `scripts/` edit, so the guard would have silently stopped
      running). Verified it now re-runs in both directions
- [x] 3.9 Correct a fifth stale reference found while reading: `EventLinkDomainTest`'s failure message
      cited `Config.xcconfig` as the home of `$(ASSOCIATED_DOMAIN)`

## 4. Verify

- [x] 4.1 `./gradlew build` green (the gate runs inside `:test:architecture`, which gates the build)
- [x] 4.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green — structural only; it
      opens no `.kt` file and proves nothing about truth
- [x] 4.3 Run the corrected re-sign step end-to-end on the ssh-mac loop and confirm the signed
      entitlements carry `E9Z8BADH58.app.snapsync.shared` and the real `applinks:` domain
- [x] 4.4 Install the resulting IPA and confirm the app resolves a device id (no -34018 in `debug.log`)

## 5. Fold in the openrsync correction (same skill, unrelated to the invariant)

- [x] 5.1 Replace `rsync -az --delete` with `rsync -a --delete --protocol=29` — macOS 26 ships
      openrsync (protocol 29), which rejects a modern local rsync's protocol-31 options
- [x] 5.2 Replace the inline `-e "..."` / alias with a wrapper script: rsync re-splits `-e`, and the
      ProxyCommand's quotes do not survive it (this is what produced the first failure, before the
      protocol mismatch was even reachable)
- [x] 5.3 Document the failure signature verbatim — the remote's usage dump plus
      `error in rsync protocol data stream (code 12)` — since it names nothing and reads as a flag typo
- [x] 5.4 State the expiry trigger: it is a runner-image property; re-check `rsync --version` rather
      than assuming

## 6. Rework after a concurrent key move on main (found at rebase)

- [x] 6.1 `internal(config): bake the values the app reads into a plist` moved four keys to a SECOND
      rendering; the first draft's key set derived from the xcconfig rendering and would have silently
      stopped covering them
- [x] 6.2 Invert the derivation: assert nothing extracts from `Config.xcconfig` a setting it does not
      itself ASSIGN. No key list, total across renderings, covers the plist move for free
- [x] 6.3 Resolve provenance from the extraction site, not proximity — the proximity draft mis-attributed
      `grep '^MARKETING_VERSION_OUT=' "$GITHUB_ENV"` in `ios.yml` to `Config.xcconfig`
- [x] 6.4 Re-verify by probe: `TEAM_ID` (fragment) and `SENTRY_DSN` (plist) both caught,
      `MARKETING_VERSION` (still assigned) correctly not flagged
- [x] 6.5 Update both spec copies and D3 to describe the derivation that actually shipped

