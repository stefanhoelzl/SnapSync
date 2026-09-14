## Context

The phone loop used to be SnapSync-only tooling, built up across several incidents:
- `scripts/device-lease` and `scripts/device-guard`, after two workspaces wedged the installer on
  2026-08-09;
- `scripts/dev-sign`, after wildcard entitlements killed universal links and split the keychain group,
  and after an empty `TEAM_ID` substitution on 2026-08-25;
- the SIGKILL-first install recipe in the `ios-device` skill, after the black-screen and hung-reinstall
  traps.

That tooling has since been generalized into a **global** skill, `~/.claude/skills/ios-device`, because
the phone is shared by every project on the machine, not only by SnapSync's workspaces. The global
skill carries every lesson above as scripts:
- `lease`: one lock per UDID under `~/.cache/ios-device/locks/`.
- `guard`: position-based, so it no longer denies a command that merely names a device tool.
- `sign`: a Linux rcodesign re-sign whose checks are a superset of dev-sign's.
- `install`: a three-state pid lookup, SIGKILL, a bounded install, and one retry.

SnapSync has not migrated, so today both sets run side by side:
- two hooks fire on every Bash call;
- two uncoordinated locks exist;
- two skills are named `ios-device`.

Signing on the Mac depends on two GitHub secrets. One of them, the profile tar, is re-baked by hand.

The decisions below were settled in an interview before this proposal. Rationale for the original
tooling lives in the archived records it cites, and in the headers of the deleted scripts.

## Goals / Non-Goals

**Goals:**
- One lease, one guard, one signer and one installer for the phone, shared with every project.
- No SnapSync-specific device tooling that duplicates the global skill.
- Keep every SnapSync-specific fact an agent needs on device, in a skill that no longer collides.
- Name the deployment once per dev build.
- The macOS runner holds no signing material.

**Non-Goals:**
- Changing the app, the extension, CI (`ios.yml`), TestFlight or App Store signing.
- The simulator loop and `scripts/sim-sign`, which the global skill does not cover.
- Closing the transition window for sibling workspaces on stale branches (see Risks).
- Changing the `deployment-configuration` contract.

## Decisions

### Adopt all five pieces, not only the lease

The lease and the guard alone would fix coordination. But keeping `dev-sign` keeps the baked profile tar
and its manual refresh, and a second install recipe that drifts from the global `install`.
- **Chosen**: lease, guard, sign, install and the build loop all move to the global skill.
- **Rejected**: lease + guard only, and lease + guard + install. Both keep a SnapSync-only signer whose
  lessons are already encoded, more strictly, in `sign`. It checks that signed entitlements *equal* the
  claim, not only that there is no wildcard, and that the profile grants every claimed key.

### Sign on Linux with rcodesign

Signing happens on the operator's machine with the certificate injected by `secrets-env`. Profiles are
fetched from App Store Connect and cached.

`sign`'s refusals cover dev-sign's four guards:
- a wildcard in a claim;
- an empty expansion (the 2026-08-25 `.app.snapsync.shared` trap);
- a missing application identifier;
- a positive check that what was signed is what was claimed.

It adds a check dev-sign lacked: a profile that does not grant a claimed entitlement is refused. That
is the silent "capability enabled after the profile was minted" failure, which `ssh-mac-build` still
documents as undetectable.

The dev loop renders `channel=dev`, so `APS_ENVIRONMENT=development`, which a development profile
grants. No special case is needed.

- **Rejected**: keep codesign on the Mac. That keeps the certificate on a GitHub runner and the manual
  profile tar.

### Rename the repo skill to `snapsync-device` and slim it

Two skills named `ios-device` make "which one loads" a harness accident.

The global skill now owns this material, so the rename removes it:
- the lease;
- install;
- the restart and black-screen recipe;
- the timeout table;
- the usbmux and `UNIX:` traps.

What stays is SnapSync-only:
- the bundle id;
- the app log and the extension log reached through the rig;
- event-link verification;
- the upload landing check;
- the boundary with `rig-channel`.

Its first instruction loads the global `ios-device`.

- **Rejected**: delete the skill and scatter its content into `rig-channel` and `local-backend`.
  Event-link verification is not rig work, and the facts would lose their single home.
- **Rejected**: keep the name and slim it. The collision persists.

### The CLAUDE.md pointer names only the repo skill

The Runbooks line becomes ``load **`snapsync-device`**``; the global `ios-device` is named in prose.
`RunbookSkillsTest` resolves pointers against the repository, and CI has no `~/.claude`. A pointer to
the global skill would either need an allow-list in the test, or make the test depend on the machine.
- **Rejected**: both of those.

### `.ios-device.yml`'s build line reads the deployment from `~/.gradle`

Today the deployment is named twice:
- in `~/.gradle/gradle.properties` (`snapsync.deployment`), which Gradle's own re-resolve obeys during
  the build;
- in a manual `resolve-deployment.py <name>` call, which must put `Deployment.xcconfig` on disk before
  `xcodebuild` loads the project.

