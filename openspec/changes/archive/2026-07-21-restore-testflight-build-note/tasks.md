## 1. Rework ios-deliver in .github/workflows/ios.yml

- [x] 1.1 Add a "Compose the release note" step after the IPA export: resolve the PR for `$GITHUB_SHA` via `gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/pulls"` (env `GH_TOKEN: ${{ github.token }}`), compose `<PR title> (#<num>, <short sha>)` — falling back to `git log -1 --format='%s'` + short SHA when no PR resolves — and append `WHATS_NEW=<note>` to `$GITHUB_ENV`. The title must never appear inside a `${{ }}` interpolation in a `run:` block.
- [x] 1.2 Replace the `apple-actions/upload-testflight-build@v5` step with `pip install codemagic-cli-tools` + `app-store-connect publish --path "$RUNNER_TEMP/export/SnapSync.ipa" --whats-new "$WHATS_NEW"`, authenticated via the CLI's `APP_STORE_CONNECT_KEY_IDENTIFIER` / `APP_STORE_CONNECT_ISSUER_ID` / `APP_STORE_CONNECT_PRIVATE_KEY` env names mapped from the existing `ASC_*` secrets (same bridge the deleted ios-promote used). Pass no `--testflight`/submit flag. No `continue-on-error`.
- [x] 1.3 Update the ios-deliver job header comment (upload mechanism + note) so the workflow prose matches the new shape; confirm `permissions: contents: read` is untouched (the PR lookup is a public-repo read).

## 2. Documentation

- [x] 2.1 Update CLAUDE.md's "`main` uploads to internal TestFlight" section: add a line that every delivered build's "What to Test" note carries the PR title, PR number, and short SHA (fallback: head-commit subject), set by the publish invocation in ios-deliver.

## 3. Verify

- [x] 3.1 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `actionlint` (if available) on the edited workflow; eyeball the composed note logic by dry-running the compose step's shell locally with a real main SHA. (Done: specs 54/54; actionlint unavailable locally, YAML parse + step-list sanity check instead; dry run against main's head resolved `feat(crash-reporting): report crashes + errors to Bugsink from both processes (#135, 882eb5b)` — while the head commit's own subject is a trailing fix commit, proving the PR-title choice.)
- [ ] 3.2 After merge (post-ship follow-up): watch the first `main` `ios-deliver` run — publish must upload, wait, and attach the note; confirm the note text in App Store Connect/TestFlight matches `<PR title> (#<num>, <short sha>)`. A red run here is the accepted first-run risk (design D1) — fix forward or revert to the action.
