---
name: asc-portal
description: >-
  Drive Apple Developer portal and App Store Connect chores from the CLI instead
  of the GUI — code-signing certificates, registering device UDIDs, provisioning
  profiles, bundle-id capabilities, and App Store / TestFlight text metadata, via
  codemagic-cli-tools' app-store-connect with credentials injected by proton-env.
  Use for "register this device", "mint/refresh a provisioning profile", "enable a
  capability on the App ID", "list certificates", "update the TestFlight what-to-test
  or App Store description", or any app-store-connect / asc portal task.
---

# asc-portal — Apple portal chores over the API

Apple Developer portal tasks that are otherwise GUI-only — code-signing certs, devices, provisioning
profiles, bundle-id capabilities, and App Store / TestFlight text metadata — are driven through the
**App Store Connect API** via the `codemagic-cli-tools` `app-store-connect` command, run with **uvx**
(no install).

Credentials are injected as env vars by **`proton-env`**, which requires **user sign-off on each run**
— that approval is the only mutation guardrail (no bespoke protection on the CI certs), so prefer
read-only subcommands and keep mutations deliberate.

## The credential bridge (the trap)

```
# proton-env injects these three (the same values as the CI secrets of the same names):
#   ASC_ISSUER_ID
#   ASC_KEY_ID
#   ASC_AUTH_KEY     # full .p8 PEM content (not a path)
```

⚠️ Those names are **NOT** what the codemagic CLI looks for (it wants `APP_STORE_CONNECT_ISSUER_ID` /
`_KEY_IDENTIFIER` / `_PRIVATE_KEY`), so a bare `proton-env -- app-store-connect …` fails with
**"Missing value ISSUER_ID"**. Bridge them with the CLI's own `@env:` prefix — no shell remap needed:

```
A="--issuer-id @env:ASC_ISSUER_ID --key-id @env:ASC_KEY_ID --private-key @env:ASC_AUTH_KEY"

proton-env -- uvx --from codemagic-cli-tools app-store-connect certificates list $A --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect devices list $A --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect profiles list $A --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect bundle-ids list $A --json
```

## Invocation notes

- The CLI prints **JSON to STDOUT and its own logs to STDERR** — capture them separately.
- `--capability` takes **N values**, so the bundle-id positional must come **BEFORE** it or it gets
  eaten:
  ```
  … bundle-ids enable-capabilities <BUNDLE_RESOURCE_ID> $A --capability "Associated Domains"
  ```
- Text metadata lives under `app-store-version-localizations` (descriptions/keywords) and
  `beta-build-localizations` (TestFlight "what to test").

## Coverage and the one gap

Covers certs (list/create/revoke), devices (register/enable/disable — Apple has **no delete**),
profiles, bundle-ids + capabilities, and App Store / TestFlight **text** metadata.

**Gap:** screenshot upload (reserve→chunk→commit) has no subcommand — drop to raw REST when a real App
Store listing needs it. (Routine listing screenshots are uploaded by the release workflow; see
CLAUDE.md's App Store section.)

The CI key is **Admin** (needed for cloud signing); if an agent should not reach app metadata / user
management, mint a narrower **App Manager** or **Developer** key for agent use and inject that one
instead.

## Two things a capability change breaks

- **Enabling a bundle-id capability silently invalidates that App ID's existing provisioning
  profiles.** Verified 2026-07-16: enabling Associated Domains flipped *SnapSync Dev Push* to
  `INVALID` while the extension's profile, whose bundle id gained nothing, stayed `ACTIVE`. Refresh
  the profile afterwards — see `ssh-mac-build` for the mint/tar/`gh secret set` sequence and why a
  stale profile fails with no error at all.
- **App Groups and Associated Domains must be enabled in the portal**, on both App IDs where
  applicable, or signed builds fail to provision. Keychain groups need **no** portal step.

## 🚫 Never select "Private" in Pricing and Availability

That is **Custom Apps** (Apple Business Manager — org-only, no consumer can install), **not** unlisted.
It is a **one-way door**: once approved, the distribution method can't be changed, and switching
private↔public needs a **brand-new app record** — burning app id `6781692480` and everything
configured on it. Public→unlisted *is* allowed, so staying Public keeps every option open.

For genuine **Unlisted App Distribution**, the sequencing is the reverse of the intuition: Apple
**declines** an unlisted request for an app that has not been submitted to review. Submit first, get
approved, *then* file the request at
<https://developer.apple.com/contact/request/unlisted-app-distribution>. There is **no API** for it.
