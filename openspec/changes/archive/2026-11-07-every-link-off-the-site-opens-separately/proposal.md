# Proposal

## Why

`web-site`'s requirement "Links to other sites never replace the SnapSync page" names five kinds of link: the App
Store, Google Play, the issue tracker, email and Apple's licence terms. Its only scenarios are the App Store and Google
Play ones, so nothing claims the other three. Phase 39 of the integration-test coverage programme (PR #530) found
that two of those three are broken on the landing page. The Terms of Use link to Apple's standard licence agreement,
and the email address under "Who is responsible" in the Privacy Policy, both open in place of the SnapSync page.

## What Changes

- **A third scenario** in that requirement: following any other link off the site (the issue tracker, an email
  address, Apple's licence terms) opens it separately and leaves the SnapSync page open. The requirement's text
  stays the same.
- **The two links that break it are fixed**: Apple's licence terms in the Terms of Use, and the email address under
  "Who is responsible". The site's other links already open separately.

The site already promised this. No outcome is added or removed, and none of the other links change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `web-site`:
  - "Links to other sites never replace the SnapSync page": gains the scenario "Following any other link off the
    site". The requirement's text and its two existing scenarios are unchanged.

## Impact

- **Site:** two links on the landing page (`site/src/pages/index.astro`) gain the attributes the store links already
  carry.
- **Tests:** the site's built-page test (`site/scripts/pages.test.ts`) gains one test that claims the new scenario.
  It checks every link on every built page that leaves the site.
- **No app, api or store change.** Release: the site ships with the next merge to `main`, like any site change.
