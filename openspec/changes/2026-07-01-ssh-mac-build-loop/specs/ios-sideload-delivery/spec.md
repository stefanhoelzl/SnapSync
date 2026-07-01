## REMOVED Requirements

### Requirement: Development IPA published as an artifact on every push

**Reason**: The every-push dev-IPA artifact was one of two ways to produce a sideloadable build. With the
headless ssh-mac build loop (dev infrastructure; `.github/workflows/ssh-mac.yml` + `CLAUDE.md` runbook)
producing dev IPAs on demand from a warm runner, the CI artifact channel is redundant. `ios.yml` no longer
exports a development IPA or uploads it as an artifact.

**Migration**: The "any branch is installable on a registered device before merge" guarantee is preserved,
not dropped — it is now served by the ssh-mac session, which builds and **manually** dev-signs an IPA that
the operator/agent installs over the usbmuxd bridge (`uvx pymobiledevice3 apps install`). This is a runbook
step, not CI behavior; no `SHALL` replaces it. Anyone who previously ran `gh run download …
snapsync-dev-ipa-<n>` uses the ssh-mac runbook instead.

### Requirement: Development signing reuses the imported Development certificate

**Reason**: This described how the CI dev export obtained a development-signed IPA (automatic signing +
`-allowProvisioningUpdates` + the Admin ASC key, minting a profile that includes registered devices). CI
performs no development export anymore, so the requirement has no subject.

**Migration**: The ssh-mac loop signs a dev IPA that installs on registered devices, but by a different
mechanism captured in the runbook: a pre-session step installs the development provisioning profile via the
Admin ASC key and then **deletes the ASC key**; in-session archives sign **manually** (imported Development
cert + installed profile), with no `-allowProvisioningUpdates` and no ASC key present while the box is
reachable. The registered-device installability outcome is unchanged.

### Requirement: The development export reuses the single gate archive

**Reason**: The dev IPA was exported from the same `SnapSync.xcarchive` that is the `ios-build` merge gate,
to keep one compile per push. With no CI dev export, there is nothing to tie to the gate archive.

**Migration**: None needed for CI (the gate archive still compiles `iosArm64` exactly once — see `ios-ci`).
The ssh-mac loop produces its own archive on its own runner, decoupled from the merge gate by design.

### Requirement: Development delivery is non-gating

**Reason**: The dev export + artifact-upload steps ran with `continue-on-error` so a delivery flake never
failed the `ios-build` check. With those steps removed, there is no non-gating delivery step to describe;
the merge gate remains the archive compile alone (see `ios-ci`).

**Migration**: None. The ssh-mac workflow is `workflow_dispatch`-only and reports no required status check,
so it cannot affect merges regardless.
