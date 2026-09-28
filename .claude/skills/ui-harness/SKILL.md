---
name: ui-harness
description: >-
  See and click SnapSync's real UI with no device and no display — serve the
  full-stack world harness over HTTP as an offscreen Compose scene, read back the semantics tree, click real buttons, type into fields, and
  capture real pixels. Use whenever the task means looking at or driving the app's
  screens without a phone: "show me the status screen", "screenshot the UI",
  "click that button", "review every UI state", "drive the world harness", or any
  harness-driver / driveWorld work. NEVER use java.awt.Robot or
  capture the real screen instead.
---

# ui-harness — driving the desktop harness headlessly

The **full-stack world harness** (`./gradlew :app:desktop:run`, `docs/testing.md`) — the real `:ui:screens`
status screen in a phone-sized frame, showing exactly what the app the JVM root (`:app:jvm`) composes over the
mocks showed on its screen; taps go back to that app as intents. Plus a right-pane **world inspector** playing the
mocks' operator faces: presets, **Invoke extension** (the extension's cycle, then the silent push that reconciles
downloads), **Heartbeat**, the gallery/backend, the upload-job queue and downloads, failure levers, **Hold**
switches, an engine-console footer. The operator plays the OS — nothing auto-runs.

**It is where every UI state is reviewed.** No state is forged — the forge harness was deleted in phase 12 — so
each is reached through a lever. The screens the app shows only while it waits (the join gate loading or
committing, a create in flight, the status screen before anything is counted) would flash past, because the mocks
answer at once: flip the matching **Hold** switch (`tag=hold-event|hold-create|hold-join|hold-leave|
hold-enumeration`) first, act, look, then flip it back to release.

The `run` task opens a real window and needs a display — useless to an agent, which can neither see
nor click one. Use the driver below instead.

## 🚫 Never screenshot the real screen

**Never use `java.awt.Robot` or capture the real screen `:0`.** It prompts the user for portal consent
on every run, and **blocks until they answer**. The driver composes the shipped harness root into an
**offscreen** Compose scene — a CPU raster Skia surface, no AWT peer, no `Robot` — so it needs **no X
server** and never raises the screen-capture consent prompt.

## The driver

`:test:harness-driver` serves **the harness over HTTP with no window at all**. It is **dev
infrastructure, non-gating, no spec** (rationale in `Driver.kt`).
Clicks go through the **real** buttons of the real panel, so there is no second way-to-drive that can
rot or lie.

```
ch bg ./gradlew :test:harness-driver:driveWorld   # full-stack world, 1240x950
```

Run it backgrounded — it blocks serving until `/quit` or 30 min idle. It is a long-lived server rather
than the work itself, so wrap it in **`ch bg`** (CLAUDE.md, *Agent harness limits*) to keep the
workspace able to go idle while it serves. Note the prefix changes the command string, so it falls
outside the `Bash(./gradlew:*)` allow rule and will prompt once.

```
# `B=...` must be its OWN statement, and use -sS (not -s). `B=... curl "$B/x"` expands $B BEFORE the
# assignment applies, so curl gets a hostless URL — and plain `-s` silences the error, so it looks
# like the driver is dead when the command is simply wrong. Capital S keeps errors visible.
B="http://127.0.0.1:$(cat test/harness-driver/build/harness-driver.port)"
curl -sS "$B/health"                                    # harness=world scene=1240x950
curl -sS "$B/tree"                                      # phone-pane semantics (~700 tokens)
curl -sS "$B/tree?scope=all"                            # whole window (~9.7k tokens — mostly chrome)
curl -sS --get --data-urlencode "text=▶ Invoke extension" "$B/click"
curl -sS "$B/click?text=%E2%9C%93&index=0"              # per-row controls NEED index=
curl -sS "$B/doubletap?text=SNAPSYNC"                   # the hidden bug-report gesture (no click semantics)
curl -sS --get --data-urlencode "text=What went wrong, and what were you doing?" \
     --data-urlencode "value=…" "$B/input"              # type into a field
curl -sS -o shot.png "$B/phone.png"                     # the 390x844 pane; /shot.png = whole window
curl -sS "$B/quit"
```

## Rules that are not obvious

- **The port is OS-assigned and written to `test/harness-driver/build/harness-driver.port`** — inside
  this worktree. That is deliberate: every CodeHydra workspace is its own worktree, so a fixed port
  would let two agents silently drive each other's world. Read the file; **never hardcode a port**.
- **Select a node** with `text=` (button label), `tag=`, or `desc=` (content description), plus
  `index=` and `substring=true`. `index=` is **required** for the world inspector's per-job `✓` `✕`
  `Net` `Http` `Cxl` `Unk` — one row per job, so those labels are ambiguous by construction.
- **`/click` settles before answering** (`waitForIdle()`), so a `200` means the state is stable. It
  also `performScrollTo()`s first, since the panel scrolls and off-viewport controls are otherwise
  unclickable.
- ⚠️ **`/tree` prints `onRoot()` — ONE root — so a popup is INVISIBLE in it.** A `ModalBottomSheet` or
  dialog renders into its own root: the bug-report sheet is fully open and driveable (`/input`,
  `/click` reach its field and buttons, which search every root) while `/tree`, even `?scope=all`,
  shows no trace of it. An empty tree after opening a sheet is **not** evidence the sheet failed to
  open — address its contents by label instead, and read `/phone.png` to see it.
- **The operator plays the OS — including acknowledgement.** `✓` on a job does *not* complete it: it
  lands the bytes on the backend mock (they show in the backend column at once) and leaves the job for the
  app to acknowledge — **the next `▶ Invoke extension` records it as `COMPLETED`**. Completing every job and
  expecting "In sync" without a second invoke will look like a bug and isn't. A completed-but-unacked job stays
  listed, so `index=0` twice hits the *same* row.
- **Presets join through the phone's own screen** (create, then the join gate's confirm), on the app's own lane —
  a preset's `/click` can answer before the app is joined, so poll `/tree` for the joined screen before the next
  step. Downloads show as the OS session's URLs; an imported photo shows
  in the gallery column marked `⬇ imported`.

## The control panel is test equipment

The right-hand panel is raw Material 3 — never `App*` components. That is deliberate: the panel is
test equipment, not product UI, so it must not be able to pass a design-system review by accident.
