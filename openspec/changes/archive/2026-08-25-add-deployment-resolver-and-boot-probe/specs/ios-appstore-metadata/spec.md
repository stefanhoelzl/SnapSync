## MODIFIED Requirements

### Requirement: The repo is the declarative source of truth for the listing text

The committed per-locale metadata files SHALL be the source of truth for the App Store text listing — the
version-localization fields (description, keywords, promotional text, support URL, marketing URL, and
optionally what's-new) and the app-info fields (name, subtitle, privacy policy URL), one JSON file per
locale, laid out per the Purpose. On a push to `main`, `appstore-metadata-apply` SHALL apply those files
to App Store Connect, overwriting any value that differs — including one edited directly in the ASC web
console. The apply SHALL resolve the app's app-info at run time from the app id, and SHALL NOT depend on
a stored or hardcoded app-info id.

Fields whose value is **derived from the deployment's device-facing domain** — the marketing URL, the
support URL and the privacy policy URL — SHALL be authored as **templates** and rendered from the resolved
deployment (capability `deployment-configuration`); the apply SHALL consume the **rendered** files. The
committed files therefore remain hand-edited listing copy, and the domain appears in them exactly once, as
a placeholder. Previously those URLs restated the host as literals that no guard inspected, so a domain
change could leave the store listing pointing at a host the app no longer uses — a link that is broken but
plausible, and therefore not obviously wrong.

Rendering SHALL NOT extend to the listing copy itself: only the domain-derived URL fields are substituted,
so editing App Store text never requires running a generator.

#### Scenario: A main push applies the committed listing
- **WHEN** a commit is pushed to `refs/heads/main` and the app has an editable version
- **THEN** `appstore-metadata-apply` writes every field present in the per-locale files to that version's localizations

#### Scenario: Domain-derived URLs are rendered, not restated
- **WHEN** the committed metadata files are inspected
- **THEN** the marketing, support and privacy policy URLs carry a placeholder for the device-facing domain
  rather than a host literal, and the applied values are rendered from the resolved deployment

#### Scenario: A console hand-edit is overwritten
- **WHEN** a field was changed in the ASC web console after the last apply, and a new commit is pushed to `main`
- **THEN** the apply overwrites that field back to the committed file's value

#### Scenario: The app-info fields reach App Store Connect
- **WHEN** `app-info/<locale>.json` sets `subtitle` or `privacyPolicyUrl` and an apply runs on `main`
- **THEN** the apply writes those values to the app's app-info localization for that locale, resolving the
  app-info from the app id with no stored id

#### Scenario: Editing listing copy needs no generator
- **WHEN** an author edits description, keywords, promotional text or what's-new
- **THEN** the edit is made directly in the committed file, with no template syntax involved
