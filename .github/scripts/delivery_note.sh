#!/usr/bin/env bash
# The delivered build's tester note, printed on stdout: what `ios-deliver` attaches as TestFlight's "What to Test" and
# `android-deliver` as the Play internal release's notes — ONE note for the run's one build number, in both stores.
#
#   `<PR title> (#<num>, <short sha>)`. The repo is rebase-merge-only, so the commits→pulls association names the PR;
#   the head subject is only the no-PR fallback, and an API flake folds into it (a degraded note must never fail the
#   delivery). A dispatch takes the operator's WHAT_TO_TEST, else `<branch> (<sha>)`.
#
# Arbitrary text (a PR title, the operator's note) reaches it only through the environment — a PR title inside `${{ }}`
# in a run block would be script injection by construction. Needs GH_TOKEN, and a checkout for the fallback.
set -euo pipefail
PULLS=$(gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/pulls" 2>/dev/null || echo '[]')
SHORT_SHA=${GITHUB_SHA:0:7}
if [ -n "${WHAT_TO_TEST:-}" ]; then
  echo "$WHAT_TO_TEST ($SHORT_SHA)"
elif [ "$GITHUB_EVENT_NAME" = "workflow_dispatch" ]; then
  echo "$GITHUB_REF_NAME ($SHORT_SHA)"
elif [ "$(jq 'length' <<<"$PULLS")" -gt 0 ]; then
  echo "$(jq -r '.[0].title' <<<"$PULLS") (#$(jq -r '.[0].number' <<<"$PULLS"), $SHORT_SHA)"
else
  echo "$(git log -1 --format='%s') ($SHORT_SHA)"
fi