If the two ever disagree, the xcconfig comes from one deployment and `Deployment.plist` from the other.

The build line reads the property itself and passes it to the resolver, defaulting to `prod`. One line,
since the global skill's config is flat `key: value` lines read with `sed`.

The rig flag stays a `~/.gradle` property, never in the tracked `gradle.properties`, for the
containment reasons `ssh-mac-build` already states.

- **Rejected**: hardcode `prod`. It keeps the two-places trap.
- **Rejected**: call a new repo script. That adds a file to maintain for one line.

### Hard cutover

One PR deletes the old lease, guard and hook. Sibling workspaces switch when they rebase onto `main`.
The sidebar tag changes from `device` to `ios-device`, the global lease's name.

- **Rejected**: teach the global lease and guard to also honour `~/.snapsync-device.lock` for a while.
  That would put SnapSync-specific code into a skill every project runs, and it would need its own
  later removal.

### Delete `DeploymentKeyProvenanceTest`, and its requirement

The test's rule is "nothing extracts from `Config.xcconfig` a key it does not assign". Its non-vacuity
anchor is "at least one in-repo file extracts from `Deployment.xcconfig`". Measured on this tree, the
only file satisfying that anchor is `scripts/dev-sign`:
- `RuntimeIdentityTest` reads the fragment, but its extraction line names neither the file nor a shell
  variable, so the scan does not attribute it;
- the test's own comment carries no setting token.

After the deletion, the test fails its own non-vacuity check.

The reader it existed for is gone, and the global `sign` reads build settings, not an xcconfig. The
operator chose to delete the guard rather than keep a rule with no remaining subject. The underlying
contract, "fail closed on an empty required value", stays in `deployment-configuration`.

- **Rejected**: drop only the anchor assertion. The spec permits that, since its non-vacuity scenario
  asks only for a non-empty scan and parsed assignments, but it leaves a guard watching an empty
  population.
- **Rejected**: re-anchor it on another reader. There is no natural one: `ios.yml` mentions the fragment
  only in a comment.

The test's dedicated Gradle input set (`deploymentKeyReaderSurfaces`, a repo-wide text tree) goes with
it. No other guard reads through it.

### Strip the runner's signing and retire the profile secret

Nothing left on the runner needs a code-signing identity:
- `sim-sign` signs ad hoc (`--sign -`);
- simulator tests are unsigned;
- the archive is built with `CODE_SIGNING_ALLOWED=NO`.

The cleanup:
- remove the keychain, certificate-import and profile-install steps from `.ssh-runner.yml`;
- add `ios-device-out` to `sync.exclude`, which the global recipe requires;
- regenerate the workflow, since `ssh-runner init` owns it.

`DEV_PROVISIONING_PROFILE_BASE64` has no other reader, so it is deleted after merge, on confirmation.
`SIGNING_DEV_CERT_*` stays because `ios.yml` uses it.

## Risks / Trade-offs

- **[Uncoordinated window]** A sibling on a pre-change branch holds the old lock while an updated
  workspace holds the new one, and both may install at once. → Short-lived: it ends at their rebase, and
  the same gap already exists today between SnapSync and every other project. Say it in the PR body.
- **[The fence now depends on a file outside the repo]** The user-level hook is
  `[ -x ~/.claude/skills/ios-device/guard ] || exit 0`, so on a machine without the global skill, device
  commands are unfenced. → The phone is attached only to this machine, which has the skill. The
  `snapsync-device` skill's first line requires loading it. Accepted.
- **[Signer regressions are no longer in this repo's review]** A change to the global `sign` is not a
  SnapSync PR. → The pre-merge device run below proves today's signer end to end. `sign` fails closed,
  and its checks are stricter than the ones deleted.
- **[Profiles fetched live]** A missing or stale profile now surfaces at sign time rather than at session
  start. → `sign` names the reason ("does not list device", "does not grant …") and falls back from
  cache to App Store Connect on its own.
- **[Losing the provenance guard]** A future script could again awk a moved key out of `Config.xcconfig`.
  → `deployment-configuration` still binds it to fail closed. The removal reason in the delta says to
  read build settings instead.

## Migration Plan

1. Land the change on one branch:
   - deletions, `.ios-device.yml`, `.secrets.yaml` mappings;
   - skill rename and edits, CLAUDE.md pointer;
   - runner config and regenerated workflow;
   - test and input-set removal, spec delta.
2. **Before merge, verify on the SE2** through the new path: lease, runner build with the rig, Linux
   sign, install, launch. Then confirm over the rig channel that `/device/state` reports a device id
   (the keychain group is right), and that an upload to a fresh event lands.
3. Merge with label `internal`.
4. After merge, each on confirmation:
   - delete `DEV_PROVISIONING_PROFILE_BASE64`;
   - remove the "Not coordinated: SnapSync" line from the global skill.

**Rollback**: revert the PR. The old scripts, hook and runner steps return intact. The deleted secret
would need re-baking (see the old `ssh-mac-build` profile section), so it is deleted only after the
merge has been used.

## Open Questions

None.
