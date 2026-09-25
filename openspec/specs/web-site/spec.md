# web-site Specification

## Purpose
Serves anyone who opens SnapSync's address in a browser — a curious guest, a prospective host, an App
Store reviewer — with one public page that says what SnapSync is, shows the real app, links to the App
Store, and carries the Privacy Policy, the Terms of Use and a way to get support. It promises that the
pictures on the site are the same software the App Store listing shows, and that the page is readable by
anyone, in light or dark mode, with a keyboard and without JavaScript. What the site may and may not
collect about its visitors is capability `privacy-security`; the page an invite link shows is capability
`event-site`.
Decision record: changes/archive/2026-07-15-add-marketing-page

## Requirements
### Requirement: A public landing page describes SnapSync
Opening SnapSync's web address in any browser SHALL show a landing page that explains what SnapSync does
and offers a link to get the app from the App Store. It SHALL need no account, no app and no sign-in.

#### Scenario: A visitor opens the site
- **WHEN** someone opens SnapSync's web address in a browser
- **THEN** they see the landing page describing SnapSync, with a link to the App Store

#### Scenario: The site opens in the browser even with the app installed
- **WHEN** someone with SnapSync installed opens the site's address (not an invite link) on their iPhone
- **THEN** the landing page opens in the browser rather than switching to the app

### Requirement: Privacy Policy, Terms of Use and support are published on the site
The site SHALL publish a Privacy Policy and Terms of Use, each reachable by its own direct link, and SHALL
offer a support route (the project's public issue tracker) and a contact email. Privacy, Terms, Support
and Contact SHALL be reachable from every page of the site.

#### Scenario: The policies can be linked directly
- **WHEN** someone follows the site's Privacy Policy link or Terms of Use link (for example from the App Store listing)
- **THEN** the page opens at that document

#### Scenario: Support is reachable from any page
- **WHEN** a visitor is on any page of the site, including the page an invite link shows
- **THEN** links to Privacy, Terms, Support and a contact email are present

### Requirement: The site shows the real app, the same as the App Store
The landing page SHALL show screenshots of the real app taken from the same captures the App Store listing
uses, so the site and the listing always depict the same software. Each screenshot's caption SHALL be real
text rather than words baked into the image.

#### Scenario: Refreshed captures reach the site
- **WHEN** the app's screenshots are refreshed for the App Store listing and the site is next published
- **THEN** the site shows the refreshed screenshots

#### Scenario: Captions are text
- **WHEN** a visitor selects a screenshot's caption, or a screen reader reaches it
- **THEN** the caption is readable text

### Requirement: The site follows the reader's light or dark mode
The site SHALL render in light or dark colours following the reader's system setting, and SHALL show each
screenshot in the matching light or dark rendering, never a light screenshot on a dark page or the reverse.

#### Scenario: A dark-mode reader
- **WHEN** a visitor whose device is in dark mode opens the landing page
- **THEN** the page is dark and every screenshot shows the app in dark mode

#### Scenario: A light-mode reader
- **WHEN** a visitor whose device is in light mode opens the landing page
- **THEN** the page is light and every screenshot shows the app in light mode

### Requirement: The landing page works without JavaScript and by keyboard
The landing page SHALL be fully readable and its screenshots browsable with JavaScript disabled. The
screenshot area SHALL be reachable and scrollable by keyboard and SHALL be announced to assistive
technology with a name.

#### Scenario: JavaScript is off
- **WHEN** a visitor opens the landing page with JavaScript disabled
- **THEN** all content, including every screenshot, is available

#### Scenario: A keyboard user browses the screenshots
- **WHEN** a keyboard user tabs to the screenshot area on a narrow screen
- **THEN** it takes focus, is announced by name, and can be scrolled with the keyboard

### Requirement: The site carries the app icon
Every page of the site SHALL show SnapSync's app icon as its browser-tab icon.

#### Scenario: The tab shows the app icon
- **WHEN** a visitor opens any page of the site
- **THEN** the browser tab shows the SnapSync app icon

### Requirement: Links to other sites never replace the SnapSync page
Every link from the site to another site (App Store, issue tracker, email, Apple's licence terms) SHALL
open separately, leaving the SnapSync page open where it was.

#### Scenario: Following the App Store link
- **WHEN** a visitor follows the App Store link from any page of the site
- **THEN** the App Store opens separately and the SnapSync page stays open
