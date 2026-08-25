## Context

`2026-08-25-add-deployment-resolver-and-boot-probe` moved nine values out of hand-written literals and
`xcodebuild` overrides into a generated `Deployment.xcconfig`. Eight survived the move. The ninth —
`SENTRY_DSN` — did not, because `//` opens a comment anywhere on an `.xcconfig` line and the renderer wrote
the DSN verbatim.

The trap was known. The line directly above it carries the workaround and its explanation:

```python
# `$()` splits the `//` so xcconfig does not treat the rest of the line as a comment.
f"BACKGROUND_UPLOAD_URL_BASE = https:/$()/{p['domain']}/api/v1",
```

Someone hit it, fixed *that string*, and wrote the comment. `sentryDsn` was added to the same grammar and
the fix did not generalise, because it was never a property of the renderer — only of one line. That is the
resolver's own founding argument turned inward: *"guards are opt-in: they cover what someone remembered,
which is exactly how `BACKGROUND_UPLOAD_URL_BASE` went unpinned."*

Two facts about the current shape decide this design.

**The inventory bounds where a value appears, never whether it survives the trip.** Its rendering set is
called "the whole containment guarantee", and it is — for containment. `test_the_dsn_is_present_on_release`
tested exactly that property, and that property held perfectly while the build was broken.

**Reviewability, not grammar, separates the values that are safe.** Of the six keys reaching the
`.xcconfig`, five are literals in committed JSON — a human read them in a pull request, and could have seen
a `//`. The sixth, the DSN, exists only inside a GitHub secret. It is the only environment-sourced value
written verbatim into that file, and it is the one that broke.

## Goals / Non-Goals

**Goals:**

- Restore crash reporting in both processes on the next delivering build.
- Remove the `//` hazard **structurally** rather than escaping around it: no value in the `.xcconfig` is
  environment-sourced, so no unreviewable string enters a grammar with no escaping.
- Give the four device-facing baked values the same standing end-to-end proof the upload base already has —
  read out of the built bundles, both of them.
- Keep the change a *relocation*: no value's meaning, default, or absence semantics changes.

**Non-Goals:**

- Runtime DSN validation, or hardening `isConfigured` against a malformed value. `isConfigured` is
  structurally a proxy — even a well-formed DSN does not mean the SDK started, Bugsink is reachable, or the
  quota is intact. Widening it makes it a better proxy, never the thing.
- Resolver-side DSN shape validation. With the value in an escaping grammar and asserted out of both
  bundles, the path it would close is already shut.
- A general escaping layer over the raw-interpolated renderings, or splitting the inventory's
  *appears-in* relation from its *derives-into* relation. Considered and declined (see Decisions).
- A liveness detector for the reporting channel. Real, but a separate capability — see Open Questions.

## Decisions

### D1 — Move the values to a bundled plist rather than escaping them

Not every key can leave the `.xcconfig`. Five are consumed as build settings or entitlement substitutions
and have nowhere else to live:

| key | consumer | can move? |
|---|---|---|
| `appName` | `PRODUCT_NAME` | no |
| `bundleId` | `PRODUCT_BUNDLE_IDENTIFIER` → signing, provisioning, APNs topic | no |
| `teamId` | `DEVELOPMENT_TEAM` → signing | no |
| `domain` | entitlements `$(ASSOCIATED_DOMAIN)` | no |
| `channel` | entitlements `$(APS_ENVIRONMENT)`, derived | no |
| `uploadBase` `apnsEnv` `sentryEnvironment` `sentryDsn` | `Info.plist` values only | **yes** |

The four that can move are exactly the free-form ones, and after they leave, every remaining value traces
to a committed literal. The alignment is the point: **plists and JSON escape; `.xcconfig`, `.properties`
and the metadata templates interpolate raw.** Put every unreviewable value in an escaping grammar and the
raw ones carry only strings a human approved.

*Alternative — escape with `$()`.* Five lines, keeps the hard-`#include` fail-closed property, ships
faster. Rejected: it re-applies a per-site fix to the same grammar that already defeated one, and leaves
the boundary defined by "which values happen to contain slashes today".

*Alternative — refuse `//` in any rendered `.xcconfig` value.* Better than escaping (refusing beats silently
rewriting), and would have caught this. Rejected as unnecessary once no environment-sourced value reaches
that file, and because the natural declaration-level form false-positives on `channel` — which is
environment-sourced, declares `XCCONFIG`, and appears in the file only through three derived enums. Fixing
that means splitting the inventory's *appears-in* from its *derives-into*, which is inventory surgery for
one key.

*Alternative — Base64 the DSN and decode at runtime.* Immune to the grammar, but adds runtime code for a
build-configuration problem and makes the baked value unreadable to the CI assertion that is the actual
gate.

### D2 — All four Info.plist-bound keys move, not only the two carrying `//`

`apnsEnv` and `sentryEnvironment` are sealed enums that cannot break the grammar. Moving them anyway buys a
line that can be stated and checked — *the `.xcconfig` carries only build settings and entitlement inputs;
`Info.plist` carries no deployment values* — instead of a boundary drawn around today's string contents.

### D3 — Keys are named as the inventory names them

`uploadBase`, `apnsEnv`, `sentryEnvironment`, `sentryDsn`. The plist becomes a direct projection of the
inventory, as the JSON and site renderings already are. The `SCREAMING_SNAKE` names were `.xcconfig` build
settings; carrying them into a plist would preserve the shape of the thing being removed.

### D4 — One reader in `:adapter:ios:ext-safe`, with every absence default preserved verbatim

Placement is forced: the extension links both `bakedUploadBase()` and the Sentry seat, so the reader must be
extension-safe. It reads the plist once and caches the map — a derived cache of an immutable file.

Each key keeps its current collapse **unchanged**:

