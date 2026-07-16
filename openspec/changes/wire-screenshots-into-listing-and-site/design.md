## Context

`screenshots.yml` (predecessor: `changes/archive/2026-07-16-add-app-store-screenshots`) already captures
App-Store-credible iPhone shots in CI, but deliberately stopped at a workflow artifact: *"this change does
**not** auto-upload to App Store Connect"*. Two facts have since changed.

- **The blocker expired.** `ios-appstore-metadata` scoped itself to text because *"screenshots and app
  previews are binaries with no CLI path"*. The **already-pinned** `asc` 2.8.2 ships
  `screenshots upload` — stable, non-experimental, with `--replace`, `--skip-existing`, `--dry-run`, and
  `--max-screenshots`. (`screenshots apply` also exists but is `[experimental]` and drives a
  browser-based `review-generate`/`review-open`/`review-approve` flow — useless headless. Use `upload`.)
- **The landing page shipped** (`marketing-site`) and shows no picture of the app at all.

Measurements taken during design, which several decisions rest on:

| what | measured |
|---|---|
| capture determinism | `joining`/`in_sync` pixel-**identical** across runs; `create` **differs** — it renders the wall clock (`12:01` vs `11:55`, and `14:41` on desktop) |
| PNG metadata | **ImageMagick** stamps `tIME` + `date:create/modify/timestamp` → pixel-identical files get different sha256 (its `cHRM` is exactly sRGB, so `-strip` would lose nothing). **`simctl`** stamps *nothing*: only an `eXIf` chunk of dimensions + colour space, byte-identical across captures |
| landing inline budget | 640px WebP: 15.4/21.2/17.0 KB per shot → **107 KB** for 6, **143 KB** base64. Page goes 119 KB → ~262 KB |
| ASC composites | 320–590 KB each (`joining` heaviest — most UI, not a QR) |
| workflow runtime | **10m53s vs 19m02s** on identical input — uniform ~2× across every step ⇒ runner lottery, not a slow step |
| build breakdown | 368s total: extension target ~78s (21%), Compose/Skiko app link ~290s (79%, not cacheable) |
| `ios-build` / `ios-test` | 13m / **3m** (warm) — `ios-test` never links Compose/Skiko; there are no iOS test sources under `:domain:ui` |
| dark capture | `simctl ui appearance dark` → backgrounds capture as exactly `#F4F6F8` / `#0C0E12` |

## Goals / Non-Goals

**Goals:**
- Make the repo the source of truth for the **whole** listing — text and screenshots — reviewable in a PR.
- Show the real app on the landing page, theme-matched, with no external request.
- One committed input, two derived surfaces: reprocessing must never need a macOS run.
- Keep every existing merge gate, required check, and deploy path untouched.

**Non-Goals:**
- Auto-capture on merge. The workflow stays **dispatch-only and non-gating**.
- Additional locales, iPad, the `Syncing` state (its pulsing arrow animates), device frames.
- Visual-regression diffing.

## Decisions

### D1: Commit the raw captures; derive per consumer

`screenshots/*.png` (6 raws) is the single source of truth. The ASC composite and the landing WebP are
both **built from it**.

**Why, over committing the derived assets** (3 composites + 6 WebPs, ~1.4 MB vs ~3–4 MB — the *cheaper*
option on bytes): with baked composites in git, **changing a headline needs a 15-minute macOS run** to
redraw white text on a green band. Deriving means a headline edit is `metadata/` → push → ubuntu
re-composite. Derived artifacts don't belong in git; the byte cost buys a workflow that isn't absurd.

Committing **`simctl`'s** output rather than ImageMagick's is what makes the set byte-stable without any
extra flag: `simctl` writes no timestamp (only an `eXIf` of dimensions + colour space), whereas every PNG
ImageMagick writes carries `tIME`/`date:*`, so pixel-identical composites would differ in sha256 and every
regeneration would be an unreadable diff. Deriving downstream keeps the churn out of git entirely — the
composites are ephemeral, so their stamps never matter.

