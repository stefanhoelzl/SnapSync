## 1. The spec's non-requirement prose

The delta in `specs/` covers requirements only. These two edits are to `## Purpose` prose and the
decision-record header, which no delta operation can express, so they are made by hand at apply time and
listed here so they cannot be silently dropped.

- [ ] 1.1 `openspec/specs/ios-url-session-upload/spec.md` Purpose (~line 16): replace "which is why it is the tier that is **simulator-testable end-to-end** (a background `URLSession` runs in the simulator)" — the tier is simulator-testable in its *pump and scheduler logic*, not end-to-end; the transport is not
- [ ] 1.2 Add this change's decision record to the `Decision record:` list near the top of that spec, beside the `2026-07-04` and `2026-07-12` entries
- [ ] 1.3 Re-read the whole spec for any fourth occurrence the three known ones were found alongside — `grep -in "simulator" openspec/specs/ios-url-session-upload/spec.md` and judge each hit, rather than assuming the list is complete

## 2. The requirement deltas

- [ ] 2.1 Apply the `Module placement and testing split` delta: the end-to-end MAY clause becomes a SHALL NOT, pointing at the transport requirement for the reason
- [ ] 2.2 Apply the `The app-driven tier uses one transport on every host` delta: SHALLs unchanged; the false forcing proof replaced by the measured mechanism; the D1 supersession, the honest limit of the inference, and the ⏰ expiry recorded; the new "A simulator transfers no bytes, and that is the host" scenario added
- [ ] 2.3 Confirm the kept SHALLs are byte-identical to the current spec's, so a reviewer can see that nothing normative moved

## 3. The code comment

- [ ] 3.1 Rewrite `IosUrlSessionUploadPlatform`'s session comment (`adapter/ios/app-only/.../IosUrlSessionUploadPlatform.kt`, ~line 131). It currently asserts the 2026-08-09 measurement as fact and says "**That was never measured, and it is false**" of the *previous* claim — the same sentence shape now applies to itself. State what was measured, name the daemon's bundle-identifier check, and keep the existing ⚠️ paragraph about app relaunch, which is still true and is now also unmeasurable there
- [ ] 3.2 Verify the comment is the ONLY change in that file — `git diff` shows no line outside the comment block, and in particular the `by lazy` session is untouched

## 4. Verify

- [ ] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` — expect the same count as before this change, all passing
- [ ] 4.2 `./gradlew build` green. A comment-only edit cannot change it, which is the point: if it does, something outside the comment moved
- [ ] 4.3 `./gradlew architectureDiagrams` produces NO diff (no module, port or composition edge changes)
- [ ] 4.4 Confirm no `expect`/`actual`, no new source set, and no `SIMULATOR_DEVICE_NAME`-shaped read was introduced anywhere — this change adds no host determination, and the requirement it edits forbids one