| key | absent → | why that value |
|---|---|---|
| `uploadBase` | `""` | `buildUploadConfig` treats blank as absent, so a misconfigured build uploads **nowhere** rather than somewhere unintended (`UploadBaseTest`) |
| `apnsEnv` | `"sandbox"` | the safe end of the pair |
| `sentryEnvironment` | `"development"` | reports honestly rather than claiming production |
| `sentryDsn` | absent → SDK never starts | absence is the off-switch |

Consolidating also relocates `SnapSyncRoot`'s inline `?: "sandbox"` out of `:app:ios`. That is not
drive-by: `UploadBase.kt`'s own KDoc states the rule — *"neither wiring-only composition root may carry the
absent-key defaulting decision"* — which is why `bakedUploadBase()` sits in the adapter. The APNs
environment was left behind when the upload base was evacuated.

### D5 — The archive assertion covers four values across both bundles

This is the load-bearing decision, and its justification is not "belt and braces".

**Today the absent-key branch is unreachable in a shipped build.** `Info.plist` substitution is
all-or-nothing: the `#include` resolved and the key is present (possibly empty, which is the designed
off-switch), or the build failed. `UploadBaseTest`'s KDoc says so in writing — *"a test binary carries no
such key, which makes this the one place that branch is reachable."*

**This change makes it reachable, per bundle, and all four fail together:**

```
  Deployment.plist missing from a bundle
      uploadBase        = ""             → buildUploadConfig returns null → uploads never happen
      apnsEnv           = "sandbox"      → a Release build on sandbox APNs → push dead
      sentryEnvironment = "development"  → events mislabelled
      sentryDsn         = absent         → no reporting to notice any of the above
```

A release build that uploads nothing, receives nothing and reports nothing — every symptom silent, with the
one channel that would have said so switched off in the same stroke. That is precisely the failure the
resolver was built against: *"a build pointed at the wrong backend … looks completely normal until a user's
photos silently stop arriving."*

It must cover **both** bundles. The extension is a separately-built nested bundle with its own resources
phase at `SnapSync.app/Extensions/BackgroundUploadExtension.appex` (iOS 26 uses `Extensions/`, not
`PlugIns/`). The chosen device verification — a diagnostic dump — exercises the **app** process only, so a
resource that lands in the app and not the extension would look like a complete success.

### D6 — Fail-closed is preserved by the atomic emit, not by a new check

`emit()` writes every rendering or none, so the `.xcconfig` and the plist are present or absent together.
If the resolver did not run, `Config.xcconfig`'s hard `#include` fails first — before the resource is ever
considered. No new build-phase guard is needed for the "resolver never ran" case; D5 covers the "ran but the
resource did not reach this bundle" case.

### D7 — The hand-injected-DSN sideload path retires

`SENTRY_DSN=… ` on the ssh-mac `xcodebuild` line works today only because a command-line build setting beats
the `.xcconfig` fragment. It cannot reach a generated resource. Since the DSN is emitted only for a
distributed build, nothing decouples it from the release channel any more.

The replacement is `gh workflow run ios.yml --ref <branch>`, added days ago for exactly this loop. Rejected:
a third `channel` value (`sideload`) that emits the DSN with development APNs — new surface, and it weakens
"one discriminator decides all four". Also rejected: documenting `SNAPSYNC_CHANNEL=release` before an
ssh-mac build — it hands a development-signed build a production `aps-environment`, which is unverified.

## Risks / Trade-offs

- **The resource does not reach one bundle** → D5's assertion, across both, on every delivering run. This
  is the one genuinely new failure mode and the reason the gate is not optional.
- **Crash reporting stays dead until this merges** → accepted knowingly. The outage is four delivering
  builds inside one day, so the larger fix costs almost nothing in additional exposure over the five-line
  escape.
- **`pbxproj` edits are the least-reviewable part of the diff** → two Copy Bundle Resources entries and one
  file reference; the assertion proves the result rather than the diff.
- **A future `.xcconfig` key could carry `//` again** → accepted, with no gate (declined deliberately). The
  five remaining values are committed literals whose grammars (DNS, reverse-DNS, alphanumeric team id,
  sealed enums) cannot produce it, and a new one would be reviewed in a pull request.
- **`.properties` and the metadata templates still interpolate raw** → unchanged and out of scope. Both
  carry only `domain`, a committed literal from a grammar that cannot produce their dangerous characters —
  the same footing the `.xcconfig` reaches after this change.
- **Nothing observes whether reporting works on a device** → unchanged by this change, and unfixable at the
  read site. The gate proves the DSN is in the bundle; it cannot prove the SDK started.

## Migration Plan

1. Add the rendering and emit it; the `.xcconfig` and both `Info.plist`s stop carrying the four values in
   the same commit — there is no interim in which both are authoritative.
2. Wire the resource into both targets, then move the four read sites behind the single reader.
3. Extend the archive assertion; a delivering run now fails rather than shipping a mute build.
4. Verify by branch dispatch: install, double-tap the "SnapSync" label, send a dump, confirm it in Bugsink
   with `data.dist` equal to that run number.

Rollback is `git revert`; no device or backend state is migrated, and no shipped build is altered.

## Open Questions

- **The `Bugsink-Resolves:` automation has no liveness interlock.** It marked SNAPSYNC-20/21/25/26 resolved
  today, while the channel whose events would reopen them was dead. A monitor cannot fix this — zero events
  is the *healthy* value — but `/ship`'s resolve step is a moment when someone is already declaring
  something fixed, and could print the most recent event's build number beside the one it is shipping.
  Separate capability, separate change.
- **Whether `.xcconfig` supports any quoting** remains unmeasured. It does not matter after this change and
  is recorded only so a future reader does not assume it was checked.