**Why, over the artifact-only status quo:** a cross-workflow artifact cannot reach `backend-deploy`
without either coupling it to a macOS job or reading a *"latest successful run"* whose commit is
unrelated — and artifacts expire at 90 days. Committed files make `backend-deploy`'s existing path
filter fire naturally, so the page can never serve stale shots. Within *one* run an artifact is the
right interface (it is how `ios-archive` reaches `ios-deliver`); **across** runs it is not.

### D2: The workflow stays dispatch-only, non-gating, and on the iOS simulator

**Why not Compose Desktop** (which was prototyped and **works**): `:test:harness-driver` renders the real
`StatusScreen` offscreen headless (`runDesktopComposeUiTest` + `captureToImage`), and with Apple's SF Pro
resolved through fontconfig the Linux render is **visually indistinguishable from iOS** — same
letterforms, same line-breaks (Noto, the default, is not: it breaks the lede in the wrong place).
Layout matched to within 844 vs 847 px; there are no `WindowInsets` anywhere to diverge. It would have
been ~1–2 min on ubuntu with **no macOS, no slot cap, no runner lottery**.

It was rejected because the simulator gives, for free and with zero legal surface, the two things the
desktop render cannot: a **real iOS status bar** (`simctl status_bar override` → 9:41) and genuinely
authentic chrome. Reproducing those means compositing Apple's UI from somewhere, and every route there
is licensed murk. The macOS cost is acceptable precisely *because* the job is dispatch-only — nobody
waits for it.

**Why not a required check / merged into `ios-test` / a unified `ci.yml`:** measured, `ios-test` is 3
minutes and never links Compose/Skiko, so merging saves ~3 slot-minutes while adding ~7 min to every
PR and letting a `simctl` timing flake freeze the merge queue. Requiring the capture puts a job with
**1.75× unexplained runner variance** on the critical path of every merge. And coupling `backend-bundle`
to a macOS capture would mean a broken Kotlin/Native compile blocks a **live-API hotfix** — trading
production agility for ~120 KB. Committed raws make all of it unnecessary.

### D3: The wall clock is left unfixed — the seam is not where it appears to be

The create screen bakes the CI minute into a captured frame. Fixing it looked like one forged input, and
**it is not**. Attempted during implementation and reverted:

```
StatusContainerHost.kt:115   private val cutoffFormatter = SystemCutoffFormatter()   ← the CONTAINER's
StatusScreen.kt:79           cutoff: CutoffFormatter = SystemCutoffFormatter()      ← the SCREEN's
StatusScreen.kt:557          var startsAt by remember { mutableStateOf(cutoff.nowLocal()) }
```

The container's formatter is **private**, and the screen constructs **its own** via a default argument that
neither `StatusPane.kt` (desktop) nor `SnapSyncRoot.kt` (iOS) overrides. So forging the container's clock
changes `nowCutoff()` and the not-started line while leaving the create screen's date untouched. There are
**two origins of "now"**, which quietly falsifies `StatusContainerHost:246` — *"the app has exactly one
origin of 'now'… `:domain:ui` stays free of any clock or timezone knowledge"*. `:domain:ui` constructs a
`SystemCutoffFormatter()` through that default. Nothing has caught it because both wrap the system clock and
behave identically in production.

