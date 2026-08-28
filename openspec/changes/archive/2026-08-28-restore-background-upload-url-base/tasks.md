## 1. Emit the composable build settings

- [x] 1.1 In `scripts/resolve-deployment.py`'s `render_xcconfig`, emit `UPLOAD_SCHEME = {upload_scheme(p['domain'])}`
      and `UPLOAD_HOST = {p['domain']}`. Both are derived emissions of the existing `domain` key — no
      inventory entry, no new rendering, no new key.
- [x] 1.2 Correct the `render_xcconfig` docstring: it still says the values it carries are what "has nowhere
      else to go" because they are build settings or entitlement substitutions. Name the third consumer —
      an `Info.plist` substitution, which can also only read a build setting — and state that composing at
      the destination is what keeps the `//` hazard structural rather than escaped.
- [x] 1.3 In `scripts/resolve_deployment_test.py`, assert both settings **through `xcconfig_values()`**, which
      reads a rendering the way xcodebuild does: `UPLOAD_HOST` reads back as the bare domain and
      `UPLOAD_SCHEME` as `https` for `prod` / `http` for the loopback literal. Asserting the emitted string
      would repeat the mistake that let the DSN truncation pass green.
      (`test_no_xcconfig_value_contains_a_comment_delimiter` already covers both new values; do not add a
      second `//` assertion.)

## 2. Restore the key to both bundles

- [x] 2.1 `iosApp/BackgroundUploadExtension/Info.plist`: add
      `<key>BackgroundUploadURLBase</key><string>$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1</string>`.
- [x] 2.2 `iosApp/iosApp/Info.plist`: add the same key and value.
- [x] 2.3 Replace the "NO deployment values here, deliberately" comment in **both** files. The replacement
      names `assetsd` as the reader, says it reads *this file* and can see no bundled resource, gives the
      symptom of absence (`PHPhotosErrorDomain -1`, empty `userInfo`, extension never launched) and the
      date of the measurement — not a restatement of the principle. The rest of the comment (why the
      Sentry pair and `apnsEnv` are still in `Deployment.plist`) stays true and stays.

## 3. Make the two carriers assert their agreement

- [x] 3.1 In `.github/workflows/ios.yml`, inside the existing "Verify the archive baked the resolved
      deployment" loop over `$APP` and `$APPEX`, read `BackgroundUploadURLBase` from
      `$BUNDLE_DIR/Info.plist` and fail unless it is **non-empty** and **exactly equal** to that bundle's
      `Deployment.plist` `uploadBase`. Exact equality, never a prefix test — a prefix test passes on an
      empty value and on a truncated `UPLOAD_HOST` (design D4).
- [x] 3.2 Extend that step's header comment to say why the comparison is meaningful: the two carriers
      compose one fact by different routes, only one of which can be truncated, so equality between them
      is a differential test of the grammar.

## 4. Correct the documents that asserted the deleted state

- [x] 4.1 `scripts/resolve-deployment.py`, the `domain` key's inventory doc: it says the subsystem
      "validates every job's destination against the extension bundle's baked value", which reads exactly
      as "the plist we bake into the extension bundle" and is how this regression was reasoned into
      existence. Say which file the daemon opens, and that both bundles carry the key in their
      `Info.plist` beside the `uploadBase` our own code reads.
- [x] 4.2 `iosApp/Configuration/Config.xcconfig` header: the split it describes is still right for the
      other values; add that one value is additionally composed into the `Info.plist`s because its reader
      is the OS, and that this is why `UPLOAD_SCHEME`/`UPLOAD_HOST` exist as separate settings.
- [x] 4.3 `app/ios/CLAUDE.md:194-197` — correct throughout, and now stale only in form: update
      `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)` to the composed value, and note the app
      bundle carries it too.

## 5. Verify

- [x] 5.1 `python3 scripts/resolve-deployment.py prod` locally; confirm `Deployment.xcconfig` carries both
      new settings and that `Deployment.plist`'s `uploadBase` is unchanged.
- [x] 5.2 `./gradlew build` (the resolver test runs here) and `python3 scripts/resolve_deployment_test.py`.
- [ ] 5.3 Push the branch and read the `ios-build` run: it archives on every branch push, so the new
      readback is the proof that `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1` actually **resolved** and that
      both bundles carry the composed value. An unresolved substitution fails as a literal `$(UPLOAD_HOST)`
      mismatch. If it does not resolve, fall back to a single `UPLOAD_HOST` with `https` and `/api/v1`
      literal in the plists (design, Risks) rather than reintroducing a URL-valued build setting.
- [ ] 5.4 Open the PR with the `bug` label and `/ship` it. CI proves the bundles carry the value; it cannot
      prove `assetsd` accepts it — that rests on the 2026-08-28 A/B, whose value this change restores
      unchanged. Confirm on device after the TestFlight build lands (registration succeeds, an upload
      completes), and resolve Bugsink `SNAPSYNC-37` from the merge via a `Bugsink-Resolves:` trailer.
