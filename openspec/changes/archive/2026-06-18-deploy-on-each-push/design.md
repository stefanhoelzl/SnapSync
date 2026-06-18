## Context

`ios-testflight-delivery` stood the signing/delivery pipeline up as the `main`-only tail of the `ios-build` job; non-`main` refs built unsigned as the merge gate. This change widens delivery to every ref so branches can be tested on device before merge. The decisions below were settled in an interview.

## Decisions

### Trigger: every push, any ref (not opt-in)
Considered opt-in (manual dispatch / PR-label / commit marker) and PR-branches-only to spare runner minutes and TestFlight clutter. Chose **every push** for zero-friction testing — accepting the ~10x macOS runner cost on every WIP push, since this is a solo project where convenience outweighs minutes.

### Build identity: `run_number` lookup, no notes
Considered encoding the branch into `CFBundleShortVersionString` (rejected — App Store Connect may reject non-numeric version strings on upload) and writing branch/commit into the build's "What to Test" notes (rejected — `betaBuildLocalizations.whatsNew` requires polling ASC until the build resource exists, adding a job and wait for marginal benefit). Chose to **change nothing**: `CFBundleVersion = github.run_number` is globally monotonic and unique per (fixed) marketing version, so builds never collide; identify a build by looking up its run number in Actions.

### Gate vs delivery: decouple
The signed archive compiles `iosArm64`, so it is the natural merge gate. Export + upload are made **non-blocking** (`continue-on-error: true`) so Apple-side delivery flakiness never blocks a merge of otherwise-good code. Simplest mechanism that keeps the `ios-build` job green while still surfacing the failure in the run — no separate job, no `xcarchive` artifact hand-off between jobs.

### Distribution: internal testing (pre-existing)
Internal TestFlight testing skips Beta App Review, so builds are installable minutes after processing. The internal group + tester are already configured (from `ios-testflight-delivery`); no workflow step is needed beyond the upload.

## Consequences

- Branch builds shift Debug→Release (archives are distribution builds): more representative of shipped behavior, but Release Kotlin/Native compiles are slower and debug-only logging may differ.
- The single app's TestFlight list accumulates builds from all branches; 90-day expiry self-trims it.
