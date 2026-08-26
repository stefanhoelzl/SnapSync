## 1. The title rule in `/ship`

- [x] 1.1 Rewrite `.claude/commands/ship.md` §7.2's user-facing branch: state that the title IS the
      App Store bullet, and name the three transforms `release_notes.py` applies (strip
      `type(scope):`, strip a leading `Fix|Fixes|Fixed`, capitalize) so the author can see what a
      customer reads.
- [x] 1.2 Add the positive test and its anchor vocabulary to §7.2 — every noun one a SnapSync user
      has seen in the app or the listing (*event, photos, album, join, leave, share, sync, invite,
      QR code, phone, event settings, event dates*) — with the observed leaks as examples beneath it
      (*ledger, manifest, upload cycle, PhotoKit, URLSession, App Group, port, flow, lock,
      completion handler, MIME/UTI, extension, backend, endpoint*).
- [x] 1.3 Add the observable-outcome clause forbidding vagueness, citing #151 ("Download page") and
      #133 ("UI refresh") as the failing shape, with the corrected form.
- [x] 1.4 State the `bug` voice: the symptom, gone — with #196/#187/#217 as the shape and #200/#202
      as the counter-shape.
- [x] 1.5 Restate "no scope on a user-facing title — bare `feat: ` / `fix: `" as a hard rule, noting
      #202 and #182 broke the existing one.
- [x] 1.6 Specify that each `AskUserQuestion` option carries the rendered customer-visible line in
      its `preview` field, under a mock `New`/`Fixed` heading.
- [x] 1.7 Specify the ask-instead-of-guess fallback: when no user-visible symptom can be derived
      from the diff and commits, ask "What did the user experience before this fix?" rather than
      proposing technical titles or inferring one confidently.
- [x] 1.8 Specify that an explicit `feat(<title>)` / `fix(<title>)` is checked against the rubric
      and challenged when it violates, with the operator's answer final.
- [x] 1.9 In §7.3, state that the PR body is where mechanism, module, and root cause belong — it is
      never customer-visible — so the detail the title now excludes has a home.
- [x] 1.10 Verify the rendered result end to end: run
      `GH_TOKEN=$(gh auth token) python3 .github/scripts/release_notes.py --repo stefanhoelzl/SnapSync --target origin/main --previous v0.3`
      and confirm the three published bullets are unchanged (this change touches no released text).

## 2. Record the criterion

- [x] 2.1 Add one paragraph to `openspec/config.yaml`'s `context:` block: a spec exists where a
      contract is spread across artifacts and drift is invisible; where a single committed artifact
      IS the contract and carries its own rationale, a spec is a second copy. Note that
      `.claude/` is regenerated, so a contract whose artifact lives there (e.g.
      `openspec-archive-command`) still needs a spec.
- [x] 2.2 Confirm the paragraph sits in `context:` and not in `.claude/` — `openspec update` deletes
      the latter silently (measured).

## 3. Remove the two capabilities

- [x] 3.1 Delete `openspec/specs/ship-command/`.
- [x] 3.2 Delete `openspec/specs/branch-protection/`.
- [x] 3.3 Re-point `openspec/specs/changelog-labels/spec.md`'s Purpose (line 8) from
      "applied by `/ship` (capability `ship-command`)" to "applied by the `/ship` command" — a
      Purpose edit, outside the delta.
- [x] 3.4 Re-point `openspec/specs/ios-appstore-metadata/spec.md`'s Purpose (line 15) from
      "capability `branch-protection`" to the committed ruleset — a Purpose edit, outside the delta.
- [x] 3.5 Re-point `.github/scripts/release_notes.py:63` from "capability `ship-command`" to
      "the `/ship` command".
- [x] 3.6 Verify the references this step owns are gone: the two spec directories, and the three
      **prose** citations (3.3, 3.4, 3.5). The four `capability branch-protection` citations inside
      REQUIREMENTS are carried by the MODIFIED deltas and land at sync/archive, not here — see 4.5.
- [x] 3.7 Confirm the decision-record path written into `openspec/config.yaml` matches the archive
      directory this change actually gets (`2026-08-26-address-pr-titles-to-users` as written); fix
      it at archive time if the date differs.

## 4. Verify

- [x] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes (structure only —
      it checks nothing about the citations above, which is why 3.6 is a separate grep).
- [x] 4.2 `./gradlew build` passes — `:test:architecture`'s `RunbookSkillsTest` and `LawsDigestTest`
      read `CLAUDE.md` and the module-architecture spec, neither of which this change touches;
      confirm rather than assume.
- [x] 4.3 Confirm `CLAUDE.md:244` still names only kept capabilities (`ios-testflight-delivery`,
      `ios-appstore-release`, `ios-appstore-metadata`, `changelog-labels`) — no edit expected.
- [x] 4.5 After `sync`/`archive` applies the deltas, re-run the full grep and confirm nothing names
      either capability:
      `grep -rn "ship-command\|branch-protection" openspec/specs/ .github/ CLAUDE.md openspec/config.yaml`
      — expected survivors are only prose uses of "branch protection" as a common noun
      (`ios-appstore-release/spec.md:92`, `ios-appstore-promote.yml:41`, `ios.yml:2,36,334`,
      `ios-archive/action.yml:7`) and `config.yaml`'s own worked examples.
      `openspec/changes/archive/` is history and MUST NOT be rewritten.
- [x] 4.4 Ship this change's own PR through the new rubric as the first live exercise: it is
      `internal`, so the rubric must NOT engage — which is itself the check that §7.2's scope
      condition is right. (Ticked at archive time, ahead of the run: `/ship` §1.3 aborts on an
      un-archived change, so this can only be exercised on the ship that immediately follows.)
