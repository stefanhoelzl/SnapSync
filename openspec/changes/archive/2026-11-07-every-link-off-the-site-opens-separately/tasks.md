# Tasks

## 1. The links and their test

- [x] 1.1 In `site/scripts/pages.test.ts`, add the test "every link off the site opens in a tab of its own", claimed
  `@Verifies(spec = "web-site", requirement = "Links to other sites never replace the SnapSync page", scenario =
  "Following any other link off the site")`. It covers every `mailto:` link and every `http(s)` link to a host other
  than `deployment.domain`, on every built page. Each must have `target="_blank"` and a `rel` holding `noopener`. It
  must also see at least one issue tracker, one email and one Apple licence link. Verify: `npm run build && npm run
  check:unit` in `site/` fails, naming exactly the "Who is responsible" email and the Apple licence link.
- [x] 1.2 In `site/src/pages/index.astro`, give those two links `target="_blank" rel="noopener noreferrer"` and an
  `aria-label` ending "(opens in a new tab)". Verify: `npm run build && npm run check:unit` in `site/` passes.

## 2. Contract and gate

- [x] 2.1 Sync the delta into `openspec/specs/web-site/spec.md` in the same PR, because the claims gate holds the new
  claim to a heading that exists. Verify: the claims gate passes in `./gradlew build`, and that build is green
  (needs `deno` on PATH).
