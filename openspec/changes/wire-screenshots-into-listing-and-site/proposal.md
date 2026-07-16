## Why

`screenshots.yml` generates App-Store-credible iPhone screenshots in CI, but they land as a **workflow
artifact a human places by hand** — so the App Store listing still has no screenshots under version
control, and the landing page still shows no picture of the app at all. The listing is half-declarative:
`ios-appstore-metadata` makes the repo the source of truth for the listing *text* while the binaries
that sell it live only in the ASC console. Meanwhile the premise that forced that split has expired —
the pinned `asc` 2.8.2 ships a stable `screenshots upload`, so screenshots no longer have "no CLI path".

A spike also found a defect this change **inherits rather than fixes**: the create screen renders the wall
clock, so a captured shot bakes the CI run's minute into the listing (observed `12:01` vs `11:55` across two
runs, and reproduced on desktop as `14:41`). Fixing it turned out to reach further than expected — see
"Known limitations" — so it is deliberately left to a follow-up rather than widened into this change.

## What Changes

- **Commit the raw captures.** `screenshots.yml` emits **6 raw PNGs only** (3 states × light/dark) as its
  artifact; a human dispatches it, downloads them, and commits them to **`screenshots/`** at the repo
  root. Those files become the **single source of truth** — every derived asset is built from them, so a
  headline or sizing change never needs a macOS run.
- **Add a dark pass.** `simctl ui <device> appearance dark` flips the app through the real
  `isSystemInDarkTheme()` path (verified: backgrounds capture as exactly `#F4F6F8` / `#0C0E12`). No app
  change, no forge change — a relaunch and a second capture.
- **The committed raws are byte-stable for free.** `simctl io screenshot` writes no timestamp — its only
  metadata is an `eXIf` chunk carrying dimensions and colour space, byte-identical across captures. So a
  re-run with an unchanged UI produces an unchanged file, and a dirty `git status` means the UI really
  changed. (This is the reason to commit `simctl`'s output rather than ImageMagick's, which *does* stamp
  `tIME`/`date:*` into every PNG it writes.)
- **The App Store listing gains screenshots.** `appstore.yml` composites band + headline from the committed
  raws and runs **`asc screenshots upload --replace`** — main-only, behind the **existing editable-version
  gate**, and gated on `metadata/**` or `screenshots/**` having changed.
- **The landing page shows the app.** A new section renders the screens in a CSS scroll-snap carousel
  (`<picture>` + `prefers-color-scheme`, rounded corners, **no device frame**), with headings as real HTML
  text. Images derive from the committed raws via a **pure-Deno WASM** task — no ImageMagick, no system
  dependency, so a fresh clone still needs only Deno.
- **Runbook.** CLAUDE.md documents dispatch → download → **eyeball** → commit.

Out of scope (unchanged): additional locales, iPad, the `Syncing` state, and any device frame.

**Known limitations (deliberately inherited, not introduced):**

- **The create screen still renders the wall clock.** Forging it is not the one-line input it appears to be:
  `StatusContainerHost` holds its formatter **private**, while `StatusScreen` takes its *own*
  `cutoff: CutoffFormatter = SystemCutoffFormatter()` default that **no mount site overrides** — so the
  screen's date never passes through the container at all. There are two origins of "now", not the one
  `StatusContainerHost` documents. Fixing it means exposing the container's formatter and threading it
  through both shells' mounts — a product-wiring change worth its own change, not a rider on this one.
  Consequence here: `create`'s two captures re-diff on every regeneration, and the listing shows the
  capture minute.

## Capabilities

### New Capabilities
<!-- none — the workflow stays dispatch-only, non-gating CI infra with no spec (the `ssh-mac.yml`
     precedent this change's predecessor already set); the forge trigger remains spec'd in
     `ios-app-shell`, whose "forges the container's inputs" contract already covers a forged clock. -->

### Modified Capabilities
- `ios-appstore-metadata`: the repo becomes the source of truth for the listing's **screenshots**, not only
  its text — inverting the standing "Text fields only — screenshots and previews are out of scope"
  requirement. The apply reuses the existing editable-version gate, and the screenshot upload is
  path-gated (a listing-text apply's behaviour is unchanged).
- `marketing-site`: the page carries screenshots of the app, inlined like every other asset (its
  self-containment and no-external-request requirements hold unamended).
- `backend-deployment`: the deploy's path scope widens from `backend/**` to also cover `screenshots/**`,
  because the bundle now embeds content derived from those files.

## Impact

- **New source**: `screenshots/*.png` (6 raws, ~3–4MB per refresh). The single input for both surfaces.
- **Code**: `ForgeStatusHost.kt` gains a forged clock (one constructor argument). `backend/` gains a
  `deno task shots` derive step (`@jsquash` WASM) and a landing-page section. No product behaviour changes.
- **CI**: `screenshots.yml` gains a dark pass and `-strip`, and emits raws. `appstore.yml` gains a composite
  + `screenshots upload` job. `backend-deploy.yml`'s path filter widens by one entry. **No new required
  check, no new secret** (the existing Admin ASC key), and `appstore-metadata-validate` stays unfiltered —
  path-filtering a required check would freeze merges.
- **Known risk**: a system notification banner can intermittently land in a capture (observed once in 11,
  never in the 9 real CI captures). The human-in-the-loop commit is the mitigation — a colour assertion is
  not viable, since legitimate content renders in the same band.
- **Corrections to the record**: the predecessor's `design.md` D4 mislabels the invite QR as `joining` (it is
  `in_sync`), and its `-target iosApp` fallback is unimplementable as written (`-target` is incompatible
  with `-derivedDataPath`).
