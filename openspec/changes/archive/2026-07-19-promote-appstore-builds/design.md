## Context

Today `ios-release.yml` builds a fresh `X.Y` archive at release time (`MARKETING_VERSION` injected from a `version` input), uploads it, attaches it to the `X.Y` App Store version record, applies review details, optionally submits, and tags `vX.Y`. The released bits were never on TestFlight.

Meanwhile `ios-deliver` (capability `ios-testflight-delivery`) already uploads **every** `main` build to App Store Connect's **internal** `development` group. Those builds are gated (`ios-deliver` runs only on `main`, only when `ios-build` + `ios-test` pass) — but today they all carry the pinned fallback `0.1.0`, because the pin existed to stop version bumps from tripping a Beta App Review on the (now-removed) external alpha group.

This change makes the release **promote one of those already-tested builds** instead of building anew. It stacks on `remove-alpha-testflight-promotion`, whose result — main builds reach only the internal group — is what makes bumping the marketing version penalty-free.

## Goals / Non-Goals

**Goals:**
- Ship the exact binary validated on TestFlight; stop building never-run bits for the store.
- Promote by an explicit `build_number`; derive the store version from the build itself.
- Give main/internal builds real, incrementing versions (`0.1 → 0.2 → …`, manual `→ 1.0`).
- Keep it simple: no recording layer (no git ref, no `whatsNew` parsing), `ios-deliver` untouched.

**Non-Goals:**
- Keeping a build-fresh escape hatch (every releasable `main` commit already has an `ios-deliver` build).
- Automating the major (`1.0`) bump (deliberately manual).
- Any App Store Connect portal action; any Kotlin/module change.

## Decisions

**D1 — Promote-only; the release never builds.** `ios-appstore-promote.yml` is a single `ubuntu` job: no archive, export, upload, Xcode, or signing certs. Every `main` commit already has a gated `ios-deliver` build, so a fresh build is never needed. This also collapses the old two-job (`macOS build` + `ubuntu finish`) split and removes the only *second* build-number source, so `CFBundleVersion` values no longer collide across upload paths.

**D2 — Version derived from the build, read from the attach fetch.** The store version = the promoted build's own `preReleaseVersion.versionString`. `appstore_release.py` already does `GET /builds?filter[version]=<build_number>` to get the build id for the attach; the marketing version rides on that same response. This is drift-proof by construction (see D3) and eliminates the "attach a mismatched build" risk entirely — record and build always match because the record is *created from* the build's version.

**D3 — Why not recompute the version from git tags.** Recomputing `last-tag+1` at promote time is wrong for any build older than the last release: the build froze its version at build time, and a tag landing in between makes the recompute higher than what the build carries.
```
   floor 0.1, no tags → builds carry 0.1.  Test #500 (0.1).  Release #510 → tag v0.1.
   Now builds carry 0.2, but #500 STILL carries 0.1.
   Promote #500: git-recompute says 0.2, build is 0.1 → mismatch / wrong label.
```
So the version must come from what *froze* it — the build — not a promote-time recompute.

**D4 — Marketing version = `max(floor, latest vX.Y tag with minor+1)`, computed in `ios.yml`.**
- **`+1` is an integer minor bump, not decimal addition:** `v0.9 → 0.10`, never `1.0`. Decimal `0.9+0.1=1.0` would steal the manual milestone.
- **`max()` compares version tuples, not strings/floats:** `(1,0) > (0,10)` so a floor of `1.0` wins a major jump; `"1.0" < "0.10"` lexically and `0.10 == 0.1` as a float would both break it.
- **Floor** lives in `Config.xcconfig` (the reinterpreted `MARKETING_VERSION`, seeded `0.1`). It is normally inert (the tag-derived value wins after the first release) and is bumped **only** to jump a major, via a normal PR. No CI writes to `main`; no protected-branch bypass.
```
   0.1 seed → v0.1 → derived 0.2 wins → 0.3 … 0.10 …
   edit floor→1.0 (PR) → max(1.0, 0.10)=1.0 → v1.0 → derived 1.1 wins → floor inert again
```

