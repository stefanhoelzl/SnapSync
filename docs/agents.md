# Agent instructions and tooling

How the instructions coding agents load are organised, and the agent tooling this repo carries (`/ship`'s
`.ship/`, the OpenSpec skills). Explanation, not contract.

## 1. What goes in `AGENTS.md`

The root `AGENTS.md` loads into **every** session, so every line costs every session. A line stays there only if a
session that loads nothing else would **do damage or waste a cycle** without it: the mission, the traps, the
runbook index, the build commands, the workflow rules, the harness limits. Everything else is reached on demand,
and where it goes depends on what it is:

| kind | home | loads |
|---|---|---|
| a task you start (a device, a Mac, a release) | a skill under `.claude/skills/`, pointed at from the root's *Runbooks* block | when its description matches, or when the pointer is followed |
| facts tied to one directory | a nested `AGENTS.md` in that directory (`app/ios/`, `adapter/ios/`, `iosApp/`) | when a file there is read |
| explanation: why, history, mechanics | `docs/` | when a pointer is followed |

The root carried a per-module inventory until 2026-10 (65 KB, 58% of the file). It was deleted after an audit moved
each still-unique fact into the KDoc of the code it described or into these docs. The module graph is
`architecture/modules.md` (generated), its groups and laws are `docs/architecture.md` §1–2, and grep finds where a
class lives. Do not grow a new inventory: it drifts, and nothing gates it.

`RunbookSkillsTest` (`:test:architecture`) holds the *Runbooks* block's pointers to skills that exist.

## 2. `/ship` and `.ship/`

`/ship` is a **global** skill (`~/.claude/skills/ship/`), not a file in this repo. Everything that varies per repo
lives in `.ship/`:

- `gates.md`: the local half of what gates a merge;
- `pr-title.md`: the App Store title policy;
- `post-merge.md`: the Bugsink resolve;
- `config.json`: only for non-default settings (none today).

The hook contract is `~/.claude/skills/ship/hooks.md`. Read it before editing any of them, and do not add another
file expecting ship to read it.

The branch ruleset is **live-only**: ship owns it as an exhaustive baseline and there is no committed copy to edit.
Read it with `gh api repos/stefanhoelzl/SnapSync/rulesets`. Required-check contexts accumulate from what actually
ran on a PR; dropping one is a deliberate, confirmed act inside `/ship` (`docs/deployment.md` §4).

## 3. OpenSpec tooling

- **The CLI is not installed.** There is no global binary and no `package.json`. Invoke it through npx, pinned to
  the version CI uses: `npx --yes @fission-ai/openspec@1.13.2 <cmd>` (e.g. `… validate --specs --strict`, as
  `.github/workflows/ci.yml` runs it). The generated skills say bare `openspec …`; translate each call.
- **`.claude/opsx` and the `openspec-*` skills are generated.** They assume the machine-global profile in
  `~/.config/openspec/config.json` is `core` (workflows propose · update · explore · apply · sync · archive) with
  `delivery: both`. Regenerate with `npx --yes @fission-ai/openspec@1.13.2 config profile core`, then `… update`,
  and commit the output verbatim. On a default profile `update` emits only four workflows and **deletes** the `sync`
  skill and command.
- **Regenerating is a no-op today, and must stay one**: `.claude/` is byte-identical to generated output, so
  `update --force` changes nothing. It was not always: the archive's placeholder gate was hand-patched into
  `SKILL.md` (and only there, which is why `/opsx:archive` never had it), and `update --force` silently deleted it,
  with a green run. Anything an archive must *enforce* belongs in `openspec/config.yaml`'s `context:` block, which
  is hand-authored, injected into every OpenSpec agent, and the one surface `update` does not rewrite.
- **`openspec validate --specs --strict` checks structure, not truth.** It asks whether a spec has a Purpose,
  Requirements, and SHALL/WHEN/THEN with a scenario; it has never opened a `.kt` file. It passed 50/50 on a tree
  carrying 28 audited drifts (four specs contradicting themselves within one file), and it passes 50/50 now that
  they are swept. Green means well-formed. It does not mean true, and nothing in CI does.

## 4. Harness limits, with their history

The root states the rules; this is why they are rules.

- The Bash tool's 10-minute cap clamps **silently**: `/ship`'s 15-minute merge wait was killed at 10 minutes on 26
  consecutive ships (July–August 2026), and the escalating `timeout` values tried in response (1.2M, 1.5M, 1.8M,
  3M ms) all clamped to 600 000. A background command should print one greppable result line as its last act
  (`/ship` uses `SHIP-WAIT RESULT: <status> (<reason>)`), written with `writeSync(1, …)` or an equivalent, because a
  `console.log` followed immediately by `process.exit()` is truncated when stdout is a pipe.
- A leading `cd <workspace root>` was on 45% of commands (6 517 of 14 340, measured July–August) and is always a
  no-op: every call starts at the root. The same non-persistence is why `USBMUXD_SOCKET_ADDRESS` is set in
  `.claude/settings.json` rather than exported per call.
- `ch bg` changes the command string, so a wrapped command can fall outside an `allowed-tools` grant like
  `Bash(npx:*)` and start prompting. The old standalone `ch-bg` wrapper still counts as the marker; `ch bg` is the
  canonical spelling.
