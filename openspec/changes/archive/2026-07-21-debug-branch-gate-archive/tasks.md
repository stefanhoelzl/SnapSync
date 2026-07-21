## 1. ios.yml

- [x] 1.1 Rework the "Select build configuration, host override, and APNs environment" step into the three shapes: dev-IPA dispatch (Debug + host override, unchanged) · `main` push or plain dispatch (Release + production APNs, unchanged) · any other push ref (Debug, no overrides). Update the step comment and the workflow-header `ios-build` description to state the per-ref configuration and the D2 escape hatch.
- [x] 1.2 Confirm no downstream step needs touching: marketing-version, `sentry-dsn`, and APNs already key off `BUILD_CONFIG == Release`; the artifact upload already keys off `main`.

## 2. Documentation

- [x] 2.1 CLAUDE.md: extend the "APNs is production for every TestFlight/App Store build" parenthetical so the branch-gate Debug archive is named alongside the dev-sideload builds as staying `development`/`sandbox`.

## 3. Verify

- [x] 3.1 `npx --yes @fission-ai/openspec@1.5.0 validate --strict` (change) and `… validate --specs --strict` (tree) both green; sanity-check the workflow YAML parses.
- [ ] 3.2 After merge (post-ship follow-up): confirm a branch push's `ios-build` completes in ~4–6 min with `-configuration Debug` in its log, and the next `main` run still archives Release and delivers.