**D5 — Retire release-time green/ancestor verification; trust upload-time provenance.** `ios-deliver`'s `if: ref == main` + `needs: [ios-build, ios-test]` already guarantees a promoted build is from a merged, gate-passing commit. The old apparatus (ancestor-of-main, required-check resolution, check-suite self-exclusion, degrade-strict) defended against "the dispatched commit is red/unmerged" — under promote-only that maps only to "the commit went red *after* upload," which is near-empty (main is protection-locked; you also tested the build on TestFlight). REMOVE the requirement + ADD a provenance requirement. Surviving guards: derived `version` matches `^\d+\.\d+$`; `vX.Y` tag absent.

**D6 — SHA resolution affects the receipt, not the bits.** The attach identifies the build by `build_number` directly, so the **right binary always ships**. `build_number → ios.yml run(head_branch=main) → head_sha` is used **only** to tag the origin commit. A wrong resolution is therefore a wrong *tag*, never wrong bits. Mechanism: page `/actions/workflows/ios.yml/runs` newest-first, stop at `run_number == build_number` (recent build = top of list). Run **metadata persists past the 90-day log window** (only manual deletion removes it), so this is durable; on a genuinely unresolvable run the workflow **fails loud** ("tag `vX.Y` by hand") rather than tag a guess. The tag message carries `build N`.

**D7 — Residual collision, accepted.** A mistyped/legacy `build_number` whose value happens to match a real `ios.yml` `run_number` could tag the wrong commit. It is filtered hard: the **`^\d+\.\d+$` guard** rejects every pre-change `0.1.0` (three-part) build, and the **tag-absent guard** rejects any already-released version. What slips through is only a *failed* legacy release's un-tagged two-part build number that collides — extremely narrow, wrong-*tag*-only, and auditable via the `build N` tag message. Not worth a recording layer to close.

**D8 — Names: workflow by mechanism, capability by outcome.** Workflow `ios-appstore-promote.yml` (it promotes). Capability stays `ios-appstore-release` (the contract is *a release*, stable across mechanism; renaming it is churn for a less-durable name, and "delivery" vs "release" keeps the sibling TestFlight capability cleanly distinguished). The file↔capability name difference is intentional.

## Risks / Trade-offs

- [Version-calc implemented as decimal/string] → **Silent** `0.9→1.0` milestone theft or a broken floor. Mitigation: D4 pins integer-minor + tuple-compare explicitly in the spec; a unit-testable pure function.
- [First store release below `1.0`] → Apple documents no minimum and a live app ships `0.0.151`, but it's not vendor-*documented*. Confirm on the first real submit; if Apple balks, `1.0` simply becomes the first submitted version.
- [Unresolvable/collided SHA] → Wrong or missing *receipt* only (D6/D7). Fail-loud on unresolvable; accept the narrow collision.
- [ASC "one editable version at a time"] → `appstore_release.py` already 409s; in a promote cadence it means `0.2`'s record can't be created until `0.1` leaves the editable state. Document as rhythm, not bug.
- [Bootstrap] → The first promotable build is the first one built *after* `ios.yml` starts computing `0.1`; pre-change `0.1.0` builds are auto-rejected by the version guard. Clean cutover — note "don't promote an old `0.1.0` build."
- [Doctrine churn in one PR] → change 1 wrote "main is never bumped"; this reverses it. Net diff is correct; the archived change-1 record is a point-in-time snapshot and stays as-is.

## Migration Plan

1. `Config.xcconfig`: `0.1.0 → 0.1`; rewrite the pin comment.
2. `ios.yml`: add `fetch-tags` + a compute step (`max(floor, lastTag+1)`), pass to `ios-archive`.
3. Rename `ios-release.yml → ios-appstore-promote.yml`; rewrite promote-only (single ubuntu job; `build_number` input; derive version + SHA; fail-loud).
4. `appstore_release.py`: derive version from the build; add SHA resolution.
5. Specs (`ios-appstore-release` REMOVE+ADD + build/attach edits; `ios-testflight-delivery` version-compute) + `CLAUDE.md`.
6. `openspec validate --specs --strict`; ship in the combined PR with `remove-alpha-testflight-promotion`.

Rollback: revert the PR; `ios-release.yml` (build-fresh) returns byte-for-byte. App Store Connect state untouched.

## Open Questions

None — scope, version model, naming, guard-retirement, SHA-failure mode, and sequencing were all settled in exploration. Two items are *verify-on-first-use*, not open decisions: Apple accepting a sub-`1.0` first release, and the exact `preReleaseVersion` include shape on the builds fetch.
