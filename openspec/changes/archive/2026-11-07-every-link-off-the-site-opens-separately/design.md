# Design

## Context

See proposal.md, "Why". At `1dacad3` the site has these links that leave it:

| Link | Opens separately |
|---|---|
| App Store and Google Play buttons, the Play tester steps | yes |
| Support, the issue tracker (header and footer of every page) | yes |
| Contact email (footer), the Privacy Policy's and the Terms' contact email | yes |
| Email under "Who is responsible" (Privacy Policy) | **no** |
| Apple's standard licence agreement (Terms of Use) | **no** |

The event page links nowhere off the site except through the shared Play button. Its zip download is a local file.

`site/scripts/pages.test.ts` checks the built pages. Its `opensSeparately(prefix)` helper asserts `target="_blank"`
and a `rel` holding `noopener` on every link whose address starts with a prefix, and fails if it finds none.

## Goals / Non-Goals

**Goals:** the two links open separately. A test fails for **any** link that leaves the site without asking to open
separately, including a link added later, not only for the five kinds named today.

**Non-Goals:** what a browser or mail app does with the request. The page's part is to ask for a separate tab; the
browser opens it, as the existing store tests already say.

## Decisions

- **Email counts as leaving the site.** The spec names email. In most browsers a plain `mailto:` hands off to the
  mail app and the page stays anyway. It is replaced when the visitor's browser sends mail to a webmail site, which
  then loads in the same tab. The three other email links already ask for a new tab, so the fifth one matches them.
  *Rejected:* treating email as outside the requirement. That changes the spec's wording to excuse a link the site
  already treats as leaving it.
- **One test over every link, not one per kind.** A link "leaves the site" when its address is `mailto:`, or is
  `http(s)` on a host other than the site's own domain (`deployment.domain`). Same-site addresses (`/…`, `#…`) and the
  page's own origin are exempt. The test claims the new scenario. It also asserts that it saw at least one issue
  tracker, one email and one Apple licence link, so a selector that matches nothing cannot pass.
  *Rejected:* adding three more `opensSeparately(prefix)` calls. A new link to an unlisted site would go unchecked.
- **Same attributes as the existing links**: `target="_blank" rel="noopener noreferrer"` and an `aria-label` ending
  "(opens in a new tab)", so screen readers announce it as the other links already do.

## Risks / Trade-offs

- [A future intentional same-tab link off the site] → It fails the test. That is the point: the spec forbids it, and
  allowing it is a spec change.
- [The claim names a scenario that exists only after sync] → The claims gate (`VerifiesGateTest`) holds claims to
  the headings in `openspec/specs/`. So the scenario must be synced into the main spec in the same PR as the test
  that claims it.
