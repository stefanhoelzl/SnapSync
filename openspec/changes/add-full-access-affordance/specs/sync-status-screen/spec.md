## ADDED Requirements

### Requirement: The joined layer offers an allow-full-access affordance under a limited grant

While permission is `LIMITED` and config is present, the joined layer SHALL render a persistent
**"Allow full access"** affordance directly **below** "Choose more photos", invoking the app's
system-Settings route (the existing `openSettings` command) — the only mechanism iOS offers, since no
API re-raises the full-access dialog under `.limited` (capability `limited-photo-access`, "An upgrade
to full access is an offered route and an ordinary transition"). It SHALL share its neighbor's calm
resting posture: outside the status-line slot, present regardless of the current health value, the
same quiet borderless-text visual weight, and never an attention state — the limited grant stays
first-class, and this affordance is an offer, not a nag. No interstitial consent surface SHALL be
interposed between the tap and Settings: the label plus the OS-mediated toggle are the consent, and
the widened scope stays bounded by the cutoff and origin exclusions (`photo-selection-policy`) like
every full-access membership. Under any other permission value the affordance SHALL be absent.

#### Scenario: The affordance shows under limited, below choose-more, in every health state
- **WHEN** permission is `LIMITED` with config present, while the health is `InSync`, `Syncing`, or
  `NotStarted`
- **THEN** the "Allow full access" affordance renders directly below "Choose more photos" in each
  case, and the status line above both is unchanged

#### Scenario: Tapping it opens the system Settings page
- **WHEN** the member taps "Allow full access"
- **THEN** the app's system Settings page opens via the `openSettings` command, and no in-app dialog
  or permission request is raised

#### Scenario: The affordance is absent under full access
- **WHEN** permission is `GRANTED`
- **THEN** no "Allow full access" affordance renders
