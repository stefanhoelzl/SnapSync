#!/usr/bin/env bash
# The local half of what gates a merge on `main`. Exit 0 passes; any other exit fails the ship and
# THIS OUTPUT IS THE REPORT (contract: ~/.claude/skills/ship/hooks.md).
#
# Covers four of the nine required status checks — the ones a Linux machine can actually run:
#
#   build         -> ./gradlew build, which also carries the diagrams freshness half
#                    (:tools:diagrams:test), the detekt complexity tiers, and the
#                    :test:architecture guards
#   spec-validate -> openspec validate --specs --strict
#   api-test      -> the deno set below
#
# The other five stay CI-only BY DECISION, not by oversight:
#   ios-build / ios-test           macOS-only; no Linux machine can run them
#   check-label                    /ship applies the label as it opens the PR
#   resolver-test                  scripts/resolve_deployment_test.py
#   appstore-metadata-validate     needs the pinned `asc` binary fetched over the network
#   build.yml's second step        ./gradlew compileIosMainKotlinMetadata -Psnapsync.rig=true
#                                  -Psnapsync.forge=true (the property-gated trees)
#
# Order is cheapest-first, so a failure arrives as early as it can.
set -euo pipefail

step() { printf '\n=== %s ===\n' "$1"; }

# `openspec list` PRINTS un-archived changes and exits 0 either way, so the assertion has to be
# explicit — a command that reports a problem without failing gates nothing. Two traps, both
# measured against 1.5.0 on 2026-09-08:
#   * `--json` returns an OBJECT — {"changes": [...], "root": {...}} — not an array. `jq -e
#     'length == 0'` counts the object's KEYS (2) and so fails on a clean tree. It must read
#     `.changes`.
#   * `jq -e ... > /dev/null` prints nothing, and this script's output IS the ship's failure
#     report, so a bare assertion would fail the ship while saying why nowhere.
# Pinned to the version CI runs (.github/workflows/build.yml); there is no global `openspec`
# binary and there should not be one.
step "no un-archived openspec changes"
changes="$(npx --yes @fission-ai/openspec@1.5.0 list --json)"
if ! printf '%s' "$changes" | jq -e '.changes | length == 0' > /dev/null; then
	echo "Cannot ship with un-archived openspec changes:"
	printf '%s' "$changes" | jq -r '.changes[] | "  - " + .name'
	echo
	echo "Archive them before shipping."
	exit 1
fi

step "spec-validate"
npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict

# `deno task check` and `deno task test` chain `deno task config` themselves, so the resolved
# deployment exists before anything reads it — no separate resolve step is needed. Every output
# lands in a gitignored path (api/dist/, api/src/deployment.ts), so this leaves no tracked change
# behind. The one CI step omitted is the bundle's `github.sha` stamp grep, which can mean nothing
# off a runner.
step "api-test"
(
	cd api
	deno fmt --check
	deno lint
	deno task check
	deno task test
	deno task schema:check
	deno task bundle
)

step "build"
./gradlew build