**Why not fix it here:** the honest fix is to expose the container's formatter and pass `cutoff =
host.cutoffFormatter` at both mount sites — making the documented invariant true rather than aspirational.
That is a **product-wiring change across two shells**, with its own reasoning and its own risk, and it
belongs in its own change rather than riding a screenshots pipeline. Deferring keeps this change's blast
radius honest.

**Consequence, accepted, and measured across two runs of the same commit:** `create`'s two captures re-diff
on every regeneration — in **exactly** the 90×32 px timestamp region and nowhere else — while
`joining-light/dark` and `in_sync-light/dark` come back **byte-identical**. So the listing shows the capture
minute, and a diff in the other four means the UI genuinely moved.

The light/dark pair is at least *coherent*: capturing both from **one** process seconds apart (D2's
single-launch loop) rather than two passes minutes apart means they agree on the minute. The earlier
per-appearance loop produced `15:47` light against `15:49` dark — so toggling your theme on the landing page
visibly jumped the timestamp. That artifact is gone; only the frozen minute remains.

### D4: `asc screenshots upload --replace`, path-gated

`--replace` is declarative — the committed set *is* the listing, and stale shots are removed. `--replace`
and `--skip-existing` are **mutually exclusive** (replace empties the set, so nothing remains to dedupe
against), so no-churn and declarative cannot both hold; declarative wins, and the path gate removes the
churn instead.

The gate is `metadata/** ∪ screenshots/**` — honest here because the watched paths **are** the composite's
two inputs, unlike a filter that guesses at a dependency graph. It applies **only** to the screenshot
upload:
- `appstore-metadata-validate` stays unfiltered — it is a required check, and a skipped required check is
  never posted, so filtering it would **freeze merges**.
- the text apply stays unfiltered — gating it would weaken its spec'd *"a console hand-edit is
  overwritten"* promise to "…eventually".

**The upload lives in its own workflow file** (`appstore-screenshots.yml`), not as a job in `appstore.yml`.
Two structural reasons, both discovered while implementing:

1. **The trigger can then *be* the gate.** A `paths:` trigger states the dependency exactly — no
   changed-files job, no `github.event.before` / `HEAD^` / all-zeros guesswork. That is only safe in a file
   holding **no required check**; `appstore.yml` hosts `appstore-metadata-validate`, so path-filtering it
   would freeze merges.
2. **Cancellation would corrupt the listing.** `--replace` deletes the set before uploading, so a cancel
   mid-flight leaves a **partial set on the public storefront**. `appstore.yml` runs
   `cancel-in-progress: true` *deliberately* (a newer text apply should supersede an older one rather than
   race it) — and that cancels the whole **run**, which a job-level `concurrency` **cannot** opt out of. A
   separate file is the only way to hold `cancel-in-progress: false` for the destructive step without
   breaking the text apply's newest-wins property. Uploads then serialise, and the newest still lands last.

The existing editable-version gate is reused unchanged. It matters **more** for screenshots than for text:
`upload --replace` is destructive, and `asc`'s behaviour on an in-review version is undefined
(upstream epic #587).

**Headlines live at `metadata/screenshots/en-US.json`**, which `asc metadata validate` ignores
(`filesScanned: 2`). They must **not** go in `metadata/version/1.0/en-US.json`: that schema is closed and
rejects unknown keys (`json: unknown field "screenshotHeadlines"`) — a merge freeze. No length gate is
needed; `caption:` word-wraps by construction.

### D5: No device frame — rounded corners only

Apple's Guidelines for Third Parties require a depiction of Apple hardware be *"an actual photograph of the
genuine Apple product **and not an artist's rendering**"*. That bars a fetched frame (`fastlane/frameit-frames`,
`PommePlate`) **and** a self-drawn ImageMagick bezel alike — and a CC0 dedication cannot grant rights to
Apple's trade dress, so permissive licensing on a third party's drawing fixes nothing. `asc screenshots
frame` exists and is unused for this reason. Rounded corners on the brand canvas imply a device without
depicting one: no license, no trademark surface, ages indefinitely, and leaves the most pixels to the UI
in Apple's small search carousel.

### D6: The landing derive is pure-Deno WASM

`deno task shots` decodes/resizes/encodes via `@jsquash` (WASM, "supports V8 environments") and writes
base64 for `app.ts` to import — so **`deno fmt/lint/check/test` and a fresh clone still need only Deno**,
with no ImageMagick and no system package. ImageMagick stays in `appstore.yml`, which is CI-only and needs
text rendering anyway. The derive is **build-time**: `marketing-site` forbids a runtime file read, and the
Edge Script serves from memory.

**Why WebP, not AVIF:** measured 15–21 KB/shot at 640px — AVIF would save ~25% for an extra dependency.
The compression win is the codec, not the downscale.

### D7: `screenshots/` at the repo root

A neutral home: neither consumer owns the input. `backend-deploy.yml`'s filter gains `screenshots/**` — an
**honest** widening, since the bundle genuinely embeds content derived from those files. The alternative
(`backend/src/shots/`) would keep `backend-deployment` unamended but put App Store assets under `backend/`
and have `appstore.yml` reach into a backend path.

## Risks / Trade-offs

- **[A system notification can land in a capture]** — *"Ready for Apple Intelligence"*, fired by **fresh-device
  onboarding**. Measured, not guessed, and the rate moved as the design did: it hit **1 of 2 runs** of the
  6-shot loop (an earlier estimate of "1 in 11, never in CI" was simply wrong — and the dark pass had doubled
  the exposure window). Reducing the capture window (3 launches, polls instead of `sleep 8`) took the loop
  154s → 125s, and the verification run came back clean. → The **human-in-the-loop commit remains the
  mitigation**: the runbook says eyeball before `git add`, and a banner is glaring. A colour assertion is
  *not* viable — legitimate content (`in_sync`'s "Anna's Birthday") renders in the same band, which is why
  the obvious cheap check false-positives. If it recurs, capture-twice-and-compare would make it a loud
  failure, at ~40s.
- **[Reusing a pre-created simulator does not work]** — `simctl create` costs a first-boot migration and
  **boot+install (209–224s) outlasts capturing all six shots**, so reusing the runner image's device looked
  like the best lever *and the fresh-device onboarding it skips is the banner's source*. It was tried and
  the image ships no `iPhone 16 Pro Max`: the run logs `no pre-created iPhone 16 Pro Max — creating one`. The
  workflow keeps the lookup and falls back to creating, so it costs nothing and starts working the day the
  image gains one.
- **[The listing shows the capture minute]** — `create` renders the wall clock (D3); a committed shot freezes
  it. → Accepted for now; the fix is a follow-up because the seam is in the shells, not the forge.
- **[Frozen dates age]** — `joining` already ships `EVENT_START = 2026-07-20` (pre-existing, inherited). →
  Accepted: refreshing screenshots is when it gets bumped.
- **[The `in_sync` shot carries a live, scannable QR]** — it encodes
  `https://<domain>/join#v=3&d=<base64url({"eventId":"00000000-0000-4000-8000-000000000000"})>` under the
  caption *"Scan to join this event"*. Scanned off the listing it resolves to a nonexistent event. → Accepted
  as polish, not security (all-zeros UUID, no data); recorded so it is a choice, not a surprise.
- **[Committed binaries]** — ~3–4 MB per refresh against a 45 MB history, refreshed rarely. → Accepted; the
  repo's largest single file is *already* `landing.html` at 120 KB, ~100 KB of which is a base64 PNG.
- **[Runner lottery]** — 1.75× spread on identical input, uniform across every step. → Irrelevant while the job
  is dispatch-only and off every critical path; it is the main reason to keep it there.

## Migration Plan

Additive and reversible in layers. Deleting `screenshots/` and the derive reverts the page; removing the
upload job reverts the listing (`--replace` is not re-run, so the live set simply stops being managed). The
forge clock and `-strip` are independently valuable and can land first. No data model, no gate, no delivery
change.

## Open Questions

- **The wall clock (D3)** — a follow-up should expose `StatusContainerHost.cutoffFormatter` and pass it at
  both mount sites, collapsing the two origins of "now" into the one the code already claims. A forged clock
  reading **today at 09:41** then becomes the one-liner it was meant to be (today, not a hardcoded date:
  both freeze identically on the listing, so a fixed date buys no freshness and only adds a constant to
  bump — the *minute* is the defect).
- Whether the `in_sync` QR should encode a real, permanently-provisioned demo event so a scan from the
  listing does something useful rather than erroring.
- Whether to revisit the Compose Desktop renderer if the macOS job ever becomes a bottleneck — it is proven
  to work, and only the status bar keeps it out.

## Corrections to the predecessor's record

- `changes/archive/2026-07-16-add-app-store-screenshots/design.md` **D4 mislabels the states**: it lists
  *"joining (`Joined` + invite QR)"*, but `joining` forges the **join-confirmation gate** (no QR — changed by
  `c7609ba`, *"joining forges the real join gate"*), and the invite QR is in **`in_sync`**.
- Its documented fallback — *"switch this to an app-target-only build (`-target iosApp` in place of
  `-scheme iosApp`)"* — is **unimplementable as written**: `xcodebuild` rejects `-target` together with
  `-derivedDataPath`, which the workflow uses. Dropping the extension from the build (~78s, 21%) needs a
  dedicated app-only scheme.
