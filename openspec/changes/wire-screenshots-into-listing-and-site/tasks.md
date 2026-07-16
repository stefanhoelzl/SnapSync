## 1. Capture pipeline (`screenshots.yml`)

- [x] 1.1 Add a dark pass: before each capture set, `xcrun simctl ui "$DEVICE" appearance <light|dark>`; capture all three states per appearance → **6 raw PNGs**.
- [x] 1.2 Change the artifact to the **raw** captures only (drop the composited output and the `en-US/6.9/` layout). Composition now belongs to the consumers.
- [x] 1.3 Update the workflow header: raws-only output, the dark pass, and **correct the record** — `-target iosApp` is *not* a drop-in fallback (`xcodebuild` rejects `-target` with `-derivedDataPath`); dropping the extension (~78s, 21% of the build) needs a dedicated app-only scheme.
- [ ] 1.4 Dispatch once and confirm: 6 raws at 1320×2868, dark backgrounds are `#0C0E12` and light `#F4F6F8`.

## 2. Commit the source of truth + runbook

- [ ] 2.1 Commit the 6 raws to `screenshots/` at the repo root.
- [ ] 2.2 Add the CLAUDE.md runbook: dispatch `screenshots.yml` → `gh run download -n <artifact>` → **eyeball the shots** → commit. Call out that a system notification can intermittently land in a capture (observed once in 11; never in a real CI run) and that re-dispatching is the fix — a colour assertion is not viable because legitimate content renders in the same band.

## 3. Landing page (`marketing-site`)

- [ ] 3.1 Add `deno task shots`: decode/resize/encode the committed raws to ~640px WebP via `@jsquash` (WASM — no ImageMagick, no system dependency) and emit base64 for `app.ts` to import. Build-time only; the Edge Script must serve from memory.
- [ ] 3.2 Wire the derive ahead of `deno check`/`deno test`/`deno bundle` so a fresh clone with only Deno still passes.
- [ ] 3.3 Add the screenshot section to `landing.html`: CSS scroll-snap (triptych ≥952px, swipe below), `<picture>` + `prefers-color-scheme`, rounded corners and **no device frame**, headings as real text, `tabindex="0"` + `aria-label`. Keep the `#how` SVG step art — it explains the concept a UI shot cannot.
- [ ] 3.4 Extend `landing.test.ts`: screenshots present as `data:` URIs, still no external `src`/`srcset`, still no `<script>`, headline text present in the document.
- [ ] 3.5 Widen `backend-deploy.yml`'s path filter to include `screenshots/**`.

## 4. App Store listing (`ios-appstore-metadata`)

- [ ] 4.1 Add `metadata/screenshots/en-US.json` with the per-state headline copy. Verify `asc metadata validate --dir metadata` still reports `valid: true` (it ignores the extra subtree). **Never** add these keys to `metadata/version/1.0/en-US.json` — the schema is closed and an unknown field fails a required check.
- [ ] 4.2 Add the composite script: raw → rounded corners → brand canvas → baked headline via `caption:` (which wraps by construction, so no length gate is needed) → 1320×2868 for `APP_IPHONE_69`, light set only.
- [ ] 4.3 Add `appstore-screenshots-upload` to `appstore.yml`: ubuntu, main-only, reusing the existing editable-version resolution, then `asc screenshots upload --replace` — **not** `apply` (experimental; drives a browser review flow). `--replace` and `--skip-existing` are mutually exclusive.
- [ ] 4.4 Gate the job on `metadata/**` or `screenshots/**` having changed. Do **not** gate `appstore-metadata-validate` (required check → skipped check → frozen merges) and do **not** gate the text apply (weakens its declarative overwrite).
- [ ] 4.5 Dry-run first (`--dry-run`) against the editable version and confirm the planned set before any live replace.

## 5. Verify end to end

- [ ] 5.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green.
- [ ] 5.2 Backend: `deno fmt --check`, `deno lint`, `deno check src/*.ts`, `deno test` green from a clean clone with only Deno installed.
- [ ] 5.3 Confirm a re-dispatch with an unchanged UI produces byte-identical raws for `joining`/`in_sync`. `create` will still differ every run — it renders the wall clock (design D3); that is the accepted, deferred limitation, not a regression.
- [ ] 5.4 Confirm the live listing shows the committed set and `snapsync.stho.net` serves the screenshots in both themes.
- [ ] 5.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
