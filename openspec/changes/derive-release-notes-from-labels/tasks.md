## 1. The changelog contract

- [ ] 1.1 Add `.github/release.yml`: exclude the `internal` label; declare category `New` for
      `enhancement` and `Fixed` for `bug`; no catch-all category.
- [ ] 1.2 Add `.github/workflows/check-label.yaml` — `on: pull_request`
      (`opened`/`labeled`/`unlabeled`/`synchronize`), job display name `check-label`, reading the PR's
      **live** labels via `gh pr view` rather than the event payload, failing with the three label
      names and commenting the hint on failure.
- [ ] 1.3 Add the `check-label` context to `.github/rulesets/main.json`
      (`integration_id` 15368, like its siblings).

## 2. The derivation

- [ ] 2.1 Add `.github/scripts/release_notes.py`: given the repo, target sha and optional previous
      tag, call `POST /repos/{repo}/releases/generate-notes`, then render plain text — headings
      verbatim, one `- ` bullet per PR, `by @user in <url>` dropped, `type(scope):` prefix and a
      leading `Fix`/`Fixes`/`Fixed` stripped, first letter capitalized.
- [ ] 2.2 Make it read `.github/release.yml` for the declared category titles and **fail** on any
      heading outside that set (the un-configured `What's Changed` fallback must never render).
- [ ] 2.3 Emit the committed fallback sentence when no heading has any item, and fail with a clear
      message when the rendered text exceeds 4000 characters.
- [ ] 2.4 Verify empirically which ref `generate-notes` reads `.github/release.yml` from (branch head
      vs. an older `target_commitish`), and record the answer in `design.md` under the risk that
      names it.

## 3. Applying the notes at release time

- [ ] 3.1 Add `.github/scripts/asc_release_notes_apply.sh` — resolve the version id by
      `versionString` exactly as `asc_review_details_apply.sh` does, then
      `asc localizations update --version <id> --locale en-US --whats-new "$notes"`.
- [ ] 3.2 Wire a generate step into `ios-appstore-promote.yml` after the origin-commit resolve and
      **before** the attach: derive the previous tag with
      `git describe --tags --abbrev=0 --match 'v[0-9]*' "$ORIGIN_SHA"`, write the notes to a file,
      and echo them to `$GITHUB_STEP_SUMMARY`.
- [ ] 3.3 Wire the apply step in after the attach and before the submit gate, unconditional on the
      `submit` input, alongside the review-details step.

## 4. Specs and docs

- [ ] 4.1 Sync the deltas into `openspec/specs/` (new `changelog-labels`; modified
      `ios-appstore-release`, `ios-appstore-metadata`, `branch-protection`) and correct
      `ios-appstore-metadata`'s Purpose, which still promises "and later 'what's new'" as committed
      text.
- [ ] 4.2 Update `CLAUDE.md`'s App Store release section: the promote now writes the release notes
      itself, derived from the labelled PRs since the previous tag, and a PR must carry a changelog
      label.
- [ ] 4.3 Validate: `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and
      `asc metadata validate --dir metadata` (unchanged, but the committed listing must still carry
      no `whatsNew`).

## 5. Release 0.2

- [ ] 5.1 After merge, promote a `main` build whose origin commit contains `.github/release.yml` and
      confirm the run writes the six-item `New`/`Fixed` changelog to the 0.2 version record.
- [ ] 5.2 Re-dispatch with `submit=true` once the notes are in place, and confirm the submission
      preflight no longer reports `en-US: whatsNew`.
