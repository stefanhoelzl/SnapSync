## ADDED Requirements

### Requirement: Not-started status preset

The forge harness's control panel SHALL offer a preset that forges the joined layer's **not-started**
health, so the clock status line can be reviewed without waiting for wall-clock time to pass and without
a device.

Because the not-started state is derived from `startsAt > now` against an injected time source, the
harness SHALL forge it by supplying a config whose `startsAt` lies in the **future** relative to the
harness's clock — not by fabricating the health value directly. Forging the *input* rather than the
*output* is what keeps the harness honest: it exercises the real reduction and its precedence, so a
regression in either shows up here.

The preset SHALL be composable with the existing permission presets, so the reviewer can confirm that
`NeedsAccess` outranks `NotStarted` (capability `sync-status-screen`).

#### Scenario: The not-started preset shows the clock line
- **WHEN** the operator selects the not-started preset
- **THEN** the phone frame's joined layer renders the clock status line naming the event's start, beneath
  the invite QR

#### Scenario: Permission outranks the forged not-started state
- **WHEN** the operator selects the not-started preset together with a not-granted permission preset
- **THEN** the status line renders the needs-access affordance, not the clock line — the real precedence
  being exercised

#### Scenario: The preset forges the start, not the health
- **WHEN** the not-started preset is applied
- **THEN** it sets a future `startsAt` on the forged config and lets the real reduction derive the health
