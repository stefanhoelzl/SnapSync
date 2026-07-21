## ADDED Requirements

### Requirement: An upgrade to full access is an offered route and an ordinary transition

The app SHALL offer a limited member an in-app route to switch the grant to Full Access: the status
screen's "Allow full access" affordance (capability `sync-status-screen`) deep-links to the app's
system Settings page, where the switch itself happens — iOS exposes no API that re-raises the
full-access dialog while the app holds `.limited`, so Settings is the only mechanism (expiry
trigger: an iOS release adding a re-prompt API). The route SHALL NOT issue a PhotoKit authorization
request (which is a no-op under a determined status) and SHALL NOT interpose any in-app consent
surface.

The app SHALL treat the resulting `LIMITED→GRANTED` change as an ordinary scope change, the mirror
of the existing downgrade requirement: the OS terminates the app when the grant changes in Settings,
and the next cold launch composes the ordinary `GRANTED` state — the baseline covers the whole
post-cutoff library under the selection policy, the selection-change observer is not registered, the
arm starts the `GRANTED`-tier producer (capability `upload-lifecycle`), and the ledger/reconcile
guarantees photos uploaded under the limited selection are not re-uploaded.

#### Scenario: The upgrade resumes as an ordinary full grant
- **WHEN** a member who uploaded photos under a `LIMITED` selection switches to Full Access in
  Settings and relaunches the app
- **THEN** the app composes the ordinary `GRANTED` state — no selection-change observer, the
  `GRANTED`-tier producer — and only newly-in-scope post-cutoff photos upload; nothing re-uploads

#### Scenario: The route raises no permission dialog
- **WHEN** the member takes the in-app route to Full Access
- **THEN** the app opens its system Settings page and issues no `PHPhotoLibrary` authorization
  request
