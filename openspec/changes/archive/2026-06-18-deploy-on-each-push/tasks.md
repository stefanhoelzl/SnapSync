## 1. Workflow

- [x] 1.1 Remove the unsigned `Build iOS device app (unsigned gate)` step from the `ios-build` job
- [x] 1.2 Drop the `if: github.ref == 'refs/heads/main'` guards from the prepare-key / archive / export / upload steps so they run on every ref
- [x] 1.3 Add `continue-on-error: true` to `Export signed IPA` and `Upload to TestFlight` so delivery failures don't fail the `ios-build` gate
- [x] 1.4 Update the workflow header comment to describe the every-ref signed path and `run_number`-based build identity

## 2. Specs

- [x] 2.1 Update `ios-ci`: scope "Build iOS on every push" to a signed archive on **every** ref (no unsigned non-`main` path); replace the unsigned/`main`-split scenarios
- [x] 2.2 Update `ios-testflight-delivery`: deliver on every push (any ref), remove the non-`main`-skips behavior, and add the decoupled-delivery requirement
- [x] 2.3 At archive/sync time, refresh the `## Purpose` lines of both published specs (deltas only carry requirements): `ios-ci` Purpose says "build-only, unsigned"; `ios-testflight-delivery` Purpose is a TBD stub

## 3. Verify end-to-end

- [ ] 3.1 Push this branch; confirm `ios-build` produces a signed archive, exports, and uploads to TestFlight, and that `ios-build`/`ios-test` checks still gate
- [ ] 3.2 Confirm the build appears in TestFlight with `CFBundleVersion` = the run number and is installable on the iPhone via internal testing (no Beta App Review)
- [ ] 3.3 Confirm a forced export/upload failure leaves the `ios-build` check green (delivery decoupled)

## 4. Archive

- [ ] 4.1 After verification, sync specs and run `openspec archive deploy-on-each-push`; update memory (`ios-testflight-delivery` is no longer `main`-only)
