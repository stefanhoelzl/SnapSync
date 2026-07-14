## 1. Make `ios-build` a pure gate

- [x] 1.1 Remove the `Export signed IPA` and `Upload to TestFlight` steps from `ios-build`, along with their
      `continue-on-error: true`. The job now archives and nothing else — it makes no Apple-side calls.
- [x] 1.2 On `refs/heads/main` only, pack the signed archive with `tar -czf` and publish it as the
      `ios-archive` workflow artifact (`upload-artifact@v7.0.1`, `if-no-files-found: error`,
      `retention-days: 1` — it is a build intermediate consumed by the next job in the same run, and this
      is a public repo). Tar is load-bearing: `upload-artifact` zips and loses executable bits/symlinks,
      which would corrupt the signed `.app` and break the later export.

## 2. Add the `ios-deliver` job

- [x] 2.1 New job `ios-deliver` on `macos-26`, guarded `if: github.ref == 'refs/heads/main'` and declaring
      `needs: [ios-build, ios-test]` — the dependency that makes a red test suite stop the release.
- [x] 2.2 Check out the repo (for `iosApp/ExportOptions.plist`), write the App Store Connect API key, mint an
      ephemeral keychain password, and import **both** signing certs — Distribution to sign the IPA, and
      Development so that this job's `-allowProvisioningUpdates` cannot mint a Development cert either
      (the same cert-cap reasoning that governs `ios-build`).
- [x] 2.3 Download the `ios-archive` artifact (`download-artifact@v8.0.1`) and untar it.
- [x] 2.4 `xcodebuild -exportArchive` the already-compiled archive into an App Store IPA, then upload it via
      `apple-actions/upload-testflight-build@v5`. **No** `continue-on-error`: this job posts no required
      check, so it is free to fail red, and a failed delivery must be visible.
- [x] 2.5 No Java/Gradle/Konan setup in this job — it compiles nothing, preserving "the device app is
      compiled exactly once per push".

## 3. Leave the merge gates alone

- [x] 3.1 `ios-test` unchanged; no `needs:` added between `ios-build` and `ios-test` (a red test must not
      *skip* `ios-build`, whose required check would then never post — freezing merges).
- [x] 3.2 `.github/rulesets/main.json` unchanged: the required contexts stay `build`, `ios-build`,
      `ios-test`. `ios-deliver` is deliberately NOT required — it never runs on a PR branch, so requiring
      it would freeze every merge.
- [x] 3.3 `concurrency.cancel-in-progress` left as-is (accepted gap, recorded as D6 in `design.md`).

## 4. Specs

- [x] 4.1 `ios-testflight-delivery`: add *Delivery gates on the test suite*; retarget the delivery
      requirement at the `ios-deliver` job (main-only, compile-once preserved); rewrite *Delivery is
      decoupled from the merge gate* into *Delivery never blocks merges, and never fails silently*
      (structural decoupling, no `continue-on-error`, failures conclude red); bind the signing and
      credential requirements to **both** jobs that run `-allowProvisioningUpdates`.
- [x] 4.2 `ios-ci`: `ios-build` is a pure gate that publishes a packed archive artifact on `main`; add
      *The merge gates are exactly the two parallel jobs*, pinning the two required contexts, forbidding a
      `needs:` between them, and forbidding `ios-deliver` from ever becoming a required check.
- [x] 4.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.

## 5. Verify

- [x] 5.1 Parse `ios.yml` and assert the job graph: `ios-build` and `ios-test` have no `needs` (still
      parallel), `ios-deliver` needs both and is main-guarded, and **no step anywhere carries
      `continue-on-error`**.
- [ ] 5.2 After merge, confirm the first `main` run delivers: `ios-deliver` runs only after both gates are
      green, the archive survives the tar/artifact round-trip, and a build appears in TestFlight whose
      `CFBundleVersion` equals the run number. (The tar hand-off is the only mechanism here not provable by
      inspection.)
