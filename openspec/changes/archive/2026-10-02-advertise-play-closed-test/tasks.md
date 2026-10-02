# Tasks

## 1. Deployment

- [x] 1.1 Add the `playTestGroupUrl` key (rendering `SITE`) to the resolver's inventory, with its doc, and add `SITE` to `androidPackageName`'s renderings; verify by `python3 scripts/resolve-deployment.py` for `prod` and `local` and inspecting `site/src/deployment.json`
- [x] 1.2 Add the resolver rules: `playTestGroupUrl` is empty or `https://groups.google.com/g/<name>`, and not set together with `playStoreUrl`; verify with new cases in `scripts/resolve_deployment_test.py` (accepted empty, accepted group URL, refused foreign URL, refused both set, package name reaches the site) passing
- [x] 1.3 Set `playTestGroupUrl` to `https://groups.google.com/g/snapsync-beta` in `deployments/components/android.json`; check that `deployments/components/android-local.json` and `local.json` resolve without error; verify the resolver suite still passes
- [x] 1.4 Update `docs/deployment.md`'s store-pages paragraph: the beta key, the three site states, and the production flip (empty the group, set `playStoreUrl`, and change `PLAY_TRACK` if promoting through the workflow); verify the paragraph names the resolver's both-set refusal

## 2. Site

- [x] 2.1 Add a `site/src/lib` module deriving the listing and opt-in addresses from the package name and `playOffered` from the two keys, with a `deno test` added to `check:unit`; verify that `npm run check:unit` passes, including `playHrefFor` over the derived listing
- [x] 2.2 Make `PlayStoreButton.astro` link to `playStoreUrl` or the derived listing, keeping `data-play-store`; add `PlayTesterSteps.astro` (steps 1–2 + the badge as step 3, copy per design D3, every link `target="_blank" rel="noopener noreferrer"` with an `aria-label`) and `PlayOffer.astro`; verify that `npm run check` passes
- [x] 2.3 Replace `<PlayStoreButton />` with `<PlayOffer />` on the landing page and in both `/join` views, and switch the Privacy Policy's referrer paragraph and the footer's trademark line to `playOffered`; verify by building the site in all three deployment states (group only, Play page only, neither) and grepping the built `index.html` and `join/index.html` for the steps, the badge, the privacy paragraph and the trademark line
- [x] 2.4 Check the steps' layout in light and dark mode at phone and desktop widths, by `npm run build && npm run preview` opened with `ch ws browser`; verify the steps sit where the badge sits and wrap on a narrow screen

## 3. Specs and verification

- [x] 3.1 Widen `web-site`'s Purpose ("and to Google Play once SnapSync is published there") to cover the tester steps, directly in `openspec/specs/web-site/spec.md`; verify that `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` and `validate advertise-play-closed-test --strict` pass
- [x] 3.2 `./gradlew build` is not affected (no Kotlin change). Run the `site-build` gate locally (`cd site && npm ci && npm run check && npm run build`) and the resolver's suite; verify both are green
- [x] 3.3 With the user's agreement only: on the A40 (under its lock), open an invite's `/join` page, follow the three steps with a test Google account, install, and first launch; verify that the join screen for that event opens. Use an event this workspace created, never someone else's. — Confirmed working by the user.
