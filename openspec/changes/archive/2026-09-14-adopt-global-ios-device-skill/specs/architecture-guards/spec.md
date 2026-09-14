## REMOVED Requirements

### Requirement: No reader is left behind on a moved deployment key

**Reason**: The reader this guard was written for — the dev re-sign extracting `TEAM_ID` and
`ASSOCIATED_DOMAIN` with `awk` — leaves the repository with `scripts/dev-sign`. Signing moves to the
global `ios-device` skill's `sign`, which reads no xcconfig at all: it expands each target's
entitlements from that target's real `xcodebuild -showBuildSettings` output, and refuses any variable
that is absent or expands to empty. Once dev-sign is gone, nothing left in the tree extracts a key from
either xcconfig. So the guard would pass vacuously while inspecting nothing, which is worse than no
guard. Its own non-vacuity check, anchored on that reader, would fail the build instead.

**Migration**: None required of a reader. The contract the guard protected stays in force in
`deployment-configuration` ("A key's readers follow it to the rendering that owns it"): a reader that
resolves an empty value for a key it requires fails closed. The global `sign` meets it by construction.
A future in-repo script that extracts a deployment key is bound by that requirement, and should read
build settings rather than parse an xcconfig.
