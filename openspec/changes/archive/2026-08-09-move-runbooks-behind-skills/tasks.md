## 1. Author the skills

- [x] 1.1 Create `.claude/skills/ios-device/SKILL.md` — frontmatter `name`/`description` keyed on
      "touching the connected iPhone". Move from `CLAUDE.md`: the usbmuxd bridge and lockdown tools,
      the `--userspace` unlock, the no-`dvt ps` warning, the measured timeout table (incl. the cold
      `uvx` resolve and the Bash 600 s cap), the SIGTERM black-screen restart, the sideload/install
      runbook and wedged-installer rule, the headless per-build loop, event-link verification, and
      "verify real uploads". Move from `app/ios/CLAUDE.md`: the tier-forcing runbook
      (`SNAPSYNC_FORCE_URLSESSION_UPLOAD`, the download-only deregister dance, its verification).
      Keep the foreign-event trap here too, in full — its one-line inline copy is a summary, not a
      replacement.
- [x] 1.2 Write `ios-device`'s compressed launch-trigger index — all **11** `SNAPSYNC_*` names, each
      with value shape, one-clause effect and its `dvt launch` invocation, plus the
      `reset → leave → create → event-link` ordering. Point at `ios-app-shell` for contract and
      guarantees; do not restate them. **Document `SNAPSYNC_POLICY_PROBE`** (read
      `LaunchDirectives.kt` and `SnapSyncRoot.kt`) — it is undocumented today and the guard is red
      until it lands.
- [x] 1.3 Create `.claude/skills/ssh-mac-build/SKILL.md` — the whole ssh-mac loop verbatim, incl. the
      wildcard-entitlements block and `build_ent`, the Debug-not-Release measurement, the profile
      refresh (cross-referencing `asc-portal`), and "pointing a build at a local backend".
- [x] 1.4 Create `.claude/skills/ui-harness/SKILL.md` — the harness-driver runbook, the port file
      rule, the `B=`/`-sS` trap, `/tree` showing one root, per-row `index=`, and the operator-plays-
      the-OS acknowledgement note.
- [x] 1.5 Create `.claude/skills/asc-portal/SKILL.md` — the `proton-env` → `@env:` credential bridge
      and its "Missing value ISSUER_ID" trap, the subcommand roster, the screenshot-upload gap, and
      the Admin-key caveat.
- [x] 1.6 Create `.claude/skills/local-backend/SKILL.md` — a ~1.5k **index**, duplicating nothing:
      point at `api/README.md` §*Develop & test* for the rig, `ssh-mac-build` for the
      `BACKGROUND_UPLOAD_URL_BASE` rebuild, `ios-device` for the launch — and own the
      `SNAPSYNC_RESET_STATE`-in-both-directions warning plus its `[boot] upload base = …` oracle.
- [x] 1.7 Add the `ch-bg` background-wrapper note to `ui-harness`, `local-backend` and
      `ssh-mac-build`, wherever they start a long-lived process (finding C1).

## 2. Rewrite CLAUDE.md

- [x] 2.1 Add the repo-layout block above *Read first*: one line per top-level directory, the
      explicit "there is no `backend/` — it split into `api/` + `site/`", the `ls openspec/specs/`
      pointer for capability names, and the `ch-bg` one-liner.
- [x] 2.2 Add the `Runbooks (load before you start)` block — five intent-first pointers with command
      tails. The `ui-harness` line carries the `java.awt.Robot` / capture-`:0` warning inline.
- [x] 2.3 Delete the moved sections: *Driving either harness headlessly*, *On-device iOS over USB*,
      *Sideload a dev IPA*, *Headless macOS build loop*, *Verifying the event link*, *Verify real
      uploads*, *The local backend rig*, *App Store Connect via API*.
- [x] 2.4 Collapse *TestFlight delivery* and *App Store releases PROMOTE* to ~15 inline operator
      lines — the two `gh workflow run` dispatches, `build_number` = the `ios.yml` `run_number`, the
      `release_notes.py` local preview, "don't hand-push a `vX.Y` tag", "a promote is single-shot;
      corrections are a manual console upload" — each pointing at `ios-testflight-delivery` /
      `ios-appstore-release` for contract and rationale.
- [x] 2.5 Collapse *Refreshing the marketing screenshots* to its dispatch block plus "eyeball them"
      and "only `create` legitimately re-diffs", pointing at `ios-appstore-metadata`.
- [x] 2.6 Add the two remaining inline traps: "there is deliberately no whole-zone storage reset" and
      "never join an event you did not create" (D3). Confirm the ungated traps that stay put are
      untouched: the `NSLog` redaction in *Logging & errors*; the bare-`openspec`, `.claude/`-is-
      generated and `validate`-checks-structure-not-truth rules in *Workflow*.
- [x] 2.7 Verify the *Test UI* section still names both harnesses and their specs, now pointing at
      `ui-harness` for driving them.

## 3. Trim app/ios/CLAUDE.md

- [x] 3.1 Remove the tier-forcing runbook (the `SNAPSYNC_FORCE_URLSESSION_UPLOAD` paragraph, the
      deregister payload block and its verification), leaving the two-tier architecture — which
      producer, which process holds the single `LedgerWriter`, why the choice is made once by
      `resolveComposition` — intact.
- [x] 3.2 Replace it with one line pointing at the `ios-device` skill for the on-device procedure.

## 4. Guards

- [x] 4.1 Add the pointer-integrity guard to `:test:architecture`, in `LawsDigestTest`'s shape:
      derive the pointer set from `CLAUDE.md`, resolve each to `.claude/skills/<name>/SKILL.md`,
      assert each file's frontmatter `name:` equals its directory. Dangling direction only — a skill
      with no pointer passes, so regenerated `openspec-*` output never fails the build.
- [x] 4.2 Add its non-vacuity twin: absent `CLAUDE.md`, missing block marker, or zero derived
      pointers fails.
- [x] 4.3 Add the launch-trigger index guard: set-equality between the `SNAPSYNC_*` names in
      `.claude/skills/ios-device/SKILL.md` and the `"SNAPSYNC_*"` literals in production Kotlin
      (main source sets only; exclude test sources and `build/`). Names only — never semantics.
      The failure message names both directions of the delta.
- [x] 4.4 Add its non-vacuity twin: absent skill file or zero source literals fails.
- [x] 4.5 Declare `CLAUDE.md` and the guarded skill files as inputs of the guards' test task, so the
      guards re-run when their subject changes rather than reporting up-to-date.

## 5. Verify

- [x] 5.1 `./gradlew build` is green — both new guards pass, including after task 1.2 lands
      `SNAPSYNC_POLICY_PROBE`.
- [x] 5.2 Prove guard 4.3 is not vacuous by temporarily deleting one trigger name from the skill and
      confirming a red build, then restoring it.
- [x] 5.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.
- [x] 5.4 `./gradlew architectureDiagrams` produces no diff (no flow or module changed; confirm
      `architecture/` is unchanged).
- [x] 5.5 Record the measured `CLAUDE.md` size before and after (`wc -c`), and confirm no fact left
      the repo: every deleted paragraph is either in a cited spec, in `api/README.md`, in an archive
      decision record, or in one of the five skills.

## 6. After merge

- [x] 6.1 Cold smoke test — run **pre-merge as an approximation**, not post-merge. Five subagents were
      each given one cold task and asked only for the single FIRST tool call they would make, with no
      hint that skills exist. **5/5 loaded the correct skill as their first action**, each citing the
      trap it guards:

      | cold task | first tool call |
      |---|---|
      | "screenshot the app running on the connected iPhone" | `Skill(ios-device)` |
      | "show me what the status screen looks like" | `Skill(ui-harness)` |
      | "changed the backend, test it against the real device" | `Skill(local-backend)` |
      | "build me an IPA I can sideload" | `Skill(ssh-mac-build)` |
      | "register a new iPhone with Apple for dev builds" | `Skill(asc-portal)` |

      ⚠️ **Weaker than the real thing, and the gap is named:** these subagents ran inside the authoring
      session's repo state, so they are not a genuinely cold workspace. It proves the pointers and
      descriptions are matchable and unambiguous; it does not prove they fire on a fresh clone weeks
      later. Re-run the same five prompts in a new workspace after merge — if one misses, reword that
      pointer rather than undoing the split.
